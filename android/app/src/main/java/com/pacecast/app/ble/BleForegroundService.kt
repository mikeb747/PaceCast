package com.pacecast.app.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pacecast.app.MainActivity
import com.pacecast.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class BleForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pacecast_ble_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pacecast.app.ACTION_START"
        const val ACTION_STOP = "com.pacecast.app.ACTION_STOP"
        const val ACTION_SWAP_PACE = "com.pacecast.app.ACTION_SWAP_PACE"
        const val ACTION_REFRESH_OVERLAY = "com.pacecast.app.ACTION_REFRESH_OVERLAY"

        fun startService(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, BleForegroundService::class.java))
            } catch (_: Exception) {}
        }

        fun refreshOverlay(context: Context) {
            if (!SpeedStateManager.isAdvertising.value) return
            try {
                val intent = Intent(context, BleForegroundService::class.java).apply {
                    action = ACTION_REFRESH_OVERLAY
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var observeJob: Job? = null

    // Dynamic Island overlay views flanking the top front-facing camera island
    private var windowManager: WindowManager? = null
    private var dynamicIslandView: View? = null
    private var leftSpeedTextView: TextView? = null
    private var leftUnitTextView: TextView? = null
    private var rightPaceTextView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_SWAP_PACE -> {
                SpeedStateManager.swapPaces(this)
                updateNotification()
                updateDynamicIslandOverlay()
            }
            ACTION_REFRESH_OVERLAY -> {
                updateDynamicIslandOverlay()
            }
            ACTION_START, null -> {
                startForegroundWithNotification()
                observeSpeedChanges()
                updateDynamicIslandOverlay()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    private fun observeSpeedChanges() {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            launch {
                SpeedStateManager.currentSpeed.collect {
                    updateNotification()
                    updateDynamicIslandOverlay()
                }
            }
            launch {
                SpeedStateManager.previousSpeed.collect {
                    updateNotification()
                }
            }
            launch {
                SpeedStateManager.unitSystem.collect {
                    updateNotification()
                    updateDynamicIslandOverlay()
                }
            }
            launch {
                SpeedStateManager.connectedDeviceCount.collect {
                    updateNotification()
                }
            }
            launch {
                SpeedStateManager.isAppInForeground.collect {
                    updateDynamicIslandOverlay()
                }
            }
            launch {
                SpeedStateManager.isAdvertising.collect { advertising ->
                    if (!advertising) {
                        removeDynamicIslandOverlay()
                    } else {
                        updateDynamicIslandOverlay()
                    }
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).roundToInt()
    }

    private fun canShowDynamicIsland(): Boolean {
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        return hasOverlayPermission &&
            SpeedStateManager.isAdvertising.value &&
            !SpeedStateManager.isAppInForeground.value
    }

    private fun updateDynamicIslandOverlay() {
        if (!canShowDynamicIsland()) {
            removeDynamicIslandOverlay()
            return
        }

        if (dynamicIslandView == null) {
            createAndAttachDynamicIsland()
        }

        val currentSpeed = SpeedStateManager.currentSpeed.value
        val unit = SpeedStateManager.unitSystem.value
        if (unit == "metric") {
            val pace = calculatePace(currentSpeed, isMetric = true)
            leftSpeedTextView?.text = String.format(Locale.US, "%.1f", currentSpeed)
            leftUnitTextView?.text = " km/h"
            rightPaceTextView?.text = "$pace /km"
        } else {
            val mphSpeed = currentSpeed * 0.621371f
            val pace = calculatePace(currentSpeed, isMetric = false)
            leftSpeedTextView?.text = String.format(Locale.US, "%.1f", mphSpeed)
            leftUnitTextView?.text = " mph"
            rightPaceTextView?.text = "$pace /mi"
        }
    }

    private fun createAndAttachDynamicIsland() {
        val wm = windowManager ?: return
        if (dynamicIslandView != null) return

        // Outer pill container that wraps around the top front-facing camera island
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#000000"))
                setStroke(dpToPx(1), Color.parseColor("#664ADE80"))
            }
            setOnClickListener {
                // Tap Dynamic Island to return to PaceCast
                val openIntent = Intent(this@BleForegroundService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(openIntent)
            }
        }

        // Left Wing (to the left of the front-facing camera cutout): Pulsing green dot + Speed
        val leftWing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(86),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(7), dpToPx(7)).apply {
                marginEnd = dpToPx(5)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4ADE80"))
            }
        }

        val speedTv = TextView(this).apply {
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        val unitTv = TextView(this).apply {
            setTextColor(Color.parseColor("#D4D4D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        leftWing.addView(statusDot)
        leftWing.addView(speedTv)
        leftWing.addView(unitTv)

        // Center Front-Facing Camera Island Spacer (38dp gap for the center punch-hole camera)
        val cameraHoleSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(20))
        }

        // Right Wing (to the right of the front-facing camera cutout): Live Pace
        val rightWing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(86),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val paceTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        rightWing.addView(paceTv)

        container.addView(leftWing)
        container.addView(cameraHoleSpacer)
        container.addView(rightWing)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(4)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(container, params)
            dynamicIslandView = container
            leftSpeedTextView = speedTv
            leftUnitTextView = unitTv
            rightPaceTextView = paceTv
        } catch (_: Exception) {
            dynamicIslandView = null
        }
    }

    private fun removeDynamicIslandOverlay() {
        val view = dynamicIslandView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {}
        dynamicIslandView = null
        leftSpeedTextView = null
        leftUnitTextView = null
        rightPaceTextView = null
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val currentSpeed = SpeedStateManager.currentSpeed.value
        val prevSpeed = SpeedStateManager.previousSpeed.value
        val unit = SpeedStateManager.unitSystem.value

        val (displaySpeed, displayPrevSpeed, unitLabel, paceLabel) = if (unit == "metric") {
            val pace = calculatePace(currentSpeed, isMetric = true)
            Tuple4(
                String.format(Locale.US, "%.1f", currentSpeed),
                String.format(Locale.US, "%.1f", prevSpeed),
                "km/h",
                "$pace /km"
            )
        } else {
            val mphSpeed = currentSpeed * 0.621371f
            val mphPrev = prevSpeed * 0.621371f
            val pace = calculatePace(currentSpeed, isMetric = false)
            Tuple4(
                String.format(Locale.US, "%.1f", mphSpeed),
                String.format(Locale.US, "%.1f", mphPrev),
                "mph",
                "$pace /mi"
            )
        }

        // Tap notification to open MainActivity
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action to Swap Pace from Lock Screen / Notification
        val swapIntent = Intent(this, BleForegroundService::class.java).apply {
            action = ACTION_SWAP_PACE
        }
        val swapPendingIntent = PendingIntent.getService(
            this,
            1,
            swapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isConnected = SpeedStateManager.connectedDeviceCount.value > 0
        val connectionStatus = if (isConnected) "Connected to Watch" else "Broadcasting"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PaceCast: $displaySpeed $unitLabel ($paceLabel) • $connectionStatus")
            .setContentText("Prev Pace: $displayPrevSpeed $unitLabel • Tap to switch")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Visible on secure lock screen
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground,
                    "Switch to $displayPrevSpeed $unitLabel",
                    swapPendingIntent
                ).build()
            )
            .build()
    }

    private data class Tuple4(val val1: String, val val2: String, val val3: String, val val4: String)

    private fun calculatePace(speedKmh: Float, isMetric: Boolean): String {
        if (speedKmh <= 0.1f) return "--:--"
        val factor = if (isMetric) 1.0f else 0.621371f
        val effectiveSpeed = speedKmh * factor
        val minutesPerUnit = 60.0f / effectiveSpeed
        val minutes = minutesPerUnit.toInt()
        val seconds = ((minutesPerUnit - minutes) * 60).toInt()
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PaceCast::BleBroadcastingWakeLock"
            )?.apply {
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun stopForegroundService() {
        removeDynamicIslandOverlay()
        releaseWakeLock()
        observeJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PaceCast BLE Broadcasting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BLE foot pod advertising active with screen locked"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeDynamicIslandOverlay()
        releaseWakeLock()
        observeJob?.cancel()
        serviceScope.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
