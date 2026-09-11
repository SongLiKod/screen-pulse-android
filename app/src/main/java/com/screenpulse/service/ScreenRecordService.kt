package com.screenpulse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.screenpulse.R
import com.screenpulse.compress.VideoCompressWorker
import com.screenpulse.repository.AudioMode
import com.screenpulse.repository.BitrateMode
import com.screenpulse.repository.CompressionMode
import com.screenpulse.repository.CountdownMode
import com.screenpulse.repository.FrameRate
import com.screenpulse.repository.RecordMode
import com.screenpulse.repository.Resolution
import com.screenpulse.viewmodel.RecordingState
import com.screenpulse.jni.NativeBridge
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.screenpulse.action.START"
        const val ACTION_STOP = "com.screenpulse.action.STOP"
        const val ACTION_PAUSE = "com.screenpulse.action.PAUSE"
        const val ACTION_RESUME = "com.screenpulse.action.RESUME"
        const val ACTION_SCREENSHOT = "com.screenpulse.action.SCREENSHOT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_FRAME_RATE = "frame_rate"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_RECORD_MODE = "record_mode"
        const val EXTRA_COUNTDOWN = "countdown"
        const val EXTRA_COMPRESSION_MODE = "compression_mode"
        const val EXTRA_CUSTOM_WIDTH = "custom_width"
        const val EXTRA_CUSTOM_HEIGHT = "custom_height"
        const val EXTRA_CUSTOM_OFFSET_X = "custom_offset_x"
        const val EXTRA_CUSTOM_OFFSET_Y = "custom_offset_y"
        const val EXTRA_SYSTEM_VOLUME = "system_volume"
        const val EXTRA_MIC_VOLUME = "mic_volume"
        const val EXTRA_WATERMARK_ENABLED = "watermark_enabled"
        const val EXTRA_WATERMARK_TEXT = "watermark_text"
        const val EXTRA_WATERMARK_TYPE = "watermark_type"
        const val EXTRA_WATERMARK_IMAGE_URI = "watermark_image_uri"
        const val EXTRA_PIP_ENABLED = "pip_enabled"
        const val EXTRA_CUSTOM_RESOLUTION_WIDTH = "custom_resolution_width"
        const val EXTRA_CUSTOM_RESOLUTION_HEIGHT = "custom_resolution_height"
        const val CHANNEL_ID = "screen_pulse_recording"
        const val NOTIFICATION_ID = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderInputSurface: Surface? = null
    private var mediaCodec: MediaCodec? = null
    private var micAudioRecord: AudioRecord? = null
    private var systemAudioRecord: AudioRecord? = null
    private var mediaMuxer: MediaMuxer? = null
    private var isRecording = false
    private var isPaused = false
    private var recordingStartTime = 0L
    private var pausedDuration = 0L
    private var totalPausedDuration = 0L
    private var outputFile: File? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var bufferInfo = MediaCodec.BufferInfo()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var durationJob: Job? = null
    private var encodeJob: Job? = null
    private var audioEncodeJob: Job? = null

    private var audioCodec: MediaCodec? = null
    private var audioRecordBuffer: ByteArray? = null

    private var currentResolution = Resolution.R1080P
    private var currentFrameRate = FrameRate.FPS_30
    private var currentBitrate = 8000000
    private var currentAudioMode = AudioMode.MIC_ONLY
    private var currentRecordMode = RecordMode.FULL_SCREEN
    private var currentCompressionMode = CompressionMode.BALANCED
    private var customWidth = 0
    private var customHeight = 0
    private var customOffsetX = 0
    private var customOffsetY = 0
    private var systemVolume = 100
    private var micVolume = 100
    private var watermarkEnabled = false
    private var watermarkText = ""
    private var pipEnabled = false
    private var customResolutionWidth = 1920
    private var customResolutionHeight = 1080
    private var recordWidth = 0
    private var recordHeight = 0
    private var recordDensityDpi = 0

    private var stateCallback: RecordingStateCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_SCREENSHOT -> takeScreenshot()
        }
        return START_STICKY
    }

    fun setStateCallback(callback: RecordingStateCallback?) {
        stateCallback = callback
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        currentResolution = Resolution.fromValue(intent.getStringExtra(EXTRA_RESOLUTION) ?: "1080P")
        currentFrameRate = FrameRate.fromValue(intent.getIntExtra(EXTRA_FRAME_RATE, 30))
        currentBitrate = intent.getIntExtra(EXTRA_BITRATE, 8000000)
        currentAudioMode = AudioMode.fromValue(intent.getIntExtra(EXTRA_AUDIO_MODE, 1))
        currentRecordMode = RecordMode.fromValue(intent.getIntExtra(EXTRA_RECORD_MODE, 0))
        currentCompressionMode = CompressionMode.fromValue(intent.getIntExtra(EXTRA_COMPRESSION_MODE, 1))
        customWidth = intent.getIntExtra(EXTRA_CUSTOM_WIDTH, 0)
        customHeight = intent.getIntExtra(EXTRA_CUSTOM_HEIGHT, 0)
        customOffsetX = intent.getIntExtra(EXTRA_CUSTOM_OFFSET_X, 0)
        customOffsetY = intent.getIntExtra(EXTRA_CUSTOM_OFFSET_Y, 0)
        systemVolume = intent.getIntExtra(EXTRA_SYSTEM_VOLUME, 100)
        micVolume = intent.getIntExtra(EXTRA_MIC_VOLUME, 100)
        watermarkEnabled = intent.getBooleanExtra(EXTRA_WATERMARK_ENABLED, false)
        watermarkText = intent.getStringExtra(EXTRA_WATERMARK_TEXT) ?: ""
        pipEnabled = intent.getBooleanExtra(EXTRA_PIP_ENABLED, false)
        customResolutionWidth = intent.getIntExtra(EXTRA_CUSTOM_RESOLUTION_WIDTH, 1920)
        customResolutionHeight = intent.getIntExtra(EXTRA_CUSTOM_RESOLUTION_HEIGHT, 1080)

        val countdown = CountdownMode.fromValue(intent.getIntExtra(EXTRA_COUNTDOWN, 0))
        if (countdown != CountdownMode.NONE) {
            stateCallback?.onStateChanged(RecordingState.COUNTDOWN)
            startCountdown(countdown.value, resultCode, resultData)
        } else {
            startRecordingInternal(resultCode, resultData)
        }
    }

    private fun startCountdown(seconds: Int, resultCode: Int, resultData: Intent?) {
        serviceScope.launch {
            for (i in seconds downTo 1) {
                stateCallback?.onCountdownTick(i)
                delay(1000L)
            }
            startRecordingInternal(resultCode, resultData)
        }
    }

    private fun startRecordingInternal(resultCode: Int, resultData: Intent?) {
        if (resultCode < 0 || resultData == null) return

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width: Int
        val height: Int
        if (currentResolution == Resolution.CUSTOM) {
            width = customResolutionWidth
            height = customResolutionHeight
        } else {
            width = currentResolution.width
            height = currentResolution.height
        }

        outputFile = generateOutputFile()

        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        try {
            NativeBridge.nativeInit()

            setupMediaMuxer()
            setupMediaCodec(width, height)
            setupVirtualDisplay(width, height, metrics.densityDpi)

            if (currentAudioMode == AudioMode.MIC_ONLY || currentAudioMode == AudioMode.MIXED) {
                setupMicAudioRecord()
            }
            if ((currentAudioMode == AudioMode.SYSTEM_ONLY || currentAudioMode == AudioMode.MIXED)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupSystemAudioCapture()
            }

            setupAudioEncoder()

            startEncodeLoop()

            isRecording = true
            isPaused = false
            recordingStartTime = System.currentTimeMillis()
            totalPausedDuration = 0L

            startDurationTracking()
            stateCallback?.onStateChanged(RecordingState.RECORDING)

            if (watermarkEnabled && watermarkText.isNotEmpty()) {
                val watermarkIntent = Intent(this, com.screenpulse.floatingwindow.FloatingWatermarkService::class.java).apply {
                    action = com.screenpulse.floatingwindow.FloatingWatermarkService.ACTION_SHOW
                    putExtra(com.screenpulse.floatingwindow.FloatingWatermarkService.EXTRA_TEXT, watermarkText)
                }
                startService(watermarkIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            stateCallback?.onError(e.message ?: "Recording failed")
        }
    }

    private fun setupMediaMuxer() {
        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false
    }

    private fun setupMediaCodec(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, resolveBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, currentFrameRate.value)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        mediaCodec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderInputSurface = createInputSurface()
                start()
            }
        } catch (e: Exception) {
            try {
                val fallbackCodecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .firstOrNull {
                        it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)
                    }
                    ?.name
                    ?: throw RuntimeException("No H.264 encoder available")
                MediaCodec.createByCodecName(fallbackCodecName).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoderInputSurface = createInputSurface()
                    start()
                }
            } catch (e2: Exception) {
                throw RuntimeException("Failed to create video encoder", e2)
            }
        }
    }

    private fun resolveBitrate(): Int {
        return if (currentBitrate > 0) {
            currentBitrate
        } else {
            BitrateMode.calculateSmartBitrate(currentResolution, currentFrameRate)
        }
    }

    private fun setupVirtualDisplay(width: Int, height: Int, densityDpi: Int) {
        recordWidth = if (currentRecordMode == RecordMode.CUSTOM_REGION && customWidth > 0) customWidth else width
        recordHeight = if (currentRecordMode == RecordMode.CUSTOM_REGION && customHeight > 0) customHeight else height
        recordDensityDpi = densityDpi

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenPulse",
            recordWidth,
            recordHeight,
            recordDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            null
        )
    }

    private fun recreateVirtualDisplay() {
        if (virtualDisplay != null || encoderInputSurface == null) return
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenPulse",
            recordWidth,
            recordHeight,
            recordDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            null
        )
    }

    private fun setupMicAudioRecord() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        micAudioRecord = AudioRecord.Builder()
            .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .build()

        micAudioRecord?.startRecording()
    }

    private fun setupSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        systemAudioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .build()

        systemAudioRecord?.startRecording()
    }

    private fun setupAudioEncoder() {
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        audioRecordBuffer = ByteArray(4096)
        startAudioEncodeLoop()
    }

    private fun startAudioEncodeLoop() {
        audioEncodeJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            val systemBuffer = ByteArray(4096)
            val mixedBuffer = ByteArray(4096)
            val processedBuffer = ByteArray(4096)

            while (isActive && isRecording) {
                if (isPaused) {
                    delay(10)
                    continue
                }

                when (currentAudioMode) {
                    AudioMode.MIC_ONLY -> {
                        val readSize = micAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSize > 0) {
                            val sampleCount = readSize / 2
                            NativeBridge.nativeApplyNoiseReduction(buffer, processedBuffer, sampleCount)
                            encodeAudioData(processedBuffer, readSize)
                        }
                    }
                    AudioMode.SYSTEM_ONLY -> {
                        val readSize = systemAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSize > 0) {
                            encodeAudioData(buffer, readSize)
                        }
                    }
                    AudioMode.MIXED -> {
                        val micRead = micAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                        val sysRead = systemAudioRecord?.read(systemBuffer, 0, systemBuffer.size) ?: 0
                        if (micRead > 0 && sysRead > 0) {
                            val sampleCount = minOf(micRead, sysRead) / 2
                            NativeBridge.nativeMixAudio(
                                systemBuffer, buffer, mixedBuffer,
                                systemVolume / 100f, micVolume / 100f,
                                sampleCount
                            )
                            NativeBridge.nativeApplyNoiseReduction(mixedBuffer, processedBuffer, sampleCount)
                            encodeAudioData(processedBuffer, sampleCount * 2)
                        }
                    }
                }
                delay(10)
            }
        }
    }

    private fun encodeAudioData(data: ByteArray, size: Int) {
        val codec = audioCodec ?: return

        val inputIndex = codec.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(data, 0, size)
            codec.queueInputBuffer(inputIndex, 0, size, System.nanoTime() / 1000, 0)
        }

        drainAudioEncoder()
    }

    private fun drainAudioEncoder() {
        val codec = audioCodec ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        if (audioTrackIndex == -1) {
                            val format = codec.outputFormat
                            audioTrackIndex = muxer.addTrack(format)
                            if (videoTrackIndex != -1 && !muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                            }
                        }

                        if (muxerStarted && audioTrackIndex != -1) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
        }
    }

    private fun startEncodeLoop() {
        encodeJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isRecording) {
                if (!isPaused) {
                    drainVideoEncoder()
                }
                delay(10)
            }
        }
    }

    private fun drainVideoEncoder() {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        if (videoTrackIndex == -1 && bufferInfo.size > 0) {
                            val format = codec.outputFormat
                            videoTrackIndex = muxer.addTrack(format)
                            tryStartMer(muxer)
                        }

                        if (muxerStarted && videoTrackIndex != -1) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
        }
    }

    private fun tryStartMer(muxer: MediaMuxer) {
        if (!muxerStarted && videoTrackIndex != -1 && audioTrackIndex != -1) {
            muxer.start()
            muxerStarted = true
        }
    }

    private fun handlePause() {
        if (!isRecording || isPaused) return
        isPaused = true
        pausedDuration = System.currentTimeMillis()
        durationJob?.cancel()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { micAudioRecord?.stop() }
        runCatching { systemAudioRecord?.stop() }
        stateCallback?.onStateChanged(RecordingState.PAUSED)
        updateNotification(getString(R.string.paused))
    }

    private fun handleResume() {
        if (!isRecording || !isPaused) return
        isPaused = false
        totalPausedDuration += System.currentTimeMillis() - pausedDuration
        runCatching { micAudioRecord?.startRecording() }
        runCatching { systemAudioRecord?.startRecording() }
        recreateVirtualDisplay()
        startDurationTracking()
        stateCallback?.onStateChanged(RecordingState.RECORDING)
        updateNotification(getString(R.string.recording))
    }

    private fun handleStop() {
        isRecording = false
        isPaused = false
        durationJob?.cancel()
        encodeJob?.cancel()
        audioEncodeJob?.cancel()

        mediaCodec?.signalEndOfInputStream()
        try {
            drainVideoEncoder()
        } catch (_: Exception) {}

        cleanup()

        val savedFile = outputFile
        stateCallback?.onStateChanged(RecordingState.IDLE)
        stateCallback?.onRecordingComplete(savedFile?.absolutePath)

        if (savedFile != null && savedFile.exists()) {
            triggerCompression(savedFile.absolutePath)
        }

        stopService(Intent(this, com.screenpulse.floatingwindow.FloatingAnnotationService::class.java))
        stopService(Intent(this, com.screenpulse.floatingwindow.FloatingPipService::class.java))
        stopService(Intent(this, com.screenpulse.floatingwindow.FloatingWatermarkService::class.java))
        stopService(Intent(this, com.screenpulse.floatingwindow.FloatingWindowService::class.java))

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun triggerCompression(inputPath: String) {
        val compressData = Data.Builder()
            .putString(VideoCompressWorker.KEY_INPUT_PATH, inputPath)
            .putString(VideoCompressWorker.KEY_OUTPUT_PATH, inputPath.replace(".mp4", "_compressed.mp4"))
            .putInt(VideoCompressWorker.KEY_COMPRESSION_MODE, currentCompressionMode.value)
            .build()

        val compressRequest = OneTimeWorkRequestBuilder<VideoCompressWorker>()
            .setInputData(compressData)
            .build()

        WorkManager.getInstance(this).enqueue(compressRequest)
    }

    private fun takeScreenshot() {
        val projection = mediaProjection ?: return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val imageReader = ImageReader.newInstance(
            width, height, ImageFormat.JPEG, 2
        )

        val surface = imageReader.surface

        val screenshotDisplay = projection.createVirtualDisplay(
            "ScreenPulseScreenshot",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    if (croppedBitmap != bitmap) bitmap.recycle()

                    saveScreenshot(croppedBitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            }

            screenshotDisplay.release()
            reader.close()
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ScreenPulse")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Screenshot_$timestamp.png")

        try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            stateCallback?.onScreenshotSaved(file.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            bitmap.recycle()
        }
    }

    private fun cleanup() {
        try {
            encodeJob?.cancel()
            audioEncodeJob?.cancel()
            virtualDisplay?.release()
            virtualDisplay = null
            encoderInputSurface?.release()
            encoderInputSurface = null
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            audioCodec?.stop()
            audioCodec?.release()
            audioCodec = null
            micAudioRecord?.stop()
            micAudioRecord?.release()
            micAudioRecord = null
            systemAudioRecord?.stop()
            systemAudioRecord?.release()
            systemAudioRecord = null
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
            mediaMuxer = null
            muxerStarted = false
            mediaProjection = null

            NativeBridge.nativeRelease()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startDurationTracking() {
        durationJob?.cancel()
        durationJob = serviceScope.launch {
            while (isActive && isRecording && !isPaused) {
                val elapsed = System.currentTimeMillis() - recordingStartTime - totalPausedDuration
                stateCallback?.onDurationUpdate(elapsed)
                delay(100L)
            }
        }
    }

    private fun generateOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenPulse")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "ScreenPulse_$timestamp.mp4")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ScreenPulse recording service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording))
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    interface RecordingStateCallback {
        fun onStateChanged(state: RecordingState)
        fun onDurationUpdate(durationMs: Long)
        fun onCountdownTick(remaining: Int)
        fun onRecordingComplete(filePath: String?)
        fun onError(message: String)
        fun onScreenshotSaved(filePath: String) {}
    }
}
