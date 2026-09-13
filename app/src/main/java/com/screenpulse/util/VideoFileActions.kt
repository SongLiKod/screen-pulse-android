package com.screenpulse.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object VideoFileActions {

    fun share(context: Context, path: String) {
        val uri = uriFor(context, path) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(com.screenpulse.R.string.share_video)))
    }

    fun exportToMovies(context: Context, path: String, displayName: String? = null): Boolean {
        return runCatching {
            val resolver = context.contentResolver
            val fileName = displayName?.ifBlank { null }
                ?: path.substringAfterLast('/').ifBlank { "ScreenPulse_${System.currentTimeMillis()}.mp4" }
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
                if (path.startsWith("content://")) {
                    resolver.openInputStream(Uri.parse(path))?.use { it.copyTo(output) }
                        ?: return@runCatching false
                } else {
                    File(path).inputStream().use { it.copyTo(output) }
                }
            } ?: return@runCatching false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(destUri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }

    fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("path", text))
    }

    fun uriFor(context: Context, path: String): Uri? {
        return if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            if (!file.exists()) null
            else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }
}
