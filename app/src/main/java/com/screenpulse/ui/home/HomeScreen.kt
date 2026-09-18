package com.screenpulse.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import com.screenpulse.repository.FrameRate
import com.screenpulse.repository.RecordMode
import com.screenpulse.repository.Resolution
import com.screenpulse.service.ScreenRecordService
import com.screenpulse.viewmodel.RecordingState
import com.screenpulse.viewmodel.RecordingViewModel
import com.screenpulse.viewmodel.SettingsViewModel
import androidx.core.content.ContextCompat
import com.screenpulse.util.LogManager
import com.screenpulse.util.MediaProjectionHolder
import com.screenpulse.util.OverlayRecordingStarter
import com.screenpulse.util.ProjectionRequestBus
import com.screenpulse.util.RecordingCache
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
    val recordingState by recordingViewModel.recordingState.collectAsState()
    val duration by recordingViewModel.recordingDuration.collectAsState()
    val countdown by recordingViewModel.countdownRemaining.collectAsState()
    val audioMode by settingsViewModel.audioMode.collectAsState()
    val recordMode by settingsViewModel.recordMode.collectAsState()
    val resolution by settingsViewModel.resolution.collectAsState()
    val frameRate by settingsViewModel.frameRate.collectAsState()
    val countdownMode by settingsViewModel.countdownMode.collectAsState()
    val customCountdownSeconds by settingsViewModel.customCountdownSeconds.collectAsState()
    val pipEnabled by settingsViewModel.pipEnabled.collectAsState()
    val pipSize by settingsViewModel.pipSize.collectAsState()
    val floatingWindowPersistent by settingsViewModel.floatingWindowPersistent.collectAsState()

    var previousState by remember { mutableStateOf(RecordingState.IDLE) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val newState = com.screenpulse.shortcut.RecordingStateManager.currentState
            recordingViewModel.setRecordingState(newState)
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

    // Auto-start persistent floating window if setting is enabled
    LaunchedEffect(floatingWindowPersistent) {
        if (floatingWindowPersistent && PermissionManager.hasOverlayPermission(context)) {
            val currentState = com.screenpulse.shortcut.RecordingStateManager.currentState
            if (currentState == RecordingState.IDLE) {
                startFloatingWindow(context, persistent = true)
            }
        } else if (!floatingWindowPersistent) {
            // When persistent mode is disabled, hide the floating window if it's in IDLE state
            val currentState = com.screenpulse.shortcut.RecordingStateManager.currentState
            if (currentState == RecordingState.IDLE) {
                val hideIntent = Intent(context, FloatingWindowService::class.java).apply {
                    action = FloatingWindowService.ACTION_HIDE
                }
                context.startService(hideIntent)
            }
        }
    }

    val shouldNavigateToVideoList by recordingViewModel.navigateToVideoList.collectAsState()

    // Navigate to video list when recording completes
    LaunchedEffect(shouldNavigateToVideoList) {
        if (shouldNavigateToVideoList) {
            recordingViewModel.consumeNavigateToVideoList()
            onNavigateToVideoList()
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LogManager.log(LogManager.TAG_UI, "MediaProjection result: code=${result.resultCode} data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            RecordingCache.saveAuthorization(result.resultCode, result.data!!)
            ContextCompat.startForegroundService(
                context,
                RecordingCache.createStartIntent(context, result.resultCode, result.data)
            )
            startFloatingWindow(context, floatingWindowPersistent)
            OverlayRecordingStarter.startPipIfEnabled(context)
        }
    }

    fun continueToRecordingStart() {
        LogManager.log(LogManager.TAG_UI, "continueToRecordingStart overlay=${PermissionManager.hasOverlayPermission(context)} mode=$recordMode")
        if (recordMode == RecordMode.CUSTOM_REGION && !PermissionManager.hasOverlayPermission(context)) {
            LogManager.log(LogManager.TAG_UI, "Custom region needs overlay permission")
            context.startActivity(PermissionManager.overlaySettingsIntent(context))
            return
        }
        if (recordMode == RecordMode.CUSTOM_REGION) {
            LogManager.log(LogManager.TAG_UI, "Custom region mode: open overlay selector")
            OverlayRecordingStarter.start(context)
        } else {
            LogManager.log(LogManager.TAG_UI, "Launching MediaProjection permission")
            mediaProjectionLauncher.launch(PermissionManager.createMediaProjectionIntent(context))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        LogManager.log(LogManager.TAG_UI, "Permission result: perms=$permissions")
        PermissionManager.promptSpecialPermissionsIfNeeded(context)
        continueToRecordingStart()
    }

    val startupPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        LogManager.log(LogManager.TAG_UI, "Startup permission result: perms=$permissions")
        PermissionManager.promptSpecialPermissionsIfNeeded(context)
    }

    LaunchedEffect(Unit) {
        val missing = PermissionManager.missingRuntimePermissions(context)
        if (missing.isNotEmpty()) {
            startupPermissionLauncher.launch(missing)
        } else {
            PermissionManager.promptSpecialPermissionsIfNeeded(context)
        }
    }

    fun requestRecording() {
        if (MediaProjectionHolder.isActive && recordMode != RecordMode.CUSTOM_REGION) {
            LogManager.log(LogManager.TAG_UI, "Record button: MediaProjection already held, start directly")
            ContextCompat.startForegroundService(context, RecordingCache.createStartIntent(context))
            if (pipEnabled) {
                startPipOverlay(context, pipSize)
            }
            startFloatingWindow(context, floatingWindowPersistent)
            return
        }
        val missing = PermissionManager.missingRuntimePermissions(context)
        LogManager.log(LogManager.TAG_UI, "Record button: missing=${missing.toList()} overlay=${PermissionManager.hasOverlayPermission(context)}")
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
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
            Spacer(modifier = Modifier.weight(0.5f))

            RecordButton(
                modifier = Modifier.weight(1f, fill = false),
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

            Spacer(modifier = Modifier.height(16.dp))

            StatusCard(state = recordingState, duration = duration)

            Spacer(modifier = Modifier.height(16.dp))

            CurrentParamsCard(
                resolution = resolution,
                frameRate = frameRate,
                audioMode = audioMode,
                recordMode = recordMode,
                countdownMode = countdownMode,
                customCountdownSeconds = customCountdownSeconds,
                floatingWindowPersistent = floatingWindowPersistent,
                isRecording = recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED,
                onResolutionSelected = { settingsViewModel.setResolution(it) },
                onFrameRateSelected = { settingsViewModel.setFrameRate(it) },
                onAudioModeSelected = { settingsViewModel.setAudioMode(it) },
                onRecordModeSelected = { settingsViewModel.setRecordMode(it) },
                onCountdownSelected = { settingsViewModel.setCountdownMode(it) },
                onFloatingWindowChanged = { enabled ->
                    settingsViewModel.setFloatingWindowPersistent(enabled)
                    if (enabled && !PermissionManager.hasOverlayPermission(context)) {
                        context.startActivity(PermissionManager.overlaySettingsIntent(context))
                    }
                }
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
            }
        }
    }
}

@Composable
private fun RecordButton(
    state: RecordingState,
    duration: Long,
    countdown: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onStop: () -> Unit
) {
    val isRecording = state == RecordingState.RECORDING || state == RecordingState.PAUSED
    val isCountdown = state == RecordingState.COUNTDOWN

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val diameter = minOf(maxWidth, maxHeight).coerceIn(80.dp, 140.dp)
            val iconSize = (diameter / 3).coerceIn(32.dp, 56.dp)

            Box(
                modifier = Modifier
                    .requiredSize(diameter)
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
                        fontSize = (diameter / 3).value.coerceIn(28f, 48f).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                    isRecording -> Icon(
                        Icons.Default.Stop,
                        contentDescription = stringResource(R.string.cd_stop),
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onError
                    )
                    else -> Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = stringResource(R.string.cd_start_record),
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
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
    resolution: Resolution,
    frameRate: FrameRate,
    audioMode: AudioMode,
    recordMode: RecordMode,
    countdownMode: CountdownMode,
    customCountdownSeconds: Int,
    floatingWindowPersistent: Boolean,
    onResolutionSelected: (Resolution) -> Unit,
    onFrameRateSelected: (FrameRate) -> Unit,
    onAudioModeSelected: (AudioMode) -> Unit,
    onRecordModeSelected: (RecordMode) -> Unit,
    onCountdownSelected: (CountdownMode) -> Unit,
    onFloatingWindowChanged: (Boolean) -> Unit,
    isRecording: Boolean = false
) {
    val resolutionOptions = Resolution.entries.map { it to it.value }
    val frameRateOptions = FrameRate.entries.map { it to stringResource(R.string.fps_format, "${it.value}") }
    val audioOptions = AudioMode.entries.map { mode ->
        mode to when (mode) {
            AudioMode.SYSTEM_ONLY -> stringResource(R.string.audio_short_system)
            AudioMode.MIC_ONLY -> stringResource(R.string.audio_short_mic)
            AudioMode.MIXED -> stringResource(R.string.audio_short_mixed)
        }
    }
    val recordModeOptions = RecordMode.entries.map { mode ->
        mode to when (mode) {
            RecordMode.FULL_SCREEN -> stringResource(R.string.full_screen_record)
            RecordMode.CUSTOM_REGION -> stringResource(R.string.region_record)
        }
    }
    val countdownOptions = CountdownMode.entries.map { mode ->
        mode to when (mode) {
            CountdownMode.NONE -> stringResource(R.string.countdown_none)
            CountdownMode.THREE_SECONDS -> stringResource(R.string.countdown_3s)
            CountdownMode.FIVE_SECONDS -> stringResource(R.string.countdown_5s)
            CountdownMode.CUSTOM -> stringResource(R.string.countdown_custom_time, customCountdownSeconds)
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
            Text(stringResource(R.string.current_settings), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))

            ParamDropdownRow(
                label = stringResource(R.string.param_resolution),
                value = resolution.value,
                options = resolutionOptions,
                enabled = !isRecording,
                onSelected = onResolutionSelected
            )
            ParamDropdownRow(
                label = stringResource(R.string.param_frame_rate),
                value = stringResource(R.string.fps_format, "${frameRate.value}"),
                options = frameRateOptions,
                enabled = !isRecording,
                onSelected = onFrameRateSelected
            )
            ParamDropdownRow(
                label = stringResource(R.string.param_audio),
                value = audioOptions.first { it.first == audioMode }.second,
                options = audioOptions,
                enabled = !isRecording,
                onSelected = onAudioModeSelected
            )
            ParamDropdownRow(
                label = stringResource(R.string.param_mode),
                value = recordModeOptions.first { it.first == recordMode }.second,
                options = recordModeOptions,
                enabled = !isRecording,
                onSelected = onRecordModeSelected
            )
            ParamDropdownRow(
                label = stringResource(R.string.countdown),
                value = countdownOptions.first { it.first == countdownMode }.second,
                options = countdownOptions,
                enabled = !isRecording,
                onSelected = onCountdownSelected
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.floating_window),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Text(
                        stringResource(
                            if (floatingWindowPersistent) R.string.show_floating_window
                            else R.string.hide_floating_window
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = floatingWindowPersistent,
                    onCheckedChange = onFloatingWindowChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    }
}

@Composable
private fun <T> ParamDropdownRow(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { expanded = true }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                if (enabled) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (option, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
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
                .requiredSize(48.dp)
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

private fun startFloatingWindow(context: Context, persistent: Boolean = false) {
    val intent = Intent(context, FloatingWindowService::class.java).apply {
        action = FloatingWindowService.ACTION_SHOW_PERSISTENT
        putExtra(FloatingWindowService.EXTRA_PERSISTENT, persistent)
    }
    context.startService(intent)
}

private fun startPipOverlay(context: Context, size: Int) {
    val intent = Intent(context, com.screenpulse.floatingwindow.FloatingPipService::class.java).apply {
        action = com.screenpulse.floatingwindow.FloatingPipService.ACTION_SHOW
        putExtra(com.screenpulse.floatingwindow.FloatingPipService.EXTRA_SIZE, size)
    }
    context.startService(intent)
}
