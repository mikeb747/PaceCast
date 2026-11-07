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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

class BleForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pacecast_ble_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pacecast.app.ACTION_START"
        const val ACTION_STOP = "com.pacecast.app.ACTION_STOP"
        const val ACTION_SWAP_PACE = "com.pacecast.app.ACTION_SWAP_PACE"

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
            val intent = Intent(context, BleForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
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
            }
            ACTION_START, null -> {
                startForegroundWithNotification()
                observeSpeedChanges()
            }
        }

        return START_STICKY
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
                }
            }
        }
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PaceCast: $displaySpeed $unitLabel ($paceLabel)")
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

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        observeJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
