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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
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
import com.screenpulse.repository.WatermarkType
import com.screenpulse.viewmodel.RecordingState
import com.screenpulse.jni.NativeBridge
import com.screenpulse.shortcut.RecordingStateManager
import com.screenpulse.util.LogManager
import com.screenpulse.util.MediaProjectionHolder
import com.screenpulse.util.RecordingCache
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
        const val ACTION_SCREENSHOT_ONLY = "com.screenpulse.action.SCREENSHOT_ONLY"
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
        const val EXTRA_CUSTOM_SAVE_TREE_URI = "custom_save_tree_uri"
        const val EXTRA_CUSTOM_RESOLUTION_WIDTH = "custom_resolution_width"
        const val EXTRA_CUSTOM_RESOLUTION_HEIGHT = "custom_resolution_height"
        const val EXTRA_CUSTOM_COUNTDOWN_SECONDS = "custom_countdown_seconds"
        const val EXTRA_FLOATING_WINDOW_PERSISTENT = "floating_window_persistent"
        const val EXTRA_CAPTURE_PROTECTED_CONTENT = "capture_protected_content"
        const val CHANNEL_ID = "screen_pulse_recording"
        const val NOTIFICATION_ID = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderInputSurface: Surface? = null
    private var regionCropRenderer: RegionCropRenderer? = null
    private var mediaCodec: MediaCodec? = null
    private var micAudioRecord: AudioRecord? = null
    private var systemAudioRecord: AudioRecord? = null
    private var mediaMuxer: MediaMuxer? = null
    @Volatile private var isRecording = false
    @Volatile private var isPaused = false
    @Volatile private var isStopping = false
    @Volatile private var isStarting = false
    private var recordingStartTime = 0L
    private var pausedDuration = 0L
    private var totalPausedDuration = 0L
    private var outputFile: File? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    @Volatile private var muxerStarted = false
    private var videoBufferInfo = MediaCodec.BufferInfo()
    private var audioBufferInfo = MediaCodec.BufferInfo()

    // Buffer for video/audio frames that arrive before the muxer has started.
    // Without this, frames produced between the first codec output and muxer.start()
    // are silently dropped, resulting in missing initial video content.
    private data class BufferedSample(
        val trackIndex: Int,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )
    private val pendingSamples = mutableListOf<BufferedSample>()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countdownJob: Job? = null
    private var durationJob: Job? = null
    private var encodeJob: Job? = null
    private var audioEncodeJob: Job? = null

    private var audioCodec: MediaCodec? = null
    private var audioRecordBuffer: ByteArray? = null
    private var audioPtsUs = 0L
    @Volatile private var recordingPtsBaseNs = 0L
    private var lastAudioMuxPtsUs = -1L
    private var lastVideoMuxPtsUs = -1L
    private val ptsLock = Any()

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
    private var watermarkType = WatermarkType.TEXT
    private var watermarkImageUri = ""
    private var pipEnabled = false
    private var customResolutionWidth = 1920
    private var customResolutionHeight = 1080
    private var recordWidth = 0
    private var recordHeight = 0
    private var recordDensityDpi = 0
    private var customSaveTreeUri = ""
    private var floatingWindowPersistent = false
    private var captureProtectedContent = false

    private var stateCallback: RecordingStateCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LogManager.log(LogManager.TAG_RECORD, "ScreenRecordService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogManager.log(LogManager.TAG_RECORD, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_SCREENSHOT -> takeScreenshot()
            ACTION_SCREENSHOT_ONLY -> handleScreenshotOnly(intent)
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
        LogManager.log(LogManager.TAG_RECORD, "handleStart resultCode=$resultCode resultData=${resultData != null}")

        if (isRecording || countdownJob?.isActive == true) {
            LogManager.log(LogManager.TAG_RECORD, "handleStart ignored: already recording or counting down")
            return
        }

        // CRITICAL: Call startForeground() immediately on Android 14+.
        // The system kills services that don't call startForeground() within ~5 seconds
        // of onStartCommand(). When a countdown is enabled, startRecordingInternal()
        // (which calls startForeground) won't be called until after the countdown finishes,
        // potentially exceeding the timeout. Calling it here ensures the service stays alive.
        startForeground(NOTIFICATION_ID, createNotification(), recordingForegroundType())

        // Reset stale stopping flag from a previous recording that hasn't fully cleaned up.
        // Without this, startRecordingInternal() would bail out because isStopping == true.
        if (isStopping) {
            LogManager.log(LogManager.TAG_RECORD, "handleStart: resetting stale isStopping flag")
            isStopping = false
        }
        isStarting = true

        // Obtain MediaProjection immediately after consent. Android 14+ invalidates the
        // token if getMediaProjection() is delayed until after countdown, or if a previous
        // projection was already stopped. Keep the live instance in MediaProjectionHolder.
        if (!obtainMediaProjection(resultCode, resultData)) {
            LogManager.log(LogManager.TAG_RECORD, "handleStart FAILED: MediaProjection unavailable")
            isStarting = false
            RecordingCache.clear()
            RecordingStateManager.updateState(RecordingState.IDLE)
            syncFloatingWindow(RecordingState.IDLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
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
        watermarkType = WatermarkType.fromValue(intent.getIntExtra(EXTRA_WATERMARK_TYPE, WatermarkType.TEXT.value))
        watermarkImageUri = intent.getStringExtra(EXTRA_WATERMARK_IMAGE_URI) ?: ""
        pipEnabled = intent.getBooleanExtra(EXTRA_PIP_ENABLED, false)
        customResolutionWidth = intent.getIntExtra(EXTRA_CUSTOM_RESOLUTION_WIDTH, 1920)
        customResolutionHeight = intent.getIntExtra(EXTRA_CUSTOM_RESOLUTION_HEIGHT, 1080)
        customSaveTreeUri = intent.getStringExtra(EXTRA_CUSTOM_SAVE_TREE_URI) ?: ""
        floatingWindowPersistent = intent.getBooleanExtra(EXTRA_FLOATING_WINDOW_PERSISTENT, false)
        captureProtectedContent = intent.getBooleanExtra(EXTRA_CAPTURE_PROTECTED_CONTENT, false)

        val countdown = CountdownMode.fromValue(intent.getIntExtra(EXTRA_COUNTDOWN, 0))
        val countdownSeconds = when (countdown) {
            CountdownMode.CUSTOM -> intent.getIntExtra(EXTRA_CUSTOM_COUNTDOWN_SECONDS, 10).coerceIn(1, 300)
            else -> countdown.value
        }
        if (countdown != CountdownMode.NONE) {
            RecordingStateManager.updateState(RecordingState.COUNTDOWN)
            syncFloatingWindow(RecordingState.COUNTDOWN)
            stateCallback?.onStateChanged(RecordingState.COUNTDOWN)
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val useCustomRegion = currentRecordMode == RecordMode.CUSTOM_REGION && customWidth > 0 && customHeight > 0
            if (useCustomRegion) {
                MediaProjectionHolder.park(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    virtualDisplayFlags()
                )
            } else {
                val (parkW, parkH) = computeFullScreenEncodeSize(metrics.widthPixels, metrics.heightPixels)
                MediaProjectionHolder.park(parkW, parkH, metrics.densityDpi, virtualDisplayFlags())
            }
            startCountdownOverlay()
            startCountdown(countdownSeconds)
        } else {
            startRecordingInternal()
        }
    }

    /**
     * Obtains a live MediaProjection immediately after user consent.
     * Reuses MediaProjectionHolder when already attached so the floating window
     * can start subsequent recordings without opening the app.
     */
    private fun obtainMediaProjection(resultCode: Int, resultData: Intent?): Boolean {
        val existing = MediaProjectionHolder.get()
        if (existing != null) {
            mediaProjection = existing
            bindProjectionStopListener()
            LogManager.log(LogManager.TAG_RECORD, "reusing held MediaProjection")
            return true
        }
        if (resultData == null) {
            LogManager.log(LogManager.TAG_RECORD, "obtainMediaProjection FAILED: resultData is null")
            return false
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "getMediaProjection FAILED", e)
            e.printStackTrace()
            return false
        }
        if (created == null) {
            LogManager.log(LogManager.TAG_RECORD, "getMediaProjection returned null")
            return false
        }
        MediaProjectionHolder.attach(created)
        bindProjectionStopListener()
        mediaProjection = created
        LogManager.log(LogManager.TAG_RECORD, "getMediaProjection OK")
        return true
    }

    private fun bindProjectionStopListener() {
        MediaProjectionHolder.setOnStopped {
            if (isStopping) return@setOnStopped
            if (isStarting || countdownJob?.isActive == true) {
                LogManager.log(LogManager.TAG_RECORD, "MediaProjection onStop ignored during countdown/start")
                return@setOnStopped
            }
            LogManager.log(LogManager.TAG_RECORD, "MediaProjection stopped by system, ending session")
            handleStop()
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            for (i in seconds downTo 1) {
                RecordingStateManager.updateCountdown(i)
                stateCallback?.onCountdownTick(i)
                // Send countdown update to floating window
                sendCountdownToFloatingWindow(i)
                // Send countdown update to fullscreen overlay
                updateCountdownOverlay(i)
                delay(1000L)
            }
            RecordingStateManager.updateCountdown(0)
            sendCountdownToFloatingWindow(0)
            hideCountdownOverlay()
            startRecordingInternal()
            countdownJob = null
        }
    }

    private fun startCountdownOverlay() {
        try {
            val intent = Intent(this, com.screenpulse.floatingwindow.FloatingCountdownService::class.java).apply {
                action = com.screenpulse.floatingwindow.FloatingCountdownService.ACTION_SHOW
            }
            startService(intent)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "startCountdownOverlay failed", e)
        }
    }

    private fun updateCountdownOverlay(remaining: Int) {
        try {
            val intent = Intent(this, com.screenpulse.floatingwindow.FloatingCountdownService::class.java).apply {
                action = com.screenpulse.floatingwindow.FloatingCountdownService.ACTION_UPDATE
                putExtra(com.screenpulse.floatingwindow.FloatingCountdownService.EXTRA_REMAINING, remaining)
            }
            startService(intent)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "updateCountdownOverlay failed", e)
        }
    }

    private fun hideCountdownOverlay() {
        try {
            val intent = Intent(this, com.screenpulse.floatingwindow.FloatingCountdownService::class.java).apply {
                action = com.screenpulse.floatingwindow.FloatingCountdownService.ACTION_HIDE
            }
            startService(intent)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "hideCountdownOverlay failed", e)
        }
    }

    private fun startRecordingInternal() {
        if (mediaProjection == null && !obtainMediaProjection(-1, null)) {
            LogManager.log(LogManager.TAG_RECORD, "startRecordingInternal FAILED: MediaProjection unavailable")
            isStarting = false
            RecordingCache.clear()
            RecordingStateManager.updateState(RecordingState.IDLE)
            syncFloatingWindow(RecordingState.IDLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Android 14 (targetSdk 34) requires the mediaProjection foreground service
        // to be running BEFORE createVirtualDisplay() is called.
        startForeground(NOTIFICATION_ID, createNotification(), recordingForegroundType())
        // Keep the countdown keep-alive VirtualDisplay until the real capture
        // display is created. Releasing it first can stop MediaProjection.

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        prepareCustomRegion(metrics.widthPixels, metrics.heightPixels)

        val (width, height) = computeFullScreenEncodeSize(metrics.widthPixels, metrics.heightPixels)

        val useCustomRegion = currentRecordMode == RecordMode.CUSTOM_REGION && customWidth > 0 && customHeight > 0
        val captureWidth = if (useCustomRegion) metrics.widthPixels else width
        val captureHeight = if (useCustomRegion) metrics.heightPixels else height
        val codecWidth = if (useCustomRegion) customWidth else width
        val codecHeight = if (useCustomRegion) customHeight else height
        recordWidth = codecWidth
        recordHeight = codecHeight

        // Guard against double-start: if already recording or stopping, bail out
        if (isRecording || isStopping) {
            LogManager.log(LogManager.TAG_RECORD, "startRecordingInternal skipped: isRecording=$isRecording isStopping=$isStopping")
            isStarting = false
            RecordingStateManager.updateState(RecordingState.IDLE)
            syncFloatingWindow(RecordingState.IDLE)
            return
        }

        outputFile = createOutputFile()
        LogManager.log(LogManager.TAG_RECORD,
            "start recording: res=${currentResolution.value} capture=${captureWidth}x$captureHeight " +
            "codec=${codecWidth}x$codecHeight fps=${currentFrameRate.value} " +
            "bitrate=${
                if (currentBitrate > 0) currentBitrate else BitrateMode.calculateSmartBitrate(codecWidth, codecHeight, currentFrameRate)
            } audioMode=${currentAudioMode} recordMode=${currentRecordMode} " +
            "region=${customOffsetX},${customOffsetY} ${customWidth}x$customHeight " +
            "enhanced=$captureProtectedContent out=${outputFile?.name} customDir=${customSaveTreeUri.isNotEmpty()}")

        try {
            NativeBridge.nativeInit()
            resetPtsClock()

            setupMediaMuxer()
            setupMediaCodec(codecWidth, codecHeight)
            MediaProjectionHolder.beginDisplayReplacement()
            try {
                setupVirtualDisplay(captureWidth, captureHeight, metrics.densityDpi)
            } finally {
                MediaProjectionHolder.endDisplayReplacement()
            }

            // Set isRecording BEFORE starting encode loops so the while-loop condition passes.
            isRecording = true
            isPaused = false
            isStopping = false
            recordingStartTime = System.currentTimeMillis()
            totalPausedDuration = 0L

            if (currentAudioMode == AudioMode.MIC_ONLY || currentAudioMode == AudioMode.MIXED) {
                setupMicAudioRecord()
            }
            if ((currentAudioMode == AudioMode.SYSTEM_ONLY || currentAudioMode == AudioMode.MIXED)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setupSystemAudioCapture()
            }

            setupAudioEncoder()

            startEncodeLoop()

            startDurationTracking()
            RecordingStateManager.updateDuration(0L)
            RecordingStateManager.updateState(RecordingState.RECORDING)
            syncFloatingWindow(RecordingState.RECORDING)
            stateCallback?.onStateChanged(RecordingState.RECORDING)
            Handler(Looper.getMainLooper()).postDelayed({ isStarting = false }, 2000)
            LogManager.log(LogManager.TAG_RECORD, "RECORDING started")

            if (watermarkEnabled && watermarkText.isNotEmpty()) {
                val watermarkIntent = Intent(this, com.screenpulse.floatingwindow.FloatingWatermarkService::class.java).apply {
                    action = com.screenpulse.floatingwindow.FloatingWatermarkService.ACTION_SHOW
                    putExtra(com.screenpulse.floatingwindow.FloatingWatermarkService.EXTRA_TEXT, watermarkText)
                }
                startService(watermarkIntent)
                LogManager.log(LogManager.TAG_RECORD, "Watermark enabled: $watermarkText")
            }

            if (currentRecordMode == RecordMode.CUSTOM_REGION && customWidth > 0 && customHeight > 0) {
                val regionIntent = Intent(this, com.screenpulse.floatingwindow.FloatingRegionService::class.java).apply {
                    action = com.screenpulse.floatingwindow.FloatingRegionService.ACTION_SHOW
                    putExtra(com.screenpulse.floatingwindow.FloatingRegionService.EXTRA_X, customOffsetX)
                    putExtra(com.screenpulse.floatingwindow.FloatingRegionService.EXTRA_Y, customOffsetY)
                    putExtra(com.screenpulse.floatingwindow.FloatingRegionService.EXTRA_WIDTH, customWidth)
                    putExtra(com.screenpulse.floatingwindow.FloatingRegionService.EXTRA_HEIGHT, customHeight)
                }
                startService(regionIntent)
                LogManager.log(LogManager.TAG_RECORD, "Region overlay started: $customOffsetX,$customOffsetY ${customWidth}x$customHeight")
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "Recording setup FAILED", e)
            e.printStackTrace()
            isRecording = false
            isStarting = false
            hideCountdownOverlay()
            cleanup()
            RecordingStateManager.updateState(RecordingState.IDLE)
            syncFloatingWindow(RecordingState.IDLE)
            stateCallback?.onError(e.message ?: "Recording failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * H.264 encoders require even dimensions; many devices need multiples of 16.
     */
    private fun alignForCodec(value: Int): Int = (value / 16 * 16).coerceAtLeast(16)

    /**
     * Scale the real screen into the selected resolution tier while keeping
     * the device aspect ratio. 1080P means the short side is at most 1080.
     */
    private fun computeFullScreenEncodeSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        if (currentResolution == Resolution.CUSTOM) {
            return alignForCodec(customResolutionWidth) to alignForCodec(customResolutionHeight)
        }
        val safeW = screenWidth.coerceAtLeast(16)
        val safeH = screenHeight.coerceAtLeast(16)
        if (captureProtectedContent) {
            val width = alignForCodec(safeW)
            val height = alignForCodec(safeH)
            LogManager.log(
                LogManager.TAG_RECORD,
                "full-screen size: enhanced capture screen=${safeW}x$safeH encode=${width}x$height"
            )
            return width to height
        }
        val targetShort = minOf(currentResolution.width, currentResolution.height).coerceAtLeast(16)
        val screenShort = minOf(safeW, safeH)
        val scale = minOf(1f, targetShort.toFloat() / screenShort.toFloat())
        val width = alignForCodec((safeW * scale).toInt().coerceAtLeast(16))
        val height = alignForCodec((safeH * scale).toInt().coerceAtLeast(16))
        LogManager.log(
            LogManager.TAG_RECORD,
            "full-screen size: screen=${safeW}x$safeH tier=${currentResolution.value} encode=${width}x$height scale=$scale"
        )
        return width to height
    }

    /**
     * Clamp the saved region to the current screen and align it for MediaCodec.
     * Invalid / empty regions fall back to full-screen capture so overlay start still works.
     */
    private fun prepareCustomRegion(screenWidth: Int, screenHeight: Int) {
        if (currentRecordMode != RecordMode.CUSTOM_REGION) return
        if (customWidth <= 0 || customHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            LogManager.log(LogManager.TAG_RECORD, "custom region invalid (${customWidth}x$customHeight), fallback to full screen")
            currentRecordMode = RecordMode.FULL_SCREEN
            customWidth = 0
            customHeight = 0
            customOffsetX = 0
            customOffsetY = 0
            return
        }
        customOffsetX = customOffsetX.coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
        customOffsetY = customOffsetY.coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
        customWidth = customWidth.coerceAtMost(screenWidth - customOffsetX).coerceAtLeast(1)
        customHeight = customHeight.coerceAtMost(screenHeight - customOffsetY).coerceAtLeast(1)
        customWidth = alignForCodec(customWidth)
        customHeight = alignForCodec(customHeight)
        if (customOffsetX + customWidth > screenWidth) {
            customOffsetX = (screenWidth - customWidth).coerceAtLeast(0)
        }
        if (customOffsetY + customHeight > screenHeight) {
            customOffsetY = (screenHeight - customHeight).coerceAtLeast(0)
        }
        LogManager.log(
            LogManager.TAG_RECORD,
            "custom region prepared: $customOffsetX,$customOffsetY ${customWidth}x$customHeight screen=${screenWidth}x$screenHeight"
        )
    }

    private fun setupMediaMuxer() {
        LogManager.log(LogManager.TAG_RECORD, "setupMediaMuxer: start, path=${outputFile?.absolutePath}")
        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false
        LogManager.log(LogManager.TAG_RECORD, "setupMediaMuxer: complete")
    }

    private fun setupMediaCodec(width: Int, height: Int) {
        LogManager.log(LogManager.TAG_RECORD, "setupMediaCodec: start, ${width}x$height, bitrate=${resolveBitrate()}, fps=${currentFrameRate.value}")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, resolveBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, currentFrameRate.value)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        LogManager.log(LogManager.TAG_RECORD, "setupMediaCodec: format created, bitrate=${format.getInteger(MediaFormat.KEY_BIT_RATE)}")

        mediaCodec = try {
            LogManager.log(LogManager.TAG_RECORD, "setupMediaCodec: trying hardware encoder")
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderInputSurface = createInputSurface()
                start()
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "setupMediaCodec: hardware encoder failed, trying fallback", e)
            try {
                val fallbackCodecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .firstOrNull {
                        it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)
                    }
                    ?.name
                    ?: throw RuntimeException("No H.264 encoder available")
                LogManager.log(LogManager.TAG_RECORD, "setupMediaCodec: using fallback codec: $fallbackCodecName")
                MediaCodec.createByCodecName(fallbackCodecName).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoderInputSurface = createInputSurface()
                    start()
                }
            } catch (e2: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "setupMediaCodec: fallback encoder also failed", e2)
                throw RuntimeException("Failed to create video encoder", e2)
            }
        }
        LogManager.log(LogManager.TAG_RECORD, "setupMediaCodec: complete, encoder=${mediaCodec?.name}")
    }

    private fun resolveBitrate(): Int {
        return if (currentBitrate > 0) {
            currentBitrate
        } else {
            BitrateMode.calculateSmartBitrate(recordWidth, recordHeight, currentFrameRate)
        }
    }

    private fun setupVirtualDisplay(width: Int, height: Int, densityDpi: Int) {
        LogManager.log(LogManager.TAG_RECORD, "setupVirtualDisplay: start, ${width}x$height, density=$densityDpi, mode=${if (currentRecordMode == RecordMode.CUSTOM_REGION) "custom" else "full"} watermark=$watermarkEnabled")

        val useRenderer = (currentRecordMode == RecordMode.CUSTOM_REGION && customWidth > 0 && customHeight > 0) || watermarkEnabled

        if (useRenderer) {
            // Use RegionCropRenderer for either region cropping or watermark overlay (or both)
            val cropX: Int
            val cropY: Int
            val cropWidth: Int
            val cropHeight: Int

            if (currentRecordMode == RecordMode.CUSTOM_REGION && customWidth > 0 && customHeight > 0) {
                // Custom region mode: crop to specified region
                cropX = customOffsetX
                cropY = customOffsetY
                cropWidth = customWidth
                cropHeight = customHeight
                recordWidth = customWidth
                recordHeight = customHeight
            } else {
                // Full screen with watermark: pass-through (no cropping)
                cropX = 0
                cropY = 0
                cropWidth = width
                cropHeight = height
                recordWidth = width
                recordHeight = height
            }
            recordDensityDpi = densityDpi

            val renderer = RegionCropRenderer()
            if (!renderer.init(encoderInputSurface!!, width, height, cropX, cropY, cropWidth, cropHeight)) {
                if (currentRecordMode == RecordMode.CUSTOM_REGION) {
                    throw RuntimeException("RegionCropRenderer init failed for custom region")
                }
                LogManager.log(LogManager.TAG_RECORD, "setupVirtualDisplay: RegionCropRenderer init failed, falling back to direct")
                regionCropRenderer = null
                recordWidth = width
                recordHeight = height
                virtualDisplay = createCaptureDisplay(
                    "ScreenPulse",
                    recordWidth,
                    recordHeight,
                    recordDensityDpi,
                    encoderInputSurface
                )
            } else {
                // Set watermark if enabled
                if (watermarkEnabled) {
                    try {
                        val watermarkBitmap = createWatermarkBitmap()
                        if (watermarkBitmap != null) {
                            renderer.setWatermark(watermarkBitmap, recordWidth, recordHeight)
                            watermarkBitmap.recycle()
                            LogManager.log(LogManager.TAG_RECORD, "setupVirtualDisplay: watermark set on renderer")
                        }
                    } catch (e: Exception) {
                        LogManager.log(LogManager.TAG_RECORD, "setupVirtualDisplay: watermark setup failed", e)
                    }
                }

                regionCropRenderer = renderer
                LogManager.log(LogManager.TAG_RECORD, "setupVirtualDisplay: RegionCropRenderer initialized, creating VirtualDisplay at full screen ${width}x$height")
                virtualDisplay = createCaptureDisplay(
                    "ScreenPulse",
                    width,
                    height,
                    densityDpi,
                    renderer.getInputSurface()
                )
            }
        } else {
            // Full screen mode without watermark: VirtualDisplay writes directly to encoder surface
            recordWidth = width
            recordHeight = height
            recordDensityDpi = densityDpi
            virtualDisplay = createCaptureDisplay(
                "ScreenPulse",
                recordWidth,
                recordHeight,
                recordDensityDpi,
                encoderInputSurface
            )
        }

        LogManager.log(LogManager.TAG_RECORD, "setupVirtualDisplay: complete, display=${virtualDisplay?.display?.displayId}, recordSize=${recordWidth}x${recordHeight}, cropRenderer=${regionCropRenderer != null}")
    }

    private fun createCaptureDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?
    ): VirtualDisplay {
        if (surface == null) {
            throw RuntimeException("createCaptureDisplay FAILED: surface is null")
        }
        val display = try {
            MediaProjectionHolder.adoptOrCreateDisplay(
                name,
                width,
                height,
                densityDpi,
                surface,
                virtualDisplayFlags()
            )
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "setupVirtualDisplay: FAILED", e)
            throw e
        }
        if (display == null) {
            throw RuntimeException("createCaptureDisplay FAILED: MediaProjection unavailable")
        }
        return display
    }

    private fun recreateVirtualDisplay() {
        if (virtualDisplay != null || encoderInputSurface == null) return
        try {
            if (regionCropRenderer != null) {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                virtualDisplay = createVirtualDisplayWithFallback(
                    "ScreenPulse",
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    regionCropRenderer?.getInputSurface()
                )
            } else {
                virtualDisplay = createVirtualDisplayWithFallback(
                    "ScreenPulse",
                    recordWidth,
                    recordHeight,
                    recordDensityDpi,
                    encoderInputSurface
                )
            }
            if (virtualDisplay == null) {
                LogManager.log(LogManager.TAG_RECORD, "recreateVirtualDisplay: createVirtualDisplay returned null (mediaProjection may be invalid)")
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "recreateVirtualDisplay: FAILED", e)
            virtualDisplay = null
        }
    }

    private fun createVirtualDisplayWithFallback(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?
    ): VirtualDisplay? {
        val projection = mediaProjection ?: return null
        if (surface == null) return null
        val flagSets = linkedSetOf(virtualDisplayFlags(), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        for (flags in flagSets) {
            try {
                val display = projection.createVirtualDisplay(
                    name,
                    width,
                    height,
                    densityDpi,
                    flags,
                    surface,
                    null,
                    null
                )
                if (display != null) return display
            } catch (e: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "createVirtualDisplay flags=$flags failed", e)
            }
        }
        return null
    }

    private fun virtualDisplayFlags(): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        if (captureProtectedContent) {
            flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        }
        return flags
    }

    private fun recordingForegroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 34) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun setupMicAudioRecord() {
        LogManager.log(LogManager.TAG_RECORD, "setupMicAudioRecord: start")
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBuffer * 4).coerceAtLeast(4096)
        LogManager.log(LogManager.TAG_RECORD, "setupMicAudioRecord: bufferSize=$bufferSize")

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioFormat)
            .build()
        val sources = intArrayOf(
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            android.media.MediaRecorder.AudioSource.MIC
        )
        var recorder: AudioRecord? = null
        for (source in sources) {
            val candidate = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "setupMicAudioRecord source=$source failed", e)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                recorder = candidate
                LogManager.log(LogManager.TAG_RECORD, "setupMicAudioRecord using source=$source")
                break
            }
            candidate?.release()
        }
        if (recorder == null) {
            LogManager.log(LogManager.TAG_RECORD, "setupMicAudioRecord FAILED: not initialized")
            micAudioRecord = null
            return
        }
        micAudioRecord = recorder
        recorder.startRecording()
        LogManager.log(LogManager.TAG_RECORD, "setupMicAudioRecord: complete, recordingState=${recorder.recordingState}")
    }

    private fun setupSystemAudioCapture() {
        LogManager.log(LogManager.TAG_RECORD, "setupSystemAudioCapture: start, SDK=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            LogManager.log(LogManager.TAG_RECORD, "setupSystemAudioCapture: skipped, SDK < Q")
            return
        }

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBuffer * 4).coerceAtLeast(4096)
        LogManager.log(
            LogManager.TAG_RECORD,
            "setupSystemAudioCapture: bufferSize=$bufferSize enhanced=$captureProtectedContent"
        )

        val configs = buildList {
            if (captureProtectedContent) add(enhancedPlaybackCaptureConfig())
            add(defaultPlaybackCaptureConfig())
        }
        var recorder: AudioRecord? = null
        for ((index, config) in configs.withIndex()) {
            val candidate = try {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "setupSystemAudioCapture config[$index] failed", e)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                recorder = candidate
                LogManager.log(LogManager.TAG_RECORD, "setupSystemAudioCapture using config[$index]")
                break
            }
            candidate?.release()
        }
        if (recorder == null) {
            LogManager.log(LogManager.TAG_RECORD, "setupSystemAudioCapture FAILED: not initialized")
            systemAudioRecord = null
            return
        }
        systemAudioRecord = recorder
        recorder.startRecording()
        LogManager.log(LogManager.TAG_RECORD, "setupSystemAudioCapture: complete, recordingState=${recorder.recordingState}")
    }

    private fun defaultPlaybackCaptureConfig(): AudioPlaybackCaptureConfiguration {
        return AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
    }

    private fun enhancedPlaybackCaptureConfig(): AudioPlaybackCaptureConfiguration {
        return AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .addMatchingUsage(AudioAttributes.USAGE_ALARM)
            .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
            .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            .build()
    }

    private fun setupAudioEncoder() {
        LogManager.log(LogManager.TAG_RECORD, "setupAudioEncoder: start")
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

            try {
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
                                applyPcmVolume(processedBuffer, readSize, micVolume)
                                encodeAudioData(processedBuffer, readSize)
                            }
                        }
                        AudioMode.SYSTEM_ONLY -> {
                            val readSize = systemAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (readSize > 0) {
                                applyPcmVolume(buffer, readSize, systemVolume)
                                encodeAudioData(buffer, readSize)
                            }
                        }
                        AudioMode.MIXED -> {
                            val micRead = micAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                            val sysRead = systemAudioRecord?.read(systemBuffer, 0, systemBuffer.size) ?: 0
                            if (micRead > 0 || sysRead > 0) {
                                if (micRead <= 0) buffer.fill(0)
                                if (sysRead <= 0) systemBuffer.fill(0)
                                val byteCount = maxOf(micRead, sysRead).coerceAtLeast(0)
                                val sampleCount = byteCount / 2
                                if (micRead > 0) {
                                    NativeBridge.nativeApplyNoiseReduction(buffer, processedBuffer, micRead / 2)
                                    System.arraycopy(processedBuffer, 0, buffer, 0, micRead)
                                }
                                NativeBridge.nativeMixAudio(
                                    systemBuffer, buffer, mixedBuffer,
                                    systemVolume / 100f, micVolume / 100f,
                                    sampleCount
                                )
                                encodeAudioData(mixedBuffer, sampleCount * 2)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Coroutine cancelled (stop or destroy) – exit cleanly
            }
        }
    }

    private fun resetPtsClock() {
        recordingPtsBaseNs = System.nanoTime()
        audioPtsUs = 0L
        lastAudioMuxPtsUs = -1L
        lastVideoMuxPtsUs = -1L
    }

    private fun capturePtsUs(): Long {
        val base = recordingPtsBaseNs
        if (base == 0L) return 0L
        return ((System.nanoTime() - base) / 1000L).coerceAtLeast(0L)
    }

    private fun toRelativePts(rawPtsUs: Long): Long {
        if (rawPtsUs <= 0L) return 0L
        val baseUs = recordingPtsBaseNs / 1000L
        val elapsed = capturePtsUs()
        val fromBase = rawPtsUs - baseUs
        val absDelta = kotlin.math.abs(fromBase - elapsed)
        val relDelta = kotlin.math.abs(rawPtsUs - elapsed)
        return if (absDelta <= relDelta) fromBase.coerceAtLeast(0L) else rawPtsUs
    }

    private fun applyMuxPts(info: MediaCodec.BufferInfo, trackIndex: Int) {
        if (info.size <= 0) return
        synchronized(ptsLock) {
            val relative = toRelativePts(info.presentationTimeUs)
            val last = if (trackIndex == audioTrackIndex) lastAudioMuxPtsUs else lastVideoMuxPtsUs
            val pts = if (relative <= last) last + 1L else relative
            info.presentationTimeUs = pts
            if (trackIndex == audioTrackIndex) {
                lastAudioMuxPtsUs = pts
            } else {
                lastVideoMuxPtsUs = pts
            }
        }
    }

    private fun applyPcmVolume(data: ByteArray, size: Int, volume: Int) {
        if (volume == 100 || size < 2) return
        val gain = volume / 100f
        val sampleCount = size / 2
        for (i in 0 until sampleCount) {
            val index = i * 2
            val sample = ((data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)).toShort()
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[index] = (scaled and 0xFF).toByte()
            data[index + 1] = ((scaled shr 8) and 0xFF).toByte()
        }
    }

    private fun encodeAudioData(data: ByteArray, size: Int) {
        val codec = audioCodec ?: return
        val inputIndex = codec.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(data, 0, size)
            val durationUs = (size / 2 * 1_000_000L / 44100L).coerceAtLeast(1L)
            val wallPts = capturePtsUs()
            val pts = if (wallPts > audioPtsUs) wallPts else audioPtsUs + durationUs
            audioPtsUs = pts
            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
        }

        drainAudioEncoder()
    }

    private fun drainAudioEncoder() {
        val codec = audioCodec ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(audioBufferInfo, 10000)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue

                    if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        audioBufferInfo.size = 0
                    }

                    if (audioBufferInfo.size > 0) {
                        if (audioTrackIndex == -1 && !muxerStarted) {
                            val format = codec.outputFormat
                            audioTrackIndex = try {
                                muxer.addTrack(format)
                            } catch (e: Exception) {
                                LogManager.log(LogManager.TAG_RECORD, "addTrack failed, using video-only", e)
                                -2
                            }
                            if (videoTrackIndex != -1 && !muxerStarted && audioTrackIndex != -2) {
                                tryStartMuxer(muxer)
                            }
                        }

                        if (muxerStarted && audioTrackIndex != -1 && audioTrackIndex != -2) {
                            outputBuffer.position(audioBufferInfo.offset)
                            outputBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            applyMuxPts(audioBufferInfo, audioTrackIndex)
                            muxer.writeSampleData(audioTrackIndex, outputBuffer, audioBufferInfo)
                        } else if (audioTrackIndex != -1 && audioTrackIndex != -2) {
                            val copy = ByteBuffer.allocateDirect(audioBufferInfo.size)
                            outputBuffer.position(audioBufferInfo.offset)
                            outputBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                            copy.put(outputBuffer)
                            copy.flip()
                            val info = MediaCodec.BufferInfo()
                            info.set(audioBufferInfo.offset, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                            synchronized(pendingSamples) {
                                pendingSamples.add(BufferedSample(audioTrackIndex, copy, info))
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> { }
            }
        }
    }

    private fun startEncodeLoop() {
        encodeJob = serviceScope.launch(Dispatchers.IO) {
            try {
                while (isActive && isRecording) {
                    if (!isPaused) {
                        // For CUSTOM_REGION mode, render cropped frames to encoder surface
                        regionCropRenderer?.drawFrame()
                        drainVideoEncoder()
                    }
                    delay(10)
                }
            } catch (_: CancellationException) {
                // Coroutine cancelled (stop or destroy) – exit cleanly
            }
        }
    }

    private fun drainVideoEncoder() {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(videoBufferInfo, 10000)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue

                    if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        videoBufferInfo.size = 0
                    }

                    if (videoBufferInfo.size > 0) {
                        if (videoTrackIndex == -1) {
                            val format = codec.outputFormat
                            videoTrackIndex = muxer.addTrack(format)
                            tryStartMuxer(muxer)
                        }

                        if (muxerStarted && videoTrackIndex != -1) {
                            outputBuffer.position(videoBufferInfo.offset)
                            outputBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size)
                            applyMuxPts(videoBufferInfo, videoTrackIndex)
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, videoBufferInfo)
                        } else if (videoTrackIndex != -1) {
                            val copy = ByteBuffer.allocateDirect(videoBufferInfo.size)
                            outputBuffer.position(videoBufferInfo.offset)
                            outputBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size)
                            copy.put(outputBuffer)
                            copy.flip()
                            val info = MediaCodec.BufferInfo()
                            info.set(videoBufferInfo.offset, videoBufferInfo.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags)
                            synchronized(pendingSamples) {
                                pendingSamples.add(BufferedSample(videoTrackIndex, copy, info))
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> { }
            }
        }
    }

    private fun drainVideoEncoderOnce(): Boolean {
        val codec = mediaCodec ?: return true
        val muxer = mediaMuxer ?: return true
        val outputIndex = codec.dequeueOutputBuffer(videoBufferInfo, 5000)
        if (outputIndex >= 0) {
            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return false
            if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                videoBufferInfo.size = 0
            }
            if (videoBufferInfo.size > 0) {
                if (videoTrackIndex == -1) {
                    val format = codec.outputFormat
                    videoTrackIndex = muxer.addTrack(format)
                    tryStartMuxer(muxer)
                }
                if (muxerStarted && videoTrackIndex != -1) {
                    outputBuffer.position(videoBufferInfo.offset)
                    outputBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size)
                    applyMuxPts(videoBufferInfo, videoTrackIndex)
                    muxer.writeSampleData(videoTrackIndex, outputBuffer, videoBufferInfo)
                } else if (videoTrackIndex != -1) {
                    val copy = ByteBuffer.allocateDirect(videoBufferInfo.size)
                    outputBuffer.position(videoBufferInfo.offset)
                    outputBuffer.limit(videoBufferInfo.offset + videoBufferInfo.size)
                    copy.put(outputBuffer)
                    copy.flip()
                    val info = MediaCodec.BufferInfo()
                    info.set(videoBufferInfo.offset, videoBufferInfo.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags)
                    synchronized(pendingSamples) {
                        pendingSamples.add(BufferedSample(videoTrackIndex, copy, info))
                    }
                }
            }
            codec.releaseOutputBuffer(outputIndex, false)
            return videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        }
        return false
    }

    private fun drainAudioEncoderOnce(): Boolean {
        val codec = audioCodec ?: return true
        val muxer = mediaMuxer ?: return true
        val outputIndex = codec.dequeueOutputBuffer(audioBufferInfo, 5000)
        if (outputIndex >= 0) {
            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return false
            if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                audioBufferInfo.size = 0
            }
            if (audioBufferInfo.size > 0) {
                if (audioTrackIndex == -1 && !muxerStarted) {
                    audioTrackIndex = try { muxer.addTrack(codec.outputFormat) } catch (e: Exception) { -2 }
                    if (videoTrackIndex != -1 && !muxerStarted && audioTrackIndex != -2) {
                        tryStartMuxer(muxer)
                    }
                }
                if (muxerStarted && audioTrackIndex != -1 && audioTrackIndex != -2) {
                    outputBuffer.position(audioBufferInfo.offset)
                    outputBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                    applyMuxPts(audioBufferInfo, audioTrackIndex)
                    muxer.writeSampleData(audioTrackIndex, outputBuffer, audioBufferInfo)
                } else if (audioTrackIndex != -1 && audioTrackIndex != -2) {
                    val copy = ByteBuffer.allocateDirect(audioBufferInfo.size)
                    outputBuffer.position(audioBufferInfo.offset)
                    outputBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                    copy.put(outputBuffer)
                    copy.flip()
                    val info = MediaCodec.BufferInfo()
                    info.set(audioBufferInfo.offset, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                    synchronized(pendingSamples) {
                        pendingSamples.add(BufferedSample(audioTrackIndex, copy, info))
                    }
                }
            }
            codec.releaseOutputBuffer(outputIndex, false)
            return audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        }
        return false
    }

    private fun tryStartMuxer(muxer: MediaMuxer) {
        if (muxerStarted || videoTrackIndex == -1) return
        val audioReady = audioTrackIndex != -1 && audioTrackIndex != -2
        val audioUnavailable = audioCodec == null || audioTrackIndex == -2
        val waitedLongEnough = recordingStartTime > 0 &&
            System.currentTimeMillis() - recordingStartTime > 1000
        if (audioReady || audioUnavailable || waitedLongEnough) {
            muxer.start()
            muxerStarted = true
            writePendingSamples(muxer)
            LogManager.log(
                LogManager.TAG_RECORD,
                "muxer started audioTrack=$audioTrackIndex audioReady=$audioReady"
            )
        }
    }

    /**
     * Write all buffered samples that were collected before the muxer started.
     * Must be called immediately after muxer.start().
     */
    private fun writePendingSamples(muxer: MediaMuxer) {
        synchronized(pendingSamples) {
            if (pendingSamples.isEmpty()) return
            LogManager.log(LogManager.TAG_RECORD, "Writing ${pendingSamples.size} buffered samples to muxer")
            for (sample in pendingSamples) {
                try {
                    applyMuxPts(sample.info, sample.trackIndex)
                    muxer.writeSampleData(sample.trackIndex, sample.data, sample.info)
                } catch (e: Exception) {
                    LogManager.log(LogManager.TAG_RECORD, "Failed to write buffered sample", e)
                }
            }
            pendingSamples.clear()
        }
    }

    private fun handlePause() {
        if (!isRecording || isPaused || isStopping) return
        LogManager.log(LogManager.TAG_RECORD, "PAUSE requested")
        isPaused = true
        pausedDuration = System.currentTimeMillis()
        durationJob?.cancel()
        // Note: Do NOT release VirtualDisplay during pause.
        // Releasing it causes crashes on resume because:
        // 1. mediaProjection may become invalid, making recreateVirtualDisplay() fail
        // 2. RegionCropRenderer's SurfaceTexture may be in an inconsistent state
        // 3. systemAudioRecord (MediaProjection-based) cannot restart after stop()
        // Instead, the encode loop already skips frames when isPaused=true.
        runCatching { micAudioRecord?.stop() }
        runCatching { systemAudioRecord?.stop() }
        RecordingStateManager.updateState(RecordingState.PAUSED)
        syncFloatingWindow(RecordingState.PAUSED)
        stateCallback?.onStateChanged(RecordingState.PAUSED)
        updateNotification(getString(R.string.paused))
    }

    private fun handleResume() {
        if (!isRecording || !isPaused || isStopping) return
        LogManager.log(LogManager.TAG_RECORD, "RESUME requested")
        isPaused = false
        totalPausedDuration += System.currentTimeMillis() - pausedDuration
        // Restart audio recording — mic and system audio
        // Note: AudioRecord.startRecording() should work after stop() for mic,
        // but systemAudioRecord (MediaProjection-based) may fail.
        // Use runCatching to handle gracefully.
        runCatching { micAudioRecord?.startRecording() }
        runCatching { systemAudioRecord?.startRecording() }
        // VirtualDisplay was NOT released during pause, so no need to recreate it.
        // The encode loop will resume processing frames automatically when isPaused=false.
        startDurationTracking()
        RecordingStateManager.updateState(RecordingState.RECORDING)
        syncFloatingWindow(RecordingState.RECORDING)
        stateCallback?.onStateChanged(RecordingState.RECORDING)
        updateNotification(getString(R.string.recording))
    }

    private fun handleStop() {
        if (isStopping) return
        isStopping = true
        isStarting = false
        LogManager.log(LogManager.TAG_RECORD, "STOP requested")
        isRecording = false
        isPaused = false
        countdownJob?.cancel()
        countdownJob = null
        hideCountdownOverlay()
        encodeJob?.cancel()
        audioEncodeJob?.cancel()

        // Move drain + cleanup + finalize off the main thread to avoid blocking it
        // for up to 2 seconds and to prevent race conditions with encode-loop coroutines
        // (both sharing videoBufferInfo / audioBufferInfo on different threads).
        serviceScope.launch(Dispatchers.IO) {
            // Wait for coroutine encode loops to exit – join() suspends until each
            // job actually finishes, which is far more reliable than a fixed delay.
            try { encodeJob?.join() } catch (_: CancellationException) {}
            try { audioEncodeJob?.join() } catch (_: CancellationException) {}

            try {
                mediaCodec?.signalEndOfInputStream()
            } catch (e: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "signalEndOfInputStream failed", e)
            }

            // Signal EOS to audio codec so it flushes remaining data
            try {
                val aCodec = audioCodec
                if (aCodec != null) {
                    val inputIndex = aCodec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        aCodec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
            } catch (e: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "audio EOS signal failed", e)
            }

            // Thorough drain: keep draining until EOS or timeout (up to 2 seconds)
            val drainDeadline = System.currentTimeMillis() + 2000
            var videoEosReceived = false
            var audioEosReceived = false
            try {
                while (System.currentTimeMillis() < drainDeadline && (!videoEosReceived || !audioEosReceived)) {
                    if (!videoEosReceived) {
                        videoEosReceived = drainVideoEncoderOnce()
                    }
                    if (!audioEosReceived) {
                        audioEosReceived = drainAudioEncoderOnce()
                    }
                    if (!videoEosReceived || !audioEosReceived) {
                        delay(10)
                    }
                }
            } catch (_: Exception) {}

            LogManager.log(LogManager.TAG_RECORD, "Final drain done. videoEos=$videoEosReceived audioEos=$audioEosReceived videoTrack=$videoTrackIndex audioTrack=$audioTrackIndex muxerStarted=$muxerStarted")

            try {
                if (!muxerStarted && videoTrackIndex != -1) {
                    mediaMuxer?.start()
                    muxerStarted = true
                    mediaMuxer?.let { writePendingSamples(it) }
                    LogManager.log(LogManager.TAG_RECORD, "handleStop: force start muxer (video-only)")
                }
            } catch (e: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "force start muxer failed", e)
            }

            cleanup()

            // Capture file path before finalizeOutput may null-out outputFile
            val savedFile = outputFile

            // Delete empty/invalid output files — they are useless and confuse users
            if (savedFile != null && savedFile.exists() && savedFile.length() == 0L) {
                LogManager.log(LogManager.TAG_RECORD, "Deleting empty output file: ${savedFile.absolutePath}")
                savedFile.delete()
            }

            finalizeOutput()

            // Post UI/state updates back to main thread
            withContext(Dispatchers.Main) {
                RecordingStateManager.updateDuration(0L)
                RecordingStateManager.updateState(RecordingState.IDLE)
                syncFloatingWindow(RecordingState.IDLE)
                stateCallback?.onStateChanged(RecordingState.IDLE)
                stateCallback?.onRecordingComplete(savedFile?.absolutePath)
                LogManager.log(LogManager.TAG_RECORD, "Recording stopped. saved=${savedFile != null && savedFile.exists()} size=${savedFile?.length()?.let { it / 1024 } ?: 0}KB path=${savedFile?.path}")

                if (customSaveTreeUri.isEmpty() && savedFile != null && savedFile.exists()) {
                    triggerCompression(savedFile.absolutePath)
                }

                stopService(Intent(this@ScreenRecordService, com.screenpulse.floatingwindow.FloatingAnnotationService::class.java))
                stopService(Intent(this@ScreenRecordService, com.screenpulse.floatingwindow.FloatingPipService::class.java))
                stopService(Intent(this@ScreenRecordService, com.screenpulse.floatingwindow.FloatingWatermarkService::class.java))
                stopService(Intent(this@ScreenRecordService, com.screenpulse.floatingwindow.FloatingRegionService::class.java))
                stopService(Intent(this@ScreenRecordService, com.screenpulse.floatingwindow.FloatingCountdownService::class.java))

                // Check if floating window persistent mode is enabled
                if (floatingWindowPersistent) {
                    LogManager.log(LogManager.TAG_RECORD, "Floating window persistent mode: keeping floating window alive")
                    // Update floating window state to IDLE instead of stopping it
                    syncFloatingWindow(RecordingState.IDLE)
                } else {
                    stopService(Intent(this@ScreenRecordService, com.screenpulse.floatingwindow.FloatingWindowService::class.java))
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun triggerCompression(inputPath: String) {
        LogManager.log(LogManager.TAG_RECORD, "Enqueue compression: $inputPath mode=${currentCompressionMode}")
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
        LogManager.log(LogManager.TAG_RECORD, "Screenshot requested")
        val projection = mediaProjection ?: run {
            LogManager.log(LogManager.TAG_RECORD, "Screenshot skipped: mediaProjection is null")
            return
        }
        captureAndSaveScreenshot(projection)
    }

    private fun handleScreenshotOnly(intent: Intent) {
        LogManager.log(LogManager.TAG_RECORD, "Screenshot-only requested")
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            LogManager.log(LogManager.TAG_RECORD, "Screenshot-only skipped: resultData is null")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "Screenshot-only: getMediaProjection FAILED", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        captureAndSaveScreenshot(projection)

        // Clean up after screenshot is taken
        serviceScope.launch(Dispatchers.Main) {
            delay(1000L) // Wait for screenshot capture to complete
            projection.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun captureAndSaveScreenshot(projection: MediaProjection) {
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
                    LogManager.log(LogManager.TAG_RECORD, "Screenshot capture failed", e)
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            } else {
                LogManager.log(LogManager.TAG_RECORD, "Screenshot image is null")
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
            LogManager.log(LogManager.TAG_RECORD, "Screenshot saved: ${file.absolutePath}")
            stateCallback?.onScreenshotSaved(file.absolutePath)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "Screenshot save failed", e)
            e.printStackTrace()
        } finally {
            bitmap.recycle()
        }
    }

    private fun createWatermarkBitmap(): Bitmap? {
        return try {
            when (watermarkType) {
                WatermarkType.IMAGE -> {
                    if (watermarkImageUri.isEmpty()) return null
                    val uri = Uri.parse(watermarkImageUri)
                    val inputStream = contentResolver.openInputStream(uri) ?: return null
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream.close()

                    // Scale down to max 256px width
                    val maxWidth = 256
                    val sampleSize = maxOf(1, options.outWidth / maxWidth)
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    val inputStream2 = contentResolver.openInputStream(uri) ?: return null
                    val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
                    inputStream2.close()
                    LogManager.log(LogManager.TAG_RECORD, "createWatermarkBitmap: image ${bitmap?.width}x${bitmap?.height}")
                    bitmap
                }
                WatermarkType.TEXT -> {
                    if (watermarkText.isEmpty()) return null
                    val textSize = 48f
                    val padding = 24f
                    val paint = Paint().apply {
                        color = Color.WHITE
                        alpha = 128
                        this.textSize = textSize
                        typeface = Typeface.DEFAULT_BOLD
                        isAntiAlias = true
                    }
                    val textBounds = Rect()
                    paint.getTextBounds(watermarkText, 0, watermarkText.length, textBounds)
                    val width = (textBounds.width() + padding * 2).toInt()
                    val height = (textBounds.height() + padding * 2).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawText(watermarkText, padding, height - padding, paint)
                    LogManager.log(LogManager.TAG_RECORD, "createWatermarkBitmap: text '${watermarkText}' ${bitmap.width}x${bitmap.height}")
                    bitmap
                }
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "createWatermarkBitmap: failed", e)
            null
        }
    }

    private fun cleanup() {
        LogManager.log(LogManager.TAG_RECORD, "cleanup: releasing resources")
        encodeJob?.cancel()
        audioEncodeJob?.cancel()

        // Release each resource independently so one failure doesn't skip the rest.
        runCatching { virtualDisplay?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: virtualDisplay release failed", it) }
        virtualDisplay = null

        runCatching { regionCropRenderer?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: regionCropRenderer release failed", it) }
        regionCropRenderer = null

        runCatching { encoderInputSurface?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: encoderInputSurface release failed", it) }
        encoderInputSurface = null

        runCatching { mediaCodec?.stop() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: mediaCodec stop failed", it) }
        runCatching { mediaCodec?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: mediaCodec release failed", it) }
        mediaCodec = null

        runCatching { audioCodec?.stop() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: audioCodec stop failed", it) }
        runCatching { audioCodec?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: audioCodec release failed", it) }
        audioCodec = null

        runCatching { micAudioRecord?.stop() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: micAudioRecord stop failed", it) }
        runCatching { micAudioRecord?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: micAudioRecord release failed", it) }
        micAudioRecord = null

        runCatching { systemAudioRecord?.stop() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: systemAudioRecord stop failed", it) }
        runCatching { systemAudioRecord?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: systemAudioRecord release failed", it) }
        systemAudioRecord = null

        // Stop the muxer to write the moov atom, then force-sync the file to disk.
        // If the muxer was never started (no frames produced), stop() will throw –
        // that's expected and handled by the runCatching block.
        runCatching {
            if (muxerStarted) {
                mediaMuxer?.stop()
                LogManager.log(LogManager.TAG_RECORD, "cleanup: mediaMuxer stopped OK")
            } else {
                LogManager.log(LogManager.TAG_RECORD, "cleanup: muxer was never started, skipping stop()")
            }
        }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: mediaMuxer stop failed", it) }
        runCatching { mediaMuxer?.release() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: mediaMuxer release failed", it) }
        mediaMuxer = null
        muxerStarted = false
        resetPtsClock()

        // Clear pending samples
        synchronized(pendingSamples) {
            pendingSamples.clear()
        }

        // Force the output file to disk so the moov atom is guaranteed to be persisted.
        runCatching {
            outputFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    java.io.RandomAccessFile(file, "rw").channel.use { it.force(true) }
                    LogManager.log(LogManager.TAG_RECORD, "cleanup: file synced to disk, size=${file.length()}")
                }
            }
        }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: file sync failed", it) }

        mediaProjection = null
        MediaProjectionHolder.clear()

        runCatching { NativeBridge.nativeRelease() }.onFailure { LogManager.log(LogManager.TAG_RECORD, "cleanup: nativeRelease failed", it) }

        LogManager.log(LogManager.TAG_RECORD, "cleanup done")
    }

    private fun startDurationTracking() {
        durationJob?.cancel()
        durationJob = serviceScope.launch {
            while (isActive && isRecording && !isPaused) {
                val elapsed = System.currentTimeMillis() - recordingStartTime - totalPausedDuration
                RecordingStateManager.updateDuration(elapsed)
                stateCallback?.onDurationUpdate(elapsed)
                // Send duration to floating window
                try {
                    val durationIntent = Intent(this@ScreenRecordService, com.screenpulse.floatingwindow.FloatingWindowService::class.java).apply {
                        action = com.screenpulse.floatingwindow.FloatingWindowService.ACTION_UPDATE_DURATION
                        putExtra(com.screenpulse.floatingwindow.FloatingWindowService.EXTRA_DURATION_MS, elapsed)
                    }
                    startService(durationIntent)
                } catch (_: Exception) {}
                delay(100L)
            }
        }
    }

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return if (customSaveTreeUri.isNotEmpty()) {
            File(cacheDir, "ScreenPulse_$timestamp.mp4")
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenPulse")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "ScreenPulse_$timestamp.mp4")
        }
    }

    private fun finalizeOutput() {
        val file = outputFile ?: return
        if (customSaveTreeUri.isEmpty()) return

        try {
            val treeUri = android.net.Uri.parse(customSaveTreeUri)
            val parent = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
            if (parent == null || !file.exists() || file.length() == 0L) {
                LogManager.log(LogManager.TAG_RECORD, "finalizeOutput skipped: tree null or file empty")
                file.delete()
                outputFile = null
                return
            }

            val baseName = file.nameWithoutExtension
            val target = parent.createFile("video/mp4", baseName)
            if (target == null) {
                LogManager.log(LogManager.TAG_RECORD, "finalizeOutput: createFile failed")
                file.delete()
                outputFile = null
                return
            }

            contentResolver.openOutputStream(target.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            LogManager.log(LogManager.TAG_RECORD, "Saved to custom dir: ${target.uri}")
            file.delete()
            outputFile = null
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "finalizeOutput error", e)
        }
    }

    private fun syncFloatingWindow(state: RecordingState) {
        try {
            val intent = Intent(this, com.screenpulse.floatingwindow.FloatingWindowService::class.java).apply {
                action = com.screenpulse.floatingwindow.FloatingWindowService.ACTION_UPDATE_STATE
                putExtra(com.screenpulse.floatingwindow.FloatingWindowService.EXTRA_STATE, state.ordinal)
            }
            startService(intent)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "syncFloatingWindow failed", e)
            e.printStackTrace()
        }
    }

    private fun sendCountdownToFloatingWindow(remaining: Int) {
        try {
            val intent = Intent(this, com.screenpulse.floatingwindow.FloatingWindowService::class.java).apply {
                action = com.screenpulse.floatingwindow.FloatingWindowService.ACTION_UPDATE_COUNTDOWN
                putExtra(com.screenpulse.floatingwindow.FloatingWindowService.EXTRA_COUNTDOWN_REMAINING, remaining)
            }
            startService(intent)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "sendCountdownToFloatingWindow failed", e)
        }
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
        countdownJob?.cancel()
        isStopping = true
        isRecording = false
        isPaused = false
        // Always clean up regardless of isStopping – the IO coroutine in handleStop
        // may have been cancelled by serviceScope.cancel() below before it finished.
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
