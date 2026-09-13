package com.screenpulse.ui.videolist

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import android.media.MediaMetadataRetriever
import com.screenpulse.R
import com.screenpulse.util.LogManager
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

    LaunchedEffect(Unit) {
        videos = loadVideos(context)
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
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
                items(videos) { video ->
                    VideoCard(
                        video = video,
                        onClick = { onVideoClick(video.displayPath) },
                        onDelete = { showDeleteDialog = video },
                        onRename = { showRenameDialog = video },
                        onShare = { shareVideo(context, video) },
                        onExport = { exportVideo(context, video) },
                        onTrim = { onVideoTrim(video.displayPath) }
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onTrim: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = formatFileSize(video.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(" | ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = video.duration,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = formatDate(video.lastModified),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onTrim) {
                    Icon(Icons.Default.ContentCut, contentDescription = stringResource(R.string.cd_trim),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.cd_export),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_rename),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun loadVideos(context: Context): List<VideoItem> {
    val items = mutableListOf<VideoItem>()
    val retriever = MediaMetadataRetriever()

    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenPulse")
    if (dir.exists()) {
        dir.listFiles { file -> file.extension == "mp4" }?.forEach { file ->
            // Skip empty or trivially small files — they are not valid recordings
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
    video.file?.delete()
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
        video.file.renameTo(newFile)
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
    val uri = video.uri ?: video.file?.let {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    } ?: return
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
}

private fun exportVideo(context: Context, video: VideoItem) {
    val ok = runCatching {
        val resolver = context.contentResolver
        val fileName = video.name.ifBlank { "ScreenPulse_${System.currentTimeMillis()}.mp4" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenPulse")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val destUri = resolver.insert(collection, values) ?: return@runCatching false
        resolver.openOutputStream(destUri)?.use { output ->
            when {
                video.file != null -> video.file.inputStream().use { it.copyTo(output) }
                video.uri != null -> resolver.openInputStream(video.uri)?.use { it.copyTo(output) }
                    ?: return@runCatching false
                else -> return@runCatching false
            }
        } ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(destUri, values, null, null)
        }
        true
    }.getOrDefault(false)
    Toast.makeText(
        context,
        context.getString(if (ok) R.string.export_success else R.string.export_failed),
        Toast.LENGTH_SHORT
    ).show()
}
