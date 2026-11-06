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

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _subscribedDeviceCount = MutableStateFlow(0)
    val subscribedDeviceCount: StateFlow<Int> = _subscribedDeviceCount.asStateFlow()

    private val _lastPayloadHex = MutableStateFlow("00 00 00 00")
    val lastPayloadHex: StateFlow<String> = _lastPayloadHex.asStateFlow()

    var currentSpeedKmh: Float = 16.8f

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var broadcastJob: Job? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            _isAdvertising.value = true
            Log.d(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            _isAdvertising.value = false
            Log.e(TAG, "BLE advertising failed with code $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(registeredDevices) {
                    registeredDevices.remove(device)
                    _subscribedDeviceCount.value = registeredDevices.size
                }
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
            if (descriptor.uuid == CCCD_DESCRIPTOR_UUID) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    synchronized(registeredDevices) {
                        registeredDevices.add(device)
                        _subscribedDeviceCount.value = registeredDevices.size
                    }
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    synchronized(registeredDevices) {
                        registeredDevices.remove(device)
                        _subscribedDeviceCount.value = registeredDevices.size
                    }
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

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(RSC_SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        start1HzBroadcastLoop()
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        broadcastJob?.cancel()
        broadcastJob = null

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        try {
            gattServer?.close()
            gattServer = null
        } catch (_: Exception) {}

        synchronized(registeredDevices) {
            registeredDevices.clear()
            _subscribedDeviceCount.value = 0
        }

        _isAdvertising.value = false
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

        val speedKmh = currentSpeedKmh.coerceIn(0f, 30f)
        val speedMs = speedKmh / 3.6f
        val rawSpeed = (speedMs * 256f).roundToInt().coerceIn(0, 65535)

        val payload = ByteArray(4)
        payload[0] = 0x00.toByte()
        payload[1] = (rawSpeed and 0xFF).toByte()
        payload[2] = ((rawSpeed shr 8) and 0xFF).toByte()
        payload[3] = 0x00.toByte()

        char.value = payload
        _lastPayloadHex.value = payload.joinToString(" ") { String.format("%02X", it) }

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
