package com.roman.zemzeme.ui

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.roman.zemzeme.R
import com.roman.zemzeme.security.AppLockManager
import com.roman.zemzeme.security.AppLockPreferenceManager
import kotlinx.coroutines.delay

private const val MAX_ATTEMPTS = 5
private const val WARNING_ATTEMPTS = 3
private const val COOLDOWN_SECONDS = 30L

@Composable
fun AppLockScreen(
    activity: FragmentActivity,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    val pinLength = remember { AppLockPreferenceManager.getPinLength() }
    var enteredPin by remember { mutableStateOf("") }
    var wrongAttempts by remember { mutableIntStateOf(0) }
    var showPinError by remember { mutableStateOf(false) }
    var cooldownEndTime by remember { mutableLongStateOf(0L) }
    var remainingCooldown by remember { mutableIntStateOf(0) }
    var showPinFallback by remember { mutableStateOf(false) }

    val biometricManager = remember { BiometricManager.from(context) }
    val canUseBiometric = remember {
        biometricManager.canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    // Cooldown countdown timer
    LaunchedEffect(cooldownEndTime) {
        if (cooldownEndTime > 0) {
            while (SystemClock.elapsedRealtime() < cooldownEndTime) {
                remainingCooldown =
                    ((cooldownEndTime - SystemClock.elapsedRealtime()) / 1000).toInt() + 1
                delay(500)
            }
            remainingCooldown = 0
            cooldownEndTime = 0
            enteredPin = ""
        }
    }

    fun launchBiometric() {
        if (!canUseBiometric) {
            showPinFallback = true
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                AppLockManager.unlock()
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                showPinFallback = true
            }

            override fun onAuthenticationFailed() {
                // Fingerprint not recognized; keep biometric prompt open
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.app_lock_biometric_title))
            .setNegativeButtonText(context.getString(R.string.app_lock_use_pin))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    // Auto-fire biometric on first composition
    LaunchedEffect(Unit) {
        if (canUseBiometric) {
            launchBiometric()
        } else {
            showPinFallback = true
        }
    }

    // Auto-submit when PIN reaches required length
    LaunchedEffect(enteredPin) {
        if (enteredPin.length == pinLength && remainingCooldown == 0) {
            if (AppLockPreferenceManager.verifyPin(enteredPin)) {
                AppLockManager.unlock()
                onUnlocked()
            } else {
                wrongAttempts++
                showPinError = true
                if (wrongAttempts >= MAX_ATTEMPTS) {
                    cooldownEndTime = SystemClock.elapsedRealtime() + COOLDOWN_SECONDS * 1000
                    wrongAttempts = 0
                }
                delay(2500)
                enteredPin = ""
                showPinError = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onBackground
            )

            when {
                remainingCooldown > 0 -> {
                    Text(
                        text = stringResource(R.string.app_lock_cooldown, remainingCooldown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF3B30)
                    )
                }

                showPinFallback -> {
                    // PIN dot indicators
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pinLength) { i ->
                            val filled = i < enteredPin.length
                            val dotColor = when {
                                showPinError -> Color(0xFFFF3B30)
                                filled -> colorScheme.primary
                                else -> colorScheme.onBackground.copy(alpha = 0.25f)
                            }
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(dotColor, CircleShape)
                            )
                        }
                    }

                    if (showPinError && wrongAttempts > 0) {
                        Text(
                            text = if (wrongAttempts >= WARNING_ATTEMPTS)
                                stringResource(
                                    R.string.app_lock_too_many_attempts,
                                    MAX_ATTEMPTS - wrongAttempts
                                )
                            else
                                stringResource(R.string.app_lock_wrong_pin),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF3B30)
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.app_lock_enter_pin),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }

                else -> {
                    // Biometric waiting state
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = stringResource(R.string.app_lock_biometric_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            // PIN numpad — visible in PIN fallback mode when not in cooldown
            if (showPinFallback && remainingCooldown == 0) {
                PinNumpad(
                    onDigit = { digit ->
                        if (enteredPin.length < pinLength) {
                            enteredPin += digit
                        }
                    },
                    onBackspace = {
                        if (enteredPin.isNotEmpty()) {
                            enteredPin = enteredPin.dropLast(1)
                        }
                    },
                    isDark = isDark
                )

                // Retry biometric button
                if (canUseBiometric) {
                    TextButton(onClick = { launchBiometric() }) {
                        Icon(
                            imageVector = Icons.Filled.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.app_lock_biometric_title),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // "Use PIN" shortcut shown when biometric prompt is active
            if (!showPinFallback && remainingCooldown == 0) {
                TextButton(onClick = { showPinFallback = true }) {
                    Text(
                        text = stringResource(R.string.app_lock_use_pin),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun PinNumpad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    isDark: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val buttonColor = colorScheme.surface
    val textColor = colorScheme.onSurface

    @Composable
    fun NumKey(label: String, onClick: () -> Unit) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = buttonColor,
            onClick = onClick
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }
        }
    }

    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "back")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { label ->
                    when (label) {
                        "" -> Spacer(modifier = Modifier.size(72.dp))
                        "back" -> Surface(
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = Color.Transparent,
                            onClick = onBackspace
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Backspace,
                                    contentDescription = stringResource(R.string.cd_backspace),
                                    tint = textColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        else -> NumKey(label) { onDigit(label) }
                    }
                }
            }
        }
    }
}
