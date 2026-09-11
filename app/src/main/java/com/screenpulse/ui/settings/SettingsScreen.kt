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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenpulse.R
import com.screenpulse.repository.*
import com.screenpulse.util.LogManager
import com.screenpulse.viewmodel.SettingsViewModel
import com.screenpulse.ui.regionselect.RegionSelectActivity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
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
    val language by settingsViewModel.language.collectAsState()
    val customSaveTreeUri by settingsViewModel.customSaveTreeUri.collectAsState()

    var showBatteryDialog by remember { mutableStateOf(false) }
    var customWidthText by remember(customResolutionWidth) { mutableStateOf(customResolutionWidth.toString()) }
    var customHeightText by remember(customResolutionHeight) { mutableStateOf(customResolutionHeight.toString()) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { settingsViewModel.setWatermarkImageUri(it.toString()) }
    }

    val regionSelectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            settingsViewModel.setCustomRegion(
                CustomRegion(
                    width = data.getIntExtra(RegionSelectActivity.EXTRA_REGION_WIDTH, 0),
                    height = data.getIntExtra(RegionSelectActivity.EXTRA_REGION_HEIGHT, 0),
                    offsetX = data.getIntExtra(RegionSelectActivity.EXTRA_REGION_X, 0),
                    offsetY = data.getIntExtra(RegionSelectActivity.EXTRA_REGION_Y, 0)
                )
            )
            LogManager.log(LogManager.TAG_UI, "Region selected from settings: ${data.getIntExtra(RegionSelectActivity.EXTRA_REGION_X, 0)}x${data.getIntExtra(RegionSelectActivity.EXTRA_REGION_Y, 0)}")
        }
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
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
            SectionTitle(stringResource(R.string.section_theme))
            ThemeSelector(themeMode) { settingsViewModel.setThemeMode(it) }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_language))
            LanguageSelector(language) { settingsViewModel.setLanguage(it) }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_save_path))
            SavePathSelector(
                customSaveTreeUri = customSaveTreeUri,
                onChoose = { uri -> settingsViewModel.setCustomSaveTreeUri(uri) },
                onReset = { settingsViewModel.setCustomSaveTreeUri("") }
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_recording_quality))

            SettingsDropdown(
                label = stringResource(R.string.label_resolution),
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
                        Text(stringResource(R.string.custom_resolution), fontWeight = FontWeight.Medium, fontSize = 14.sp)
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
                                label = { Text(stringResource(R.string.label_width)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Text(stringResource(R.string.multiply), fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = customHeightText,
                                onValueChange = {
                                    customHeightText = it
                                    it.toIntOrNull()?.let { h -> settingsViewModel.setCustomResolutionHeight(h) }
                                },
                                label = { Text(stringResource(R.string.label_height)) },
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
                label = stringResource(R.string.label_frame_rate),
                value = stringResource(R.string.fps_format, "${frameRate.value}"),
                options = FrameRate.entries.map { stringResource(R.string.fps_format, "${it.value}") },
                onSelected = {
                    val fps = it.filter { c -> c.isDigit() }.toIntOrNull() ?: 30
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
                    text = stringResource(R.string.bitrate_smart_value, smartBitrate / 1_000_000),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_audio))

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
                        text = stringResource(R.string.system_audio_requires),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            }

            if (audioMode == AudioMode.SYSTEM_ONLY || audioMode == AudioMode.MIXED) {
                Spacer(modifier = Modifier.height(12.dp))
                VolumeSlider(
                    label = stringResource(R.string.system_volume),
                    value = systemVolume,
                    onValueChanged = { settingsViewModel.setSystemVolume(it) }
                )
            }

            if (audioMode == AudioMode.MIC_ONLY || audioMode == AudioMode.MIXED) {
                Spacer(modifier = Modifier.height(12.dp))
                VolumeSlider(
                    label = stringResource(R.string.mic_volume),
                    value = micVolume,
                    onValueChanged = { settingsViewModel.setMicVolume(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_record_mode))

            RecordModeSelector(
                selectedMode = recordMode,
                onModeSelected = { settingsViewModel.setRecordMode(it) }
            )

            if (recordMode == RecordMode.CUSTOM_REGION) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { regionSelectLauncher.launch(Intent(context, RegionSelectActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.btn_select_region))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_compression))

            CompressionModeSelector(
                selectedMode = compressionMode,
                onModeSelected = { settingsViewModel.setCompressionMode(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_countdown))

            CountdownSelector(
                selectedMode = countdownMode,
                onModeSelected = { settingsViewModel.setCountdownMode(it) },
                customSeconds = customCountdownSeconds,
                onCustomSecondsChanged = { settingsViewModel.setCustomCountdownSeconds(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_watermark))

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
                    Text(stringResource(R.string.enable_watermark))
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
                                        WatermarkType.TEXT -> stringResource(R.string.watermark_text_type)
                                        WatermarkType.IMAGE -> stringResource(R.string.watermark_image_type)
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
                        label = { Text(stringResource(R.string.watermark_text)) },
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
                        Text(if (watermarkImageUri.isEmpty()) stringResource(R.string.select_image) else stringResource(R.string.change_image))
                    }
                    if (watermarkImageUri.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.image_selected),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.section_shortcut))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.shortcut_desc), fontSize = 13.sp)
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
                            if (shortcutKeyName.isEmpty()) stringResource(R.string.set_key)
                            else stringResource(R.string.current_key, shortcutKeyName)
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
            SectionTitle(stringResource(R.string.section_advanced))

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
                        Text(stringResource(R.string.pip_recording), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.pip_recording_desc),
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
                        Text(stringResource(R.string.pip_size_format, "$pipSize"), fontWeight = FontWeight.Medium)
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
            title = { Text(stringResource(R.string.battery_optimization)) },
            text = { Text(stringResource(R.string.battery_optimization_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.battery_disable)) }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text(stringResource(R.string.battery_later)) }
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
        title = { Text(stringResource(R.string.press_key_title)) },
        text = {
            Column {
                Text(stringResource(R.string.press_key_hint))
                if (capturedKey.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.key_value, capturedKey),
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
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
                            ThemeMode.FOLLOW_SYSTEM -> stringResource(R.string.follow_system)
                            ThemeMode.LIGHT -> stringResource(R.string.light_mode)
                            ThemeMode.DARK -> stringResource(R.string.dark_mode)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageSelector(selected: Language, onSelected: (Language) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Language.values().sortedBy { it.ordinal }.forEach { lang ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(lang) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected == lang,
                        onClick = { onSelected(lang) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (lang) {
                            Language.SYSTEM -> stringResource(R.string.language_follow_system)
                            Language.ENGLISH -> stringResource(R.string.language_english)
                            Language.CHINESE -> stringResource(R.string.language_chinese)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavePathSelector(
    customSaveTreeUri: String,
    onChoose: (String) -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            LogManager.log(LogManager.TAG_UI, "Save folder selected: $uri")
            onChoose(uri.toString())
        }
    }

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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (customSaveTreeUri.isEmpty()) stringResource(R.string.save_path_default)
                    else stringResource(R.string.save_path_custom),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.weight(1f))
                if (customSaveTreeUri.isNotEmpty()) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.btn_reset_default))
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (customSaveTreeUri.isEmpty()) {
                    stringResource(R.string.current_path, defaultSavePath(context))
                } else {
                    customSaveTreeUri
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { folderLauncher.launch(null) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.btn_choose_folder))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { openFolder(context, customSaveTreeUri) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.btn_open_folder))
                }
            }
        }
    }
}

private fun defaultSavePath(context: android.content.Context): String {
    return try {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir
        File(base, "ScreenPulse").absolutePath
    } catch (e: Exception) {
        "ScreenPulse"
    }
}

private fun openFolder(context: android.content.Context, customTreeUri: String) {
    return try {
        val intent: Intent
        if (customTreeUri.isNotEmpty()) {
            val treeUri = android.net.Uri.parse(customTreeUri)
            intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            val dir = java.io.File(defaultSavePath(context))
            if (!dir.exists()) dir.mkdirs()
            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, dir)
            intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        LogManager.log(LogManager.TAG_UI, "openFolder failed: ${e.message}")
    }
}

@Composable
private fun AudioModeSelector(selectedMode: AudioMode, onModeSelected: (AudioMode) -> Unit) {
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
                                AudioMode.SYSTEM_ONLY -> stringResource(R.string.audio_system_only)
                                AudioMode.MIC_ONLY -> stringResource(R.string.audio_mic_only)
                                AudioMode.MIXED -> stringResource(R.string.audio_mixed)
                            },
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (mode) {
                                AudioMode.SYSTEM_ONLY -> stringResource(R.string.audio_system_only_desc)
                                AudioMode.MIC_ONLY -> stringResource(R.string.audio_mic_only_desc)
                                AudioMode.MIXED -> stringResource(R.string.audio_mixed_desc)
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
                            RecordMode.FULL_SCREEN -> stringResource(R.string.full_screen_record)
                            RecordMode.CUSTOM_REGION -> stringResource(R.string.region_record)
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
                                CompressionMode.FAST -> stringResource(R.string.compress_fast)
                                CompressionMode.BALANCED -> stringResource(R.string.compress_balanced)
                                CompressionMode.HD_LOSSLESS -> stringResource(R.string.compress_hd)
                            },
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (mode) {
                                CompressionMode.FAST -> stringResource(R.string.compress_fast_desc)
                                CompressionMode.BALANCED -> stringResource(R.string.compress_balanced_desc)
                                CompressionMode.HD_LOSSLESS -> stringResource(R.string.compress_hd_desc)
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
private fun CountdownSelector(
    selectedMode: CountdownMode,
    onModeSelected: (CountdownMode) -> Unit,
    customSeconds: Int,
    onCustomSecondsChanged: (Int) -> Unit
) {
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
                            CountdownMode.NONE -> stringResource(R.string.countdown_none)
                            CountdownMode.THREE_SECONDS -> stringResource(R.string.countdown_3s)
                            CountdownMode.FIVE_SECONDS -> stringResource(R.string.countdown_5s)
                            CountdownMode.CUSTOM -> stringResource(R.string.countdown_custom)
                        }
                    )
                }
                if (mode == CountdownMode.CUSTOM && selectedMode == CountdownMode.CUSTOM) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customSeconds.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let { onCustomSecondsChanged(it.coerceIn(1, 300)) }
                            },
                            label = { Text(stringResource(R.string.countdown_custom_seconds)) },
                            modifier = Modifier.width(120.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.countdown_custom_unit))
                    }
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
            Text(stringResource(R.string.bitrate_mode_select), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
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
                                BitrateMode.SMART -> stringResource(R.string.bitrate_smart)
                                BitrateMode.MANUAL -> stringResource(R.string.bitrate_manual)
                            },
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (mode) {
                                BitrateMode.SMART -> stringResource(R.string.bitrate_smart_desc)
                                BitrateMode.MANUAL -> stringResource(R.string.bitrate_manual_desc)
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
                Text(stringResource(R.string.label_bitrate), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.bitrate_mbps, sliderValue.toInt()), color = MaterialTheme.colorScheme.primary)
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
                Text(stringResource(R.string.percent_format, sliderValue.toInt()), color = MaterialTheme.colorScheme.primary)
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
