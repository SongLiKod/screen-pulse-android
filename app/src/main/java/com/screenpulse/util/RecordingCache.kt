package com.screenpulse.util

import android.content.Context
import android.content.Intent
import com.screenpulse.ScreenPulseApp
import com.screenpulse.repository.BitrateMode
import com.screenpulse.repository.CustomRegion
import com.screenpulse.repository.RecordMode
import com.screenpulse.repository.SettingsRepository
import com.screenpulse.service.ScreenRecordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Caches the MediaProjection authorization result and recording configuration
 * so that recording can be started directly from the floating window
 * without opening the app UI.
 *
 * The cache is populated when the user starts a recording from the app.
 * It is cleared when the MediaProjection token becomes invalid.
 */
object RecordingCache {

    data class Config(
        val audioMode: Int = 1,
        val recordMode: Int = 0,
        val countdownMode: Int = 0,
        val customCountdownSeconds: Int = 10,
        val resolution: String = "1080P",
        val frameRate: Int = 30,
        val bitrate: Int = 8000000,
        val bitrateMode: Int = BitrateMode.SMART.value,
        val compressionMode: Int = 1,
        val systemVolume: Int = 100,
        val micVolume: Int = 100,
        val watermarkEnabled: Boolean = false,
        val watermarkText: String = "",
        val watermarkType: Int = 0,
        val watermarkImageUri: String = "",
        val pipEnabled: Boolean = false,
        val pipSize: Int = 150,
        val customResolutionWidth: Int = 1920,
        val customResolutionHeight: Int = 1080,
        val regionWidth: Int = 0,
        val regionHeight: Int = 0,
        val regionOffsetX: Int = 0,
        val regionOffsetY: Int = 0,
        val customSaveTreeUri: String = "",
        val floatingWindowPersistent: Boolean = false
    )

    private var _resultCode: Int = 0
    private var _resultData: Intent? = null
    private var _config: Config = Config()
    @Volatile
    private var pendingRegion: CustomRegion? = null

    /** Whether the cache contains a valid MediaProjection authorization. */
    val isValid: Boolean
        get() = _resultData != null

    val config: Config
        get() = _config

    /**
     * Saves the MediaProjection authorization result and recording configuration.
     */
    fun save(code: Int, data: Intent, config: Config) {
        _resultCode = code
        _resultData = data
        _config = config
        pendingRegion?.let { applyPendingRegionLocked(it) }
        LogManager.log(LogManager.TAG_FLOAT, "RecordingCache saved: resultCode=$code")
    }

    /**
     * Clears the cache. Called when the MediaProjection token becomes invalid.
     */
    fun clear() {
        _resultCode = 0
        _resultData = null
        LogManager.log(LogManager.TAG_FLOAT, "RecordingCache cleared")
    }

    /**
     * Reloads recording parameters from SettingsRepository so the floating window
     * always starts with the latest user configuration.
     */
    fun refreshConfigFromSettings(context: Context) {
        val appContext = context.applicationContext
        val repo = (appContext as? ScreenPulseApp)?.settingsRepository
            ?: SettingsRepository(appContext)
        val latest = runBlocking {
            val audioMode = repo.audioMode.first()
            val recordMode = repo.recordMode.first()
            val countdownMode = repo.countdownMode.first()
            val customCountdownSeconds = repo.customCountdownSeconds.first()
            val resolution = repo.resolution.first()
            val frameRate = repo.frameRate.first()
            val bitrate = repo.bitrate.first()
            val bitrateMode = repo.bitrateMode.first()
            val compressionMode = repo.compressionMode.first()
            val systemVolume = repo.systemVolume.first()
            val micVolume = repo.micVolume.first()
            val watermarkEnabled = repo.watermarkEnabled.first()
            val watermarkText = repo.watermarkText.first()
            val watermarkType = repo.watermarkType.first()
            val watermarkImageUri = repo.watermarkImageUri.first()
            val pipEnabled = repo.pipEnabled.first()
            val pipSize = repo.pipSize.first()
            val customResolutionWidth = repo.customResolutionWidth.first()
            val customResolutionHeight = repo.customResolutionHeight.first()
            val customRegion = repo.customRegion.first()
            val customSaveTreeUri = repo.customSaveTreeUri.first()
            val floatingWindowPersistent = repo.floatingWindowPersistent.first()
            Config(
                audioMode = audioMode.value,
                recordMode = recordMode.value,
                countdownMode = countdownMode.value,
                customCountdownSeconds = customCountdownSeconds,
                resolution = resolution.value,
                frameRate = frameRate.value,
                bitrate = bitrate,
                bitrateMode = bitrateMode.value,
                compressionMode = compressionMode.value,
                systemVolume = systemVolume,
                micVolume = micVolume,
                watermarkEnabled = watermarkEnabled,
                watermarkText = watermarkText,
                watermarkType = watermarkType.value,
                watermarkImageUri = watermarkImageUri,
                pipEnabled = pipEnabled,
                pipSize = pipSize,
                customResolutionWidth = customResolutionWidth,
                customResolutionHeight = customResolutionHeight,
                regionWidth = if (recordMode == RecordMode.CUSTOM_REGION) customRegion.width else 0,
                regionHeight = if (recordMode == RecordMode.CUSTOM_REGION) customRegion.height else 0,
                regionOffsetX = if (recordMode == RecordMode.CUSTOM_REGION) customRegion.offsetX else 0,
                regionOffsetY = if (recordMode == RecordMode.CUSTOM_REGION) customRegion.offsetY else 0,
                customSaveTreeUri = customSaveTreeUri,
                floatingWindowPersistent = floatingWindowPersistent
            )
        }
        _config = latest
        pendingRegion?.let { applyPendingRegionLocked(it) }
        LogManager.log(LogManager.TAG_FLOAT, "RecordingCache config refreshed from settings")
    }

    fun applyRegion(region: CustomRegion) {
        pendingRegion = region
        applyPendingRegionLocked(region)
        LogManager.log(
            LogManager.TAG_FLOAT,
            "RecordingCache region applied: ${region.offsetX},${region.offsetY} ${region.width}x${region.height}"
        )
    }

    private fun applyPendingRegionLocked(region: CustomRegion) {
        _config = _config.copy(
            regionWidth = region.width,
            regionHeight = region.height,
            regionOffsetX = region.offsetX,
            regionOffsetY = region.offsetY
        )
    }

    /**
     * Creates a ScreenRecordService ACTION_START intent from the current config.
     * resultCode/resultData are optional when a live MediaProjection is already held.
     */
    fun createStartIntent(
        context: Context,
        resultCode: Int? = null,
        resultData: Intent? = null
    ): Intent {
        refreshConfigFromSettings(context)
        pendingRegion?.let {
            applyPendingRegionLocked(it)
            pendingRegion = null
        }
        val c = _config
        val effectiveBitrate = if (c.bitrateMode == BitrateMode.SMART.value) 0 else c.bitrate
        val code = resultCode ?: _resultCode
        val data = resultData ?: _resultData

        return Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, code)
            if (data != null) {
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            }
            putExtra(ScreenRecordService.EXTRA_AUDIO_MODE, c.audioMode)
            putExtra(ScreenRecordService.EXTRA_RECORD_MODE, c.recordMode)
            putExtra(ScreenRecordService.EXTRA_COUNTDOWN, c.countdownMode)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_COUNTDOWN_SECONDS, c.customCountdownSeconds)
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, c.resolution)
            putExtra(ScreenRecordService.EXTRA_FRAME_RATE, c.frameRate)
            putExtra(ScreenRecordService.EXTRA_BITRATE, effectiveBitrate)
            putExtra(ScreenRecordService.EXTRA_COMPRESSION_MODE, c.compressionMode)
            putExtra(ScreenRecordService.EXTRA_SYSTEM_VOLUME, c.systemVolume)
            putExtra(ScreenRecordService.EXTRA_MIC_VOLUME, c.micVolume)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_ENABLED, c.watermarkEnabled)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_TEXT, c.watermarkText)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_TYPE, c.watermarkType)
            putExtra(ScreenRecordService.EXTRA_WATERMARK_IMAGE_URI, c.watermarkImageUri)
            putExtra(ScreenRecordService.EXTRA_PIP_ENABLED, c.pipEnabled)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_RESOLUTION_WIDTH, c.customResolutionWidth)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_RESOLUTION_HEIGHT, c.customResolutionHeight)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_WIDTH, c.regionWidth)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_HEIGHT, c.regionHeight)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_OFFSET_X, c.regionOffsetX)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_OFFSET_Y, c.regionOffsetY)
            putExtra(ScreenRecordService.EXTRA_CUSTOM_SAVE_TREE_URI, c.customSaveTreeUri)
            putExtra(ScreenRecordService.EXTRA_FLOATING_WINDOW_PERSISTENT, c.floatingWindowPersistent)
        }
    }
}