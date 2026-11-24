package com.pacecast.app.ble

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Lightweight AccessibilityService whose sole purpose is providing a WindowManager context
 * capable of attaching the Dynamic Island pill with WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
 * (Z-Layer 22), which sits above Android's SystemUI Status Bar (TYPE_STATUS_BAR, Z-Layer 15).
 *
 * This allows direct taps on the Dynamic Island inside the top camera-cutout / status-bar strip
 * to reach PaceCast instead of being swallowed by the Status Bar window.
 */
class PaceCastIslandAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PaceCastIsland"

        @Volatile
        var instance: PaceCastIslandAccessibilityService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val pkg = context.packageName
                enabledServices.contains("$pkg/${PaceCastIslandAccessibilityService::class.java.canonicalName}") ||
                    enabledServices.contains("$pkg/.ble.PaceCastIslandAccessibilityService")
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "PaceCastIslandAccessibilityService connected (TYPE_ACCESSIBILITY_OVERLAY ready)")
        BleForegroundService.refreshOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No accessibility events are monitored or read.
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d(TAG, "PaceCastIslandAccessibilityService unbound")
        BleForegroundService.refreshOverlay(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
