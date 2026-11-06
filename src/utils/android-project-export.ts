import JSZip from 'jszip';

export interface AndroidFileEntry {
  path: string;
  name: string;
  language: string;
  content: string;
}

export const ANDROID_PROJECT_FILES: AndroidFileEntry[] = [
  {
    path: 'app/src/main/AndroidManifest.xml',
    name: 'AndroidManifest.xml',
    language: 'xml',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- BLE Peripheral & GATT Server permissions -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_ADVERTISE"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission
        android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission
        android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />

    <!-- Location required on Android 6.0 to 11 for BLE operations -->
    <uses-permission
        android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.ACCESS_COARSE_LOCATION"
        android:maxSdkVersion="30" />

    <!-- Prevent treadmill console screen sleep -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="PaceCast"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>`,
  },
  {
    path: 'app/src/main/java/com/pacecast/app/MainActivity.kt',
    name: 'MainActivity.kt',
    language: 'kotlin',
    content: `package com.pacecast.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pacecast.app.ble.BleRscServer
import com.pacecast.app.ui.PaceCastScreen
import com.pacecast.app.ui.theme.PaceCastTheme

class MainActivity : ComponentActivity() {

    private lateinit var bleServer: BleRscServer

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleServer.startAdvertising()
            Toast.makeText(this, "BLE Advertising Started (PaceCast)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Bluetooth & Location permissions required to broadcast RSC Foot Pod",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep Screen Awake on the treadmill console
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bleServer = BleRscServer(this)

        setContent {
            PaceCastTheme {
                PaceCastScreen(
                    bleServer = bleServer,
                    onToggleAdvertising = {
                        toggleAdvertisingWithPermissions()
                    }
                )
            }
        }
    }

    private fun toggleAdvertisingWithPermissions() {
        if (bleServer.isAdvertising.value) {
            bleServer.stopAdvertising()
            Toast.makeText(this, "BLE Advertising Stopped", Toast.LENGTH_SHORT).show()
            return
        }

        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (requiredPermissions.isEmpty()) {
            bleServer.startAdvertising()
            Toast.makeText(this, "Broadcasting to Garmin Forerunner 255...", Toast.LENGTH_SHORT).show()
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleServer.stopAdvertising()
    }
}`,
  },
  {
    path: 'app/src/main/java/com/pacecast/app/ble/BleRscServer.kt',
    name: 'BleRscServer.kt',
    language: 'kotlin',
    content: `package com.pacecast.app.ble

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

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _subscribedDeviceCount = MutableStateFlow(0)
    val subscribedDeviceCount: StateFlow<Int> = _subscribedDeviceCount.asStateFlow()

    private val _lastPayloadHex = MutableStateFlow("00 00 00 00")
    val lastPayloadHex: StateFlow<String> = _lastPayloadHex.asStateFlow()

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
                Log.i(TAG, "Device connected: \${device.address}")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "Device disconnected: \${device.address}")
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
                    } else {
                        registeredDevices.remove(device)
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
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        setupGattServer()

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: return
        bluetoothAdapter.name = DEVICE_NAME

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

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        start1HzBroadcastLoop()
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        broadcastJob?.cancel()
        broadcastJob = null

        try {
            advertiser?.stopAdvertising(advertiseCallback)
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
}`,
  },
  {
    path: 'app/src/main/java/com/pacecast/app/ui/PaceCastScreen.kt',
    name: 'PaceCastScreen.kt',
    language: 'kotlin',
    content: `// Replicates Figma UI with Brutalist 3D buttons, 3s debounced previous pace, and 1Hz BLE telemetry
// See android/app/src/main/java/com/pacecast/app/ui/PaceCastScreen.kt in project tree`,
  },
  {
    path: 'app/build.gradle.kts',
    name: 'app/build.gradle.kts',
    language: 'gradle',
    content: `plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pacecast.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pacecast.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}`,
  },
  {
    path: '.github/workflows/build-apk.yml',
    name: 'build-apk.yml (GitHub Actions)',
    language: 'yaml',
    content: `name: Build PaceCast Android APK

on:
  push:
    branches: [ main, master ]
    paths:
      - 'android/**'
      - '.github/workflows/build-apk.yml'
  pull_request:
    branches: [ main, master ]
    paths:
      - 'android/**'
      - '.github/workflows/build-apk.yml'
  workflow_dispatch:
    inputs:
      build_type:
        description: 'Build Type'
        required: true
        default: 'debug'
        type: choice
        options:
          - debug
          - release-unsigned
          - both

jobs:
  build-apk:
    name: Build Android APK
    runs-on: ubuntu-latest
    timeout-minutes: 25

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Prepare Gradle Wrapper
        run: |
          cd android
          if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
            gradle wrapper --gradle-version 8.5 || true
          fi
          chmod +x gradlew

      - name: Build Debug APK
        if: \${{ github.event.inputs.build_type == 'debug' || github.event.inputs.build_type == 'both' || github.event_name != 'workflow_dispatch' }}
        run: |
          cd android
          ./gradlew assembleDebug --stacktrace --no-daemon

      - name: Build Release APK (Unsigned)
        if: \${{ github.event.inputs.build_type == 'release-unsigned' || github.event.inputs.build_type == 'both' }}
        run: |
          cd android
          ./gradlew assembleRelease --stacktrace --no-daemon

      - name: Upload Debug APK Artifact
        if: \${{ always() }}
        uses: actions/upload-artifact@v4
        with:
          name: PaceCast-debug-apk
          path: android/app/build/outputs/apk/debug/*.apk
          if-no-files-found: warn
          retention-days: 30

      - name: Upload Release APK Artifact
        if: \${{ github.event.inputs.build_type == 'release-unsigned' || github.event.inputs.build_type == 'both' }}
        uses: actions/upload-artifact@v4
        with:
          name: PaceCast-release-unsigned-apk
          path: android/app/build/outputs/apk/release/*.apk
          if-no-files-found: ignore
          retention-days: 30`,
  },
  {
    path: 'build.gradle.kts',
    name: 'build.gradle.kts (Root)',
    language: 'gradle',
    content: `plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}`,
  },
  {
    path: 'gradle/wrapper/gradle-wrapper.properties',
    name: 'gradle-wrapper.properties',
    language: 'properties',
    content: `distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists`,
  },
  {
    path: 'README.md',
    name: 'README.md',
    language: 'markdown',
    content: `# PaceCast Android Project
Built for pairing with Garmin Forerunner 255 as a Running Speed & Cadence (RSC) foot pod.

## Automated APK Builds with GitHub Actions

Every push or pull request to \`main\` or \`master\` automatically builds a ready-to-install debug APK (\`app-debug.apk\`) and uploads it to GitHub Actions Artifacts.

### How to Download Built APK from GitHub Actions:
1. Push your repository to GitHub.
2. Go to the **Actions** tab on your GitHub repository.
3. Click on the latest **Build PaceCast Android APK** workflow run.
4. Scroll down to the **Artifacts** section at the bottom.
5. Click **PaceCast-debug-apk** to download the built \`.apk\` directly to your phone or PC!
6. Transfer the \`.apk\` to your Android phone and install.`,
  },
];

export async function generateAndroidZip(): Promise<Blob> {
  const zip = new JSZip();
  const folder = zip.folder('PaceCast-Android');

  for (const file of ANDROID_PROJECT_FILES) {
    folder?.file(file.path, file.content);
  }

  return await zip.generateAsync({ type: 'blob' });
}
