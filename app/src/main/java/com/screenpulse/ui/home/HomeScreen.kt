package com.screenpulse.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenpulse.floatingwindow.FloatingWindowService
import com.screenpulse.permission.PermissionManager
import com.screenpulse.R
import com.screenpulse.repository.AudioMode
import com.screenpulse.repository.CountdownMode
import com.screenpulse.repository.CustomRegion
import com.screenpulse.repository.RecordMode
import com.screenpulse.service.ScreenRecordService
import com.screenpulse.ui.regionselect.RegionSelectActivity
import com.screenpulse.viewmodel.RecordingState
import com.screenpulse.viewmodel.RecordingViewModel
import com.screenpulse.viewmodel.SettingsViewModel
import com.screenpulse.util.LogManager
import com.screenpulse.util.ProjectionRequestBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recordingViewModel: RecordingViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToVideoList: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val recordingState by recordingViewModel.recordingState.collectAsState()
    val duration by recordingViewModel.recordingDuration.collectAsState()
    val countdown by recordingViewModel.countdownRemaining.collectAsState()
    val audioMode by settingsViewModel.audioMode.collectAsState()
    val recordMode by settingsViewModel.recordMode.collectAsState()
    val countdownMode by settingsViewModel.countdownMode.collectAsState()
    val resolution by settingsViewModel.resolution.collectAsState()
    val frameRate by settingsViewModel.frameRate.collectAsState()
    val bitrate by settingsViewModel.bitrate.collectAsState()
    val compressionMode by settingsViewModel.compressionMode.collectAsState()
    val systemVolume by settingsViewModel.systemVolume.collectAsState()
    val micVolume by settingsViewModel.micVolume.collectAsState()
    val watermarkEnabled by settingsViewModel.watermarkEnabled.collectAsState()
    val watermarkText by settingsViewModel.watermarkText.collectAsState()
    val pipEnabled by settingsViewModel.pipEnabled.collectAsState()
    val pipSize by settingsViewModel.pipSize.collectAsState()
    val bitrateMode by settingsViewModel.bitrateMode.collectAsState()
    val customResolutionWidth by settingsViewModel.customResolutionWidth.collectAsState()
    val customResolutionHeight by settingsViewModel.customResolutionHeight.collectAsState()
    val customSaveTreeUri by settingsViewModel.customSaveTreeUri.collectAsState()
    val customCountdownSeconds by settingsViewModel.customCountdownSeconds.collectAsState()

    var previousState by remember { mutableStateOf(RecordingState.IDLE) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val newState = com.screenpulse.shortcut.RecordingStateManager.currentState
n            recordingViewModel.setRecordingState(newState)
            recordingViewModel.updateCountdown(com.screenpulse.shortcut.RecordingStateManager.countdownRemaining)
            recordingViewModel.updateDuration(com.screenpulse.shortcut.RecordingStateManager.currentDurationMs)
            // Detect recording completed: transition from RECORDING/PAUSED -> IDLE
            if ((previousState == RecordingState.RECORDING || previousState == RecordingState.PAUSED) &&
                newState == RecordingState.IDLE) {
                recordingViewModel.requestNavigateToVideoList()
            }
            previousState = newState
            delay(150L)
        }
    }

    val customRegionData by settingsViewModel.customRegion.collectAsState()
    val shouldNavigateToVideoList by recordingViewModel.navigateToVideoList.collectAsState()

    // Navigate to video list when recording completes
    LaunchedEffect(shouldNavigateToVideoList) {
        if (shouldNavigateToVideoList) {
            recordingViewModel.consumeNavigateToVideoList()
            onNavigateToVideoList()
        }
    }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var showOverlayDialog by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LogManager.log(LogManager.TAG_UI, "MediaProjection result: code=${result.resultCode} data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startRecording(
                context = context,
                resultCode = result.resultCode,
                resultData = result.data!!,
                audioMode = audioMode,
                recordMode = recordMode,
                countdownMode = countdownMode,
                customCountdownSeconds = customCountdownSeconds,
                resolution = resolution,
                frameRate = frameRate,
                bitrate = bitrate,
                bitrateMode = bitrateMode,
                compressionMode = compressionMode,
                systemVolume = systemVolume,
                micVolume = micVolume,
                watermarkEnabled = watermarkEnabled,
                watermarkText = watermarkText,
                customResolutionWidth = customResolutionWidth,
                customResolutionHeight = customResolutionHeight,
                regionWidth = if (recordMode == RecordMode.CUSTOM_REGION) customRegionData.width else 0,
                regionHeight = if (recordMode == RecordMode.CUSTOM_REGION) customRegionData.height else 0,
                regionOffsetX = if (recordMode == RecordMode.CUSTOM_REGION) customRegionData.offsetX else 0,
                regionOffsetY = if (recordMode == RecordMode.CUSTOM_REGION) customRegionData.offsetY else 0,
                customSaveTreeUri = customSaveTreeUri,
                settingsViewModel = settingsViewModel
            )
            startFloatingWindow(context)
            if (pipEnabled) {
                startPipOverlay(context, pipSize)
            }
        }
    }

    val regionSelectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LogManager.log(LogManager.TAG_UI, "Region select result: code=${result.resultCode} data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val d = result.data!!
            val region = CustomRegion(
                width = d.getIntExtra(RegionSelectActivity.EXTRA_REGION_WIDTH, 0),
                height = d.getIntExtra(RegionSelectActivity.EXTRA_REGION_HEIGHT, 0),
                offsetX = d.getIntExtra(RegionSelectActivity.EXTRA_REGION_X, 0),
                offsetY = d.getIntExtra(RegionSelectActivity.EXTRA_REGION_Y, 0)
            )
            settingsViewModel.setCustomRegion(region)
            LogManager.log(LogManager.TAG_UI, "Region selected: ${region.offsetX},${region.offsetY} ${region.width}x${region.height} -> asking MediaProjection")
            mediaProjectionLauncher.launch(PermissionManager.createMediaProjectionIntent(context))
        } else {
            LogManager.log(LogManager.TAG_UI, "Region selection cancelled by user")
        }
    }

    fun continueToRecordingStart() {
        LogManager.log(LogManager.TAG_UI, "continueToRecordingStart overlay=${PermissionManager.hasOverlayPermission(context)} mode=$recordMode")
        when {
            !PermissionManager.hasOverlayPermission(context) -> {
                LogManager.log(LogManager.TAG_UI, "Overlay permission missing, show dialog")
                showOverlayDialog = true
            }
            recordMode == RecordMode.CUSTOM_REGION -> {
                LogManager.log(LogManager.TAG_UI, "Custom region mode: open region selector")
                regionSelectLauncher.launch(Intent(context, RegionSelectActivity::class.java))
            }
            else -> {
                LogManager.log(LogManager.TAG_UI, "Launching MediaProjection permission")
                mediaProjectionLauncher.launch(PermissionManager.createMediaProjectionIntent(context))
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        LogManager.log(LogManager.TAG_UI, "Permission result: allGranted=$allGranted perms=$permissions")
        if (allGranted) {
            continueToRecordingStart()
        } else {
            showPermissionDialog = true
        }
    }

    fun requestRecording() {
        val permissions = PermissionManager.getRequiredPermissions()
        val needsPermission = permissions.any { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, perm
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        LogManager.log(LogManager.TAG_UI, "Record button: needsPermission=$needsPermission overlay=${PermissionManager.hasOverlayPermission(context)}")
        if (needsPermission) {
            permissionLauncher.launch(permissions)
        } else {
            continueToRecordingStart()
        }
    }

    val projectionRequestCount by ProjectionRequestBus.requestCount.collectAsState()
    LaunchedEffect(projectionRequestCount) {
        if (projectionRequestCount > 0) {
            LogManager.log(LogManager.TAG_UI, "Projection requested from floating window, launching")
            requestRecording()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.BugReport, contentDescription = stringResource(R.string.cd_logs))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            RecordButton(
                state = recordingState,
                duration = duration,
                countdown = countdown,
                onClick = { requestRecording() },
                onStop = {
                    context.startService(Intent(context, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    })
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            StatusCard(state = recordingState, duration = duration)

            Spacer(modifier = Modifier.height(16.dp))

            CurrentParamsCard(
                resolution = resolution.value,
                frameRate = stringResource(R.string.fps_format, "${frameRate.value}"),
                audioMode = when (audioMode) {
                    AudioMode.SYSTEM_ONLY -> stringResource(R.string.audio_short_system)
                    AudioMode.MIC_ONLY -> stringResource(R.string.audio_short_mic)
                    AudioMode.MIXED -> stringResource(R.string.audio_short_mixed)
                },
                recordMode = if (recordMode == RecordMode.FULL_SCREEN) stringResource(R.string.full_screen_record)
                else stringResource(R.string.region_record)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    icon = Icons.Default.VideoLibrary,
                    label = stringResource(R.string.btn_videos),
                    onClick = onNavigateToVideoList
                )
                ActionButton(
                    icon = Icons.Default.Settings,
                    label = stringResource(R.string.settings),
                    onClick = onNavigateToSettings
                )
                ActionButton(
                    icon = Icons.Default.CameraAlt,
                    label = stringResource(R.string.screenshot),
                    onClick = {
                        context.startService(Intent(context, ScreenRecordService::class.java).apply {
                            action = ScreenRecordService.ACTION_SCREENSHOT
                        })
                    },
                    enabled = recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED
                )
            }

            if (showPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { showPermissionDialog = false },
                    title = { Text(stringResource(R.string.permission_dialog_title)) },
                    text = { Text(stringResource(R.string.permission_dialog_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showPermissionDialog = false
                            permissionLauncher.launch(PermissionManager.getRequiredPermissions())
                        }) { Text(stringResource(R.string.grant_permission)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionDialog = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }

            if (showOverlayDialog) {
                AlertDialog(
                    onDismissRequest = { showOverlayDialog = false },
                    title = { Text(stringResource(R.string.overlay_dialog_title)) },
                    text = { Text(stringResource(R.string.overlay_dialog_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showOverlayDialog = false
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}"))
                            context.startActivity(intent)
                        }) { Text(stringResource(R.string.grant_permission)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showOverlayDialog = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordButton(
    state: RecordingState,
    duration: Long,
    countdown: Int,
    onClick: () -> Unit,
    onStop: () -> Unit
) {
    val isRecording = state == RecordingState.RECORDING || state == RecordingState.PAUSED
    val isCountdown = state == RecordingState.COUNTDOWN

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isCountdown -> MaterialTheme.colorScheme.secondary
                        isRecording -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                .clickable(enabled = !isCountdown) {
                    if (isRecording) onStop() else onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                isCountdown -> Text(
                    "$countdown",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondary
                )
                isRecording -> Icon(
                    Icons.Default.Stop,
                    contentDescription = stringResource(R.string.cd_stop),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
                else -> Icon(
                    Icons.Default.FiberManualRecord,
                    contentDescription = stringResource(R.string.cd_start_record),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = when {
                isCountdown -> stringResource(R.string.starting)
                isRecording -> formatDuration(duration)
                else -> stringResource(R.string.tap_to_record)
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun StatusCard(state: RecordingState, duration: Long) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (state) {
                        RecordingState.IDLE -> Icons.Default.Circle
                        RecordingState.COUNTDOWN -> Icons.Default.Timer
                        RecordingState.RECORDING -> Icons.Default.FiberManualRecord
                        RecordingState.PAUSED -> Icons.Default.Pause
                    },
                    contentDescription = null,
                    tint = when (state) {
                        RecordingState.RECORDING -> MaterialTheme.colorScheme.error
                        RecordingState.PAUSED -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (state) {
                        RecordingState.IDLE -> stringResource(R.string.status_ready)
                        RecordingState.COUNTDOWN -> stringResource(R.string.status_countdown)
                        RecordingState.RECORDING -> stringResource(R.string.status_recording)
                        RecordingState.PAUSED -> stringResource(R.string.status_paused)
                    },
                    fontWeight = FontWeight.Medium
                )
            }
            if (state == RecordingState.RECORDING || state == RecordingState.PAUSED) {
                Text(
                    text = formatDuration(duration),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun CurrentParamsCard(
    resolution: String,
    frameRate: String,
    audioMode: String,
    recordMode: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.current_settings), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            ParamRow(stringResource(R.string.param_resolution), resolution)
            ParamRow(stringResource(R.string.param_frame_rate), frameRate)
            ParamRow(stringResource(R.string.param_audio), audioMode)
            ParamRow(stringResource(R.string.param_mode), recordMode)
        }
    }
}

@Composable
private fun ParamRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun startRecording(
    context: Context,
    resultCode: Int,
    resultData: Intent,
    audioMode: AudioMode,
    recordMode: RecordMode,
    countdownMode: CountdownMode,
    resolution: com.screenpulse.repository.Resolution,
    frameRate: com.screenpulse.repository.FrameRate,
    bitrate: Int,
    bitrateMode: com.screenpulse.repository.BitrateMode,
    compressionMode: com.screenpulse.repository.CompressionMode,
    systemVolume: Int,
    micVolume: Int,
    watermarkEnabled: Boolean,
    watermarkText: String,
    customResolutionWidth: Int,
    customResolutionHeight: Int,
    customCountdownSeconds: Int,
    regionWidth: Int,
    regionHeight: Int,
    regionOffsetX: Int,
    regionOffsetY: Int,
    customSaveTreeUri: String,
    settingsViewModel: SettingsViewModel
) {
    val effectiveBitrate = if (bitrateMode == com.screenpulse.repository.BitrateMode.SMART) {
        0
    } else {
        bitrate
    }
    val intent = Intent(context, ScreenRecordService::class.java).apply {
        action = ScreenRecordService.ACTION_START
        putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
        putExtra(ScreenRecordService.EXTRA_RESULT_DATA, resultData)
        putExtra(ScreenRecordService.EXTRA_AUDIO_MODE, audioMode.value)
        putExtra(ScreenRecordService.EXTRA_RECORD_MODE, recordMode.value)
        putExtra(ScreenRecordService.EXTRA_COUNTDOWN, countdownMode.value)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_COUNTDOWN_SECONDS, customCountdownSeconds)
        putExtra(ScreenRecordService.EXTRA_RESOLUTION, resolution.value)
        putExtra(ScreenRecordService.EXTRA_FRAME_RATE, frameRate.value)
        putExtra(ScreenRecordService.EXTRA_BITRATE, effectiveBitrate)
        putExtra(ScreenRecordService.EXTRA_COMPRESSION_MODE, compressionMode.value)
        putExtra(ScreenRecordService.EXTRA_SYSTEM_VOLUME, systemVolume)
        putExtra(ScreenRecordService.EXTRA_MIC_VOLUME, micVolume)
        putExtra(ScreenRecordService.EXTRA_WATERMARK_ENABLED, watermarkEnabled)
        putExtra(ScreenRecordService.EXTRA_WATERMARK_TEXT, watermarkText)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_RESOLUTION_WIDTH, customResolutionWidth)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_RESOLUTION_HEIGHT, customResolutionHeight)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_WIDTH, regionWidth)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_HEIGHT, regionHeight)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_OFFSET_X, regionOffsetX)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_OFFSET_Y, regionOffsetY)
        putExtra(ScreenRecordService.EXTRA_CUSTOM_SAVE_TREE_URI, customSaveTreeUri)
    }
    context.startService(intent)
}

private fun startFloatingWindow(context: Context) {
    val intent = Intent(context, FloatingWindowService::class.java)
    context.startService(intent)
}

private fun startPipOverlay(context: Context, size: Int) {
    val intent = Intent(context, com.screenpulse.floatingwindow.FloatingPipService::class.java).apply {
        action = com.screenpulse.floatingwindow.FloatingPipService.ACTION_SHOW
        putExtra(com.screenpulse.floatingwindow.FloatingPipService.EXTRA_SIZE, size)
    }
    context.startService(intent)
}
