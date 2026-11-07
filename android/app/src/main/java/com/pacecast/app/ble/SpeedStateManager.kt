package com.pacecast.app.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

object SpeedStateManager {
    private const val PREFS_NAME = "pacecast_prefs"
    private const val KEY_CURRENT_SPEED = "current_speed"
    private const val KEY_PREVIOUS_SPEED = "previous_speed"
    private const val KEY_UNIT_SYSTEM = "unit_system"

    private val _currentSpeed = MutableStateFlow(16.8f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    private val _previousSpeed = MutableStateFlow(6.0f)
    val previousSpeed: StateFlow<Float> = _previousSpeed.asStateFlow()

    private val _unitSystem = MutableStateFlow("metric")
    val unitSystem: StateFlow<String> = _unitSystem.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _currentSpeed.value = prefs.getFloat(KEY_CURRENT_SPEED, 16.8f)
        _previousSpeed.value = prefs.getFloat(KEY_PREVIOUS_SPEED, 6.0f)
        _unitSystem.value = prefs.getString(KEY_UNIT_SYSTEM, "metric") ?: "metric"
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
