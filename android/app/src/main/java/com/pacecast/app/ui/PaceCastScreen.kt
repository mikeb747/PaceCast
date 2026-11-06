package com.pacecast.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

    var currentSpeed by remember { mutableFloatStateOf(16.8f) }
    var previousSpeed by remember { mutableFloatStateOf(6.0f) }
    var keypadBuffer by remember { mutableStateOf("") }

    // Dynamic Presets (52dp high, editable via 1s hold)
    var presets by remember { mutableStateOf(listOf(5.0f, 8.0f, 10.0f, 12.0f, 15.0f, 16.8f)) }
    var editingPresetIndex by remember { mutableStateOf<Int?>(null) }
    var presetEditBuffer by remember { mutableStateOf("") }

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

    fun selectPreset(preset: Float) {
        debounceJob?.cancel()
        isAdjustmentBurstActive = false

        previousSpeed = currentSpeed
        currentSpeed = preset.coerceIn(0.0f, 30.0f)
        keypadBuffer = ""
    }

    fun openPresetEditor(index: Int) {
        editingPresetIndex = index
        presetEditBuffer = String.format(Locale.US, "%.1f", presets[index])
    }

    fun savePresetEdit() {
        val index = editingPresetIndex ?: return
        val parsed = presetEditBuffer.toFloatOrNull() ?: return
        val clamped = ((parsed * 10f).roundToInt() / 10f).coerceIn(0.0f, 24.0f)
        val updated = presets.toMutableList()
        updated[index] = clamped
        presets = updated
        editingPresetIndex = null
        presetEditBuffer = ""
    }

    fun handlePresetKeypad(key: String) {
        if (key == "DEL") {
            if (presetEditBuffer.isNotEmpty()) {
                presetEditBuffer = presetEditBuffer.dropLast(1)
            }
            return
        }

        if (key == "." && presetEditBuffer.contains(".")) return
        if (presetEditBuffer.contains(".") && presetEditBuffer.substringAfter(".").length >= 1) return

        val candidate = if (presetEditBuffer == "0" && key != ".") key else presetEditBuffer + key
        val num = candidate.toFloatOrNull()
        if (num != null && num > 24.0f) return
        if (candidate.length <= 4) {
            presetEditBuffer = candidate
        }
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

    // Locks to 100% viewport height, no vertical scrolling, zero bottom empty space
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalCard)
            .systemBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: Intrinsic height (wrapContentHeight), 15% larger title & pill
            HeaderSection(
                isAdvertising = isAdvertising,
                deviceCount = deviceCount,
                onToggle = onToggleAdvertising
            )

            // Current Pace Card: Intrinsic height, 15% larger typography
            CurrentPaceCard(
                speedKmh = currentSpeed,
                onAdjust = { delta -> applyAdjustment(delta) }
            )

            // Speed Presets Section: Intrinsic height (52dp buttons)
            PresetsSection(
                currentSpeed = currentSpeed,
                presets = presets,
                onSelectPreset = { preset -> selectPreset(preset) },
                onLongPressPreset = { index -> openPresetEditor(index) }
            )

            // Previous Pace Card & Keypad Display Row: Intrinsic height
            RecallAndDisplayRow(
                previousSpeed = previousSpeed,
                keypadBuffer = keypadBuffer,
                currentSpeed = currentSpeed,
                onRecall = { swapPaces() }
            )

            // Exact Pace Numpad: Takes all remaining vertical space (Modifier.weight(1f))
            KeypadSection(
                keypadBuffer = keypadBuffer,
                onKeyPress = { key -> handleKeyPress(key) },
                onSubmit = { submitKeypadPace() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        // Edit Preset Dialog (0.0 - 24.0 km/h with 0.1 increments)
        if (editingPresetIndex != null) {
            Dialog(onDismissRequest = { editingPresetIndex = null }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = CharcoalCard,
                    border = BorderStroke(1.dp, CharcoalBorder),
                    modifier = Modifier.fillMaxWidth().padding(4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Edit Preset #${(editingPresetIndex ?: 0) + 1}",
                                    color = TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Enter speed 0.0 – 24.0 km/h (0.1 increments)",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(
                                onClick = { editingPresetIndex = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = TextSecondary
                                )
                            }
                        }

                        // Target Speed Preview Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CharcoalSurface)
                                .border(1.dp, CharcoalBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "TARGET SPEED",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = if (presetEditBuffer.isNotEmpty()) presetEditBuffer else "—",
                                            color = if (presetEditBuffer.isNotEmpty()) MintGreen else TextSecondary,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "km/h",
                                            color = TextSecondary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(bottom = 3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Quick Nudges Row (-0.5, -0.1, +0.1, +0.5)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(-0.5f, -0.1f, 0.1f, 0.5f).forEach { delta ->
                                OutlinedButton(
                                    onClick = {
                                        val cur = presetEditBuffer.toFloatOrNull() ?: 0f
                                        val next = ((cur + delta) * 10f).roundToInt() / 10f
                                        presetEditBuffer = String.format(Locale.US, "%.1f", next.coerceIn(0.0f, 24.0f))
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                    border = BorderStroke(1.dp, CharcoalBorder),
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = if (delta > 0) "+$delta" else "$delta",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        // 3x4 Numeric Keypad
                        val keys = listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9"),
                            listOf(".", "0", "DEL")
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            keys.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    row.forEach { key ->
                                        Keypad3DButton(
                                            text = key,
                                            modifier = Modifier.weight(1f).height(48.dp),
                                            onClick = { handlePresetKeypad(key) }
                                        )
                                    }
                                }
                            }
                        }

                        // Save and Cancel buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { editingPresetIndex = null },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = BorderStroke(1.dp, CharcoalBorder)
                            ) {
                                Text("Cancel", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { savePresetEdit() },
                                enabled = presetEditBuffer.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MintGreen,
                                    contentColor = CharcoalCard,
                                    disabledContainerColor = ButtonNeutralBg.copy(alpha = 0.5f),
                                    disabledContentColor = TextMuted
                                )
                            ) {
                                Text("Save Preset", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Title (Clean PaceCast, 15% larger: 28sp)
        Text(
            text = "PaceCast",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp
        )

        // Interactive Status Pill (15% larger: 14sp)
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
                    .size(9.dp)
                    .scale(if (isAdvertising) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(if (isAdvertising) MintGreen else TextMuted)
            )
            Text(
                text = if (isAdvertising) "Broadcasting" else "Standby",
                color = if (isAdvertising) MintGreen else TextSecondary,
                fontSize = 14.sp,
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
            .clip(RoundedCornerShape(16.dp))
            .background(CharcoalSurface)
            .border(1.dp, CharcoalBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Speed & Pace Readouts (15% larger typography)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.US, "%.1f", speedKmh),
                        color = TextPrimary,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "km/h",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                Text(
                    text = "SPEED",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(44.dp)
                    .background(CharcoalBorder)
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = calculatePace(speedKmh),
                        color = MintGreen,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "/km",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                Text(
                    text = "PACE",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Tactile Brutalist 3D Adjuster Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                modifier = Modifier.padding(horizontal = 2.dp)
            ) {
                Text(
                    text = "± 0.1",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Hold for 0.5",
                    color = TextSecondary,
                    fontSize = 11.sp
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
private fun PresetsSection(
    currentSpeed: Float,
    presets: List<Float>,
    onSelectPreset: (Float) -> Unit,
    onLongPressPreset: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PRESETS",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "Press and hold to change",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        // 6 Preset buttons in a horizontal row (Exactly 52.dp high)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            presets.forEachIndexed { index, preset ->
                val isSelected = Math.abs(currentSpeed - preset) < 0.05f
                PresetButton(
                    preset = preset,
                    isSelected = isSelected,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectPreset(preset) },
                    onLongPress = { onLongPressPreset(index) }
                )
            }
        }
    }
}

@Composable
private fun PresetButton(
    preset: Float,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val label = if (preset % 1f == 0f) String.format(Locale.US, "%.0f", preset) else String.format(Locale.US, "%.1f", preset)

    Box(
        modifier = modifier
            .height(52.dp) // Exactly 52 pixels high
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MintGreenDark else Color(0xFF111213))
            .padding(bottom = if (isPressed) 1.dp else 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) MintGreen else CharcoalSurface)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        val holdJob = coroutineScope.launch {
                            delay(1000L) // 1 second hold to trigger edit
                            onLongPress()
                        }
                        tryAwaitRelease()
                        isPressed = false
                        holdJob.cancel()
                    },
                    onTap = {
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) CharcoalCard else TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous Pace Card
        Box(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ButtonNeutralBg)
                .border(1.dp, CharcoalBorder, RoundedCornerShape(14.dp))
                .clickable { onRecall() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Previous pace",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", previousSpeed),
                            color = TextPrimary,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "km/h",
                            color = TextSecondary,
                            fontSize = 13.sp,
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
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CharcoalSurface)
                .border(
                    width = 1.dp,
                    color = if (keypadBuffer.isNotEmpty()) MintGreen.copy(alpha = 0.5f) else CharcoalBorder,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (keypadBuffer.isNotEmpty()) keypadBuffer else "—",
                    color = if (keypadBuffer.isNotEmpty()) MintGreen else TextSecondary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "km/h",
                    color = TextSecondary,
                    fontSize = 13.sp,
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
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "DEL")
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 4 equal-height rows for number keys (each weight(1f))
        keys.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { key ->
                    Keypad3DButton(
                        text = key,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onKeyPress(key) }
                    )
                }
            }
        }

        // 1 equal-height row for Enter Pace button (weight(1f))
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
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Enter pace",
                    color = if (isEnabled) CharcoalCard else TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (isEnabled) CharcoalCard else TextMuted,
                    modifier = Modifier.size(24.dp)
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
            .height(58.dp)
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
            fontSize = 34.sp,
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
            fontSize = 22.sp, // 22sp as specifically requested
            fontWeight = FontWeight.Bold
        )
    }
}
