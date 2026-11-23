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
import android.graphics.Rect
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
import kotlin.math.roundToInt

class BleForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pacecast_ble_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pacecast.app.ACTION_START"
        const val ACTION_STOP = "com.pacecast.app.ACTION_STOP"
        const val ACTION_SWAP_PACE = "com.pacecast.app.ACTION_SWAP_PACE"
        const val ACTION_REFRESH_OVERLAY = "com.pacecast.app.ACTION_REFRESH_OVERLAY"
        const val ACTION_TOGGLE_DEBUG = "com.pacecast.app.ACTION_TOGGLE_DEBUG"

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
    private var pillContainerView: LinearLayout? = null
    private var dynamicIslandLayoutParams: WindowManager.LayoutParams? = null
    private var leftWingView: LinearLayout? = null
    private var rightWingView: LinearLayout? = null
    private var cameraHoleSpacerView: View? = null
    private var leftSpeedTextView: TextView? = null
    private var leftUnitTextView: TextView? = null
    private var rightPaceTextView: TextView? = null
    private var rightPaceUnitTextView: TextView? = null

    // Long-press debug overlay view showing DisplayCutout & Dynamic Island metrics
    private var debugOverlayView: View? = null
    private var debugInfoTextView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        if (SpeedStateManager.cameraCutoutRect.value == null) {
            SpeedStateManager.init(this)
        }
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
            ACTION_TOGGLE_DEBUG -> {
                SpeedStateManager.toggleIslandDebug()
                updateNotification()
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
            launch {
                SpeedStateManager.isIslandDebugEnabled.collect {
                    updateNotification()
                    updateDynamicIslandOverlay()
                }
            }
            launch {
                SpeedStateManager.islandYDeltaPx.collect {
                    applyCutoutPositionAndSize()
                }
            }
            launch {
                SpeedStateManager.islandHeightDeltaPx.collect {
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

    private fun pxToDpStr(px: Int): String {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        return String.format(Locale.US, "%.1f", px / density)
    }

    private fun getScreenWidthPx(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.width()
                ?: resources.displayMetrics.widthPixels
        } else {
            resources.displayMetrics.widthPixels
        }
    }

    private fun getScreenHeightPx(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.height()
                ?: resources.displayMetrics.heightPixels
        } else {
            resources.displayMetrics.heightPixels
        }
    }

    private fun canShowDynamicIsland(): Boolean {
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        // Show when minimized while broadcasting, OR while in-app if Island Debug mode is active
        return hasOverlayPermission &&
            SpeedStateManager.isAdvertising.value &&
            (!SpeedStateManager.isAppInForeground.value || SpeedStateManager.isIslandDebugEnabled.value)
    }

    private fun resolveSystemDisplayCutout(): android.view.DisplayCutout? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cutout = windowManager?.currentWindowMetrics?.windowInsets?.displayCutout
            if (cutout != null) return cutout
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val cutout = windowManager?.defaultDisplay?.cutout
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

    private fun getUserYDeltaPx(): Int {
        return SpeedStateManager.islandYDeltaPx.value
    }

    private fun getUserHeightDeltaPx(): Int {
        return SpeedStateManager.islandHeightDeltaPx.value
    }

    private fun adjustUserIslandMetrics(dY: Int, dH: Int, reset: Boolean = false) {
        SpeedStateManager.adjustIslandMetrics(dY, dH, reset, this)
        applyCutoutPositionAndSize()
        refreshDebugOverlayText()
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
            density = resources.displayMetrics.density,
            userYDeltaPx = getUserYDeltaPx(),
            userHeightDeltaPx = getUserHeightDeltaPx()
        )
    }

    private fun computeTouchWrapperHeight(targetY: Int, targetPillHeight: Int, holeRect: Rect?): Int {
        val sysCutout = resolveSystemDisplayCutout()
        val safeTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sysCutout != null) {
            sysCutout.safeInsetTop
        } else {
            holeRect?.bottom ?: dpToPx(44)
        }
        // Extend transparent touch hitbox 24dp below safeInsetTop so finger taps on the island register outside the StatusBar intercept strip
        return maxOf(targetPillHeight, (safeTop - targetY) + dpToPx(24))
    }

    private fun openMainAppFromOverlay() {
        val openIntent = Intent(this@BleForegroundService, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            startActivity(openIntent)
        } catch (_: Exception) {
            try {
                val pi = PendingIntent.getActivity(
                    this@BleForegroundService,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pi.send()
            } catch (_: Exception) {}
        }
    }

    private fun applyCutoutPositionAndSize() {
        val rootView = dynamicIslandView ?: return
        val pillView = pillContainerView ?: return
        val leftWing = leftWingView ?: return
        val rightWing = rightWingView ?: return
        val spacer = cameraHoleSpacerView ?: return
        val params = dynamicIslandLayoutParams ?: return
        val wm = windowManager ?: return

        val holeRect = resolveCameraCutoutRect()
        val pathBounds = resolveCameraCutoutPathBounds()
        val metrics = computeIslandMetrics(holeRect, pathBounds)
        val targetPillHeight = metrics.pillHeightPx
        val targetSpacerWidth = metrics.spacerWidthPx
        val targetY = metrics.targetY
        val touchWrapperHeight = computeTouchWrapperHeight(targetY, targetPillHeight, holeRect)

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

        refreshDebugOverlayText()
    }

    private fun buildDebugInfoString(): String {
        val holeRect = resolveCameraCutoutRect()
        val pathBounds = resolveCameraCutoutPathBounds()
        val sysCutout = resolveSystemDisplayCutout()
        val metrics = computeIslandMetrics(holeRect, pathBounds)
        val pillHeightPx = metrics.pillHeightPx
        val spacerWidthPx = metrics.spacerWidthPx
        val targetY = metrics.targetY

        val leftW = leftWingView?.layoutParams?.width ?: leftWingView?.measuredWidth ?: 0
        val totalWidth = (dpToPx(8) * 2) + (leftW * 2) + spacerWidthPx

        val density = resources.displayMetrics.density
        val screenW = getScreenWidthPx()
        val screenH = getScreenHeightPx()

        val cutoutRectStr = if (holeRect != null) {
            "L=${holeRect.left}, T=${holeRect.top}, R=${holeRect.right}, B=${holeRect.bottom}\n" +
                "  size=${holeRect.width()}x${holeRect.height()}px, center=(${holeRect.centerX()}, ${holeRect.centerY()})\n" +
                "  estLensTop=${metrics.estLensTopPx ?: (holeRect.bottom - holeRect.width())}px, estLensCenterY=${metrics.estLensCenterYPx ?: (holeRect.bottom - holeRect.width() / 2)}px"
        } else {
            "null (fallback used)"
        }

        val pathStr = if (pathBounds != null) {
            "L=${pathBounds.left}, T=${pathBounds.top}, R=${pathBounds.right}, B=${pathBounds.bottom} (${pathBounds.width()}x${pathBounds.height()}px)"
        } else {
            "N/A"
        }

        val safeInsetsStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sysCutout != null) {
            "T=${sysCutout.safeInsetTop}px, B=${sysCutout.safeInsetBottom}px, L=${sysCutout.safeInsetLeft}px, R=${sysCutout.safeInsetRight}px"
        } else {
            "N/A"
        }

        val allRectsCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sysCutout != null) {
            sysCutout.boundingRects.size
        } else {
            if (holeRect != null) 1 else 0
        }

        return buildString {
            append("DISPLAY CUTOUT (rects=$allRectsCount):\n")
            append("  $cutoutRectStr\n")
            append("  pathBounds: $pathStr\n")
            append("  safeInsets: $safeInsetsStr\n")
            append("  screen: ${screenW}x${screenH}px (${String.format(Locale.US, "%.2f", density)}x)\n\n")
            append("DYNAMIC ISLAND OVERLAY [${metrics.mode.label}]:\n")
            append("  top (y): $targetY px (${pxToDpStr(targetY)} dp) [adj=${getUserYDeltaPx()}px]\n")
            append("  height:  $pillHeightPx px (${pxToDpStr(pillHeightPx)} dp) [adj=${getUserHeightDeltaPx()}px]\n")
            append("  bottom:  ${targetY + pillHeightPx} px (centerY=${targetY + pillHeightPx / 2} px)\n")
            append("  width:   $totalWidth px (wing=${leftW}px, gap=${spacerWidthPx}px)")
        }
    }

    private fun refreshDebugOverlayText() {
        debugInfoTextView?.text = buildDebugInfoString()
    }

    private fun toggleDebugOverlay() {
        val next = debugOverlayView == null
        SpeedStateManager.setIslandDebugEnabled(next)
        if (next) {
            showDebugOverlay()
        } else {
            removeDebugOverlay()
        }
    }

    private fun createDebugActionButton(label: String, bgColor: String = "#222527", textColor: String = "#FFFFFF", onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(textColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor(bgColor))
                setStroke(dpToPx(1), Color.parseColor("#3E4448"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = dpToPx(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showDebugOverlay() {
        val wm = windowManager ?: return
        if (debugOverlayView != null) return

        val holeRect = resolveCameraCutoutRect()
        val metrics = computeIslandMetrics(holeRect)
        val pillHeightPx = metrics.pillHeightPx
        val targetY = metrics.targetY

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#F2111315"))
                setStroke(dpToPx(1), Color.parseColor("#4ADE80"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "DYNAMIC ISLAND & CUTOUT DEBUG"
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(6))
        }

        val infoTv = TextView(this).apply {
            text = buildDebugInfoString()
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, 0, 0, dpToPx(8))
        }

        // Row 1: Live Top Y and Height pixel nudges
        val nudgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
        }
        nudgeRow.addView(createDebugActionButton("Y -1px") { adjustUserIslandMetrics(-1, 0) })
        nudgeRow.addView(createDebugActionButton("Y +1px") { adjustUserIslandMetrics(+1, 0) })
        nudgeRow.addView(createDebugActionButton("H -2px") { adjustUserIslandMetrics(0, -2) })
        nudgeRow.addView(createDebugActionButton("H +2px") { adjustUserIslandMetrics(0, +2) })

        // Row 2: Open App, Reset & Close buttons
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        actionRow.addView(createDebugActionButton("Open App", "#2563EB", "#FFFFFF") {
            SpeedStateManager.setIslandDebugEnabled(false)
            removeDebugOverlay()
            val openIntent = Intent(this@BleForegroundService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(openIntent)
        })
        actionRow.addView(createDebugActionButton("Reset", "#2A2E33", "#FCD34D") {
            adjustUserIslandMetrics(0, 0, reset = true)
        })
        actionRow.addView(createDebugActionButton("Close", "#16A34A", "#FFFFFF") {
            SpeedStateManager.setIslandDebugEnabled(false)
            removeDebugOverlay()
        })

        card.addView(titleTv)
        card.addView(infoTv)
        card.addView(nudgeRow)
        card.addView(actionRow)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Guarantee debug card sits below safeInsetTop (status bar touch-intercept zone) so all buttons receive touches
        val sysCutout = resolveSystemDisplayCutout()
        val safeTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sysCutout != null) {
            sysCutout.safeInsetTop
        } else {
            holeRect?.bottom ?: dpToPx(44)
        }
        val safeDebugY = maxOf(safeTop + dpToPx(8), targetY + pillHeightPx + dpToPx(10), dpToPx(48))

        val debugParams = WindowManager.LayoutParams(
            dpToPx(330),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = safeDebugY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(card, debugParams)
            debugOverlayView = card
            debugInfoTextView = infoTv
        } catch (_: Exception) {
            debugOverlayView = null
            debugInfoTextView = null
        }
    }

    private fun removeDebugOverlay() {
        val view = debugOverlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {}
        debugOverlayView = null
        debugInfoTextView = null
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

        if (SpeedStateManager.isIslandDebugEnabled.value && !SpeedStateManager.isAppInForeground.value) {
            showDebugOverlay()
            refreshDebugOverlayText()
        } else {
            removeDebugOverlay()
        }
    }

    private fun createAndAttachDynamicIsland() {
        val wm = windowManager ?: return
        if (dynamicIslandView != null) return

        val holeRect = resolveCameraCutoutRect()
        val metrics = computeIslandMetrics(holeRect)
        val initialPillHeight = metrics.pillHeightPx
        val initialSpacerWidth = metrics.spacerWidthPx
        val initialY = metrics.targetY
        val initialTouchWrapperHeight = computeTouchWrapperHeight(initialY, initialPillHeight, holeRect)

        // Visible black pill container that wraps tightly around the text flanking the top camera island (no green outline)
        val pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = initialPillHeight
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
            setOnClickListener {
                openMainAppFromOverlay()
            }
        }

        // Transparent touch wrapper extending slightly below safeInsetTop so tapping the Dynamic Island registers and re-opens PaceCast
        val touchWrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                openMainAppFromOverlay()
            }
        }

        // Left Wing (to the left of the front-facing camera cutout): Speed + Unit (aligned next to camera hole, no dot)
        val leftWing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val speedTv = TextView(this).apply {
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        val unitTv = TextView(this).apply {
            setTextColor(Color.parseColor("#D4D4D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        leftWing.addView(speedTv)
        leftWing.addView(unitTv)

        // Center Front-Facing Camera Island Spacer sized to the device's physical camera cutout
        val cameraHoleSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(initialSpacerWidth, initialPillHeight)
        }

        // Right Wing (to the right of the front-facing camera cutout): Live Pace + Unit (aligned next to camera hole)
        val rightWing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val paceTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }

        val paceUnitTv = TextView(this).apply {
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

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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
            wm.addView(touchWrapper, params)
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
        } catch (_: Exception) {
            dynamicIslandView = null
            pillContainerView = null
            dynamicIslandLayoutParams = null
            leftWingView = null
            rightWingView = null
            cameraHoleSpacerView = null
        }
    }

    private fun removeDynamicIslandOverlay() {
        removeDebugOverlay()
        val view = dynamicIslandView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {}
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

        if (SpeedStateManager.isIslandDebugEnabled.value) {
            val debugIntent = Intent(this, BleForegroundService::class.java).apply {
                action = ACTION_TOGGLE_DEBUG
            }
            val debugPendingIntent = PendingIntent.getService(
                this,
                2,
                debugIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground,
                    "Hide Debug",
                    debugPendingIntent
                ).build()
            )
        }

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
