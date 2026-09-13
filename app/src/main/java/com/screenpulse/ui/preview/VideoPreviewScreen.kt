package com.screenpulse.ui.preview

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.screenpulse.R
import com.screenpulse.util.VideoFileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class VideoMeta(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPreviewScreen(
    filePath: String,
    onBack: () -> Unit,
    onTrim: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val isContentUri = filePath.startsWith("content://")
    val file = if (isContentUri) null else File(filePath)
    val videoUri = remember(filePath) {
        if (isContentUri) Uri.parse(filePath) else Uri.fromFile(File(filePath))
    }
    val displayName = file?.name ?: filePath.substringAfterLast('/').ifEmpty { "video.mp4" }
    val displayPath = file?.absolutePath ?: filePath
    val exists = file != null && file.exists()
    val length = file?.length() ?: -1L
    val playable = isContentUri || (exists && length > 0)

    var isPlaying by remember { mutableStateOf(false) }
    var isEnded by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var userSeeking by remember { mutableStateOf(false) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var resumePlaying by remember { mutableStateOf(true) }
    var pendingSeekMs by remember { mutableLongStateOf(0L) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var brightnessOverride by remember { mutableFloatStateOf(-1f) }
    var meta by remember { mutableStateOf<VideoMeta?>(null) }

    val window = (context as? Activity)?.window
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    LaunchedEffect(filePath) {
        val loaded = withContext(Dispatchers.IO) { loadMeta(context, filePath) }
        meta = loaded
        durationMs = loaded.durationMs
        if (loaded.width > 0 && loaded.height > 0) {
            videoAspect = loaded.width.toFloat() / loaded.height.toFloat()
        }
    }

    DisposableEffect(isFullscreen) {
        if (isFullscreen) hideSystemUI(window) else showSystemUI(window)
        onDispose { showSystemUI(window) }
    }

    DisposableEffect(window) {
        onDispose {
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = videoViewRef
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (view != null) {
                        pendingSeekMs = view.currentPosition.toLong()
                        positionMs = pendingSeekMs
                        resumePlaying = view.isPlaying
                        if (view.isPlaying) view.pause()
                        isPlaying = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (view != null && playable) {
                        view.seekTo(pendingSeekMs.toInt())
                        if (resumePlaying && !isEnded) {
                            view.start()
                            isPlaying = true
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(showControls, isPlaying, isFullscreen, userSeeking) {
        if (showControls && isPlaying && !userSeeking) {
            delay(3000L)
            showControls = false
        }
    }

    LaunchedEffect(videoViewRef, userSeeking) {
        val view = videoViewRef ?: return@LaunchedEffect
        while (true) {
            if (!userSeeking) {
                positionMs = view.currentPosition.toLong().coerceAtLeast(0L)
                if (durationMs <= 0L) durationMs = view.duration.toLong().coerceAtLeast(0L)
            }
            delay(200L)
        }
    }

    LaunchedEffect(muted, mediaPlayer) {
        val volume = if (muted) 0f else 1f
        mediaPlayer?.setVolume(volume, volume)
    }

    fun revealControls() {
        showControls = true
    }

    fun seekTo(ms: Long) {
        val clamped = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        pendingSeekMs = clamped
        positionMs = clamped
        videoViewRef?.seekTo(clamped.toInt())
        isEnded = durationMs > 0L && clamped >= durationMs - 200L
        revealControls()
    }

    fun skip(deltaMs: Long) {
        seekTo(positionMs + deltaMs)
    }

    fun togglePlay() {
        val view = videoViewRef ?: return
        if (isEnded) {
            isEnded = false
            seekTo(0L)
            view.start()
            isPlaying = true
            resumePlaying = true
            revealControls()
            return
        }
        if (view.isPlaying) {
            view.pause()
            isPlaying = false
            resumePlaying = false
        } else {
            view.start()
            isPlaying = true
            resumePlaying = true
        }
        revealControls()
    }

    var seekOriginMs by remember { mutableLongStateOf(0L) }
    var volumeOrigin by remember { mutableIntStateOf(0) }
    var brightnessOrigin by remember { mutableFloatStateOf(0.5f) }

    fun currentBrightness(): Float {
        if (brightnessOverride >= 0f) return brightnessOverride
        val sys = android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
            128
        ) / 255f
        return sys.coerceIn(0.01f, 1f)
    }

    fun applyVolumeFraction(fraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val next = (volumeOrigin + fraction * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        if (next > 0 && muted) muted = false
        gestureHint = context.getString(R.string.player_volume, next * 100 / max)
    }

    fun applyBrightnessFraction(fraction: Float) {
        val win = window ?: return
        val next = (brightnessOrigin + fraction).coerceIn(0.01f, 1f)
        brightnessOverride = next
        win.attributes = win.attributes.apply { screenBrightness = next }
        gestureHint = context.getString(R.string.player_brightness, (next * 100).roundToInt())
    }

    val overlay: @Composable BoxScope.() -> Unit = {
        PlayerOverlay(
            showControls = showControls,
            isPlaying = isPlaying,
            isEnded = isEnded,
            isFullscreen = isFullscreen,
            muted = muted,
            positionMs = positionMs,
            durationMs = durationMs,
            gestureHint = gestureHint,
            onTogglePlay = { togglePlay() },
            onSeeking = { seeking, value ->
                userSeeking = seeking
                positionMs = value
                if (!seeking) seekTo(value)
                else revealControls()
            },
            onSkipBack = { skip(-10_000L) },
            onSkipForward = { skip(10_000L) },
            onMute = { muted = !muted; revealControls() },
            onFullscreen = {
                pendingSeekMs = positionMs
                resumePlaying = isPlaying || resumePlaying
                isFullscreen = !isFullscreen
                revealControls()
            }
        )
    }

    if (isFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            PlayerSurface(
                modifier = Modifier.fillMaxSize(),
                playable = playable,
                videoUri = videoUri,
                pendingSeekMs = pendingSeekMs,
                resumePlaying = resumePlaying,
                onViewReady = { videoViewRef = it },
                onPlayerReady = { player, w, h, dur ->
                    mediaPlayer = player
                    if (w > 0 && h > 0) videoAspect = w.toFloat() / h.toFloat()
                    if (dur > 0L) durationMs = dur
                    val volume = if (muted) 0f else 1f
                    player.setVolume(volume, volume)
                },
                onPlaying = { playing ->
                    isPlaying = playing
                    if (playing) isEnded = false
                },
                onCompletion = {
                    isPlaying = false
                    isEnded = true
                    resumePlaying = false
                    positionMs = durationMs
                    showControls = true
                },
                onTap = { showControls = !showControls },
                onDoubleTap = { togglePlay() },
                onGestureStart = {
                    seekOriginMs = positionMs
                    volumeOrigin = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    brightnessOrigin = currentBrightness()
                },
                onHorizontalSeek = { fraction ->
                    val span = durationMs.coerceAtLeast(1L)
                    val target = (seekOriginMs + (fraction * span).toLong()).coerceIn(0L, span)
                    gestureHint = context.getString(R.string.player_seek_hint, formatClock(target))
                    target
                },
                onHorizontalSeekEnd = { target ->
                    seekTo(target)
                    gestureHint = null
                },
                onVerticalLeft = { applyBrightnessFraction(it) },
                onVerticalRight = { applyVolumeFraction(it) },
                onGestureEnd = {
                    scope.launch {
                        delay(600L)
                        gestureHint = null
                    }
                }
            )
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        pendingSeekMs = positionMs
                        resumePlaying = isPlaying || resumePlaying
                        isFullscreen = false
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
                    }
                    Text(
                        text = displayName,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp
                    )
                    IconButton(onClick = {
                        pendingSeekMs = positionMs
                        resumePlaying = isPlaying || resumePlaying
                        isFullscreen = false
                    }) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.cd_fullscreen_exit), tint = Color.White)
                    }
                }
            }
            overlay()
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(displayName, maxLines = 1) },
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
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoAspect.coerceIn(9f / 21f, 21f / 9f))
                        .background(Color.Black)
                ) {
                    if (playable) {
            PlayerSurface(
                modifier = Modifier.fillMaxSize(),
                playable = playable,
                videoUri = videoUri,
                pendingSeekMs = pendingSeekMs,
                resumePlaying = resumePlaying,
                onViewReady = { videoViewRef = it },
                onPlayerReady = { player, w, h, dur ->
                    mediaPlayer = player
                    if (w > 0 && h > 0) videoAspect = w.toFloat() / h.toFloat()
                    if (dur > 0L) durationMs = dur
                    val volume = if (muted) 0f else 1f
                    player.setVolume(volume, volume)
                },
                onPlaying = { playing ->
                    isPlaying = playing
                    if (playing) isEnded = false
                },
                onCompletion = {
                    isPlaying = false
                    isEnded = true
                    resumePlaying = false
                    positionMs = durationMs
                    showControls = true
                },
                onTap = { showControls = !showControls },
                onDoubleTap = { togglePlay() },
                onGestureStart = {
                    seekOriginMs = positionMs
                    volumeOrigin = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    brightnessOrigin = currentBrightness()
                },
                onHorizontalSeek = { fraction ->
                    val span = durationMs.coerceAtLeast(1L)
                    val target = (seekOriginMs + (fraction * span).toLong()).coerceIn(0L, span)
                    gestureHint = context.getString(R.string.player_seek_hint, formatClock(target))
                    target
                },
                onHorizontalSeekEnd = { target ->
                    seekTo(target)
                    gestureHint = null
                },
                onVerticalLeft = { applyBrightnessFraction(it) },
                onVerticalRight = { applyVolumeFraction(it) },
                onGestureEnd = {
                    scope.launch {
                        delay(600L)
                        gestureHint = null
                    }
                }
            )
                        overlay()
                    } else {
                        Text(
                            text = stringResource(
                                if (file != null && file.exists()) R.string.player_file_invalid
                                else R.string.player_file_missing
                            ),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = onTrim) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ContentCut, contentDescription = null)
                            Text(stringResource(R.string.action_trim), fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = {
                        val ok = VideoFileActions.exportToMovies(context, filePath, displayName)
                        Toast.makeText(
                            context,
                            context.getString(if (ok) R.string.export_success else R.string.export_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Text(stringResource(R.string.export), fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { VideoFileActions.share(context, filePath) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Text(stringResource(R.string.share), fontSize = 12.sp)
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.file_info), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow(stringResource(R.string.info_name), displayName)
                        InfoRow(stringResource(R.string.info_size), if (length < 0) "—" else formatFileSize(length))
                        InfoRow(stringResource(R.string.info_duration), formatClock(meta?.durationMs ?: durationMs))
                        InfoRow(
                            stringResource(R.string.info_resolution),
                            if ((meta?.width ?: 0) > 0) "${meta?.width}×${meta?.height}" else "—"
                        )
                        InfoRow(stringResource(R.string.info_fps), meta?.fps ?: "—")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.info_path), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            TextButton(
                                onClick = {
                                    VideoFileActions.copyText(context, displayPath)
                                    Toast.makeText(context, context.getString(R.string.path_copied), Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    displayPath,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PlayerSurface(
    modifier: Modifier,
    playable: Boolean,
    videoUri: Uri,
    pendingSeekMs: Long,
    resumePlaying: Boolean,
    onViewReady: (VideoView) -> Unit,
    onPlayerReady: (MediaPlayer, Int, Int, Long) -> Unit,
    onPlaying: (Boolean) -> Unit,
    onCompletion: () -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onGestureStart: () -> Unit,
    onHorizontalSeek: (Float) -> Long,
    onHorizontalSeekEnd: (Long) -> Unit,
    onVerticalLeft: (Float) -> Unit,
    onVerticalRight: (Float) -> Unit,
    onGestureEnd: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tap = rememberUpdatedState(onTap)
    val doubleTap = rememberUpdatedState(onDoubleTap)
    val gestureStart = rememberUpdatedState(onGestureStart)
    val horizontalSeek = rememberUpdatedState(onHorizontalSeek)
    val horizontalSeekEnd = rememberUpdatedState(onHorizontalSeekEnd)
    val verticalLeft = rememberUpdatedState(onVerticalLeft)
    val verticalRight = rememberUpdatedState(onVerticalRight)
    val gestureEnd = rememberUpdatedState(onGestureEnd)
    val pending = rememberUpdatedState(pendingSeekMs)
    val shouldResume = rememberUpdatedState(resumePlaying)
    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap.value() },
                    onDoubleTap = { doubleTap.value() }
                )
            }
            .pointerInput(size) {
                var mode = 0
                var totalX = 0f
                var totalY = 0f
                var seekTarget = -1L
                detectDragGestures(
                    onDragStart = {
                        mode = 0
                        totalX = 0f
                        totalY = 0f
                        seekTarget = -1L
                        gestureStart.value()
                    },
                    onDragEnd = {
                        if (mode == 1 && seekTarget >= 0L) horizontalSeekEnd.value(seekTarget)
                        gestureEnd.value()
                    },
                    onDragCancel = { gestureEnd.value() }
                ) { change, dragAmount ->
                    change.consume()
                    totalX += dragAmount.x
                    totalY += dragAmount.y
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()
                    if (mode == 0 && (abs(totalX) > 24f || abs(totalY) > 24f)) {
                        mode = if (abs(totalX) > abs(totalY)) 1 else {
                            if (change.position.x < w / 2f) 2 else 3
                        }
                    }
                    when (mode) {
                        1 -> seekTarget = horizontalSeek.value(totalX / w)
                        2 -> verticalLeft.value(-totalY / h)
                        3 -> verticalRight.value(-totalY / h)
                    }
                }
            }
    ) {
        if (playable) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnErrorListener { _, _, _ -> true }
                        setOnPreparedListener { mp ->
                            onPlayerReady(mp, mp.videoWidth, mp.videoHeight, duration.toLong().coerceAtLeast(0L))
                            val seek = pending.value
                            if (seek > 0L) seekTo(seek.toInt())
                            if (shouldResume.value) {
                                start()
                                onPlaying(true)
                            } else {
                                onPlaying(false)
                            }
                        }
                        setOnCompletionListener { onCompletion() }
                        setVideoURI(videoUri)
                        onViewReady(this)
                    }
                }
            )
        }
    }
}

@Composable
private fun BoxScope.PlayerOverlay(
    showControls: Boolean,
    isPlaying: Boolean,
    isEnded: Boolean,
    isFullscreen: Boolean,
    muted: Boolean,
    positionMs: Long,
    durationMs: Long,
    gestureHint: String?,
    onTogglePlay: () -> Unit,
    onSeeking: (Boolean, Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onMute: () -> Unit,
    onFullscreen: () -> Unit
) {
    GestureHint(gestureHint)
    AnimatedVisibility(
        visible = showControls,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        FilledIconButton(
            onClick = onTogglePlay,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = when {
                    isEnded -> Icons.Default.Replay
                    isPlaying -> Icons.Default.Pause
                    else -> Icons.Default.PlayArrow
                },
                contentDescription = stringResource(
                    when {
                        isEnded -> R.string.player_replay
                        isPlaying -> R.string.cd_pause
                        else -> R.string.cd_play
                    }
                ),
                modifier = Modifier.size(36.dp)
            )
        }
    }
    AnimatedVisibility(
        visible = showControls,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        BottomBar(
            positionMs = positionMs,
            durationMs = durationMs,
            muted = muted,
            isFullscreen = isFullscreen,
            onSeeking = onSeeking,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
            onMute = onMute,
            onFullscreen = onFullscreen
        )
    }
}

@Composable
private fun BoxScope.GestureHint(text: String?) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 72.dp),
    ) {
        Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text.orEmpty(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    positionMs: Long,
    durationMs: Long,
    muted: Boolean,
    isFullscreen: Boolean,
    onSeeking: (Boolean, Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onMute: () -> Unit,
    onFullscreen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatClock(positionMs), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(52.dp))
            Slider(
                value = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                onValueChange = { onSeeking(true, (it * durationMs).toLong()) },
                onValueChangeFinished = { onSeeking(false, positionMs) },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Text(formatClock(durationMs), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(52.dp), maxLines = 1)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSkipBack) {
                Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.player_skip_back), tint = Color.White)
            }
            IconButton(onClick = onMute) {
                Icon(
                    if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = stringResource(R.string.edit_mute),
                    tint = Color.White
                )
            }
            IconButton(onClick = onSkipForward) {
                Icon(Icons.Default.Forward10, contentDescription = stringResource(R.string.player_skip_forward), tint = Color.White)
            }
            IconButton(onClick = onFullscreen) {
                Icon(
                    if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = stringResource(if (isFullscreen) R.string.cd_fullscreen_exit else R.string.cd_fullscreen),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
    }
}

private fun hideSystemUI(window: Window?) {
    if (window == null) return
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
    WindowCompat.setDecorFitsSystemWindows(window, false)
}

private fun showSystemUI(window: Window?) {
    if (window == null) return
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.show(WindowInsetsCompat.Type.systemBars())
    WindowCompat.setDecorFitsSystemWindows(window, true)
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1073741824 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0)
        bytes >= 1048576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun loadMeta(context: Context, path: String): VideoMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        if (path.startsWith("content://")) retriever.setDataSource(context, Uri.parse(path))
        else retriever.setDataSource(path)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val fpsRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
        val fps = fpsRaw?.toFloatOrNull()?.let { String.format(Locale.getDefault(), "%.0f fps", it) } ?: "—"
        VideoMeta(duration, width, height, fps)
    } catch (_: Exception) {
        VideoMeta(0L, 0, 0, "—")
    } finally {
        runCatching { retriever.release() }
    }
}
