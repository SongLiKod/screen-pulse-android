package com.screenpulse.viewmodel

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenpulse.ScreenPulseApp
import com.screenpulse.repository.*
import com.screenpulse.util.RecordingCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ScreenPulseApp).settingsRepository

    val themeMode: StateFlow<ThemeMode> = repository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.FOLLOW_SYSTEM)

    val audioMode: StateFlow<AudioMode> = repository.audioMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioMode.SYSTEM_ONLY)

    val resolution: StateFlow<Resolution> = repository.resolution
        .stateIn(viewModelScope, SharingStarted.Eagerly, Resolution.R1080P)

    val frameRate: StateFlow<FrameRate> = repository.frameRate
        .stateIn(viewModelScope, SharingStarted.Eagerly, FrameRate.FPS_30)

    val bitrate: StateFlow<Int> = repository.bitrate
        .stateIn(viewModelScope, SharingStarted.Eagerly, 8000000)

    val compressionMode: StateFlow<CompressionMode> = repository.compressionMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, CompressionMode.BALANCED)

    val recordMode: StateFlow<RecordMode> = repository.recordMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordMode.FULL_SCREEN)

    val countdownMode: StateFlow<CountdownMode> = repository.countdownMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, CountdownMode.NONE)

    val customCountdownSeconds: StateFlow<Int> = repository.customCountdownSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, 10)

    val shortcutKey: StateFlow<Int> = repository.shortcutKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val watermarkEnabled: StateFlow<Boolean> = repository.watermarkEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val watermarkText: StateFlow<String> = repository.watermarkText
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val systemVolume: StateFlow<Int> = repository.systemVolume
        .stateIn(viewModelScope, SharingStarted.Eagerly, 100)

    val micVolume: StateFlow<Int> = repository.micVolume
        .stateIn(viewModelScope, SharingStarted.Eagerly, 100)

    val pipEnabled: StateFlow<Boolean> = repository.pipEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val shortcutKeyName: StateFlow<String> = repository.shortcutKeyName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val watermarkType: StateFlow<WatermarkType> = repository.watermarkType
        .stateIn(viewModelScope, SharingStarted.Eagerly, WatermarkType.TEXT)

    val watermarkImageUri: StateFlow<String> = repository.watermarkImageUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val pipSize: StateFlow<Int> = repository.pipSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 150)

    val customResolutionWidth: StateFlow<Int> = repository.customResolutionWidth
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1920)

    val customResolutionHeight: StateFlow<Int> = repository.customResolutionHeight
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1080)

    val bitrateMode: StateFlow<BitrateMode> = repository.bitrateMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, BitrateMode.SMART)

    val language: StateFlow<Language> = repository.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, Language.SYSTEM)

    val customSaveTreeUri: StateFlow<String> = repository.customSaveTreeUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val customRegion: StateFlow<CustomRegion> = repository.customRegion
        .stateIn(viewModelScope, SharingStarted.Eagerly, CustomRegion(0, 0, 0, 0))

    val savedRegions: StateFlow<List<SavedRegion>> = repository.savedRegions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val floatingWindowPersistent: StateFlow<Boolean> = repository.floatingWindowPersistent
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val captureProtectedContent: StateFlow<Boolean> = repository.captureProtectedContent
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val appLockEnabled: StateFlow<Boolean> = repository.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val appLockScope: StateFlow<AppLockScope> = repository.appLockScope
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLockScope.WHOLE_APP)

    val appLockBiometric: StateFlow<Boolean> = repository.appLockBiometric
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val appLockBackgroundTimeout: StateFlow<Int> = repository.appLockBackgroundTimeout
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    val appLockPinHash: StateFlow<String> = repository.appLockPinHash
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val appLockPinSalt: StateFlow<String> = repository.appLockPinSalt
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAppLockEnabled(enabled) }
    }
    fun setAppLockScope(scope: AppLockScope) {
        viewModelScope.launch { repository.setAppLockScope(scope) }
    }
    fun setAppLockBiometric(enabled: Boolean) {
        viewModelScope.launch { repository.setAppLockBiometric(enabled) }
    }
    fun setAppLockBackgroundTimeout(seconds: Int) {
        viewModelScope.launch { repository.setAppLockBackgroundTimeout(seconds) }
    }
    fun setAppLockPin(pin: String) {
        viewModelScope.launch {
            val salt = com.screenpulse.security.AppLockManager.newSalt()
            val hash = com.screenpulse.security.AppLockManager.hashPin(pin, salt)
            repository.setAppLockPin(hash, salt)
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            RecordingCache.ensureConfigLoaded(getApplication())
            RecordingCache.markSyncedFromUi()
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setAudioMode(mode: AudioMode) {
        RecordingCache.patch { it.copy(audioMode = mode.value) }
        viewModelScope.launch { repository.setAudioMode(mode) }
    }
    fun setResolution(res: Resolution) {
        RecordingCache.patch { it.copy(resolution = res.value) }
        viewModelScope.launch { repository.setResolution(res) }
    }
    fun setFrameRate(fps: FrameRate) {
        RecordingCache.patch { it.copy(frameRate = fps.value) }
        viewModelScope.launch { repository.setFrameRate(fps) }
    }
    fun setBitrate(bitrate: Int) {
        RecordingCache.patch { it.copy(bitrate = bitrate) }
        viewModelScope.launch { repository.setBitrate(bitrate) }
    }
    fun setCompressionMode(mode: CompressionMode) {
        RecordingCache.patch { it.copy(compressionMode = mode.value) }
        viewModelScope.launch { repository.setCompressionMode(mode) }
    }
    fun setRecordMode(mode: RecordMode) {
        RecordingCache.patch { it.copy(recordMode = mode.value) }
        viewModelScope.launch { repository.setRecordMode(mode) }
    }
    fun setCountdownMode(mode: CountdownMode) {
        RecordingCache.patch { it.copy(countdownMode = mode.value) }
        viewModelScope.launch { repository.setCountdownMode(mode) }
    }
    fun setCustomCountdownSeconds(seconds: Int) {
        RecordingCache.patch { it.copy(customCountdownSeconds = seconds) }
        viewModelScope.launch { repository.setCustomCountdownSeconds(seconds) }
    }
    fun setShortcutKey(keyCode: Int) {
        viewModelScope.launch {
            repository.setShortcutKey(keyCode)
            com.screenpulse.shortcut.RecordingStateManager.setKeyCode(keyCode)
        }
    }
    fun setWatermarkEnabled(enabled: Boolean) {
        RecordingCache.patch { it.copy(watermarkEnabled = enabled) }
        viewModelScope.launch { repository.setWatermarkEnabled(enabled) }
    }
    fun setWatermarkText(text: String) {
        RecordingCache.patch { it.copy(watermarkText = text) }
        viewModelScope.launch { repository.setWatermarkText(text) }
    }
    fun setSystemVolume(volume: Int) {
        RecordingCache.patch { it.copy(systemVolume = volume) }
        viewModelScope.launch { repository.setSystemVolume(volume) }
    }
    fun setMicVolume(volume: Int) {
        RecordingCache.patch { it.copy(micVolume = volume) }
        viewModelScope.launch { repository.setMicVolume(volume) }
    }
    fun setPipEnabled(enabled: Boolean) {
        RecordingCache.patch { it.copy(pipEnabled = enabled) }
        viewModelScope.launch { repository.setPipEnabled(enabled) }
    }
    fun setShortcutKeyName(name: String) = viewModelScope.launch { repository.setShortcutKeyName(name) }
    fun setWatermarkType(type: WatermarkType) {
        RecordingCache.patch { it.copy(watermarkType = type.value) }
        viewModelScope.launch { repository.setWatermarkType(type) }
    }
    fun setWatermarkImageUri(uri: String) {
        RecordingCache.patch { it.copy(watermarkImageUri = uri) }
        viewModelScope.launch { repository.setWatermarkImageUri(uri) }
    }
    fun setPipSize(size: Int) {
        RecordingCache.patch { it.copy(pipSize = size) }
        viewModelScope.launch { repository.setPipSize(size) }
    }
    fun setCustomResolutionWidth(width: Int) {
        RecordingCache.patch { it.copy(customResolutionWidth = width) }
        viewModelScope.launch { repository.setCustomResolutionWidth(width) }
    }
    fun setCustomResolutionHeight(height: Int) {
        RecordingCache.patch { it.copy(customResolutionHeight = height) }
        viewModelScope.launch { repository.setCustomResolutionHeight(height) }
    }
    fun setBitrateMode(mode: BitrateMode) {
        RecordingCache.patch { it.copy(bitrateMode = mode.value) }
        viewModelScope.launch { repository.setBitrateMode(mode) }
    }
    fun setCustomRegion(region: CustomRegion) {
        RecordingCache.applyRegion(region)
        viewModelScope.launch { repository.setCustomRegion(region) }
    }
    fun deleteSavedRegion(id: String) = viewModelScope.launch { repository.deleteSavedRegion(id) }
    fun updateSavedRegion(region: SavedRegion) = viewModelScope.launch { repository.updateSavedRegion(region) }
    fun setFloatingWindowPersistent(enabled: Boolean) {
        RecordingCache.patch { it.copy(floatingWindowPersistent = enabled) }
        viewModelScope.launch { repository.setFloatingWindowPersistent(enabled) }
    }
    fun setCaptureProtectedContent(enabled: Boolean) {
        RecordingCache.patch { it.copy(captureProtectedContent = enabled) }
        viewModelScope.launch { repository.setCaptureProtectedContent(enabled) }
    }

    fun setLanguage(language: Language) = viewModelScope.launch {
        repository.setLanguage(language)
        applyLanguage(language)
    }

    fun setCustomSaveTreeUri(uri: String) {
        RecordingCache.patch { it.copy(customSaveTreeUri = uri) }
        viewModelScope.launch {
            repository.setCustomSaveTreeUri(uri)
            val app = getApplication<Application>()
            val prefs = app.getSharedPreferences("screen_pulse_save_path", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("custom_save_tree_uri", uri).apply()
            if (uri.isNotEmpty()) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { app.contentResolver.takePersistableUriPermission(uri.toUri(), flags) }
            }
        }
    }

    private fun applyLanguage(language: Language) {
        val locales = if (language == Language.SYSTEM || language.tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
