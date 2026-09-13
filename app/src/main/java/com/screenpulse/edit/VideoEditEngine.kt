package com.screenpulse.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import com.screenpulse.util.LogManager
import java.io.File
import java.nio.ByteBuffer

data class KeepRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val startUs: Long get() = startMs * 1000L
    val endUs: Long get() = endMs * 1000L
}

data class CropNorm(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val isIdentity: Boolean
        get() = left <= 0.01f && top <= 0.01f && right >= 0.99f && bottom >= 0.99f

    fun toPixelRect(width: Int, height: Int): Rect {
        val l = (left.coerceIn(0f, 1f) * width).toInt().coerceIn(0, width - 2)
        val t = (top.coerceIn(0f, 1f) * height).toInt().coerceIn(0, height - 2)
        val r = (right.coerceIn(0f, 1f) * width).toInt().coerceIn(l + 2, width)
        val b = (bottom.coerceIn(0f, 1f) * height).toInt().coerceIn(t + 2, height)
        val evenW = (r - l) and 1.inv()
        val evenH = (b - t) and 1.inv()
        return Rect(l, t, l + evenW.coerceAtLeast(2), t + evenH.coerceAtLeast(2))
    }
}

object VideoEditEngine {

    fun export(
        context: Context,
        inputPath: String,
        outputPath: String,
        ranges: List<KeepRange>,
        mute: Boolean,
        crop: CropNorm?
    ): Boolean {
        val valid = ranges.filter { it.durationMs >= 80L }
        if (valid.isEmpty()) return false
        return try {
            val size = videoSize(context, inputPath)
            val pixelCrop = if (crop == null || crop.isIdentity || size == null) {
                null
            } else {
                crop.toPixelRect(size.first, size.second)
            }
            val needsCrop = pixelCrop != null &&
                (pixelCrop.width() < size!!.first - 2 || pixelCrop.height() < size.second - 2)
            val ok = if (needsCrop) {
                remuxWithCrop(context, inputPath, outputPath, valid, mute, pixelCrop!!)
            } else {
                remux(context, inputPath, outputPath, valid, mute)
            }
            if (!ok) runCatching { File(outputPath).delete() }
            ok
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "export failed", e)
            runCatching { File(outputPath).delete() }
            false
        }
    }

    fun overwriteOriginal(originalPath: String, tempPath: String): Boolean {
        val original = File(originalPath)
        val temp = File(tempPath)
        if (!temp.exists() || temp.length() < 1024) return false
        val backup = File(original.parent, original.name + ".bak")
        return try {
            if (original.exists()) original.renameTo(backup)
            val moved = temp.renameTo(original)
            if (moved) {
                backup.delete()
                true
            } else {
                if (backup.exists()) backup.renameTo(original)
                false
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "overwrite failed", e)
            if (backup.exists() && !original.exists()) backup.renameTo(original)
            false
        }
    }

    fun saveCover(context: Context, videoPath: String, timeMs: Long): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            openRetriever(context, retriever, videoPath)
            val frame = retriever.getFrameAtTime(
                timeMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return false
            val file = coverFileFor(videoPath) ?: return false
            file.outputStream().use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            frame.recycle()
            true
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "save cover failed", e)
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun coverFileFor(videoPath: String): File? {
        if (videoPath.startsWith("content://")) return null
        val video = File(videoPath)
        val parent = video.parentFile ?: return null
        val stem = video.name.substringBeforeLast('.')
        return File(parent, "$stem.cover.jpg")
    }

    fun loadStrip(context: Context, path: String, count: Int): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()
        try {
            openRetriever(context, retriever, path)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return emptyList()
            val n = count.coerceIn(4, 12)
            for (i in 0 until n) {
                val t = if (n == 1) 0L else durationMs * i / (n - 1)
                val frame = retriever.getFrameAtTime(
                    t * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                val scaled = scaleToWidth(frame, 120)
                if (scaled != frame) frame.recycle()
                frames.add(scaled)
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "strip failed", e)
        } finally {
            runCatching { retriever.release() }
        }
        return frames
    }

    fun durationMs(context: Context, path: String): Long {
        val extractor = MediaExtractor()
        return try {
            openExtractor(context, extractor, path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                    return format.getLong(MediaFormat.KEY_DURATION) / 1000L
                }
            }
            val retriever = MediaMetadataRetriever()
            try {
                openRetriever(context, retriever, path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun splitRanges(ranges: List<KeepRange>, atMs: Long): List<KeepRange> {
        val result = mutableListOf<KeepRange>()
        ranges.forEach { range ->
            if (atMs > range.startMs + 80L && atMs < range.endMs - 80L) {
                result += KeepRange(range.startMs, atMs)
                result += KeepRange(atMs, range.endMs)
            } else {
                result += range
            }
        }
        return result
    }

    fun deleteRange(ranges: List<KeepRange>, index: Int): List<KeepRange> {
        if (index !in ranges.indices || ranges.size <= 1) return ranges
        return ranges.filterIndexed { i, _ -> i != index }
    }

    fun nudgeRange(ranges: List<KeepRange>, index: Int, start: Boolean, deltaMs: Long, durationMs: Long): List<KeepRange> {
        if (index !in ranges.indices) return ranges
        return ranges.mapIndexed { i, range ->
            if (i != index) range
            else if (start) {
                val startMs = (range.startMs + deltaMs).coerceIn(0L, range.endMs - 80L)
                range.copy(startMs = startMs)
            } else {
                val endMs = (range.endMs + deltaMs).coerceIn(range.startMs + 80L, durationMs)
                range.copy(endMs = endMs)
            }
        }
    }

    private fun remux(
        context: Context,
        inputPath: String,
        outputPath: String,
        ranges: List<KeepRange>,
        mute: Boolean
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            openExtractor(context, extractor, inputPath)
            File(outputPath).parentFile?.mkdirs()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = linkedMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mute && mime.startsWith("audio/")) continue
                map[i] = muxer.addTrack(format)
            }
            if (map.isEmpty()) return false
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((src, dst) in map) {
                for (i in 0 until extractor.trackCount) extractor.unselectTrack(i)
                extractor.selectTrack(src)
                var timelineUs = 0L
                for (range in ranges) {
                    extractor.seekTo(range.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val time = extractor.sampleTime
                        if (time > range.endUs) break
                        if (time >= range.startUs) {
                            info.offset = 0
                            info.size = size
                            info.presentationTimeUs = timelineUs + (time - range.startUs)
                            info.flags = extractor.sampleFlags
                            muxer.writeSampleData(dst, buffer, info)
                        }
                        extractor.advance()
                    }
                    timelineUs += range.durationMs * 1000L
                }
            }
            true
        } finally {
            runCatching { extractor.release() }
            runCatching {
                muxer?.stop()
                muxer?.release()
            }
        }
    }

    private fun remuxWithCrop(
        context: Context,
        inputPath: String,
        outputPath: String,
        ranges: List<KeepRange>,
        mute: Boolean,
        crop: Rect
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        return try {
            openRetriever(context, retriever, inputPath)
            openExtractor(context, extractor, inputPath)
            val outW = crop.width().coerceAtLeast(2) and 1.inv()
            val outH = crop.height().coerceAtLeast(2) and 1.inv()
            File(outputPath).parentFile?.mkdirs()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * 4).coerceIn(1_000_000, 8_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var audioSrc = -1
            var audioDst = -1
            if (!mute) {
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioSrc = i
                        audioDst = muxer.addTrack(extractor.getTrackFormat(i))
                        break
                    }
                }
            }

            var muxerStarted = false
            var videoDst = -1
            val timeoutUs = 10_000L
            val bufferInfo = MediaCodec.BufferInfo()
            var ptsUs = 0L
            val stepUs = 33_333L
            val yuv = ByteArray(outW * outH * 3 / 2)
            val codec = encoder
            val mux = muxer

            fun drainEncoder(endOfStream: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + if (endOfStream) 2000L else 0L
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream || SystemClock.elapsedRealtime() > deadline) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoDst = mux.addTrack(codec.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outIndex)
                            if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                mux.writeSampleData(videoDst, encoded, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        else -> return
                    }
                }
            }

            fun queueFrame(bitmap: Bitmap?, endOfStream: Boolean) {
                val inIndex = codec.dequeueInputBuffer(100_000L)
                if (inIndex < 0) return
                val input = codec.getInputBuffer(inIndex) ?: return
                input.clear()
                if (bitmap != null) {
                    bitmapToNv12(bitmap, outW, outH, yuv)
                    input.put(yuv)
                    codec.queueInputBuffer(inIndex, 0, yuv.size, ptsUs, 0)
                    ptsUs += stepUs
                } else {
                    codec.queueInputBuffer(
                        inIndex,
                        0,
                        0,
                        ptsUs,
                        if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    )
                }
                drainEncoder(false)
            }

            for (range in ranges) {
                var tUs = range.startUs
                while (tUs < range.endUs) {
                    val frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val left = crop.left.coerceIn(0, frame.width - 2)
                        val top = crop.top.coerceIn(0, frame.height - 2)
                        val width = crop.width().coerceAtMost(frame.width - left).coerceAtLeast(2)
                        val height = crop.height().coerceAtMost(frame.height - top).coerceAtLeast(2)
                        val cropped = Bitmap.createBitmap(frame, left, top, width, height)
                        val scaled = if (cropped.width == outW && cropped.height == outH) {
                            cropped
                        } else {
                            Bitmap.createScaledBitmap(cropped, outW, outH, true)
                        }
                        queueFrame(scaled, false)
                        if (scaled != cropped) scaled.recycle()
                        if (cropped != frame) cropped.recycle()
                        frame.recycle()
                    }
                    tUs += stepUs
                }
            }
            queueFrame(null, true)
            drainEncoder(true)

            if (audioSrc >= 0 && audioDst >= 0 && muxerStarted) {
                copyAudioRanges(extractor, mux, audioSrc, audioDst, ranges)
            }
            muxerStarted
        } finally {
            runCatching {
                encoder?.stop()
                encoder?.release()
            }
            runCatching {
                muxer?.stop()
                muxer?.release()
            }
            runCatching { extractor.release() }
            runCatching { retriever.release() }
        }
    }

    private fun bitmapToNv12(src: Bitmap, width: Int, height: Int, out: ByteArray) {
        val argb = IntArray(width * height)
        val bmp = if (src.width == width && src.height == height) src else {
            Bitmap.createScaledBitmap(src, width, height, true)
        }
        bmp.getPixels(argb, 0, width, 0, 0, width, height)
        if (bmp != src) bmp.recycle()
        val frame = width * height
        var uv = frame
        var yIndex = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uv++] = u.coerceIn(0, 255).toByte()
                    out[uv++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun copyAudioRanges(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        src: Int,
        dst: Int,
        ranges: List<KeepRange>
    ) {
        val buffer = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        var timelineUs = 0L
        extractor.selectTrack(src)
        for (range in ranges) {
            extractor.seekTo(range.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > range.endUs) break
                if (time >= range.startUs) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = timelineUs + (time - range.startUs)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(dst, buffer, info)
                }
                extractor.advance()
            }
            timelineUs += range.durationMs * 1000L
        }
    }

    private fun videoSize(context: Context, path: String): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        return try {
            openExtractor(context, extractor, path)
            videoSize(extractor)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun videoSize(extractor: MediaExtractor): Pair<Int, Int>? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT)
            }
        }
        return null
    }

    private fun openExtractor(context: Context, extractor: MediaExtractor, path: String) {
        if (path.startsWith("content://")) {
            extractor.setDataSource(context, Uri.parse(path), null)
        } else {
            extractor.setDataSource(path)
        }
    }

    private fun openRetriever(context: Context, retriever: MediaMetadataRetriever, path: String) {
        if (path.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(path))
        } else {
            retriever.setDataSource(path)
        }
    }

    private fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width <= targetWidth) return src
        val height = (src.height * targetWidth.toFloat() / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, height, true)
    }
}
