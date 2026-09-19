package com.screenpulse.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import com.screenpulse.R
import com.screenpulse.repository.AppLockScope
import com.screenpulse.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppLockGate(
    settingsViewModel: SettingsViewModel,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    val enabled by settingsViewModel.appLockEnabled.collectAsState()
    val scope by settingsViewModel.appLockScope.collectAsState()
    val biometricEnabled by settingsViewModel.appLockBiometric.collectAsState()
    val backgroundTimeout by settingsViewModel.appLockBackgroundTimeout.collectAsState()
    val pinHash by settingsViewModel.appLockPinHash.collectAsState()
    val pinSalt by settingsViewModel.appLockPinSalt.collectAsState()
    val isUnlocked by AppLockManager.isUnlocked.collectAsState()

    LaunchedEffect(enabled, backgroundTimeout) {
        AppLockManager.lockEnabled = enabled
        AppLockManager.backgroundTimeoutSec = backgroundTimeout
    }

    var currentRoute by remember { mutableStateOf<String?>(null) }
    DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            currentRoute = destination.route
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    val hasPin = pinHash.isNotEmpty()
    val needLock = enabled && hasPin && !isUnlocked &&
        AppLockManager.isProtectedRoute(currentRoute, scope)

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (needLock) {
            LockScreen(
                biometricEnabled = biometricEnabled,
                pinSalt = pinSalt,
                pinHash = pinHash,
                onUnlocked = { AppLockManager.unlock() }
            )
        }
    }
}

@Composable
internal fun LockScreen(
    biometricEnabled: Boolean,
    pinSalt: String,
    pinHash: String,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val bioAvailable = biometricEnabled && remember(context) { AppLockManager.biometricAvailable(context) }

    fun launchBiometric() {
        val activity = context.findActivity() as? FragmentActivity
        if (activity == null) {
            error = context.getString(R.string.app_lock_biometric_unavailable)
            return
        }
        AppLockManager.authenticate(
            activity = activity,
            title = context.getString(R.string.app_lock_title),
            subtitle = context.getString(R.string.app_lock_subtitle),
            negativeText = context.getString(R.string.app_lock_use_pin),
            onSuccess = onUnlocked,
            onError = { msg -> error = msg }
        )
    }

    // Auto-prompt biometrics once when the lock screen appears.
    LaunchedEffect(bioAvailable) {
        if (bioAvailable) {
            delay(350)
            launchBiometric()
        }
    }

    fun submit() {
        if (pin.length < AppLockManager.MIN_PIN_LENGTH) {
            error = context.getString(R.string.app_lock_pin_too_short)
            return
        }
        if (AppLockManager.verifyPin(pin, pinSalt, pinHash)) {
            onUnlocked()
        } else {
            error = context.getString(R.string.app_lock_pin_wrong)
            pin = ""
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(stringResource(R.string.app_lock_title), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(R.string.app_lock_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.height(30.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(AppLockManager.MAX_PIN_LENGTH) { index ->
                    val filled = index < pin.length
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                if (filled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error ?: " ",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.height(20.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            if (bioAvailable) {
                OutlinedButton(onClick = { launchBiometric() }) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.app_lock_use_biometric))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            PinPad(
                onDigit = { d ->
                    error = null
                    if (pin.length < AppLockManager.MAX_PIN_LENGTH) pin += d
                },
                onDelete = { pin = pin.dropLast(1) },
                onConfirm = { submit() }
            )
        }
    }
}

@Composable
private fun PinPad(
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit
) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "del", "0", "ok")
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { key ->
                    when (key) {
                        "del" -> TextButton(onClick = onDelete, modifier = Modifier.size(72.dp)) {
                            Text("⌫", fontSize = 22.sp)
                        }
                        "ok" -> TextButton(onClick = onConfirm, modifier = Modifier.size(72.dp)) {
                            Text(stringResource(R.string.app_lock_unlock), fontSize = 16.sp)
                        }
                        else -> Button(
                            onClick = { onDigit(key) },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(key, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppLockPinDialog(
    requireCurrent: Boolean,
    currentSalt: String,
    currentHash: String,
    onDismiss: () -> Unit,
    onConfirmed: (String) -> Unit
) {
    val context = LocalContext.current
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun digitsOnly(source: String) = source.filter { it.isDigit() }.take(AppLockManager.MAX_PIN_LENGTH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (requireCurrent) R.string.app_lock_change_pin else R.string.app_lock_set_pin)) },
        text = {
            Column {
                if (requireCurrent) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = digitsOnly(it); error = null },
                        label = { Text(stringResource(R.string.app_lock_pin_current)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = digitsOnly(it); error = null },
                    label = { Text(stringResource(R.string.app_lock_pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = digitsOnly(it); error = null },
                    label = { Text(stringResource(R.string.app_lock_pin_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    requireCurrent && !AppLockManager.verifyPin(current, currentSalt, currentHash) ->
                        error = context.getString(R.string.app_lock_pin_wrong)
                    newPin.length < AppLockManager.MIN_PIN_LENGTH ->
                        error = context.getString(R.string.app_lock_pin_too_short)
                    newPin != confirmPin ->
                        error = context.getString(R.string.app_lock_pin_mismatch)
                    else -> onConfirmed(newPin)
                }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
