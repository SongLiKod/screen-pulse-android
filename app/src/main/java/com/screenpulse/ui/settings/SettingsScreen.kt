package com.screenpulse.ui.settings

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenpulse.repository.*
import com.screenpulse.viewmodel.SettingsViewModel
import com.screenpulse.ui.regionselect.RegionSelectActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToRegionSelect: () -> Unit
) {
    val context = LocalContext.current
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val audioMode by settingsViewModel.audioMode.collectAsState()
    val resolution by settingsViewModel.resolution.collectAsState()
    val frameRate by settingsViewModel.frameRate.collectAsState()
    val bitrate by settingsViewModel.bitrate.collectAsState()
    val compressionMode by settingsViewModel.compressionMode.collectAsState()
    val recordMode by settingsViewModel.recordMode.collectAsState()
    val countdownMode by settingsViewModel.countdownMode.collectAsState()
    val watermarkEnabled by settingsViewModel.watermarkEnabled.collectAsState()
    val watermarkText by settingsViewModel.watermarkText.collectAsState()
    val watermarkType by settingsViewModel.watermarkType.collectAsState()
    val watermarkImageUri by settingsViewModel.watermarkImageUri.collectAsState()
    val systemVolume by settingsViewModel.systemVolume.collectAsState()
    val micVolume by settingsViewModel.micVolume.collectAsState()
    val pipEnabled by settingsViewModel.pipEnabled.collectAsState()
    val pipSize by settingsViewModel.pipSize.collectAsState()
    val shortcutKey by settingsViewModel.shortcutKey.collectAsState()
    val shortcutKeyName by settingsViewModel.shortcutKeyName.collectAsState()
    val customResolutionWidth by settingsViewModel.customResolutionWidth.collectAsState()
    val customResolutionHeight by settingsViewModel.customResolutionHeight.collectAsState()
    val bitrateMode by settingsViewModel.bitrateMode.collectAsState()

    var showBatteryDialog by remember { mutableStateOf(false) }
    var customWidthText by remember(customResolutionWidth) { mutableStateOf(customResolutionWidth.toString()) }
    var customHeightText by remember(customResolutionHeight) { mutableStateOf(customResolutionHeight.toString()) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { settingsViewModel.setWatermarkImageUri(it.toString()) }
    }

    LaunchedEffect(Unit) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            showBatteryDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SectionTitle("Theme")
            ThemeSelector(themeMode) { settingsViewModel.setThemeMode(it) }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Recording Quality")

            SettingsDropdown(
                label = "Resolution",
                value = resolution.value,
                options = Resolution.entries.map { it.value },
                onSelected = { settingsViewModel.setResolution(Resolution.fromValue(it)) }
            )

            if (resolution == Resolution.CUSTOM) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Custom Resolution", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customWidthText,
                                onValueChange = {
                                    customWidthText = it
                                    it.toIntOrNull()?.let { w -> settingsViewModel.setCustomResolutionWidth(w) }
                                },
                                label = { Text("Width") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Text("x", fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = customHeightText,
                                onValueChange = {
                                    customHeightText = it
                                    it.toIntOrNull()?.let { h -> settingsViewModel.setCustomResolutionHeight(h) }
                                },
                                label = { Text("Height") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsDropdown(
                label = "Frame Rate",
                value = "${frameRate.value} FPS",
                options = FrameRate.entries.map { "${it.value} FPS" },
                onSelected = {
                    val fps = it.replace(" FPS", "").toInt()
                    settingsViewModel.setFrameRate(FrameRate.fromValue(fps))
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            BitrateModeSelector(
                selectedMode = bitrateMode,
                onModeSelected = { settingsViewModel.setBitrateMode(it) }
            )

            if (bitrateMode == BitrateMode.MANUAL) {
                Spacer(modifier = Modifier.height(12.dp))
                BitrateSlider(
                    bitrate = bitrate,
                    onBitrateChanged = { settingsViewModel.setBitrate(it) }
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                val smartBitrate = BitrateMode.calculateSmartBitrate(resolution, frameRate)
                Text(
                    text = "Smart adaptive: ${smartBitrate / 1_000_000} Mbps",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Audio")

            AudioModeSelector(
                selectedMode = audioMode,
                onModeSelected = { settingsViewModel.setAudioMode(it) }
            )

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "System audio recording requires Android 10+. Only microphone recording is available on this device.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            }

            if (audioMode == AudioMode.SYSTEM_ONLY || audioMode == AudioMode.MIXED) {
                Spacer(modifier = Modifier.height(12.dp))
                VolumeSlider(
                    label = "System Volume",
                    value = systemVolume,
                    onValueChanged = { settingsViewModel.setSystemVolume(it) }
                )
            }

            if (audioMode == AudioMode.MIC_ONLY || audioMode == AudioMode.MIXED) {
                Spacer(modifier = Modifier.height(12.dp))
                VolumeSlider(
                    label = "Mic Volume",
                    value = micVolume,
                    onValueChanged = { settingsViewModel.setMicVolume(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Record Mode")

            RecordModeSelector(
                selectedMode = recordMode,
                onModeSelected = { settingsViewModel.setRecordMode(it) }
            )

            if (recordMode == RecordMode.CUSTOM_REGION) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNavigateToRegionSelect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Select Recording Region")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Compression")

            CompressionModeSelector(
                selectedMode = compressionMode,
                onModeSelected = { settingsViewModel.setCompressionMode(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Countdown")

            CountdownSelector(
                selectedMode = countdownMode,
                onModeSelected = { settingsViewModel.setCountdownMode(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Watermark")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Watermark")
                    Switch(
                        checked = watermarkEnabled,
                        onCheckedChange = { settingsViewModel.setWatermarkEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }

            if (watermarkEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        WatermarkType.entries.forEach { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { settingsViewModel.setWatermarkType(type) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = watermarkType == type,
                                    onClick = { settingsViewModel.setWatermarkType(type) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (type) {
                                        WatermarkType.TEXT -> "Text Watermark"
                                        WatermarkType.IMAGE -> "Image Watermark"
                                    }
                                )
                            }
                        }
                    }
                }

                if (watermarkType == WatermarkType.TEXT) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = watermarkText,
                        onValueChange = { settingsViewModel.setWatermarkText(it) },
                        label = { Text("Watermark Text") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (watermarkImageUri.isEmpty()) "Select Image" else "Change Image")
                    }
                    if (watermarkImageUri.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Image selected",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Shortcut Key")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Configure physical key for recording control", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    var isListeningForKey by remember { mutableStateOf(false) }
                    Button(
                        onClick = { isListeningForKey = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (shortcutKeyName.isEmpty()) "Set Key"
                            else "Current: $shortcutKeyName"
                        )
                    }
                    if (isListeningForKey) {
                        KeyCaptureDialog(
                            onKeyCaptured = { keyCode, keyName ->
                                settingsViewModel.setShortcutKey(keyCode)
                                settingsViewModel.setShortcutKeyName(keyName)
                                isListeningForKey = false
                            },
                            onDismiss = { isListeningForKey = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Advanced")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PiP Recording", fontWeight = FontWeight.Medium)
                        Text(
                            "Front camera overlay during recording",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = pipEnabled,
                        onCheckedChange = { settingsViewModel.setPipEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }

            if (pipEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("PiP Window Size: ${pipSize}dp", fontWeight = FontWeight.Medium)
                        Slider(
                            value = pipSize.toFloat(),
                            onValueChange = { settingsViewModel.setPipSize(it.toInt()) },
                            valueRange = 80f..300f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("Battery Optimization") },
            text = { Text("For stable long-duration recording, please disable battery optimization for ScreenPulse.") },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }) { Text("Disable") }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text("Later") }
            }
        )
    }
}

@Composable
private fun KeyCaptureDialog(
    onKeyCaptured: (Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    var capturedKey by remember { mutableStateOf("") }
    var capturedKeyCode by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Press a Key") },
        text = {
            Column {
                Text("Press any physical key on your device...")
                if (capturedKey.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Key: $capturedKey",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onKeyCaptured(capturedKeyCode, capturedKey) },
                enabled = capturedKey.isNotEmpty()
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ThemeSelector(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected == mode,
                        onClick = { onSelected(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            ThemeMode.FOLLOW_SYSTEM -> "Follow System"
                            ThemeMode.LIGHT -> "Light Mode"
                            ThemeMode.DARK -> "Dark Mode"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioModeSelector(selectedMode: AudioMode, onModeSelected: (AudioMode) -> Unit) {
    val context = LocalContext.current
    val availableModes = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        listOf(AudioMode.MIC_ONLY)
    } else {
        AudioMode.entries
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            availableModes.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when (mode) {
                                AudioMode.SYSTEM_ONLY -> "System Sound Only"
                                AudioMode.MIC_ONLY -> "Microphone Only"
                                AudioMode.MIXED -> "System + Microphone"
                            },
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (mode) {
                                AudioMode.SYSTEM_ONLY -> "Record internal audio only"
                                AudioMode.MIC_ONLY -> "Record external audio only"
                                AudioMode.MIXED -> "Record both audio sources"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordModeSelector(selectedMode: RecordMode, onModeSelected: (RecordMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            RecordMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            RecordMode.FULL_SCREEN -> "Full Screen"
                            RecordMode.CUSTOM_REGION -> "Custom Region"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompressionModeSelector(selectedMode: CompressionMode, onModeSelected: (CompressionMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            CompressionMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when (mode) {
                                CompressionMode.FAST -> "Fast Compress"
                                CompressionMode.BALANCED -> "Balanced"
                                CompressionMode.HD_LOSSLESS -> "HD Lossless"
                            },
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (mode) {
                                CompressionMode.FAST -> "Smallest file size"
                                CompressionMode.BALANCED -> "Balance quality and size"
                                CompressionMode.HD_LOSSLESS -> "Best quality"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountdownSelector(selectedMode: CountdownMode, onModeSelected: (CountdownMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            CountdownMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            CountdownMode.NONE -> "None"
                            CountdownMode.THREE_SECONDS -> "3 seconds"
                            CountdownMode.FIVE_SECONDS -> "5 seconds"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BitrateModeSelector(selectedMode: BitrateMode, onModeSelected: (BitrateMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Bitrate Mode", fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            BitrateMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { onModeSelected(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when (mode) {
                                BitrateMode.SMART -> "Smart Adaptive"
                                BitrateMode.MANUAL -> "Manual"
                            },
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (mode) {
                                BitrateMode.SMART -> "Auto-adjust based on resolution and frame rate"
                                BitrateMode.MANUAL -> "Set bitrate manually"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BitrateSlider(bitrate: Int, onBitrateChanged: (Int) -> Unit) {
    var sliderValue by remember(bitrate) { mutableFloatStateOf(bitrate / 1000000f) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Bitrate", fontWeight = FontWeight.Medium)
                Text("${sliderValue.toInt()} Mbps", color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    onBitrateChanged((it * 1000000).toInt())
                },
                valueRange = 2f..20f,
                steps = 8,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun VolumeSlider(label: String, value: Int, onValueChanged: (Int) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, fontWeight = FontWeight.Medium)
                Text("${sliderValue.toInt()}%", color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    onValueChanged(it.toInt())
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(value, color = MaterialTheme.colorScheme.primary)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
