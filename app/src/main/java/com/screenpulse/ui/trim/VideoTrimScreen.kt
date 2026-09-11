package com.screenpulse.ui.trim

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenpulse.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimScreen(
    filePath: String,
    onBack: () -> Unit,
    onTrimComplete: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var duration by remember { mutableLongStateOf(0L) }
    var trimStart by remember { mutableFloatStateOf(0f) }
    var trimEnd by remember { mutableFloatStateOf(1f) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                        break
                    }
                }
                extractor.release()
            } catch (e: Exception) {
                e.printStackTrace()
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.video_duration), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        formatDuration(duration),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.trim_range), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.trim_start_label, formatDuration((trimStart * duration).toLong())))
                    Text(stringResource(R.string.trim_end_label, formatDuration((trimEnd * duration).toLong())))
                    Text(
                        stringResource(R.string.trim_duration_label, formatDuration(((trimEnd - trimStart) * duration).toLong())),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(trimEnd - trimStart)
                                .offset(x = (trimStart * 100).toInt().let {
                                    val totalWidth = 100
                                    (it * totalWidth / 100).dp
                                })
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(24.dp)
                                .offset(x = (trimStart * 100).toInt().let {
                                    val totalWidth = 100
                                    (it * totalWidth / 100).dp
                                })
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        val delta = dragAmount / size.width
                                        trimStart = (trimStart + delta).coerceIn(0f, trimEnd - 0.01f)
                                    }
                                }
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(24.dp)
                                .align(Alignment.CenterEnd)
                                .offset(x = -((1f - trimEnd) * 100).toInt().let {
                                    val totalWidth = 100
                                    (it * totalWidth / 100).dp
                                })
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        val delta = dragAmount / size.width
                                        trimEnd = (trimEnd + delta).coerceIn(trimStart + 0.01f, 1f)
                                    }
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    isProcessing = true
                    scope.launch {
                        val outputPath = filePath.replace(".mp4", "_trimmed.mp4")
                        val success = withContext(Dispatchers.IO) {
                            trimVideo(filePath, outputPath, trimStart, trimEnd, duration)
                        }
                        isProcessing = false
                        if (success) {
                            onTrimComplete(outputPath)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing && trimEnd > trimStart,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isProcessing) stringResource(R.string.processing) else stringResource(R.string.video_trim))
            }
        }
    }
}

private fun trimVideo(
    inputPath: String,
    outputPath: String,
    startFraction: Float,
    endFraction: Float,
    totalDurationMs: Long
): Boolean {
    return try {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        val startUs = (startFraction * totalDurationMs * 1000).toLong()
        val endUs = (endFraction * totalDurationMs * 1000).toLong()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackIndices = mutableMapOf<Int, Int>()

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val muxerTrackIndex = muxer.addTrack(format)
            trackIndices[i] = muxerTrackIndex
        }

        muxer.start()

        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        for (trackIndex in 0 until extractor.trackCount) {
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime - startUs
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(trackIndices[trackIndex]!!, buffer, bufferInfo)
                }

                extractor.advance()
            }
        }

        extractor.release()
        muxer.stop()
        muxer.release()

        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (durationMs % 1000) / 10
    return String.format("%02d:%02d.%02d", minutes, seconds, millis)
}
