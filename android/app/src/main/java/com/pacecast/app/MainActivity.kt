package com.pacecast.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pacecast.app.ble.BleForegroundService
import com.pacecast.app.ble.BleRscServer
import com.pacecast.app.ble.SpeedStateManager
import com.pacecast.app.ble.WatchConnectionDetector
import com.pacecast.app.ui.PaceCastScreen
import com.pacecast.app.ui.theme.PaceCastTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bleServer: BleRscServer
    private lateinit var watchDetector: WatchConnectionDetector

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            bleServer.startAdvertising()
            BleForegroundService.startService(this)
            Toast.makeText(this, "Broadcasting started (PaceCast)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled to broadcast", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBluetoothAndAdvertise()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        enableFullScreenImmersive()

        SpeedStateManager.init(this)
        bleServer = BleRscServer(this)
        watchDetector = WatchConnectionDetector(this)
        watchDetector.start()

        lifecycleScope.launch {
            watchDetector.connectedWatchName.collect { name ->
                SpeedStateManager.updateActivePhoneLinkWatchName(name)
            }
        }

        // Start broadcasting immediately when the app is opened
        startAdvertisingWithPermissions()

        setContent {
            PaceCastTheme {
                PaceCastAppContent(
                    bleServer = bleServer,
                    onToggleAdvertising = {
                        toggleAdvertisingWithPermissions()
                    }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullScreenImmersive()
        }
    }

    private fun enableFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startAdvertisingWithPermissions() {
        if (bleServer.isAdvertising.value) return

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (requiredPermissions.isEmpty()) {
            checkBluetoothAndAdvertise()
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    private fun checkBluetoothAndAdvertise() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bleServer.startAdvertising()
            BleForegroundService.startService(this)
            Toast.makeText(this, "Broadcasting started (PaceCast)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleAdvertisingWithPermissions() {
        if (bleServer.isAdvertising.value) {
            bleServer.stopAdvertising()
            BleForegroundService.stopService(this)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "BLE Advertising Stopped", Toast.LENGTH_SHORT).show()
            return
        }

        startAdvertisingWithPermissions()
    }

    override fun onResume() {
        super.onResume()
        SpeedStateManager.setAppInForeground(true)
        if (bleServer.isAdvertising.value) {
            BleForegroundService.refreshOverlay(this)
        }
    }

    override fun onPause() {
        super.onPause()
        SpeedStateManager.setAppInForeground(false)
        if (bleServer.isAdvertising.value) {
            BleForegroundService.refreshOverlay(this)
        }
    }

    override fun onStop() {
        super.onStop()
        SpeedStateManager.setAppInForeground(false)
        // If we stopped broadcasting and the app is closed/backgrounded, do not keep any service or wake lock alive
        if (!bleServer.isAdvertising.value) {
            BleForegroundService.stopService(this)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            BleForegroundService.refreshOverlay(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SpeedStateManager.setAppInForeground(false)
        watchDetector.stop()
        bleServer.shutdown()
        BleForegroundService.stopService(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
fun PaceCastAppContent(
    bleServer: BleRscServer,
    onToggleAdvertising: () -> Unit
) {
    var showSplash by remember { mutableStateOf(true) }
    val splashScale = remember { Animatable(0.05f) }
    val splashAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        splashScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 500)
        )
        splashAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 700)
        )
        showSplash = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PaceCastScreen(
            bleServer = bleServer,
            onToggleAdvertising = onToggleAdvertising
        )

        if (showSplash) {
            PaceCastSplashScreen(
                scale = splashScale.value,
                alpha = splashAlpha.value
            )
        }
    }
}

@Composable
fun PaceCastSplashScreen(scale: Float, alpha: Float) {
    val splashGreen = Color(0xFFEAFECD)
    // Fade background color from green (#EAFECD) to black (#000000) as alpha animates from 1f -> 0f
    val bgColor = androidx.compose.ui.graphics.lerp(Color.Black, splashGreen, alpha)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor.copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.panda_splash),
            contentDescription = "PaceCast Splash",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
            contentScale = ContentScale.Fit
        )
    }
}
