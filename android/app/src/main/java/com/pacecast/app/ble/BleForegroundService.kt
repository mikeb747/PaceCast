package com.pacecast.app.ble

import android.annotation.SuppressLint
import android.app.ActivityOptions
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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
import kotlin.math.abs
import kotlin.math.roundToInt

class BleForegroundService : Service() {

    companion object {
        private const val TAG = "PaceCastIsland"
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
            context.startForegroundService(intent)
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
    private var activeOverlayWindowManager: WindowManager? = null
    private var dynamicIslandView: View? = null
    private var pillContainerView: LinearLayout? = null
    private var dynamicIslandLayoutParams: WindowManager.LayoutParams? = null
    private var leftWingView: LinearLayout? = null
    private var rightWingView: LinearLayout? = null
    private var cameraHoleSpacerView: View? = null
    private var leftSpeedTextView: TextView? = null
    private var leftUnitTextView: TextView? = null
    private var rightPaceTextView: TextView? = null
    private var rightPaceUnitTextView: TextView? = null
    private var lastOverlayOpenRequestMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        if (SpeedStateManager.cameraCutoutRect.value == null) {
            SpeedStateManager.init(this)
        }
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
            launch {
                SpeedStateManager.cameraCutoutRect.collect {
                    applyCutoutPositionAndSize()
                }
            }
            launch {
                SpeedStateManager.cameraCutoutPathBounds.collect {
                    applyCutoutPositionAndSize()
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
        return PaceCastIslandAccessibilityService.instance != null &&
            SpeedStateManager.isAdvertising.value &&
            !SpeedStateManager.isAppInForeground.value
    }

    private fun resolveSystemDisplayCutout(): android.view.DisplayCutout? {
        val wm = activeOverlayWindowManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cutout = wm.currentWindowMetrics.windowInsets.displayCutout
            if (cutout != null) return cutout
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val cutout = wm.defaultDisplay?.cutout
            if (cutout != null) return cutout
        }
        return null
    }

    private fun resolveCameraCutoutRect(): Rect? {
        val sysCutout = resolveSystemDisplayCutout()
        val rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            sysCutout?.boundingRects?.filter { !it.isEmpty }?.minByOrNull { it.top }
        } else {
            null
        }

        if (rect != null) {
            SpeedStateManager.updateCameraCutoutRect(rect, this)
            return rect
        }

        return SpeedStateManager.cameraCutoutRect.value
    }

    private fun resolveCameraCutoutPathBounds(): Rect? {
        val sysCutout = resolveSystemDisplayCutout()
        val pathBounds = DynamicIslandGeometry.extractCutoutPathBounds(sysCutout)
        if (pathBounds != null) {
            SpeedStateManager.updateCameraCutoutPathBounds(pathBounds, this)
            return pathBounds
        }
        return SpeedStateManager.cameraCutoutPathBounds.value
    }

    /**
     * Computes density-independent Dynamic Island geometry from DisplayCutout metrics.
     */
    private fun computeIslandMetrics(
        holeRect: Rect?,
        cutoutPathBounds: Rect? = resolveCameraCutoutPathBounds()
    ): DynamicIslandMetrics {
        return DynamicIslandGeometry.compute(
            holeRect = holeRect,
            cutoutPathBounds = cutoutPathBounds,
            density = resources.displayMetrics.density
        )
    }

    private fun openMainAppFromOverlay() {
        Log.d(TAG, "openMainAppFromOverlay() called (inForeground=${SpeedStateManager.isAppInForeground.value})")
        if (SpeedStateManager.isAppInForeground.value) return

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastOverlayOpenRequestMs < 400L) return
        lastOverlayOpenRequestMs = nowMs

        val launchCtx: Context = PaceCastIslandAccessibilityService.instance ?: this@BleForegroundService
        val intent = (packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this@BleForegroundService, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        val pi = PendingIntent.getActivity(
            launchCtx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val opts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                ).toBundle()
        } else {
            null
        }

        try {
            pi.send(launchCtx, 0, null, null, null, null, opts)
            Log.d(TAG, "PendingIntent.send() succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent.send() threw exception, falling back to startActivity", e)
            try {
                launchCtx.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "startActivity fallback failed", e2)
            }
        }
    }

    private fun applyCutoutPositionAndSize() {
        val rootView = dynamicIslandView ?: return
        val pillView = pillContainerView ?: return
        val leftWing = leftWingView ?: return
        val rightWing = rightWingView ?: return
        val spacer = cameraHoleSpacerView ?: return
        val params = dynamicIslandLayoutParams ?: return
        val wm = activeOverlayWindowManager ?: return

        val holeRect = resolveCameraCutoutRect()
        val pathBounds = resolveCameraCutoutPathBounds()
        val metrics = computeIslandMetrics(holeRect, pathBounds)
        val targetPillHeight = metrics.pillHeightPx
        val targetSpacerWidth = metrics.spacerWidthPx
        val targetY = metrics.targetY
        val touchWrapperHeight = targetPillHeight + dpToPx(8)

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        leftWing.measure(unspecified, unspecified)
        rightWing.measure(unspecified, unspecified)
        val wingWidth = maxOf(leftWing.measuredWidth, rightWing.measuredWidth)

        val leftLp = leftWing.layoutParams as? LinearLayout.LayoutParams
        if (leftLp != null && leftLp.width != wingWidth) {
            leftLp.width = wingWidth
            leftWing.layoutParams = leftLp
        }
        val rightLp = rightWing.layoutParams as? LinearLayout.LayoutParams
        if (rightLp != null && rightLp.width != wingWidth) {
            rightLp.width = wingWidth
            rightWing.layoutParams = rightLp
        }

        val spacerLp = spacer.layoutParams as? LinearLayout.LayoutParams
        if (spacerLp != null && (spacerLp.width != targetSpacerWidth || spacerLp.height != targetPillHeight)) {
            spacerLp.width = targetSpacerWidth
            spacerLp.height = targetPillHeight
            spacer.layoutParams = spacerLp
        }
        val pillLp = pillView.layoutParams as? FrameLayout.LayoutParams
        if (pillLp != null && pillLp.height != targetPillHeight) {
            pillLp.height = targetPillHeight
            pillView.layoutParams = pillLp
        }
        if (pillView.minimumHeight != targetPillHeight) {
            pillView.minimumHeight = targetPillHeight
        }
        (pillView.background as? GradientDrawable)?.cornerRadius = targetPillHeight / 2f

        if (params.x != 0 || params.y != targetY || params.height != touchWrapperHeight) {
            params.x = 0
            params.y = targetY
            params.height = touchWrapperHeight
            if (rootView.isAttachedToWindow) {
                try {
                    wm.updateViewLayout(rootView, params)
                } catch (_: Exception) {}
            }
        }
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
            rightPaceTextView?.text = pace
            rightPaceUnitTextView?.text = " /km"
        } else {
            val mphSpeed = currentSpeed * 0.621371f
            val pace = calculatePace(currentSpeed, isMetric = false)
            leftSpeedTextView?.text = String.format(Locale.US, "%.1f", mphSpeed)
            leftUnitTextView?.text = " mph"
            rightPaceTextView?.text = pace
            rightPaceUnitTextView?.text = " /mi"
        }

        applyCutoutPositionAndSize()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createAndAttachDynamicIsland() {
        if (dynamicIslandView != null) return

        val a11yService = PaceCastIslandAccessibilityService.instance ?: return
        val targetWm = a11yService.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val uiContext: Context = a11yService
        val overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val holeRect = resolveCameraCutoutRect()
        val metrics = computeIslandMetrics(holeRect)
        val initialPillHeight = metrics.pillHeightPx
        val initialSpacerWidth = metrics.spacerWidthPx
        val initialY = metrics.targetY
        val initialTouchWrapperHeight = initialPillHeight + dpToPx(8)

        // Visible black pill container that wraps tightly around the text flanking the top camera island.
        // Kept non-clickable so touchWrapper handles the entire hitbox as one unified target.
        val pillContainer = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = initialPillHeight
            isClickable = false
            isFocusable = false
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                initialPillHeight,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = initialPillHeight / 2f
                setColor(Color.parseColor("#000000"))
            }
        }

        val touchWrapper = FrameLayout(uiContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            isClickable = true
            isFocusable = false
            contentDescription = "Open PaceCast"
            setOnClickListener {
                openMainAppFromOverlay()
            }

            var downRawX = 0f
            var downRawY = 0f
            var downTimeMs = 0L
            val maxTapSlopPx = dpToPx(36).toFloat()

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        Log.d(TAG, "Dynamic Island ACTION_DOWN at raw=(${event.rawX}, ${event.rawY}) layerType=$overlayType")
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downTimeMs = event.eventTime
                        pillContainer.animate()
                            .scaleX(0.95f)
                            .scaleY(0.95f)
                            .alpha(0.85f)
                            .setDuration(70L)
                            .start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.rawX - downRawX)
                        val dy = abs(event.rawY - downRawY)
                        if (dx > maxTapSlopPx || dy > maxTapSlopPx) {
                            pillContainer.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(90L)
                                .start()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        pillContainer.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(90L)
                            .start()
                        val dx = abs(event.rawX - downRawX)
                        val dy = abs(event.rawY - downRawY)
                        val durationMs = event.eventTime - downTimeMs
                        Log.d(TAG, "Dynamic Island ACTION_UP dx=$dx dy=$dy durationMs=$durationMs")
                        if (dx <= maxTapSlopPx && dy <= maxTapSlopPx && durationMs <= 1200L) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            view.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        pillContainer.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(90L)
                            .start()
                        true
                    }
                    else -> false
                }
            }
        }

        // Left Wing (to the left of the front-facing camera cutout): Speed + Unit
        val leftWing = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val speedTv = TextView(uiContext).apply {
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        val unitTv = TextView(uiContext).apply {
            setTextColor(Color.parseColor("#D4D4D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        leftWing.addView(speedTv)
        leftWing.addView(unitTv)

        // Center Front-Facing Camera Island Spacer sized to the device's physical camera cutout
        val cameraHoleSpacer = View(uiContext).apply {
            layoutParams = LinearLayout.LayoutParams(initialSpacerWidth, initialPillHeight)
        }

        // Right Wing (to the right of the front-facing camera cutout): Live Pace + Unit
        val rightWing = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val paceTv = TextView(uiContext).apply {
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        val paceUnitTv = TextView(uiContext).apply {
            setTextColor(Color.parseColor("#D4D4D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        rightWing.addView(paceTv)
        rightWing.addView(paceUnitTv)

        pillContainer.addView(leftWing)
        pillContainer.addView(cameraHoleSpacer)
        pillContainer.addView(rightWing)
        touchWrapper.addView(pillContainer)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            initialTouchWrapperHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = initialY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            targetWm.addView(touchWrapper, params)
            activeOverlayWindowManager = targetWm
            dynamicIslandView = touchWrapper
            pillContainerView = pillContainer
            dynamicIslandLayoutParams = params
            leftWingView = leftWing
            rightWingView = rightWing
            cameraHoleSpacerView = cameraHoleSpacer
            leftSpeedTextView = speedTv
            leftUnitTextView = unitTv
            rightPaceTextView = paceTv
            rightPaceUnitTextView = paceUnitTv
            Log.d(TAG, "Dynamic Island attached with TYPE_ACCESSIBILITY_OVERLAY y=$initialY h=$initialPillHeight")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Dynamic Island", e)
            activeOverlayWindowManager = null
            dynamicIslandView = null
            pillContainerView = null
            dynamicIslandLayoutParams = null
            leftWingView = null
            rightWingView = null
            cameraHoleSpacerView = null
        }
    }

    private fun removeDynamicIslandOverlay() {
        val view = dynamicIslandView ?: return
        val wm = activeOverlayWindowManager
        try {
            wm?.removeView(view)
        } catch (_: Exception) {}
        activeOverlayWindowManager = null
        dynamicIslandView = null
        pillContainerView = null
        dynamicIslandLayoutParams = null
        leftWingView = null
        rightWingView = null
        cameraHoleSpacerView = null
        leftSpeedTextView = null
        leftUnitTextView = null
        rightPaceTextView = null
        rightPaceUnitTextView = null
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PaceCast: $displaySpeed $unitLabel ($paceLabel) • $connectionStatus")
            .setContentText("Prev Pace: $displayPrevSpeed $unitLabel • Tap to switch")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Visible on secure lock screen
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    "Switch to $displayPrevSpeed $unitLabel",
                    swapPendingIntent
                ).build()
            )

        return builder.build()
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
