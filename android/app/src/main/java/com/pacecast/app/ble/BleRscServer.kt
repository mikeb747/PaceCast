package com.pacecast.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
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
import java.util.UUID
import kotlin.math.roundToInt

/**
 * BLE Running Speed and Cadence (RSC) Peripheral & GATT Server for PaceCast.
 * Broadcasts speed to Garmin Forerunner 255 and other fitness head units.
 */
class BleRscServer(private val context: Context) {

    companion object {
        private const val TAG = "PaceCast_BleRscServer"

        // Standard Bluetooth SIG UUIDs
        val RSC_SERVICE_UUID: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        val RSC_FEATURE_CHAR_UUID: UUID = UUID.fromString("00002a54-0000-1000-8000-00805f9b34fb")
        val RSC_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEVICE_NAME = "PaceCast"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private var rscMeasurementChar: BluetoothGattCharacteristic? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var broadcastJob: Job? = null

    // State flows for UI observation
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _subscribedDeviceCount = MutableStateFlow(0)
    val subscribedDeviceCount: StateFlow<Int> = _subscribedDeviceCount.asStateFlow()

    private val _lastPayloadHex = MutableStateFlow("00 00 00 00")
    val lastPayloadHex: StateFlow<String> = _lastPayloadHex.asStateFlow()

    // Current speed source in km/h
    var currentSpeedKmh: Float = 16.8f

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.i(TAG, "BLE Advertising started successfully for $DEVICE_NAME")
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "BLE Advertising failed with error code: $errorCode")
            _isAdvertising.value = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "Device connected: ${device.address}")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "Device disconnected: ${device.address}")
                synchronized(registeredDevices) {
                    registeredDevices.remove(device)
                    _subscribedDeviceCount.value = registeredDevices.size
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == RSC_FEATURE_CHAR_UUID) {
                // Return [0x00, 0x00]: Instantaneous Speed and Cadence only
                val featureValue = byteArrayOf(0x00, 0x00)
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    featureValue
                )
                Log.d(TAG, "Handled RSC Feature read request for ${device.address}")
            } else {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    offset,
                    null
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor.uuid == CCCD_DESCRIPTOR_UUID) {
                val isNotify = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                synchronized(registeredDevices) {
                    if (isNotify) {
                        registeredDevices.add(device)
                        Log.i(TAG, "Device subscribed to RSC notifications: ${device.address}")
                    } else {
                        registeredDevices.remove(device)
                        Log.i(TAG, "Device unsubscribed from RSC notifications: ${device.address}")
                    }
                    _subscribedDeviceCount.value = registeredDevices.size
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    offset,
                    null
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled or unavailable")
            return
        }

        setupGattServer()

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser not supported on this device")
            return
        }

        // Set device name for Garmin scanner
        bluetoothAdapter.name = DEVICE_NAME

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(RSC_SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)

        start1HzBroadcastLoop()
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        broadcastJob?.cancel()
        broadcastJob = null

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising: ${e.message}")
        }

        try {
            gattServer?.close()
            gattServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server: ${e.message}")
        }

        synchronized(registeredDevices) {
            registeredDevices.clear()
            _subscribedDeviceCount.value = 0
        }

        _isAdvertising.value = false
        Log.i(TAG, "PaceCast BLE stopped")
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        if (gattServer != null) return

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT Server")
            return
        }

        val service = BluetoothGattService(
            RSC_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // 1. RSC Feature (0x2A54): Read-only returning [0x00, 0x00]
        val featureChar = BluetoothGattCharacteristic(
            RSC_FEATURE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        featureChar.value = byteArrayOf(0x00, 0x00)
        service.addCharacteristic(featureChar)

        // 2. RSC Measurement (0x2A53): Notify with CCCD (0x2902)
        val measurementChar = BluetoothGattCharacteristic(
            RSC_MEASUREMENT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        measurementChar.addDescriptor(cccd)
        service.addCharacteristic(measurementChar)

        gattServer?.addService(service)
        rscMeasurementChar = measurementChar
    }

    private fun start1HzBroadcastLoop() {
        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            while (isActive) {
                sendRscNotification()
                delay(1000L) // 1 Hz timer
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendRscNotification() {
        val server = gattServer ?: return
        val char = rscMeasurementChar ?: return

        // Speed conversion formula:
        // speedMs = currentPaceKmh / 3.6
        // rawSpeed = round(speedMs * 256).toInt()
        val speedKmh = currentSpeedKmh.coerceIn(0f, 30f)
        val speedMs = speedKmh / 3.6f
        val rawSpeed = (speedMs * 256f).roundToInt().coerceIn(0, 65535)

        // 4-byte Little-Endian RSC notification payload:
        // Byte 0: Flags (0x00)
        // Byte 1: Speed LSB (rawSpeed and 0xFF)
        // Byte 2: Speed MSB ((rawSpeed shr 8) and 0xFF)
        // Byte 3: Cadence (0x00)
        val payload = ByteArray(4)
        payload[0] = 0x00.toByte()
        payload[1] = (rawSpeed and 0xFF).toByte()
        payload[2] = ((rawSpeed shr 8) and 0xFF).toByte()
        payload[3] = 0x00.toByte()

        char.value = payload

        val hex = payload.joinToString(" ") { String.format("%02X", it) }
        _lastPayloadHex.value = hex

        synchronized(registeredDevices) {
            for (device in registeredDevices) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, char, false, payload)
                } else {
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, char, false)
                }
            }
        }
    }
}
