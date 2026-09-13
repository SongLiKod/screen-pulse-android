package com.screenpulse.ui.videolist

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.MediaMetadataRetriever
import androidx.work.WorkManager
import com.screenpulse.R
import com.screenpulse.compress.CompressTracker
import com.screenpulse.compress.CompressUiState
import com.screenpulse.edit.VideoEditEngine
import com.screenpulse.util.LogManager
import com.screenpulse.util.VideoFileActions
import com.screenpulse.util.VideoThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoItem(
    val file: File? = null,
    val uri: Uri? = null,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val duration: String,
    val displayPath: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onVideoTrim: (String) -> Unit
) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf<VideoItem?>(null) }
    var showRenameDialog by remember { mutableStateOf<VideoItem?>(null) }
    val compressInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagFlow(CompressTracker.TAG)
        .collectAsState(initial = emptyList())

    fun refreshVideos() {
        videos = loadVideos(context)
    }

    LaunchedEffect(Unit) {
        refreshVideos()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_videos)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { refreshVideos() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        if (videos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.no_videos),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(videos, key = { it.displayPath }) { video ->
                    VideoCard(
                        video = video,
                        compressState = CompressTracker.stateFor(video.displayPath, compressInfos),
                        onClick = { onVideoClick(video.displayPath) },
                        onDelete = { showDeleteDialog = video },
                        onRename = { showRenameDialog = video },
                        onShare = { shareVideo(context, video) },
                        onExport = { exportVideo(context, video) },
                        onTrim = { onVideoTrim(video.displayPath) },
                        onRetryCompress = { CompressTracker.retry(context, video.displayPath) }
                    )
                }
            }
        }

        showDeleteDialog?.let { video ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text(stringResource(R.string.delete_video)) },
                text = { Text(stringResource(R.string.delete_confirm, video.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteVideo(context, video)
                        videos = loadVideos(context)
                        showDeleteDialog = null
                    }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        showRenameDialog?.let { video ->
            var newName by remember { mutableStateOf(video.name.replace(".mp4", "")) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = null },
                title = { Text(stringResource(R.string.rename_video)) },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.file_name_label)) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        renameVideo(context, video, newName)
                        videos = loadVideos(context)
                        showRenameDialog = null
                    }) { Text(stringResource(R.string.rename)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}

@Composable
private fun VideoCard(
    video: VideoItem,
    compressState: CompressUiState?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onTrim: () -> Unit,
    onRetryCompress: () -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember(video.displayPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.displayPath) {
        thumbnail = withContext(Dispatchers.IO) {
            VideoThumbnailLoader.load(context, video.displayPath, video.uri)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    val frame = thumbnail
                    if (frame != null) {
                        Image(
                            bitmap = frame.asImageBitmap(),
                            contentDescription = stringResource(R.string.preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = stringResource(R.string.preview),
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = video.duration,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${formatFileSize(video.size)}  ·  ${formatDate(video.lastModified)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    when {
                        compressState?.running == true -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.compress_progress, compressState.progress),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            LinearProgressIndicator(
                                progress = compressState.progress / 100f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            )
                        }
                        compressState?.failed == true -> {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = compressState.error ?: stringResource(R.string.compress_failed),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = onRetryCompress, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text(stringResource(R.string.compress_retry), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                VideoAction(
                    icon = Icons.Default.ContentCut,
                    label = stringResource(R.string.action_trim),
                    onClick = onTrim
                )
                VideoAction(
                    icon = Icons.Default.FileDownload,
                    label = stringResource(R.string.export),
                    onClick = onExport
                )
                VideoAction(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.share),
                    onClick = onShare
                )
                VideoAction(
                    icon = Icons.Default.Edit,
                    label = stringResource(R.string.rename),
                    onClick = onRename
                )
                VideoAction(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.delete),
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RowScope.VideoAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = tint)
            Spacer(modifier = Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, color = tint, maxLines = 1)
        }
    }
}

private fun loadVideos(context: Context): List<VideoItem> {
    val items = mutableListOf<VideoItem>()
    val retriever = MediaMetadataRetriever()

    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenPulse")
    if (dir.exists()) {
        dir.listFiles { file -> file.extension.equals("mp4", true) && !file.name.endsWith(".edit.tmp.mp4") }?.forEach { file ->
            if (file.length() < 1024) {
                LogManager.log(LogManager.TAG_UI, "Skipping invalid/small video file: ${file.name} size=${file.length()}")
                return@forEach
            }
            val duration = try {
                retriever.setDataSource(file.absolutePath)
                formatDuration(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            } catch (_: Exception) { "00:00" }
            items.add(
                VideoItem(
                    file = file,
                    name = file.name,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    duration = duration,
                    displayPath = file.absolutePath
                )
            )
        }
    }

    val customUri = loadCustomSaveTreeUri(context)
    if (customUri != null) {
        try {
            val treeDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, customUri)
            if (treeDir != null && treeDir.isDirectory) {
                treeDir.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".mp4") == true }
                    ?.forEach { doc ->
                        val duration = try {
                            retriever.setDataSource(context, doc.uri)
                            formatDuration(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
                        } catch (_: Exception) { "00:00" }
                        items.add(
                            VideoItem(
                                uri = doc.uri,
                                name = doc.name ?: "video.mp4",
                                size = doc.length(),
                                lastModified = doc.lastModified(),
                                duration = duration,
                                displayPath = doc.uri.toString()
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "loadVideos custom dir error: ${e.message}")
        }
    }

    try { retriever.release() } catch (_: Exception) {}
    return items.sortedByDescending { it.lastModified }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun loadCustomSaveTreeUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("screen_pulse_save_path", Context.MODE_PRIVATE)
    val s = prefs.getString("custom_save_tree_uri", null) ?: return null
    return if (s.isEmpty()) null else Uri.parse(s)
}

private fun deleteVideo(context: Context, video: VideoItem) {
    video.file?.let { file ->
        VideoEditEngine.coverFileFor(file.absolutePath)?.delete()
        file.delete()
    }
    video.uri?.let {
        try {
            androidx.documentfile.provider.DocumentFile.fromSingleUri(context, it)?.delete()
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "delete custom video error: ${e.message}")
        }
    }
}

private fun renameVideo(context: Context, video: VideoItem, newName: String) {
    val fileName = if (newName.endsWith(".mp4")) newName else "$newName.mp4"
    if (video.file != null) {
        val newFile = File(video.file.parent, fileName)
        val cover = VideoEditEngine.coverFileFor(video.file.absolutePath)
        video.file.renameTo(newFile)
        cover?.takeIf { it.exists() }?.renameTo(File(newFile.parent, newFile.name.substringBeforeLast('.') + ".cover.jpg"))
        VideoThumbnailLoader.invalidate(video.displayPath)
    }
    video.uri?.let {
        try {
            androidx.documentfile.provider.DocumentFile.fromSingleUri(context, it)?.renameTo(fileName)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "rename custom video error: ${e.message}")
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1073741824 -> String.format("%.1f GB", bytes / 1073741824.0)
        bytes >= 1048576 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun shareVideo(context: Context, video: VideoItem) {
    VideoFileActions.share(context, video.displayPath)
}

private fun exportVideo(context: Context, video: VideoItem) {
    val ok = VideoFileActions.exportToMovies(context, video.displayPath, video.name)
    Toast.makeText(
        context,
        context.getString(if (ok) R.string.export_success else R.string.export_failed),
        Toast.LENGTH_SHORT
    ).show()
}
