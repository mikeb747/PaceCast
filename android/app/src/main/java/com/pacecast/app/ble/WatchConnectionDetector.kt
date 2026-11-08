package com.pacecast.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method

class WatchConnectionDetector(private val context: Context) {

    companion object {
        private const val TAG = "WatchDetector"

        private val WATCH_KEYWORDS = listOf(
            "forerunner", "garmin", "fenix", "epix", "venu", "enduro", "marq",
            "tactix", "instinct", "vivoactive", "coros", "polar", "suunto", "apple watch"
        )
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectedWatchName = MutableStateFlow<String?>(null)
    val connectedWatchName: StateFlow<String?> = _connectedWatchName.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var pollJob: Job? = null

    private val isConnectedMethod: Method? by lazy {
        try {
            BluetoothDevice::class.java.getMethod("isConnected")
        } catch (_: Exception) {
            null
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    checkConnectedWatch()
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            context.registerReceiver(bluetoothReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register bluetooth receiver", e)
        }

        checkConnectedWatch()

        // Also poll every 3 seconds while active
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(3000L)
                checkConnectedWatch()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun checkConnectedWatch() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _connectedWatchName.value = null
            return
        }

        try {
            val bondedDevices = adapter.bondedDevices ?: emptySet()
            val candidateWatches = bondedDevices.filter { device ->
                val name = device.name ?: ""
                WATCH_KEYWORDS.any { keyword -> name.contains(keyword, ignoreCase = true) }
            }

            if (candidateWatches.isEmpty()) {
                _connectedWatchName.value = null
                return
            }

            // Check if any candidate watch has an active ACL/GATT connection
            val connectedGattDevices = try {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            for (watch in candidateWatches) {
                val isGattConnected = connectedGattDevices.any { it.address == watch.address }
                val isAclConnected = try {
                    (isConnectedMethod?.invoke(watch) as? Boolean) == true
                } catch (_: Exception) {
                    false
                }

                if (isGattConnected || isAclConnected) {
                    val displayName = watch.name ?: "Garmin Watch"
                    _connectedWatchName.value = displayName
                    return
                }
            }

            _connectedWatchName.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connected watch", e)
            _connectedWatchName.value = null
        }
    }
}
