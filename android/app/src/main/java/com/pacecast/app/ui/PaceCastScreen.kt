package com.pacecast.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pacecast.app.ble.BleRscServer
import com.pacecast.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PaceCastScreen(
    bleServer: BleRscServer,
    onToggleAdvertising: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAdvertising by bleServer.isAdvertising.collectAsState()
    val deviceCount by bleServer.subscribedDeviceCount.collectAsState()
    val lastHex by bleServer.lastPayloadHex.collectAsState()

    var currentSpeed by remember { mutableFloatStateOf(16.8f) }
    var previousSpeed by remember { mutableFloatStateOf(6.0f) }
    var keypadBuffer by remember { mutableStateOf("") }

    // 3-second debounce state management
    var isAdjustmentBurstActive by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Keep BleRscServer synchronized with current speed
    LaunchedEffect(currentSpeed) {
        bleServer.currentSpeedKmh = currentSpeed
    }

    // Helper functions for pace transitions
    fun applyAdjustment(delta: Float) {
        if (!isAdjustmentBurstActive) {
            previousSpeed = currentSpeed
            isAdjustmentBurstActive = true
        }

        debounceJob?.cancel()
        debounceJob = coroutineScope.launch {
            delay(3000L)
            isAdjustmentBurstActive = false
        }

        val next = ((currentSpeed + delta) * 10f).roundToInt() / 10f
        currentSpeed = next.coerceIn(0.0f, 30.0f)
    }

    fun swapPaces() {
        debounceJob?.cancel()
        isAdjustmentBurstActive = false

        val temp = currentSpeed
        currentSpeed = previousSpeed
        previousSpeed = temp
    }

    fun submitKeypadPace() {
        debounceJob?.cancel()
        isAdjustmentBurstActive = false

        val parsed = keypadBuffer.toFloatOrNull()
        if (parsed != null) {
            val clamped = ((parsed * 10f).roundToInt() / 10f).coerceIn(0.0f, 30.0f)
            previousSpeed = currentSpeed
            currentSpeed = clamped
        }
        keypadBuffer = ""
    }

    fun handleKeyPress(key: String) {
        if (key == "DEL") {
            if (keypadBuffer.isNotEmpty()) {
                keypadBuffer = keypadBuffer.dropLast(1)
            }
            return
        }

        if (key == "." && keypadBuffer.contains(".")) return
        if (keypadBuffer.contains(".") && keypadBuffer.substringAfter(".").length >= 1) return

        val candidate = if (keypadBuffer == "0" && key != ".") key else keypadBuffer + key
        val num = candidate.toFloatOrNull()
        if (num != null && num > 30.0f) return
        if (candidate.length <= 4) {
            keypadBuffer = candidate
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalOuter)
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(CharcoalCard)
                .border(1.dp, CharcoalBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with PaceCast logo & interactive status pill
            HeaderSection(
                isAdvertising = isAdvertising,
                deviceCount = deviceCount,
                onToggle = onToggleAdvertising
            )

            // Current Pace Card
            CurrentPaceCard(
                speedKmh = currentSpeed,
                onAdjust = { delta -> applyAdjustment(delta) }
            )

            // Previous Pace Card & Keypad Display Row
            RecallAndDisplayRow(
                previousSpeed = previousSpeed,
                keypadBuffer = keypadBuffer,
                currentSpeed = currentSpeed,
                onRecall = { swapPaces() }
            )

            // Exact Pace Numpad Section
            KeypadSection(
                keypadBuffer = keypadBuffer,
                onKeyPress = { key -> handleKeyPress(key) },
                onSubmit = { submitKeypadPace() }
            )

            // Live BLE Foot Pod Monitor (Garmin 255 RSC GATT info)
            BleTelemetryFooter(
                isAdvertising = isAdvertising,
                lastHex = lastHex,
                speedKmh = currentSpeed
            )
        }
    }
}

@Composable
private fun HeaderSection(
    isAdvertising: Boolean,
    deviceCount: Int,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo & Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MintGreen.copy(alpha = 0.15f))
                    .border(1.dp, MintGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚡",
                    fontSize = 18.sp
                )
            }
            Column {
                Text(
                    text = "PaceCast",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Garmin 255 Foot Pod",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Interactive Status Pill
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (isAdvertising) MintGreen.copy(alpha = 0.12f) else CharcoalSurface)
                .border(
                    width = 1.dp,
                    color = if (isAdvertising) MintGreen.copy(alpha = 0.5f) else CharcoalBorder,
                    shape = RoundedCornerShape(20.dp)
                )
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(if (isAdvertising) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(if (isAdvertising) MintGreen else TextMuted)
            )
            Text(
                text = if (isAdvertising) "Broadcasting" else "Standby",
                color = if (isAdvertising) MintGreen else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CurrentPaceCard(
    speedKmh: Float,
    onAdjust: (Float) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    fun calculatePace(speed: Float): String {
        if (speed <= 0f) return "--:--"
        val totalSecs = (3600f / speed).roundToInt()
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.US, "%d:%02d", mins, secs)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CharcoalSurface)
            .border(1.dp, CharcoalBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Speed & Pace Readouts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.US, "%.1f", speedKmh),
                        color = TextPrimary,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "km/h",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Text(
                    text = "SPEED",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(48.dp)
                    .background(CharcoalBorder)
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = calculatePace(speedKmh),
                        color = MintGreen,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "/km",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Text(
                    text = "PACE",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Tactile Brutalist 3D Adjuster Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Minus Button
            Tactile3DButton(
                text = "−",
                backgroundColor = ButtonNeutralBg,
                borderColor = ButtonNeutralBorder,
                textColor = Color.White,
                modifier = Modifier.weight(1f),
                onSingleTap = { onAdjust(-0.1f) },
                onHoldTick = { onAdjust(-0.5f) }
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = "± 0.1",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Hold for 0.5",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }

            // Plus Button (Mint Green)
            Tactile3DButton(
                text = "+",
                backgroundColor = MintGreen,
                borderColor = MintGreenDark,
                textColor = CharcoalCard,
                modifier = Modifier.weight(1f),
                onSingleTap = { onAdjust(+0.1f) },
                onHoldTick = { onAdjust(+0.5f) }
            )
        }
    }
}

@Composable
private fun RecallAndDisplayRow(
    previousSpeed: Float,
    keypadBuffer: String,
    currentSpeed: Float,
    onRecall: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous Pace Card (Tap to swap)
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(ButtonNeutralBg)
                .border(1.dp, CharcoalBorder, RoundedCornerShape(14.dp))
                .clickable { onRecall() }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Previous pace",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", previousSpeed),
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "km/h",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Swap interval pace",
                    tint = MintGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Keypad Display Preview Box
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(CharcoalSurface)
                .border(
                    width = 1.dp,
                    color = if (keypadBuffer.isNotEmpty()) MintGreen.copy(alpha = 0.5f) else CharcoalBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (keypadBuffer.isNotEmpty()) keypadBuffer else "—",
                    color = if (keypadBuffer.isNotEmpty()) MintGreen else TextSecondary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "km/h",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun KeypadSection(
    keypadBuffer: String,
    onKeyPress: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "DEL")
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { key ->
                    Keypad3DButton(
                        text = key,
                        modifier = Modifier.weight(1f),
                        onClick = { onKeyPress(key) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Enter Pace Button
        val isEnabled = keypadBuffer.isNotEmpty()
        Button(
            onClick = { if (isEnabled) onSubmit() },
            enabled = isEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MintGreen,
                disabledContainerColor = ButtonNeutralBg.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Enter pace",
                    color = if (isEnabled) CharcoalCard else TextMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (isEnabled) CharcoalCard else TextMuted
                )
            }
        }
    }
}

@Composable
private fun Tactile3DButton(
    text: String,
    backgroundColor: Color,
    borderColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onSingleTap: () -> Unit,
    onHoldTick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(borderColor)
            .padding(bottom = if (isPressed) 1.dp else 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onSingleTap()

                        val holdJob = coroutineScope.launch {
                            delay(450L) // initial hold delay
                            while (isPressed) {
                                onHoldTick()
                                delay(190L) // repeat rate
                            }
                        }

                        tryAwaitRelease()
                        isPressed = false
                        holdJob.cancel()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun Keypad3DButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111213))
            .padding(bottom = if (isPressed) 1.dp else 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222527))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BleTelemetryFooter(
    isAdvertising: Boolean,
    lastHex: String,
    speedKmh: Float
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141517))
            .border(1.dp, CharcoalBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "GATT RSC (0x1814) 1Hz Loop",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isAdvertising) "ACTIVE" else "IDLE",
                color = if (isAdvertising) MintGreen else TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Little-Endian Payload:",
                color = TextMuted,
                fontSize = 11.sp
            )
            Text(
                text = lastHex,
                color = MintGreen,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
