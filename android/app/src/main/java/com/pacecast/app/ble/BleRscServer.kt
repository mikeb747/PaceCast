package com.pacecast.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import kotlin.math.roundToInt

class BleRscServer(private val context: Context) {

    companion object {
        private const val TAG = "BleRscServer"

        val RSC_SERVICE_UUID: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        val RSC_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002A53-0000-1000-8000-00805f9b34fb")
        val RSC_FEATURE_CHAR_UUID: UUID = UUID.fromString("00002A54-0000-1000-8000-00805f9b34fb")
        val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var rscMeasurementChar: BluetoothGattCharacteristic? = null

    private val registeredDevices = Collections.synchronizedSet(HashSet<BluetoothDevice>())
    private val connectedDevices = Collections.synchronizedSet(HashSet<BluetoothDevice>())

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _subscribedDeviceCount = MutableStateFlow(0)
    val subscribedDeviceCount: StateFlow<Int> = _subscribedDeviceCount.asStateFlow()

    private val currentSpeedKmh: Float
        get() = SpeedStateManager.currentSpeed.value

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var broadcastJob: Job? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            _isAdvertising.value = true
            SpeedStateManager.setAdvertising(true)
            Log.d(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            _isAdvertising.value = false
            SpeedStateManager.setAdvertising(false)
            Log.e(TAG, "BLE advertising failed with code $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(TAG, "Device ${device.address} connection state changed: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                Log.d(TAG, "GATT Client connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                synchronized(registeredDevices) {
                    registeredDevices.remove(device)
                    _subscribedDeviceCount.value = registeredDevices.size
                    SpeedStateManager.updateConnectedDeviceCount(registeredDevices.size)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.d(TAG, "MTU changed for ${device.address} to $mtu")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            Log.d(TAG, "Read request for characteristic: ${characteristic.uuid}, offset=$offset")

            if (characteristic.uuid == RSC_FEATURE_CHAR_UUID) {
                // RSC Feature: instantaneous speed supported (0x00, 0x00)
                val featureValue = byteArrayOf(0x00, 0x00)
                val slice = if (offset < featureValue.size) featureValue.copyOfRange(offset, featureValue.size) else byteArrayOf()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else if (characteristic.uuid == RSC_MEASUREMENT_CHAR_UUID) {
                val value = characteristic.value ?: byteArrayOf(0x00, 0x00, 0x00, 0x00)
                val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else byteArrayOf()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            Log.d(TAG, "Read request for descriptor: ${descriptor.uuid}")
            if (descriptor.uuid == CCCD_DESCRIPTOR_UUID) {
                val isSubscribed = synchronized(registeredDevices) { registeredDevices.contains(device) }
                val responseValue = if (isSubscribed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                val slice = if (offset < responseValue.size) responseValue.copyOfRange(offset, responseValue.size) else byteArrayOf()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
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
            value: ByteArray
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            Log.d(TAG, "Write request for descriptor: ${descriptor.uuid}, value=${value.joinToString { "%02X".format(it) }}")
            if (descriptor.uuid == CCCD_DESCRIPTOR_UUID) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    connectedDevices.add(device)
                    synchronized(registeredDevices) {
                        registeredDevices.add(device)
                        _subscribedDeviceCount.value = registeredDevices.size
                        SpeedStateManager.updateConnectedDeviceCount(registeredDevices.size)
                    }
                    Log.d(TAG, "Device ${device.address} subscribed to RSC notifications. Active count: ${registeredDevices.size}")
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    synchronized(registeredDevices) {
                        registeredDevices.remove(device)
                        _subscribedDeviceCount.value = registeredDevices.size
                        SpeedStateManager.updateConnectedDeviceCount(registeredDevices.size)
                    }
                    Log.d(TAG, "Device ${device.address} unsubscribed from RSC notifications")
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (_isAdvertising.value) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        setupGattServer()

        bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Single Primary Advertising Packet with device name and RSC service
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(RSC_SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
        _isAdvertising.value = true
        SpeedStateManager.setAdvertising(true)
        start1HzBroadcastLoop()
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising(immediateClose: Boolean = false) {
        broadcastJob?.cancel()
        broadcastJob = null

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        val server = gattServer
        val char = rscMeasurementChar
        gattServer = null
        rscMeasurementChar = null

        if (server != null) {
            val allDevices = HashSet<BluetoothDevice>()
            synchronized(registeredDevices) { allDevices.addAll(registeredDevices) }
            synchronized(connectedDevices) { allDevices.addAll(connectedDevices) }
            try {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.let { allDevices.addAll(it) }
            } catch (_: Exception) {}

            // 1. Transmit an immediate 0.0 m/s frame so watch drops speed immediately
            if (char != null) {
                val zeroPayload = byteArrayOf(0x00, 0x00, 0x00, 0x00)
                char.value = zeroPayload
                for (device in allDevices) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(device, char, false, zeroPayload)
                        } else {
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(device, char, false)
                        }
                    } catch (_: Exception) {}
                }
            }

            // 2. Remove services and issue active BLE link-layer disconnect (cancelConnection)
            try {
                server.clearServices()
            } catch (_: Exception) {}

            for (device in allDevices) {
                try {
                    server.cancelConnection(device)
                    Log.d(TAG, "Sent cancelConnection disconnect to device: ${device.address}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel connection for ${device.address}", e)
                }
            }

            // 3. Allow Bluetooth controller ~200ms to transmit LL_TERMINATE_IND over the air before destroying GATT server handle
            if (immediateClose) {
                try {
                    server.close()
                } catch (_: Exception) {}
            } else {
                scope.launch {
                    delay(200L)
                    try {
                        server.close()
                    } catch (_: Exception) {}
                }
            }
        }

        connectedDevices.clear()
        synchronized(registeredDevices) {
            registeredDevices.clear()
            _subscribedDeviceCount.value = 0
            SpeedStateManager.updateConnectedDeviceCount(0)
        }

        _isAdvertising.value = false
        SpeedStateManager.setAdvertising(false)
    }

    fun shutdown() {
        stopAdvertising(immediateClose = true)
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        if (gattServer != null) return

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
        val service = BluetoothGattService(RSC_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val featureChar = BluetoothGattCharacteristic(
            RSC_FEATURE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        featureChar.value = byteArrayOf(0x00, 0x00)
        service.addCharacteristic(featureChar)

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
                delay(1000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendRscNotification() {
        val server = gattServer ?: return
        val char = rscMeasurementChar ?: return

        // Use 64-bit Double math normalized to 0.1 km/h to eliminate 32-bit Float representation drift
        val exactSpeedKmh = ((currentSpeedKmh.toDouble() * 10.0).roundToInt() / 10.0).coerceIn(0.0, 30.0)
        // Direct conversion to 1/256 m/s BLE RSC uint16 units for maximum possible accuracy
        val rawSpeed = ((exactSpeedKmh * 256.0) / 3.6).roundToInt().coerceIn(0, 65535)

        val payload = ByteArray(4)
        payload[0] = 0x00.toByte()
        payload[1] = (rawSpeed and 0xFF).toByte()
        payload[2] = ((rawSpeed shr 8) and 0xFF).toByte()
        payload[3] = 0x00.toByte()

        char.value = payload

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
