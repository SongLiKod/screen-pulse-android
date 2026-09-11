package com.screenpulse.compress

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.screenpulse.repository.CompressionMode
import com.screenpulse.util.LogManager

class VideoCompressWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_INPUT_PATH = "input_path"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_COMPRESSION_MODE = "compression_mode"
        const val KEY_PROGRESS = "progress"
    }

    override suspend fun doWork(): Result {
        val inputPath = inputData.getString(KEY_INPUT_PATH) ?: return Result.failure()
        val outputPath = inputData.getString(KEY_OUTPUT_PATH) ?: return Result.failure()
        val modeValue = inputData.getInt(KEY_COMPRESSION_MODE, CompressionMode.BALANCED.value)
        val mode = CompressionMode.fromValue(modeValue)
        LogManager.log(LogManager.TAG_COMPRESS, "compress start: $inputPath -> $outputPath mode=$mode")

        return try {
            compressVideo(inputPath, outputPath, mode)
            LogManager.log(LogManager.TAG_COMPRESS, "compress done: ${outputPath}")
            Result.success(Data.Builder()
                .putString(KEY_OUTPUT_PATH, outputPath)
                .build())
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_COMPRESS, "compress FAILED", e)
            Result.failure(Data.Builder()
                .putString("error", e.message)
                .build())
        }
    }

    private suspend fun compressVideo(inputPath: String, outputPath: String, mode: CompressionMode) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && videoTrackIndex == -1 -> {
                    videoTrackIndex = i
                    videoFormat = format
                }
                mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }
        }

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val (targetBitrate, targetFrameRate) = when (mode) {
            CompressionMode.FAST -> Pair(2000000, 24)
            CompressionMode.BALANCED -> Pair(4000000, 30)
            CompressionMode.HD_LOSSLESS -> Pair(
                videoFormat?.getInteger(MediaFormat.KEY_BIT_RATE) ?: 8000000,
                videoFormat?.getInteger(MediaFormat.KEY_FRAME_RATE) ?: 30
            )
        }

        if (videoFormat != null) {
            val outputVideoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                videoFormat.getInteger(MediaFormat.KEY_WIDTH),
                videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            val decoder = MediaCodec.createDecoderByType(
                videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            )

            extractor.selectTrack(videoTrackIndex)

            val muxerVideoTrack = muxer.addTrack(outputVideoFormat)
            if (audioFormat != null && audioTrackIndex != -1) {
                val muxerAudioTrack = muxer.addTrack(audioFormat)
            }
            muxer.start()

            encoder.stop()
            encoder.release()
            decoder.stop()
            decoder.release()
        }

        extractor.release()
        muxer.stop()
        muxer.release()
    }
}
