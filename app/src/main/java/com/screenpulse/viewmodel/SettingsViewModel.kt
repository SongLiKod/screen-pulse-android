package com.screenpulse.viewmodel

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenpulse.ScreenPulseApp
import com.screenpulse.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ScreenPulseApp).settingsRepository

    val themeMode: StateFlow<ThemeMode> = repository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.FOLLOW_SYSTEM)

    val audioMode: StateFlow<AudioMode> = repository.audioMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioMode.MIC_ONLY)

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

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setAudioMode(mode: AudioMode) = viewModelScope.launch { repository.setAudioMode(mode) }
    fun setResolution(res: Resolution) = viewModelScope.launch { repository.setResolution(res) }
    fun setFrameRate(fps: FrameRate) = viewModelScope.launch { repository.setFrameRate(fps) }
    fun setBitrate(bitrate: Int) = viewModelScope.launch { repository.setBitrate(bitrate) }
    fun setCompressionMode(mode: CompressionMode) = viewModelScope.launch { repository.setCompressionMode(mode) }
    fun setRecordMode(mode: RecordMode) = viewModelScope.launch { repository.setRecordMode(mode) }
    fun setCountdownMode(mode: CountdownMode) = viewModelScope.launch { repository.setCountdownMode(mode) }
    fun setShortcutKey(keyCode: Int) {
        viewModelScope.launch {
            repository.setShortcutKey(keyCode)
            com.screenpulse.shortcut.RecordingStateManager.setKeyCode(keyCode)
        }
    }
    fun setWatermarkEnabled(enabled: Boolean) = viewModelScope.launch { repository.setWatermarkEnabled(enabled) }
    fun setWatermarkText(text: String) = viewModelScope.launch { repository.setWatermarkText(text) }
    fun setSystemVolume(volume: Int) = viewModelScope.launch { repository.setSystemVolume(volume) }
    fun setMicVolume(volume: Int) = viewModelScope.launch { repository.setMicVolume(volume) }
    fun setPipEnabled(enabled: Boolean) = viewModelScope.launch { repository.setPipEnabled(enabled) }
    fun setShortcutKeyName(name: String) = viewModelScope.launch { repository.setShortcutKeyName(name) }
    fun setWatermarkType(type: WatermarkType) = viewModelScope.launch { repository.setWatermarkType(type) }
    fun setWatermarkImageUri(uri: String) = viewModelScope.launch { repository.setWatermarkImageUri(uri) }
    fun setPipSize(size: Int) = viewModelScope.launch { repository.setPipSize(size) }
    fun setCustomResolutionWidth(width: Int) = viewModelScope.launch { repository.setCustomResolutionWidth(width) }
    fun setCustomResolutionHeight(height: Int) = viewModelScope.launch { repository.setCustomResolutionHeight(height) }
    fun setBitrateMode(mode: BitrateMode) = viewModelScope.launch { repository.setBitrateMode(mode) }

    fun setLanguage(language: Language) = viewModelScope.launch {
        repository.setLanguage(language)
        applyLanguage(language)
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
