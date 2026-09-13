package com.screenpulse.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache

object VideoThumbnailLoader {

    private val cache = LruCache<String, Bitmap>(24)

    fun load(context: Context, path: String, uri: Uri? = null): Bitmap? {
        cache.get(path)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri != null) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(path)
            }
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val timeUs = if (durationMs >= 1000L) 1_000_000L else 0L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (frame == null) return null
            val scaled = scaleToWidth(frame, 240)
            if (scaled != frame) frame.recycle()
            cache.put(path, scaled)
            scaled
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "thumbnail failed: $path", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= targetWidth) return src
        val height = (src.height * targetWidth.toFloat() / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, height, true)
    }
}
