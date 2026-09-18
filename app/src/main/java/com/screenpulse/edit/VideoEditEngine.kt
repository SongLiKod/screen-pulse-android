package com.screenpulse.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
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
            val encoded = encodedVideoSize(context, inputPath)
            val rotation = videoRotation(context, inputPath)
            val display = encoded?.let { orientedSize(it.first, it.second, rotation) }
            val pixelCrop = if (crop == null || crop.isIdentity || encoded == null || display == null) {
                null
            } else {
                val displayCrop = crop.toPixelRect(display.first, display.second)
                mapDisplayCropToEncoded(
                    displayCrop,
                    display.first,
                    display.second,
                    encoded.first,
                    encoded.second,
                    rotation
                )
            }
            val needsCrop = pixelCrop != null &&
                (pixelCrop.width() < encoded!!.first - 2 || pixelCrop.height() < encoded.second - 2)
            val ok = if (needsCrop) {
                remuxWithCrop(
                    context,
                    inputPath,
                    outputPath,
                    valid,
                    mute,
                    pixelCrop!!,
                    encoded.first,
                    encoded.second,
                    rotation
                )
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

    fun displaySize(context: Context, path: String): Pair<Int, Int>? {
        val encoded = encodedVideoSize(context, path) ?: return null
        return orientedSize(encoded.first, encoded.second, videoRotation(context, path))
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
            var buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val startedAt = SystemClock.elapsedRealtime()
            for ((src, dst) in map) {
                for (i in 0 until extractor.trackCount) extractor.unselectTrack(i)
                extractor.selectTrack(src)
                var timelineUs = 0L
                for (range in ranges) {
                    extractor.seekTo(range.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var lastTime = Long.MIN_VALUE
                    var stall = 0
                    while (SystemClock.elapsedRealtime() - startedAt < 180_000L) {
                        val size = try {
                            extractor.readSampleData(buffer, 0)
                        } catch (_: IllegalArgumentException) {
                            buffer = ByteBuffer.allocate(buffer.capacity() * 2)
                            extractor.readSampleData(buffer, 0)
                        }
                        if (size < 0) break
                        val time = extractor.sampleTime
                        if (time > range.endUs) break
                        if (time == lastTime) {
                            stall++
                            if (stall > 8) break
                        } else {
                            stall = 0
                            lastTime = time
                        }
                        if (time >= range.startUs) {
                            info.offset = 0
                            info.size = size
                            info.presentationTimeUs = timelineUs + (time - range.startUs)
                            info.flags = extractor.sampleFlags
                            muxer.writeSampleData(dst, buffer, info)
                        }
                        if (!extractor.advance()) break
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
        crop: Rect,
        encodedW: Int,
        encodedH: Int,
        rotation: Int
    ): Boolean {
        val extractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxerStarted = false
        return try {
            openExtractor(context, extractor, inputPath)
            val videoTrack = findTrack(extractor, "video/") ?: return false
            val srcFormat = extractor.getTrackFormat(videoTrack)
            val mime = srcFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val outW = crop.width().coerceAtLeast(2) and 1.inv()
            val outH = crop.height().coerceAtLeast(2) and 1.inv()
            File(outputPath).parentFile?.mkdirs()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val hint = ((rotation % 360) + 360) % 360
            if (hint != 0) muxer.setOrientationHint(hint)

            val fps = srcFormat.integerOr(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(1, 60)
            encoder = createAvcEncoder(outW, outH, fps) ?: return false
            decoder = createYuvDecoder(mime, srcFormat) ?: return false
            extractor.selectTrack(videoTrack)

            var audioSrc = -1
            var audioDst = -1
            if (!mute) {
                openExtractor(context, audioExtractor, inputPath)
                audioSrc = findTrack(audioExtractor, "audio/") ?: -1
                if (audioSrc >= 0) {
                    audioDst = muxer.addTrack(audioExtractor.getTrackFormat(audioSrc))
                }
            }

            val timeoutUs = 10_000L
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val yuv = ByteArray(outW * outH * 3 / 2)
            val mux = muxer
            val dec = decoder
            val enc = encoder
            var videoDst = -1
            var sawInputEos = false
            var sawDecoderEos = false
            var sawEncoderEos = false
            var rangeIndex = 0
            var firstPtsUs = -1L
            extractor.seekTo(ranges.first().startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val startedAt = SystemClock.elapsedRealtime()
            val deadlineMs = 180_000L

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    val inIndex = enc.dequeueInputBuffer(100_000L)
                    if (inIndex >= 0) {
                        enc.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
                val waitUntil = SystemClock.elapsedRealtime() + if (endOfStream) 3_000L else 0L
                while (!sawEncoderEos) {
                    val outIndex = enc.dequeueOutputBuffer(encInfo, timeoutUs)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream || SystemClock.elapsedRealtime() > waitUntil) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoDst = mux.addTrack(enc.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val encoded = enc.getOutputBuffer(outIndex)
                            if (encoded != null && encInfo.size > 0 && muxerStarted &&
                                encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                encoded.position(encInfo.offset)
                                encoded.limit(encInfo.offset + encInfo.size)
                                mux.writeSampleData(videoDst, encoded, encInfo)
                            }
                            enc.releaseOutputBuffer(outIndex, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEncoderEos = true
                            }
                        }
                        else -> Unit
                    }
                }
            }

            fun queueCropped(image: Image, ptsUs: Long): Boolean {
                drainEncoder(false)
                val inIndex = enc.dequeueInputBuffer(100_000L)
                if (inIndex < 0) return false
                val input = enc.getInputBuffer(inIndex)
                if (input == null || !cropYuvToNv12(image, crop, encodedW, encodedH, outW, outH, yuv)) {
                    enc.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                    return false
                }
                input.clear()
                input.put(yuv)
                enc.queueInputBuffer(inIndex, 0, yuv.size, ptsUs, 0)
                drainEncoder(false)
                return true
            }

            var wroteFrames = 0
            while (!sawDecoderEos && SystemClock.elapsedRealtime() - startedAt < deadlineMs) {
                if (wroteFrames == 0 && SystemClock.elapsedRealtime() - startedAt > 15_000L) break
                if (!sawInputEos) {
                    val inIndex = dec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val input = dec.getInputBuffer(inIndex)
                        val sampleSize = if (input != null) extractor.readSampleData(input, 0) else -1
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            while (rangeIndex < ranges.size && sampleTime > ranges[rangeIndex].endUs) {
                                rangeIndex++
                            }
                            if (rangeIndex >= ranges.size) {
                                dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                dec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(decInfo, timeoutUs)
                if (outIndex >= 0) {
                    val pts = decInfo.presentationTimeUs
                    val keep = ranges.any { pts in it.startUs until it.endUs }
                    val image = if (keep && decInfo.size > 0) {
                        runCatching { dec.getOutputImage(outIndex) }.getOrNull()
                    } else null
                    if (image != null) {
                        if (firstPtsUs < 0L) firstPtsUs = pts
                        if (queueCropped(image, (pts - firstPtsUs).coerceAtLeast(0L))) wroteFrames++
                        image.close()
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawDecoderEos = true
                    }
                }
            }
            drainEncoder(true)
            if (audioSrc >= 0 && audioDst >= 0 && muxerStarted) {
                copyAudioRanges(audioExtractor, mux, audioSrc, audioDst, ranges)
            }
            muxerStarted && wroteFrames > 0 && File(outputPath).length() > 1024L
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_UI, "crop export failed", e)
            false
        } finally {
            runCatching {
                decoder?.stop()
                decoder?.release()
            }
            runCatching {
                encoder?.stop()
                encoder?.release()
            }
            runCatching {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            }
            runCatching { extractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun MediaFormat.integerOr(key: String, fallback: Int): Int {
        return if (containsKey(key)) getInteger(key) else fallback
    }

    private fun createAvcEncoder(width: Int, height: Int, fps: Int): MediaCodec? {
        val colors = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        for (color in colors) {
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                    setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceIn(800_000, 12_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                return codec
            } catch (_: Exception) {
                runCatching { codec?.release() }
            }
        }
        return null
    }

    private fun createYuvDecoder(mime: String, format: MediaFormat): MediaCodec? {
        val preferred = when {
            mime.contains("hevc", true) || mime.contains("h265", true) ->
                listOf("c2.android.hevc.decoder", "OMX.google.hevc.decoder")
            mime.contains("avc", true) || mime.contains("h264", true) ->
                listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
            mime.contains("vp9", true) ->
                listOf("c2.android.vp9.decoder", "OMX.google.vp9.decoder")
            mime.contains("vp8", true) ->
                listOf("c2.android.vp8.decoder", "OMX.google.vp8.decoder")
            else -> emptyList()
        }
        for (name in preferred) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(name)
                codec.configure(format, null, null, 0)
                codec.start()
                return codec
            } catch (_: Exception) {
                runCatching { codec?.release() }
            }
        }
        return runCatching {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        }.getOrNull()
    }

    private fun cropYuvToNv12(
        image: Image,
        crop: Rect,
        cropSpaceW: Int,
        cropSpaceH: Int,
        outW: Int,
        outH: Int,
        out: ByteArray
    ): Boolean {
        return try {
            if (image.planes.size < 3) return false
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer.duplicate()
            val uBuf = uPlane.buffer.duplicate()
            val vBuf = vPlane.buffer.duplicate()
            val yRow = yPlane.rowStride.coerceAtLeast(1)
            val yPix = yPlane.pixelStride.coerceAtLeast(1)
            val uRow = uPlane.rowStride.coerceAtLeast(1)
            val uPix = uPlane.pixelStride.coerceAtLeast(1)
            val vRow = vPlane.rowStride.coerceAtLeast(1)
            val vPix = vPlane.pixelStride.coerceAtLeast(1)
            val picture = image.cropRect
            val picW = picture.width().coerceAtLeast(2)
            val picH = picture.height().coerceAtLeast(2)
            val spaceW = cropSpaceW.coerceAtLeast(2)
            val spaceH = cropSpaceH.coerceAtLeast(2)
            val left = (picture.left + crop.left * picW / spaceW)
                .coerceIn(picture.left, picture.right - 2) and 1.inv()
            val top = (picture.top + crop.top * picH / spaceH)
                .coerceIn(picture.top, picture.bottom - 2) and 1.inv()
            val cropW = (crop.width() * picW / spaceW).coerceAtLeast(2)
            val cropH = (crop.height() * picH / spaceH).coerceAtLeast(2)
            val maxX = picture.right - 1
            val maxY = picture.bottom - 1
            val yLimit = yBuf.limit()
            val uLimit = uBuf.limit()
            val vLimit = vBuf.limit()
            val ySize = outW * outH
            var dst = 0
            for (row in 0 until outH) {
                val sy = (top + row * cropH / outH).coerceIn(picture.top, maxY)
                for (col in 0 until outW) {
                    val sx = (left + col * cropW / outW).coerceIn(picture.left, maxX)
                    val index = sy * yRow + sx * yPix
                    out[dst++] = if (index in 0 until yLimit) yBuf.get(index) else 16
                }
            }
            var uv = ySize
            for (row in 0 until outH step 2) {
                val sy = (top + row * cropH / outH).coerceIn(picture.top, maxY) and 1.inv()
                for (col in 0 until outW step 2) {
                    val sx = (left + col * cropW / outW).coerceIn(picture.left, maxX) and 1.inv()
                    val uvX = sx / 2
                    val uvY = sy / 2
                    val uIndex = uvY * uRow + uvX * uPix
                    val vIndex = uvY * vRow + uvX * vPix
                    out[uv++] = if (uIndex in 0 until uLimit) uBuf.get(uIndex) else 128.toByte()
                    out[uv++] = if (vIndex in 0 until vLimit) vBuf.get(vIndex) else 128.toByte()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun orientedSize(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
        return if (rotation == 90 || rotation == 270) height to width else width to height
    }

    private fun mapDisplayCropToEncoded(
        displayCrop: Rect,
        displayW: Int,
        displayH: Int,
        encodedW: Int,
        encodedH: Int,
        rotation: Int
    ): Rect {
        val r = ((rotation % 360) + 360) % 360
        fun map(x: Int, y: Int): Pair<Int, Int> {
            val cx = x.coerceIn(0, (displayW - 1).coerceAtLeast(0))
            val cy = y.coerceIn(0, (displayH - 1).coerceAtLeast(0))
            return when (r) {
                90 -> cy to (encodedH - 1 - cx)
                180 -> (encodedW - 1 - cx) to (encodedH - 1 - cy)
                270 -> (encodedW - 1 - cy) to cx
                else -> cx to cy
            }
        }
        val rightInclusive = (displayCrop.right - 1).coerceAtLeast(displayCrop.left)
        val bottomInclusive = (displayCrop.bottom - 1).coerceAtLeast(displayCrop.top)
        val points = listOf(
            map(displayCrop.left, displayCrop.top),
            map(rightInclusive, displayCrop.top),
            map(displayCrop.left, bottomInclusive),
            map(rightInclusive, bottomInclusive)
        )
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val l = xs.minOrNull()?.coerceIn(0, encodedW - 2) ?: 0
        val t = ys.minOrNull()?.coerceIn(0, encodedH - 2) ?: 0
        val mappedRight = (xs.maxOrNull() ?: l) + 1
        val mappedBottom = (ys.maxOrNull() ?: t) + 1
        val rr = mappedRight.coerceIn(l + 2, encodedW)
        val b = mappedBottom.coerceIn(t + 2, encodedH)
        val evenW = (rr - l) and 1.inv()
        val evenH = (b - t) and 1.inv()
        return Rect(l, t, l + evenW.coerceAtLeast(2), t + evenH.coerceAtLeast(2))
    }

    private fun videoRotation(context: Context, path: String): Int {
        val retriever = MediaMetadataRetriever()
        try {
            openRetriever(context, retriever, path)
            val fromMeta = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull()
            if (fromMeta != null) return ((fromMeta % 360) + 360) % 360
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        val extractor = MediaExtractor()
        return try {
            openExtractor(context, extractor, path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    return ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360
                }
            }
            0
        } catch (_: Exception) {
            0
        } finally {
            runCatching { extractor.release() }
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

    private fun encodedVideoSize(context: Context, path: String): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        return try {
            openExtractor(context, extractor, path)
            encodedVideoSize(extractor)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun encodedVideoSize(extractor: MediaExtractor): Pair<Int, Int>? {
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
