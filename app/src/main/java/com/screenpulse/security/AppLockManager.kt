package com.screenpulse.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.screenpulse.repository.AppLockScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Holds the in-memory lock state and provides PIN hashing + biometric helpers.
 * The persisted configuration (enabled / scope / PIN hash) lives in SettingsRepository;
 * this object mirrors a synchronous snapshot of it so lifecycle callbacks can decide
 * whether to re-lock without suspending on DataStore.
 */
object AppLockManager {

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _privacyUnlocked = MutableStateFlow(false)
    val privacyUnlocked: StateFlow<Boolean> = _privacyUnlocked.asStateFlow()

    // Synchronous snapshot kept in sync by the gate UI.
    @Volatile
    var lockEnabled: Boolean = false

    @Volatile
    var backgroundTimeoutSec: Int = 0

    private var backgroundAtElapsed = 0L

    fun unlock() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun unlockPrivacy() {
        _privacyUnlocked.value = true
    }

    fun lockPrivacy() {
        _privacyUnlocked.value = false
    }

    fun onBackground() {
        backgroundAtElapsed = SystemClock.elapsedRealtime()
        _privacyUnlocked.value = false
        if (lockEnabled && backgroundTimeoutSec <= 0) {
            _isUnlocked.value = false
        }
    }

    fun onForeground() {
        if (!lockEnabled) return
        if (!_isUnlocked.value) return
        val elapsedSec = (SystemClock.elapsedRealtime() - backgroundAtElapsed) / 1000L
        if (backgroundTimeoutSec <= 0 || elapsedSec > backgroundTimeoutSec) {
            _isUnlocked.value = false
        }
    }

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, salt: String, expectedHash: String): Boolean {
        if (salt.isBlank() || expectedHash.isBlank()) return false
        return hashPin(pin, salt) == expectedHash
    }

    fun biometricAvailable(context: Context): Boolean {
        return runCatching {
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        runCatching {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL
                    ) {
                        onError(errString.toString())
                    }
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(negativeText)
                .build()
            prompt.authenticate(info)
        }.onFailure {
            onError(it.message ?: "Biometric unavailable")
        }
    }

    fun isProtectedRoute(route: String?, scope: AppLockScope): Boolean {
        if (scope == AppLockScope.WHOLE_APP) return true
        if (route == null) return false
        return route.startsWith("video_list") ||
            route.startsWith("video_preview") ||
            route.startsWith("video_trim")
    }
}
