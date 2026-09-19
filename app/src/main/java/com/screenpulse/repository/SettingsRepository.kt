package com.screenpulse.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "screen_pulse_prefs")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val AUDIO_MODE = intPreferencesKey("audio_mode")
        val RESOLUTION = stringPreferencesKey("resolution")
        val FRAME_RATE = intPreferencesKey("frame_rate")
        val BITRATE = intPreferencesKey("bitrate")
        val COMPRESSION_MODE = intPreferencesKey("compression_mode")
        val RECORD_MODE = intPreferencesKey("record_mode")
        val COUNTDOWN_MODE = intPreferencesKey("countdown_mode")
        val CUSTOM_COUNTDOWN_SECONDS = intPreferencesKey("custom_countdown_seconds")
        val SHORTCUT_KEY = intPreferencesKey("shortcut_key")
        val SHORTCUT_KEY_NAME = stringPreferencesKey("shortcut_key_name")
        val WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        val WATERMARK_TEXT = stringPreferencesKey("watermark_text")
        val WATERMARK_TYPE = intPreferencesKey("watermark_type")
        val WATERMARK_IMAGE_URI = stringPreferencesKey("watermark_image_uri")
        val SYSTEM_VOLUME = intPreferencesKey("system_volume")
        val MIC_VOLUME = intPreferencesKey("mic_volume")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val PIP_SIZE = intPreferencesKey("pip_size")
        val CUSTOM_WIDTH = intPreferencesKey("custom_width")
        val CUSTOM_HEIGHT = intPreferencesKey("custom_height")
        val CUSTOM_OFFSET_X = intPreferencesKey("custom_offset_x")
        val CUSTOM_OFFSET_Y = intPreferencesKey("custom_offset_y")
        val CUSTOM_RESOLUTION_WIDTH = intPreferencesKey("custom_resolution_width")
        val CUSTOM_RESOLUTION_HEIGHT = intPreferencesKey("custom_resolution_height")
        val BITRATE_MODE = intPreferencesKey("bitrate_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val CUSTOM_SAVE_TREE_URI = stringPreferencesKey("custom_save_tree_uri")
        val FLOATING_WINDOW_PERSISTENT = booleanPreferencesKey("floating_window_persistent")
        val CAPTURE_PROTECTED_CONTENT = booleanPreferencesKey("capture_protected_content")
        val SAVED_REGIONS = stringPreferencesKey("saved_regions")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_SCOPE = intPreferencesKey("app_lock_scope")
        val APP_LOCK_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
        val APP_LOCK_BG_TIMEOUT = intPreferencesKey("app_lock_bg_timeout")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val APP_LOCK_PIN_SALT = stringPreferencesKey("app_lock_pin_salt")
    }

    val customSaveTreeUri: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_SAVE_TREE_URI] ?: ""
    }

    val floatingWindowPersistent: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FLOATING_WINDOW_PERSISTENT] ?: false
    }

    val captureProtectedContent: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CAPTURE_PROTECTED_CONTENT] ?: false
    }

    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_ENABLED] ?: false
    }

    val appLockScope: Flow<AppLockScope> = context.dataStore.data.map { prefs ->
        AppLockScope.fromValue(prefs[Keys.APP_LOCK_SCOPE] ?: AppLockScope.WHOLE_APP.value)
    }

    val appLockBiometric: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_BIOMETRIC] ?: true
    }

    val appLockBackgroundTimeout: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_BG_TIMEOUT] ?: 30
    }

    val appLockPinHash: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_PIN_HASH] ?: ""
    }

    val appLockPinSalt: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_LOCK_PIN_SALT] ?: ""
    }

    suspend fun setCustomSaveTreeUri(uri: String) {
        context.dataStore.edit { it[Keys.CUSTOM_SAVE_TREE_URI] = uri }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.fromValue(prefs[Keys.THEME_MODE] ?: ThemeMode.FOLLOW_SYSTEM.value)
    }

    val audioMode: Flow<AudioMode> = context.dataStore.data.map { prefs ->
        AudioMode.fromValue(prefs[Keys.AUDIO_MODE] ?: AudioMode.SYSTEM_ONLY.value)
    }

    val resolution: Flow<Resolution> = context.dataStore.data.map { prefs ->
        Resolution.fromValue(prefs[Keys.RESOLUTION] ?: Resolution.R1080P.value)
    }

    val frameRate: Flow<FrameRate> = context.dataStore.data.map { prefs ->
        FrameRate.fromValue(prefs[Keys.FRAME_RATE] ?: FrameRate.FPS_30.value)
    }

    val bitrate: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BITRATE] ?: 8000000
    }

    val compressionMode: Flow<CompressionMode> = context.dataStore.data.map { prefs ->
        CompressionMode.fromValue(prefs[Keys.COMPRESSION_MODE] ?: CompressionMode.BALANCED.value)
    }

    val recordMode: Flow<RecordMode> = context.dataStore.data.map { prefs ->
        RecordMode.fromValue(prefs[Keys.RECORD_MODE] ?: RecordMode.FULL_SCREEN.value)
    }

    val countdownMode: Flow<CountdownMode> = context.dataStore.data.map { prefs ->
        CountdownMode.fromValue(prefs[Keys.COUNTDOWN_MODE] ?: CountdownMode.NONE.value)
    }

    val customCountdownSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_COUNTDOWN_SECONDS] ?: 10
    }

    val shortcutKey: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHORTCUT_KEY] ?: 0
    }

    val watermarkEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WATERMARK_ENABLED] ?: false
    }

    val watermarkText: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.WATERMARK_TEXT] ?: ""
    }

    val systemVolume: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.SYSTEM_VOLUME] ?: 100
    }

    val micVolume: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.MIC_VOLUME] ?: 100
    }

    val pipEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.PIP_ENABLED] ?: false
    }

    val customRegion: Flow<CustomRegion> = context.dataStore.data.map { prefs ->
        CustomRegion(
            width = prefs[Keys.CUSTOM_WIDTH] ?: 0,
            height = prefs[Keys.CUSTOM_HEIGHT] ?: 0,
            offsetX = prefs[Keys.CUSTOM_OFFSET_X] ?: 0,
            offsetY = prefs[Keys.CUSTOM_OFFSET_Y] ?: 0,
        )
    }

    val savedRegions: Flow<List<SavedRegion>> = context.dataStore.data.map { prefs ->
        SavedRegion.decodeList(prefs[Keys.SAVED_REGIONS] ?: "")
    }

    val shortcutKeyName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SHORTCUT_KEY_NAME] ?: ""
    }

    val watermarkType: Flow<WatermarkType> = context.dataStore.data.map { prefs ->
        WatermarkType.fromValue(prefs[Keys.WATERMARK_TYPE] ?: WatermarkType.TEXT.value)
    }

    val watermarkImageUri: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.WATERMARK_IMAGE_URI] ?: ""
    }

    val pipSize: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.PIP_SIZE] ?: 150
    }

    val customResolutionWidth: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_RESOLUTION_WIDTH] ?: 1920
    }

    val customResolutionHeight: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_RESOLUTION_HEIGHT] ?: 1080
    }

    val bitrateMode: Flow<BitrateMode> = context.dataStore.data.map { prefs ->
        BitrateMode.fromValue(prefs[Keys.BITRATE_MODE] ?: BitrateMode.SMART.value)
    }

    val language: Flow<Language> = context.dataStore.data.map { prefs ->
        Language.fromValue(prefs[Keys.LANGUAGE] ?: Language.SYSTEM.value)
    }

    suspend fun setLanguage(language: Language) {
        context.dataStore.edit { it[Keys.LANGUAGE] = language.value }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.value }
    }

    suspend fun setAudioMode(mode: AudioMode) {
        context.dataStore.edit { it[Keys.AUDIO_MODE] = mode.value }
    }

    suspend fun setResolution(res: Resolution) {
        context.dataStore.edit { it[Keys.RESOLUTION] = res.value }
    }

    suspend fun setFrameRate(fps: FrameRate) {
        context.dataStore.edit { it[Keys.FRAME_RATE] = fps.value }
    }

    suspend fun setBitrate(bitrate: Int) {
        context.dataStore.edit { it[Keys.BITRATE] = bitrate }
    }

    suspend fun setCompressionMode(mode: CompressionMode) {
        context.dataStore.edit { it[Keys.COMPRESSION_MODE] = mode.value }
    }

    suspend fun setRecordMode(mode: RecordMode) {
        context.dataStore.edit { it[Keys.RECORD_MODE] = mode.value }
    }

    suspend fun setCountdownMode(mode: CountdownMode) {
        context.dataStore.edit { it[Keys.COUNTDOWN_MODE] = mode.value }
    }

    suspend fun setCustomCountdownSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.CUSTOM_COUNTDOWN_SECONDS] = seconds.coerceIn(1, 300) }
    }

    suspend fun setShortcutKey(keyCode: Int) {
        context.dataStore.edit { it[Keys.SHORTCUT_KEY] = keyCode }
    }

    suspend fun setWatermarkEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WATERMARK_ENABLED] = enabled }
    }

    suspend fun setWatermarkText(text: String) {
        context.dataStore.edit { it[Keys.WATERMARK_TEXT] = text }
    }

    suspend fun setSystemVolume(volume: Int) {
        context.dataStore.edit { it[Keys.SYSTEM_VOLUME] = volume }
    }

    suspend fun setMicVolume(volume: Int) {
        context.dataStore.edit { it[Keys.MIC_VOLUME] = volume }
    }

    suspend fun setPipEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIP_ENABLED] = enabled }
    }

    suspend fun setCustomRegion(region: CustomRegion) {
        context.dataStore.edit {
            it[Keys.CUSTOM_WIDTH] = region.width
            it[Keys.CUSTOM_HEIGHT] = region.height
            it[Keys.CUSTOM_OFFSET_X] = region.offsetX
            it[Keys.CUSTOM_OFFSET_Y] = region.offsetY
        }
    }

    suspend fun setSavedRegions(regions: List<SavedRegion>) {
        context.dataStore.edit { it[Keys.SAVED_REGIONS] = SavedRegion.encodeList(regions) }
    }

    suspend fun addSavedRegion(region: SavedRegion) {
        context.dataStore.edit { prefs ->
            val current = SavedRegion.decodeList(prefs[Keys.SAVED_REGIONS] ?: "")
            prefs[Keys.SAVED_REGIONS] = SavedRegion.encodeList(current + region)
        }
    }

    suspend fun deleteSavedRegion(id: String) {
        context.dataStore.edit { prefs ->
            val current = SavedRegion.decodeList(prefs[Keys.SAVED_REGIONS] ?: "")
            prefs[Keys.SAVED_REGIONS] = SavedRegion.encodeList(current.filter { it.id != id })
        }
    }

    suspend fun updateSavedRegion(region: SavedRegion) {
        context.dataStore.edit { prefs ->
            val current = SavedRegion.decodeList(prefs[Keys.SAVED_REGIONS] ?: "")
            prefs[Keys.SAVED_REGIONS] = SavedRegion.encodeList(
                current.map { if (it.id == region.id) region else it }
            )
        }
    }

    suspend fun setShortcutKeyName(name: String) {
        context.dataStore.edit { it[Keys.SHORTCUT_KEY_NAME] = name }
    }

    suspend fun setWatermarkType(type: WatermarkType) {
        context.dataStore.edit { it[Keys.WATERMARK_TYPE] = type.value }
    }

    suspend fun setWatermarkImageUri(uri: String) {
        context.dataStore.edit { it[Keys.WATERMARK_IMAGE_URI] = uri }
    }

    suspend fun setPipSize(size: Int) {
        context.dataStore.edit { it[Keys.PIP_SIZE] = size }
    }

    suspend fun setCustomResolutionWidth(width: Int) {
        context.dataStore.edit { it[Keys.CUSTOM_RESOLUTION_WIDTH] = width }
    }

    suspend fun setCustomResolutionHeight(height: Int) {
        context.dataStore.edit { it[Keys.CUSTOM_RESOLUTION_HEIGHT] = height }
    }

    suspend fun setBitrateMode(mode: BitrateMode) {
        context.dataStore.edit { it[Keys.BITRATE_MODE] = mode.value }
    }

    suspend fun setFloatingWindowPersistent(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FLOATING_WINDOW_PERSISTENT] = enabled }
    }

    suspend fun setCaptureProtectedContent(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CAPTURE_PROTECTED_CONTENT] = enabled }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setAppLockScope(scope: AppLockScope) {
        context.dataStore.edit { it[Keys.APP_LOCK_SCOPE] = scope.value }
    }

    suspend fun setAppLockBiometric(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK_BIOMETRIC] = enabled }
    }

    suspend fun setAppLockBackgroundTimeout(seconds: Int) {
        context.dataStore.edit { it[Keys.APP_LOCK_BG_TIMEOUT] = seconds }
    }

    suspend fun setAppLockPin(hash: String, salt: String) {
        context.dataStore.edit {
            it[Keys.APP_LOCK_PIN_HASH] = hash
            it[Keys.APP_LOCK_PIN_SALT] = salt
        }
    }
}

data class CustomRegion(
    val width: Int,
    val height: Int,
    val offsetX: Int,
    val offsetY: Int,
) {
    fun isValid(): Boolean = width >= 16 && height >= 16
}

data class SavedRegion(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val offsetX: Int,
    val offsetY: Int
) {
    fun toCustomRegion(): CustomRegion = CustomRegion(width, height, offsetX, offsetY)

    companion object {
        fun encodeList(regions: List<SavedRegion>): String {
            val arr = org.json.JSONArray()
            regions.forEach { region ->
                arr.put(org.json.JSONObject().apply {
                    put("id", region.id)
                    put("name", region.name)
                    put("width", region.width)
                    put("height", region.height)
                    put("offsetX", region.offsetX)
                    put("offsetY", region.offsetY)
                })
            }
            return arr.toString()
        }

        fun decodeList(raw: String): List<SavedRegion> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val arr = org.json.JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            SavedRegion(
                                id = obj.optString("id"),
                                name = obj.optString("name"),
                                width = obj.optInt("width"),
                                height = obj.optInt("height"),
                                offsetX = obj.optInt("offsetX"),
                                offsetY = obj.optInt("offsetY")
                            )
                        )
                    }
                }.filter { it.id.isNotBlank() && it.width > 0 && it.height > 0 }
            }.getOrDefault(emptyList())
        }
    }
}
