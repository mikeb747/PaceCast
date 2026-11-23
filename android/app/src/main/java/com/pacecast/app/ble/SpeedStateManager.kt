package com.pacecast.app.ble

import android.content.Context
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

object SpeedStateManager {
    private const val PREFS_NAME = "pacecast_prefs"
    private const val KEY_CURRENT_SPEED = "current_speed"
    private const val KEY_PREVIOUS_SPEED = "previous_speed"
    private const val KEY_UNIT_SYSTEM = "unit_system"
    private const val KEY_CUTOUT_LEFT = "cutout_left"
    private const val KEY_CUTOUT_TOP = "cutout_top"
    private const val KEY_CUTOUT_RIGHT = "cutout_right"
    private const val KEY_CUTOUT_BOTTOM = "cutout_bottom"

    private val _currentSpeed = MutableStateFlow(16.8f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _previousSpeed = MutableStateFlow(6.0f)
    val previousSpeed: StateFlow<Float> = _previousSpeed.asStateFlow()

    private val _unitSystem = MutableStateFlow("metric")
    val unitSystem: StateFlow<String> = _unitSystem.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    private val _activePhoneLinkWatchName = MutableStateFlow<String?>(null)
    val activePhoneLinkWatchName: StateFlow<String?> = _activePhoneLinkWatchName.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _cameraCutoutRect = MutableStateFlow<Rect?>(null)
    val cameraCutoutRect: StateFlow<Rect?> = _cameraCutoutRect.asStateFlow()

    fun updateConnectedDeviceCount(count: Int) {
        _connectedDeviceCount.value = count
    }

    fun updateActivePhoneLinkWatchName(name: String?) {
        _activePhoneLinkWatchName.value = name
    }

    fun setAppInForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
    }

    fun setAdvertising(advertising: Boolean) {
        _isAdvertising.value = advertising
    }

    fun updateCameraCutoutRect(rect: Rect, context: Context? = null) {
        if (rect.isEmpty) return
        if (_cameraCutoutRect.value == rect) return
        _cameraCutoutRect.value = Rect(rect)
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putInt(KEY_CUTOUT_LEFT, rect.left)
            putInt(KEY_CUTOUT_TOP, rect.top)
            putInt(KEY_CUTOUT_RIGHT, rect.right)
            putInt(KEY_CUTOUT_BOTTOM, rect.bottom)
            apply()
        }
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _currentSpeed.value = prefs.getFloat(KEY_CURRENT_SPEED, 16.8f)
        _previousSpeed.value = prefs.getFloat(KEY_PREVIOUS_SPEED, 6.0f)
        _unitSystem.value = prefs.getString(KEY_UNIT_SYSTEM, "metric") ?: "metric"
        val left = prefs.getInt(KEY_CUTOUT_LEFT, -1)
        val top = prefs.getInt(KEY_CUTOUT_TOP, -1)
        val right = prefs.getInt(KEY_CUTOUT_RIGHT, -1)
        val bottom = prefs.getInt(KEY_CUTOUT_BOTTOM, -1)
        if (left >= 0 && top >= 0 && right > left && bottom > top) {
            _cameraCutoutRect.value = Rect(left, top, right, bottom)
        }
    }

    fun setSpeed(speed: Float, context: Context? = null) {
        val clamped = ((speed * 10f).roundToInt() / 10f).coerceIn(0.0f, 30.0f)
        _previousSpeed.value = _currentSpeed.value
        _currentSpeed.value = clamped
        save(context)
    }

    fun updateSpeedDirectly(speed: Float, context: Context? = null) {
        val clamped = ((speed * 10f).roundToInt() / 10f).coerceIn(0.0f, 30.0f)
        _currentSpeed.value = clamped
        save(context)
    }

    fun setPreviousSpeedDirectly(speed: Float, context: Context? = null) {
        val clamped = ((speed * 10f).roundToInt() / 10f).coerceIn(0.0f, 30.0f)
        _previousSpeed.value = clamped
        save(context)
    }

    fun swapPaces(context: Context? = null) {
        val temp = _currentSpeed.value
        _currentSpeed.value = _previousSpeed.value
        _previousSpeed.value = temp
        save(context)
    }

    fun setUnitSystem(unit: String, context: Context? = null) {
        _unitSystem.value = unit
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putString(KEY_UNIT_SYSTEM, unit)
            apply()
        }
    }

    private fun save(context: Context?) {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putFloat(KEY_CURRENT_SPEED, _currentSpeed.value)
            putFloat(KEY_PREVIOUS_SPEED, _previousSpeed.value)
            apply()
        }
    }
}
