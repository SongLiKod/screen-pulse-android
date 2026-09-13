package com.screenpulse.ui.trim

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.screenpulse.R
import com.screenpulse.edit.CropNorm
import com.screenpulse.edit.KeepRange
import com.screenpulse.edit.VideoEditEngine
import com.screenpulse.util.VideoThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimScreen(
    filePath: String,
    onBack: () -> Unit,
    onTrimComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isContentUri = filePath.startsWith("content://")
    val videoUri = remember(filePath) {
        if (isContentUri) Uri.parse(filePath) else Uri.fromFile(File(filePath))
    }

    var durationMs by remember { mutableLongStateOf(0L) }
    var ranges by remember { mutableStateOf(listOf(KeepRange(0L, 0L))) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var looping by remember { mutableStateOf(true) }
    var mute by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(CropNorm(0f, 0f, 1f, 1f)) }
    var cropMode by remember { mutableStateOf(false) }
    var strip by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var showOverwrite by remember { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    val selected = ranges.getOrNull(selectedIndex) ?: ranges.firstOrNull()

    LaunchedEffect(filePath) {
        val ms = withContext(Dispatchers.IO) { VideoEditEngine.durationMs(context, filePath) }
        durationMs = ms
        ranges = listOf(KeepRange(0L, ms.coerceAtLeast(80L)))
        selectedIndex = 0
        strip = withContext(Dispatchers.IO) { VideoEditEngine.loadStrip(context, filePath, 8) }
    }

    LaunchedEffect(videoViewRef, looping, selectedIndex, ranges, isPlaying) {
        val view = videoViewRef ?: return@LaunchedEffect
        while (true) {
            playheadMs = view.currentPosition.toLong().coerceIn(0L, durationMs)
            val range = ranges.getOrNull(selectedIndex)
            if (looping && isPlaying && range != null) {
                if (playheadMs < range.startMs || playheadMs >= range.endMs) {
                    view.seekTo(range.startMs.toInt())
                    if (!view.isPlaying) {
                        view.start()
                        isPlaying = true
                    }
                }
            }
            delay(80L)
        }
    }

    LaunchedEffect(mute, mediaPlayer) {
        val volume = if (mute) 0f else 1f
        mediaPlayer?.setVolume(volume, volume)
    }

    fun seekTo(ms: Long) {
        val clamped = ms.coerceIn(0L, durationMs)
        videoViewRef?.seekTo(clamped.toInt())
        playheadMs = clamped
    }

    fun applyRanges(next: List<KeepRange>) {
        ranges = next
        selectedIndex = selectedIndex.coerceIn(0, (next.size - 1).coerceAtLeast(0))
    }

    fun runExport(overwrite: Boolean) {
        if (isProcessing) return
        isProcessing = true
        videoViewRef?.let { view ->
            runCatching { view.pause() }
            runCatching { view.stopPlayback() }
        }
        isPlaying = false
        scope.launch {
            val output = if (overwrite) {
                "${filePath}.edit.tmp.mp4"
            } else {
                clipOutputPath(filePath)
            }
            val ok = try {
                withContext(Dispatchers.IO) {
                    val exported = VideoEditEngine.export(
                        context = context,
                        inputPath = filePath,
                        outputPath = output,
                        ranges = ranges,
                        mute = mute,
                        crop = crop
                    )
                    if (exported && overwrite) {
                        VideoEditEngine.overwriteOriginal(filePath, output)
                    } else {
                        exported
                    }
                }
            } catch (_: Exception) {
                false
            } finally {
                isProcessing = false
            }
            val message = when {
                !ok -> R.string.export_clip_failed
                overwrite -> R.string.edit_overwrite_success
                else -> R.string.export_clip_success
            }
            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
            if (ok) {
                VideoThumbnailLoader.invalidate(filePath)
                onTrimComplete(if (overwrite) filePath else output)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_trim)) },
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setOnErrorListener { _, _, _ -> true }
                            setOnPreparedListener { mp ->
                                mediaPlayer = mp
                                mp.isLooping = false
                                val volume = if (mute) 0f else 1f
                                mp.setVolume(volume, volume)
                            }
                            setOnCompletionListener {
                                isPlaying = false
                                val range = ranges.getOrNull(selectedIndex)
                                if (looping && range != null) {
                                    seekTo(range.startMs)
                                    start()
                                    isPlaying = true
                                }
                            }
                            setVideoURI(videoUri)
                            videoViewRef = this
                        }
                    }
                )
                if (cropMode) {
                    CropOverlay(
                        crop = crop,
                        onCropChange = { crop = it }
                    )
                }
                if (!cropMode) IconButton(
                    onClick = {
                        val view = videoViewRef ?: return@IconButton
                        if (view.isPlaying) {
                            view.pause()
                            isPlaying = false
                        } else {
                            val range = ranges.getOrNull(selectedIndex)
                            if (range != null && (playheadMs < range.startMs || playheadMs >= range.endMs)) {
                                view.seekTo(range.startMs.toInt())
                            }
                            view.start()
                            isPlaying = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.edit_current_time, formatDuration(playheadMs)),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(
                    R.string.trim_duration_label,
                    formatDuration(ranges.sumOf { it.durationMs })
                ),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            TimelineStrip(
                durationMs = durationMs,
                playheadMs = playheadMs,
                ranges = ranges,
                selectedIndex = selectedIndex,
                frames = strip,
                onSeek = { seekTo(it) },
                onSelectRange = { selectedIndex = it },
                onMoveEdge = { index, start, ms ->
                    applyRanges(VideoEditEngine.nudgeRange(ranges, index, start, ms - if (start) ranges[index].startMs else ranges[index].endMs, durationMs))
                    seekTo(ms)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ranges.forEachIndexed { index, range ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = {
                            selectedIndex = index
                            seekTo(range.startMs)
                        },
                        label = {
                            Text(
                                "${stringResource(R.string.edit_segment, index + 1)} ${formatDuration(range.startMs)}–${formatDuration(range.endMs)}"
                            )
                        }
                    )
                }
            }

            if (selected != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.trim_start_label, formatDuration(selected.startMs)))
                NudgeRow(
                    onNudge = { delta ->
                        applyRanges(VideoEditEngine.nudgeRange(ranges, selectedIndex, true, delta, durationMs))
                        seekTo((selected.startMs + delta).coerceIn(0L, selected.endMs - 80L))
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.trim_end_label, formatDuration(selected.endMs)))
                NudgeRow(
                    onNudge = { delta ->
                        applyRanges(VideoEditEngine.nudgeRange(ranges, selectedIndex, false, delta, durationMs))
                        seekTo((selected.endMs + delta).coerceIn(selected.startMs + 80L, durationMs))
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditAction(
                    icon = if (looping) Icons.Default.Repeat else Icons.Default.PlayArrow,
                    label = stringResource(R.string.edit_loop),
                    selected = looping,
                    onClick = { looping = !looping }
                )
                EditAction(
                    icon = Icons.Default.CallSplit,
                    label = stringResource(R.string.edit_split),
                    onClick = {
                        val next = VideoEditEngine.splitRanges(ranges, playheadMs)
                        if (next.size != ranges.size) {
                            val newIndex = next.indexOfFirst { playheadMs == it.startMs }.coerceAtLeast(0)
                            ranges = next
                            selectedIndex = newIndex
                        }
                    }
                )
                EditAction(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.edit_delete_segment),
                    enabled = ranges.size > 1,
                    onClick = {
                        applyRanges(VideoEditEngine.deleteRange(ranges, selectedIndex))
                    }
                )
                EditAction(
                    icon = if (mute) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    label = stringResource(R.string.edit_mute),
                    selected = mute,
                    onClick = { mute = !mute }
                )
                EditAction(
                    icon = Icons.Default.Image,
                    label = stringResource(R.string.edit_set_cover),
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                VideoEditEngine.saveCover(context, filePath, playheadMs)
                            }
                            if (ok) VideoThumbnailLoader.invalidate(filePath)
                            Toast.makeText(
                                context,
                                context.getString(if (ok) R.string.edit_cover_success else R.string.edit_cover_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                EditAction(
                    icon = Icons.Default.Crop,
                    label = stringResource(R.string.edit_crop),
                    selected = cropMode,
                    onClick = { cropMode = !cropMode }
                )
            }

            if (cropMode) {
                TextButton(onClick = { crop = CropNorm(0f, 0f, 1f, 1f) }) {
                    Text(stringResource(R.string.edit_reset_crop))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { runExport(false) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing && ranges.any { it.durationMs >= 80L },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isProcessing) stringResource(R.string.processing) else stringResource(R.string.export_clip))
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showOverwrite = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing && !isContentUri && ranges.any { it.durationMs >= 80L },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.edit_overwrite))
            }
        }
    }

    if (isProcessing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.processing)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.processing))
                }
            },
            confirmButton = {}
        )
    }

    if (showOverwrite) {
        AlertDialog(
            onDismissRequest = { showOverwrite = false },
            title = { Text(stringResource(R.string.edit_overwrite)) },
            text = { Text(stringResource(R.string.edit_overwrite_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showOverwrite = false
                    runExport(true)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showOverwrite = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun NudgeRow(onNudge: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { onNudge(-1000L) }, label = { Text(stringResource(R.string.edit_nudge_minus_1)) })
        AssistChip(onClick = { onNudge(-100L) }, label = { Text(stringResource(R.string.edit_nudge_minus_01)) })
        AssistChip(onClick = { onNudge(100L) }, label = { Text(stringResource(R.string.edit_nudge_plus_01)) })
        AssistChip(onClick = { onNudge(1000L) }, label = { Text(stringResource(R.string.edit_nudge_plus_1)) })
    }
}

@Composable
private fun EditAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun TimelineStrip(
    durationMs: Long,
    playheadMs: Long,
    ranges: List<KeepRange>,
    selectedIndex: Int,
    frames: List<Bitmap>,
    onSeek: (Long) -> Unit,
    onSelectRange: (Int) -> Unit,
    onMoveEdge: (Int, Boolean, Long) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    var widthPx by remember { mutableIntStateOf(1) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(durationMs, ranges) {
                detectTapGestures { offset ->
                    if (durationMs <= 0L) return@detectTapGestures
                    val ms = (offset.x / size.width * durationMs).toLong().coerceIn(0L, durationMs)
                    val hit = ranges.indexOfLast { ms in it.startMs..it.endMs }
                    if (hit >= 0) onSelectRange(hit)
                    onSeek(ms)
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (frames.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                frames.forEach { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (durationMs <= 0L) return@Canvas
            ranges.forEachIndexed { index, range ->
                val startX = size.width * range.startMs / durationMs
                val endX = size.width * range.endMs / durationMs
                drawRect(
                    color = if (index == selectedIndex) container.copy(alpha = 0.55f) else container.copy(alpha = 0.28f),
                    topLeft = Offset(startX, 0f),
                    size = androidx.compose.ui.geometry.Size((endX - startX).coerceAtLeast(2f), size.height)
                )
                drawRect(color = primary, topLeft = Offset(startX, 0f), size = androidx.compose.ui.geometry.Size(6f, size.height))
                drawRect(color = primary, topLeft = Offset(endX - 6f, 0f), size = androidx.compose.ui.geometry.Size(6f, size.height))
            }
            val playX = size.width * playheadMs / durationMs
            drawLine(
                color = Color.White,
                start = Offset(playX, 0f),
                end = Offset(playX, size.height),
                strokeWidth = 3f
            )
        }
        val selected = ranges.getOrNull(selectedIndex)
        if (selected != null && durationMs > 0L && widthPx > 1) {
            EdgeHandle(
                fraction = selected.startMs / durationMs.toFloat(),
                widthPx = widthPx,
                onDrag = { dx ->
                    val deltaMs = (dx / widthPx * durationMs).toLong()
                    onMoveEdge(selectedIndex, true, (selected.startMs + deltaMs).coerceIn(0L, selected.endMs - 80L))
                }
            )
            EdgeHandle(
                fraction = selected.endMs / durationMs.toFloat(),
                widthPx = widthPx,
                onDrag = { dx ->
                    val deltaMs = (dx / widthPx * durationMs).toLong()
                    onMoveEdge(selectedIndex, false, (selected.endMs + deltaMs).coerceIn(selected.startMs + 80L, durationMs))
                }
            )
        }
    }
}

@Composable
private fun BoxScope.EdgeHandle(fraction: Float, widthPx: Int, onDrag: (Float) -> Unit) {
    val dragHandler = rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(18.dp)
            .offset { IntOffset((fraction.coerceIn(0f, 1f) * widthPx - 9.dp.toPx()).toInt(), 0) }
            .background(Color.Transparent)
            .pointerInput(widthPx) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    dragHandler.value(dragAmount.x)
                }
            }
    )
}

@Composable
private fun CropOverlay(
    crop: CropNorm,
    onCropChange: (CropNorm) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val cropRef = rememberUpdatedState(crop)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures { }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val l = size.width * crop.left
            val t = size.height * crop.top
            val r = size.width * crop.right
            val b = size.height * crop.bottom
            drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, t))
            drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, b), size = androidx.compose.ui.geometry.Size(size.width, size.height - b))
            drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, t), size = androidx.compose.ui.geometry.Size(l, b - t))
            drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(r, t), size = androidx.compose.ui.geometry.Size(size.width - r, b - t))
            drawRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = androidx.compose.ui.geometry.Size((r - l).coerceAtLeast(2f), (b - t).coerceAtLeast(2f)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }
        val handleSize = 22.dp
        val handlePx = with(density) { handleSize.toPx() }
        fun updateDelta(dx: Float, dy: Float, corner: Int) {
            if (boxSize.width == 0 || boxSize.height == 0) return
            val current = cropRef.value
            val xDelta = dx / boxSize.width
            val yDelta = dy / boxSize.height
            onCropChange(
                when (corner) {
                    0 -> current.copy(
                        left = (current.left + xDelta).coerceIn(0f, current.right - 0.08f),
                        top = (current.top + yDelta).coerceIn(0f, current.bottom - 0.08f)
                    )
                    1 -> current.copy(
                        right = (current.right + xDelta).coerceIn(current.left + 0.08f, 1f),
                        top = (current.top + yDelta).coerceIn(0f, current.bottom - 0.08f)
                    )
                    2 -> current.copy(
                        left = (current.left + xDelta).coerceIn(0f, current.right - 0.08f),
                        bottom = (current.bottom + yDelta).coerceIn(current.top + 0.08f, 1f)
                    )
                    else -> current.copy(
                        right = (current.right + xDelta).coerceIn(current.left + 0.08f, 1f),
                        bottom = (current.bottom + yDelta).coerceIn(current.top + 0.08f, 1f)
                    )
                }
            )
        }
        val positions = listOf(
            crop.left to crop.top,
            crop.right to crop.top,
            crop.left to crop.bottom,
            crop.right to crop.bottom
        )
        positions.forEachIndexed { index, (fx, fy) ->
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { (fx * boxSize.width - handlePx / 2).toDp() },
                        y = with(density) { (fy * boxSize.height - handlePx / 2).toDp() }
                    )
                    .size(handleSize)
                    .background(Color.White, CircleShape)
                    .border(1.dp, Color.Black, CircleShape)
                    .pointerInput(boxSize, index) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            updateDelta(dragAmount.x, dragAmount.y, index)
                        }
                    }
            )
        }
    }
}

private fun clipOutputPath(inputPath: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    return if (inputPath.contains(".")) {
        inputPath.replace(Regex("\\.mp4$", RegexOption.IGNORE_CASE), "_clip_$stamp.mp4")
    } else {
        "${inputPath}_clip_$stamp.mp4"
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (durationMs % 1000) / 10
    return String.format(Locale.getDefault(), "%02d:%02d.%02d", minutes, seconds, millis)
}
