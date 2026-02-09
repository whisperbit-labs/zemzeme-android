package com.roman.zemzeme.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.roman.zemzeme.R
import com.roman.zemzeme.core.ui.component.button.CloseButton
import com.roman.zemzeme.core.ui.component.sheet.ZemzemeBottomSheet
import com.roman.zemzeme.features.file.FileUtils
import com.roman.zemzeme.features.sharing.ApkSharingHelper
import com.roman.zemzeme.net.TorMode
import com.roman.zemzeme.net.TorPreferenceManager
import com.roman.zemzeme.net.ArtiTorManager
import com.roman.zemzeme.service.MeshServiceHolder
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.p2p.P2PTransport
import com.roman.zemzeme.p2p.P2PNodeStatus
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.roman.zemzeme.onboarding.NetworkStatus
import com.roman.zemzeme.onboarding.NetworkStatusManager
import com.roman.zemzeme.security.AppLockManager
import com.roman.zemzeme.security.AppLockPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All possible states for the Network transport toggles (P2P, Nostr).
 */
private enum class NetworkToggleState(
    val subtitleResId: Int,
    val subtitleColor: Color?
) {
    /** Setting ON + Internet available — fully operational */
    ACTIVE(subtitleResId = R.string.about_p2p_subtitle, subtitleColor = null),

    /** Setting ON + No internet — user wants transport but no connectivity */
    NETWORK_OFF(subtitleResId = R.string.network_setting_off_subtitle, subtitleColor = Color(0xFFFF3B30)),

    /** Setting OFF (regardless of network) — user disabled this transport */
    DISABLED(subtitleResId = R.string.about_p2p_subtitle, subtitleColor = null);

    companion object {
        fun from(userEnabled: Boolean, networkAvailable: Boolean): NetworkToggleState = when {
            userEnabled && networkAvailable -> ACTIVE
            userEnabled -> NETWORK_OFF
            else -> DISABLED
        }
    }
}

/**
 * All possible states for the BLE Mesh toggle.
 */
private enum class BleMeshToggleState(
    val isChecked: Boolean,
    val subtitleResId: Int,
    val subtitleColor: Color?
) {
    /** Setting ON + Bluetooth ON — fully operational */
    ACTIVE(isChecked = true, subtitleResId = R.string.about_ble_subtitle, subtitleColor = null),

    /** Setting ON + Bluetooth OFF — user wants BLE but hardware is off */
    BLUETOOTH_OFF(isChecked = false, subtitleResId = R.string.ble_setting_off_subtitle, subtitleColor = Color(0xFFFF9500)),

    /** Setting OFF (regardless of Bluetooth) — user disabled BLE Mesh */
    DISABLED(isChecked = false, subtitleResId = R.string.about_ble_subtitle, subtitleColor = null);

    companion object {
        fun from(userEnabled: Boolean, systemBtEnabled: Boolean): BleMeshToggleState = when {
            userEnabled && systemBtEnabled -> ACTIVE
            userEnabled -> BLUETOOTH_OFF
            else -> DISABLED
        }
    }
}

/** Steps used in the app-lock setup / disable flow inside AboutSheet */
private enum class AppLockSetupStep { NONE, SET_PIN, CONFIRM_PIN, VERIFY_DISABLE }

/**
 * Feature row for displaying app capabilities
 */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                lineHeight = 18.sp
            )
        }
    }
}

/**
 * Theme selection chip with Apple-like styling
 */
@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            colorScheme.primary
        } else {
            colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Unified settings toggle row with icon, title, subtitle, and switch
 * Apple-like design with proper spacing
 */
@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    subtitleColor: Color? = null,
    statusIndicator: (@Composable () -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.4f)
                )
                statusIndicator?.invoke()
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor ?: colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.3f),
                lineHeight = 16.sp
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

/**
 * Clickable action row with icon, title, and subtitle (no switch).
 * Used for action buttons like "Share via System".
 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.3f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * Apple-like About/Settings Sheet with high-quality design
 * Professional UX optimized for checkout scenarios
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    isBluetoothEnabled: Boolean = true,
    onShowDebug: (() -> Unit)? = null,
    nickname: String = "",
    onNicknameChange: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val networkStatus by NetworkStatusManager.networkStatusFlow.collectAsState()
    val isAirplaneModeOn by NetworkStatusManager.airplaneModeFlow.collectAsState()
    
    // Get version name from package info
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0" // fallback version
        }
    }

    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.98f else 0f,
        label = "topBarAlpha"
    )

    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    // Hoist shared state to avoid duplicate collection in multiple LazyColumn items
    var backgroundEnabled by remember { mutableStateOf(com.roman.zemzeme.service.MeshServicePreferences.isBackgroundEnabled(true)) }
    val torMode by TorPreferenceManager.modeFlow.collectAsState()
    val torProvider = remember { ArtiTorManager.getInstance() }
    val torStatus by torProvider.statusFlow.collectAsState()
    val torAvailable = remember { torProvider.isTorAvailable() }
    val p2pConfig = remember { P2PConfig(context) }
    val transportToggles by P2PConfig.transportTogglesFlow.collectAsState()
    val attachedMeshService by MeshServiceHolder.meshServiceFlow.collectAsState()
    val transportRuntimeState by produceState<com.roman.zemzeme.mesh.BluetoothMeshService.TransportRuntimeState?>(
        initialValue = attachedMeshService?.transportRuntimeState?.value,
        key1 = attachedMeshService
    ) {
        value = attachedMeshService?.transportRuntimeState?.value
        val service = attachedMeshService ?: return@produceState
        service.transportRuntimeState.collect { latest ->
            value = latest
        }
    }
    val p2pEnabled = transportRuntimeState?.desiredToggles?.p2pEnabled ?: transportToggles.p2pEnabled
    val nostrEnabled = transportRuntimeState?.desiredToggles?.nostrEnabled ?: transportToggles.nostrEnabled
    val bleEnabled = transportRuntimeState?.desiredToggles?.bleEnabled ?: transportToggles.bleEnabled
    val p2pTransport = remember { P2PTransport.getInstance(context) }
    val p2pStatus by p2pTransport.p2pRepository.nodeStatus.collectAsState()
    val p2pScope = rememberCoroutineScope()
    val p2pRunning = transportRuntimeState?.p2pRunning ?: (p2pStatus == P2PNodeStatus.RUNNING)
    val isNetworkAvailable = networkStatus == NetworkStatus.CONNECTED
    val nostrRelayManager = remember { com.roman.zemzeme.nostr.NostrRelayManager.getInstance(context) }
    val nostrConnected by nostrRelayManager.isConnected.collectAsState()
    val effectiveNostrConnected = transportRuntimeState?.nostrConnected ?: nostrConnected

    if (isPresented) {
        ZemzemeBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Header Section - App Identity
                    item(key = "header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = TextStyle(
                                    fontFamily = NunitoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = colorScheme.onBackground
                            )

                            // Version
                            Text(
                                text = stringResource(R.string.version_prefix, versionName ?: ""),
                                fontSize = 13.sp,
                                fontFamily = NunitoFontFamily,
                                color = colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                            Text(
                                text = stringResource(R.string.about_tagline),
                                fontSize = 13.sp,
                                fontFamily = NunitoFontFamily,
                                color = colorScheme.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    // Username Section
                    if (onNicknameChange != null) {
                        item(key = "username") {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Text(
                                    text = stringResource(R.string.section_username),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onBackground.copy(alpha = 0.5f),
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                                )
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    NicknameEditor(
                                        value = nickname,
                                        onValueChange = { newValue ->
                                            val filtered = newValue.filter { it in 'a'..'z' || it in '0'..'9' }.take(15)
                                            onNicknameChange(filtered)
                                        },
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Features Section - Grouped Card
                    item(key = "features") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.about_appearance).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    FeatureRow(
                                        icon = Icons.Filled.Bluetooth,
                                        title = stringResource(R.string.about_offline_mesh_title),
                                        subtitle = stringResource(R.string.about_offline_mesh_desc)
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    FeatureRow(
                                        icon = Icons.Default.Public,
                                        title = stringResource(R.string.about_online_geohash_title),
                                        subtitle = stringResource(R.string.about_online_geohash_desc)
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    FeatureRow(
                                        icon = Icons.Default.Lock,
                                        title = stringResource(R.string.about_e2e_title),
                                        subtitle = stringResource(R.string.about_e2e_desc)
                                    )
                                }
                            }
                        }
                    }

                    // Appearance Section
                    item(key = "appearance") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.section_theme),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            val themePref by com.roman.zemzeme.ui.theme.ThemePreferenceManager.themeFlow.collectAsState()
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ThemeChip(
                                        label = stringResource(R.string.about_system),
                                        selected = themePref.isSystem,
                                        onClick = { com.roman.zemzeme.ui.theme.ThemePreferenceManager.set(context, com.roman.zemzeme.ui.theme.ThemePreference.System) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ThemeChip(
                                        label = stringResource(R.string.about_light),
                                        selected = themePref.isLight,
                                        onClick = { com.roman.zemzeme.ui.theme.ThemePreferenceManager.set(context, com.roman.zemzeme.ui.theme.ThemePreference.Light) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ThemeChip(
                                        label = stringResource(R.string.about_dark),
                                        selected = themePref.isDark,
                                        onClick = { com.roman.zemzeme.ui.theme.ThemePreferenceManager.set(context, com.roman.zemzeme.ui.theme.ThemePreference.Dark) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Security Section
                    item(key = "security") {
                        val activity = LocalContext.current as? FragmentActivity
                        var appLockEnabled by remember {
                            mutableStateOf(AppLockPreferenceManager.isEnabled())
                        }
                        var setupStep by remember { mutableStateOf(AppLockSetupStep.NONE) }
                        var pinInput by remember { mutableStateOf("") }
                        var newPinFirst by remember { mutableStateOf("") }
                        var pinError by remember { mutableStateOf<String?>(null) }

                        // Pre-fetch strings for use inside non-Composable lambdas
                        val errTooShort = stringResource(R.string.app_lock_pin_min_length)
                        val errMismatch = stringResource(R.string.app_lock_pin_mismatch)
                        val errWrongPin = stringResource(R.string.app_lock_wrong_pin)
                        val bioTitle = stringResource(R.string.app_lock_biometric_title)
                        val bioSubtitle = stringResource(R.string.app_lock_biometric_subtitle)
                        val setPinLabel = stringResource(R.string.app_lock_set_pin)

                        // Biometric availability
                        val biometricManager = remember { BiometricManager.from(context) }
                        val canUseBiometric = remember {
                            biometricManager.canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) ==
                                    BiometricManager.BIOMETRIC_SUCCESS
                        }

                        // Fire biometric prompt as first step when enabling lock
                        fun launchBiometricForSetup() {
                            val act = activity ?: run { setupStep = AppLockSetupStep.SET_PIN; return }
                            val executor = ContextCompat.getMainExecutor(context)
                            val callback = object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    setupStep = AppLockSetupStep.SET_PIN
                                }
                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                    // Includes "Use PIN instead" negative button tap
                                    setupStep = AppLockSetupStep.SET_PIN
                                }
                                override fun onAuthenticationFailed() { /* wrong finger — prompt stays open */ }
                            }
                            val prompt = BiometricPrompt(act, executor, callback)
                            val info = BiometricPrompt.PromptInfo.Builder()
                                .setTitle(bioTitle)
                                .setSubtitle(bioSubtitle)
                                .setNegativeButtonText(setPinLabel)
                                .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
                                .build()
                            prompt.authenticate(info)
                        }

                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.app_lock_security_section),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                SettingsToggleRow(
                                    icon = Icons.Filled.Lock,
                                    title = stringResource(R.string.app_lock_title),
                                    subtitle = if (appLockEnabled)
                                        if (canUseBiometric) stringResource(R.string.app_lock_subtitle_on)
                                        else stringResource(R.string.app_lock_subtitle_pin)
                                    else
                                        stringResource(R.string.app_lock_subtitle_off),
                                    checked = appLockEnabled,
                                    onCheckedChange = { wantEnabled ->
                                        pinInput = ""
                                        pinError = null
                                        if (wantEnabled) {
                                            if (canUseBiometric && activity != null) {
                                                launchBiometricForSetup()
                                            } else {
                                                setupStep = AppLockSetupStep.SET_PIN
                                            }
                                        } else {
                                            setupStep = AppLockSetupStep.VERIFY_DISABLE
                                        }
                                    }
                                )
                            }
                        }

                        // ── SET_PIN dialog ──────────────────────────────────────
                        if (setupStep == AppLockSetupStep.SET_PIN) {
                            AlertDialog(
                                onDismissRequest = { setupStep = AppLockSetupStep.NONE },
                                title = { Text(stringResource(R.string.app_lock_set_pin)) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedTextField(
                                            value = pinInput,
                                            onValueChange = {
                                                if (it.length <= 6 && it.all { c -> c.isDigit() })
                                                    pinInput = it
                                            },
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done
                                            ),
                                            visualTransformation = PasswordVisualTransformation(),
                                            label = { Text(stringResource(R.string.app_lock_set_pin)) },
                                            placeholder = { Text(stringResource(R.string.placeholder_6_digits)) },
                                            isError = pinError != null,
                                            supportingText = pinError?.let {
                                                { Text(it, color = MaterialTheme.colorScheme.error) }
                                            }
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        if (pinInput.length != 6) {
                                            pinError = errTooShort
                                        } else {
                                            newPinFirst = pinInput
                                            pinInput = ""
                                            pinError = null
                                            setupStep = AppLockSetupStep.CONFIRM_PIN
                                        }
                                    }) { Text(stringResource(android.R.string.ok)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { setupStep = AppLockSetupStep.NONE }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }

                        // ── CONFIRM_PIN dialog ───────────────────────────────────
                        if (setupStep == AppLockSetupStep.CONFIRM_PIN) {
                            AlertDialog(
                                onDismissRequest = { setupStep = AppLockSetupStep.NONE },
                                title = { Text(stringResource(R.string.app_lock_confirm_pin)) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedTextField(
                                            value = pinInput,
                                            onValueChange = {
                                                if (it.length <= 6 && it.all { c -> c.isDigit() })
                                                    pinInput = it
                                            },
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done
                                            ),
                                            visualTransformation = PasswordVisualTransformation(),
                                            label = { Text(stringResource(R.string.app_lock_confirm_pin)) },
                                            isError = pinError != null,
                                            supportingText = pinError?.let {
                                                { Text(it, color = MaterialTheme.colorScheme.error) }
                                            }
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        if (pinInput == newPinFirst) {
                                            AppLockPreferenceManager.savePin(pinInput)
                                            AppLockPreferenceManager.setEnabled(true)
                                            appLockEnabled = true
                                            setupStep = AppLockSetupStep.NONE
                                        } else {
                                            pinError = errMismatch
                                            pinInput = ""
                                        }
                                    }) { Text(stringResource(android.R.string.ok)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { setupStep = AppLockSetupStep.NONE }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }

                        // ── VERIFY_DISABLE dialog ────────────────────────────────
                        if (setupStep == AppLockSetupStep.VERIFY_DISABLE) {
                            AlertDialog(
                                onDismissRequest = { setupStep = AppLockSetupStep.NONE },
                                title = { Text(stringResource(R.string.app_lock_disable_title)) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.app_lock_disable_msg))
                                        OutlinedTextField(
                                            value = pinInput,
                                            onValueChange = {
                                                if (it.length <= 6 && it.all { c -> c.isDigit() })
                                                    pinInput = it
                                            },
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done
                                            ),
                                            visualTransformation = PasswordVisualTransformation(),
                                            label = { Text(stringResource(R.string.app_lock_enter_pin)) },
                                            isError = pinError != null,
                                            supportingText = pinError?.let {
                                                { Text(it, color = MaterialTheme.colorScheme.error) }
                                            }
                                        )
                                        if (canUseBiometric && activity != null) {
                                            TextButton(onClick = {
                                                val executor =
                                                    ContextCompat.getMainExecutor(context)
                                                val callback =
                                                    object : BiometricPrompt.AuthenticationCallback() {
                                                        override fun onAuthenticationSucceeded(
                                                            result: BiometricPrompt.AuthenticationResult
                                                        ) {
                                                            AppLockPreferenceManager.setEnabled(false)
                                                            AppLockPreferenceManager.clearPin()
                                                            AppLockManager.unlock()
                                                            appLockEnabled = false
                                                            setupStep = AppLockSetupStep.NONE
                                                        }

                                                        override fun onAuthenticationError(
                                                            errorCode: Int,
                                                            errString: CharSequence
                                                        ) { /* stay in dialog */ }

                                                        override fun onAuthenticationFailed() {}
                                                    }
                                                val prompt = BiometricPrompt(
                                                    activity,
                                                    executor,
                                                    callback
                                                )
                                                val info =
                                                    BiometricPrompt.PromptInfo.Builder()
                                                        .setTitle(context.getString(R.string.app_lock_disable_title))
                                                        .setNegativeButtonText(context.getString(R.string.app_lock_use_pin))
                                                        .setAllowedAuthenticators(
                                                            BIOMETRIC_STRONG or BIOMETRIC_WEAK
                                                        )
                                                        .build()
                                                prompt.authenticate(info)
                                            }) {
                                                Icon(
                                                    Icons.Filled.Security,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(stringResource(R.string.app_lock_biometric_title))
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        if (AppLockPreferenceManager.verifyPin(pinInput)) {
                                            AppLockPreferenceManager.setEnabled(false)
                                            AppLockPreferenceManager.clearPin()
                                            AppLockManager.unlock()
                                            appLockEnabled = false
                                            setupStep = AppLockSetupStep.NONE
                                        } else {
                                            pinError = errWrongPin
                                            pinInput = ""
                                        }
                                    }) { Text(stringResource(android.R.string.ok)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { setupStep = AppLockSetupStep.NONE }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                    }

                    // Settings Section - Unified Card with Toggles
                    item(key = "settings") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.section_settings),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    // Background Mode Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Bluetooth,
                                        title = stringResource(R.string.about_background_title),
                                        subtitle = stringResource(R.string.about_background_desc),
                                        checked = backgroundEnabled,
                                        onCheckedChange = { enabled ->
                                            backgroundEnabled = enabled
                                            com.roman.zemzeme.service.MeshServicePreferences.setBackgroundEnabled(enabled)
                                            if (!enabled) {
                                                com.roman.zemzeme.service.MeshForegroundService.stop(context)
                                            } else {
                                                com.roman.zemzeme.service.MeshForegroundService.start(context)
                                            }
                                        }
                                    )
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )

                                    // BLE Mesh Toggle
                                    val bleToggleState = remember(bleEnabled, isBluetoothEnabled) {
                                        BleMeshToggleState.from(bleEnabled, isBluetoothEnabled)
                                    }

                                    var showBluetoothRequiredDialog by remember { mutableStateOf(false) }
                                    val bleRunning = transportRuntimeState?.bleRunning == true

                                    SettingsToggleRow(
                                        icon = Icons.Filled.Bluetooth,
                                        title = stringResource(R.string.setting_ble_mesh),
                                        subtitle = stringResource(bleToggleState.subtitleResId),
                                        subtitleColor = bleToggleState.subtitleColor,
                                        checked = bleToggleState.isChecked,
                                        onCheckedChange = { enabled ->
                                            if (enabled && !isBluetoothEnabled) {
                                                p2pConfig.bleEnabled = true
                                                showBluetoothRequiredDialog = true
                                            } else {
                                                p2pConfig.bleEnabled = enabled
                                                p2pScope.launch {
                                                    val meshService = attachedMeshService
                                                    if (meshService != null) {
                                                        val result = meshService.setBleEnabled(enabled)
                                                        result.onFailure { e ->
                                                            android.util.Log.e("AboutSheet", "Failed to apply BLE toggle: ${e.message}")
                                                        }
                                                    }
                                                }
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (enabled) context.getString(R.string.toast_ble_mesh_enabled) else context.getString(R.string.toast_ble_mesh_disabled),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        statusIndicator = if (bleToggleState.isChecked) {
                                            {
                                                val statusColor = if (bleRunning) {
                                                    if (isDark) Color(0xFF00F5FF) else Color(0xFF248A3D)
                                                } else {
                                                    Color(0xFFFF9500)
                                                }
                                                Surface(
                                                    color = statusColor,
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(8.dp)
                                                ) {}
                                            }
                                        } else null
                                    )

                                    if (showBluetoothRequiredDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showBluetoothRequiredDialog = false },
                                            title = { Text(stringResource(R.string.ble_setting_dialog_title)) },
                                            text = { Text(stringResource(R.string.ble_setting_dialog_msg)) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    showBluetoothRequiredDialog = false
                                                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                                }) {
                                                    Text(stringResource(R.string.ble_setting_dialog_btn))
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showBluetoothRequiredDialog = false }) {
                                                    Text(stringResource(android.R.string.cancel))
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            
                        }
                    }

                    // Refresh Icon & Name
                    item(key = "icon_switch") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.icon_switch_section).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                        }
                        var pendingSwitch by remember {
                            mutableStateOf<com.roman.zemzeme.iconswitch.IconSwitcher.SwitchResult?>(null)
                        }

                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pendingSwitch = com.roman.zemzeme.iconswitch.IconSwitcher.pickNext(context)
                                        }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.icon_switch_title),
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.icon_switch_title),
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = NunitoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.icon_switch_subtitle),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = NunitoFontFamily
                                            ),
                                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }

                        if (pendingSwitch != null) {
                            val result = pendingSwitch!!
                            val iconResId = if (result.index == 1) {
                                R.mipmap.ic_launcher
                            } else {
                                context.resources.getIdentifier(
                                    "ic_icon_${result.index}", "mipmap", context.packageName
                                )
                            }
                            AlertDialog(
                                onDismissRequest = { pendingSwitch = null },
                                icon = if (iconResId != 0) {
                                    {
                                        val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconResId)
                                        if (drawable != null) {
                                            val bitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
                                            val canvas = android.graphics.Canvas(bitmap)
                                            drawable.setBounds(0, 0, 128, 128)
                                            drawable.draw(canvas)
                                            androidx.compose.foundation.Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = result.name,
                                                modifier = Modifier.size(64.dp)
                                            )
                                        }
                                    }
                                } else null,
                                title = {
                                    Text(
                                        text = result.name,
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontFamily = NunitoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                },
                                text = {
                                    Text(
                                        text = stringResource(R.string.icon_switch_confirm),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = NunitoFontFamily
                                        )
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        com.roman.zemzeme.iconswitch.IconSwitcher.applySwitch(context, result.index)
                                        pendingSwitch = null
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.icon_switch_toast),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }) {
                                        Text(stringResource(R.string.icon_switch_apply))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingSwitch = null }) {
                                        Text(stringResource(android.R.string.cancel))
                                    }
                                }
                            )
                        }
                    }

                    // NETWORK section — Tor, P2P, Nostr
                    item(key = "network_settings") {
                        val torP2PUnsupportedMessage = context.getString(R.string.toast_p2p_tor_disabled)
                        var showNetworkRequiredDialog by remember { mutableStateOf(false) }

                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.section_network),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    // Network status warning — inside the card as first row
                                    if (!isNetworkAvailable) {
                                        val warningIcon = if (isAirplaneModeOn) Icons.Filled.AirplanemodeActive else Icons.Filled.WifiOff
                                        val warningText = if (isAirplaneModeOn) stringResource(R.string.network_airplane_mode) else stringResource(R.string.network_not_available)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFFF3B30).copy(alpha = 0.12f))
                                                .clickable {
                                                    try {
                                                        if (isAirplaneModeOn) {
                                                            context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
                                                        } else {
                                                            context.startActivity(Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                                                        }
                                                    } catch (_: Exception) {
                                                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                                    }
                                                }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = warningIcon,
                                                contentDescription = null,
                                                tint = Color(0xFFFF3B30),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = warningText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFFF3B30)
                                            )
                                        }
                                    } else if (!p2pEnabled && !nostrEnabled) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFFF9500).copy(alpha = 0.12f))
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.CloudOff,
                                                contentDescription = null,
                                                tint = Color(0xFFFF9500),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.network_transports_off_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFFF9500)
                                            )
                                        }
                                    }

                                    // P2P Network Toggle
                                    val p2pToggleState = NetworkToggleState.from(p2pEnabled, isNetworkAvailable)

                                    SettingsToggleRow(
                                        icon = Icons.Filled.Wifi,
                                        title = stringResource(R.string.setting_p2p_network),
                                        subtitle = stringResource(p2pToggleState.subtitleResId),
                                        subtitleColor = p2pToggleState.subtitleColor,
                                        checked = p2pEnabled,
                                        onCheckedChange = { enabled ->
                                            if (enabled && torMode == TorMode.ON) {
                                                TorPreferenceManager.set(context, TorMode.OFF)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    torP2PUnsupportedMessage,
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }

                                            p2pConfig.p2pEnabled = enabled

                                            if (enabled && !isNetworkAvailable) {
                                                showNetworkRequiredDialog = true
                                            }

                                            p2pScope.launch {
                                                val meshService = attachedMeshService
                                                if (meshService != null) {
                                                    val result = meshService.setP2PEnabled(enabled)
                                                    result.onFailure { e ->
                                                        android.util.Log.e("AboutSheet", "Failed to apply P2P toggle via mesh service: ${e.message}")
                                                    }
                                                } else {
                                                    // Fallback path when mesh service is not attached
                                                    if (enabled) {
                                                        com.roman.zemzeme.nostr.NostrRelayManager.isEnabled = false
                                                        com.roman.zemzeme.nostr.NostrRelayManager.getInstance(context).disconnect()
                                                        p2pTransport.start().onFailure { fallbackError ->
                                                            android.util.Log.e("AboutSheet", "P2P fallback start failed: ${fallbackError.message}")
                                                        }
                                                    } else {
                                                        p2pTransport.stop()
                                                    }
                                                }
                                            }
                                        },
                                        statusIndicator = if (p2pEnabled) {
                                            {
                                                val statusColor = when {
                                                    !isNetworkAvailable -> Color(0xFFFF3B30)
                                                    p2pRunning -> if (isDark) Color(0xFF00F5FF) else Color(0xFF248A3D)
                                                    p2pStatus == P2PNodeStatus.STARTING -> Color(0xFFFF9500)
                                                    else -> Color(0xFFFF3B30)
                                                }
                                                Surface(
                                                    color = statusColor,
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(8.dp)
                                                ) {}
                                            }
                                        } else null
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )

                                    // Nostr Relays Toggle
                                    val nostrToggleState = NetworkToggleState.from(nostrEnabled, isNetworkAvailable)

                                    SettingsToggleRow(
                                        icon = Icons.Filled.Cloud,
                                        title = stringResource(R.string.setting_nostr_relays),
                                        subtitle = stringResource(nostrToggleState.subtitleResId),
                                        subtitleColor = nostrToggleState.subtitleColor,
                                        checked = nostrEnabled,
                                        onCheckedChange = { enabled ->
                                            p2pConfig.nostrEnabled = enabled

                                            if (enabled && !isNetworkAvailable) {
                                                showNetworkRequiredDialog = true
                                            }

                                            p2pScope.launch {
                                                val meshService = attachedMeshService
                                                if (meshService != null) {
                                                    val result = meshService.setNostrEnabled(enabled)
                                                    result.onFailure { e ->
                                                        android.util.Log.e("AboutSheet", "Failed to apply Nostr toggle via mesh service: ${e.message}")
                                                    }
                                                } else {
                                                    if (enabled) {
                                                        p2pTransport.stop()
                                                        com.roman.zemzeme.nostr.NostrRelayManager.isEnabled = true
                                                        nostrRelayManager.connect()
                                                    } else {
                                                        com.roman.zemzeme.nostr.NostrRelayManager.isEnabled = false
                                                        nostrRelayManager.disconnect()
                                                    }
                                                }
                                            }
                                            android.widget.Toast.makeText(context, if (enabled) context.getString(R.string.toast_nostr_enabled) else context.getString(R.string.toast_nostr_disabled), android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        statusIndicator = if (nostrEnabled) {
                                            {
                                                val statusColor = when {
                                                    !isNetworkAvailable -> Color(0xFFFF3B30)
                                                    effectiveNostrConnected -> if (isDark) Color(0xFF00F5FF) else Color(0xFF248A3D)
                                                    else -> Color(0xFFFF9500)
                                                }
                                                Surface(
                                                    color = statusColor,
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(8.dp)
                                                ) {}
                                            }
                                        } else null
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )

                                    // Tor Toggle — routes Nostr traffic through Tor (indented as sub-option)
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Security,
                                        title = stringResource(R.string.setting_tor_nostr),
                                        subtitle = stringResource(R.string.about_tor_route),
                                        checked = torMode == TorMode.ON,
                                        onCheckedChange = { enabled ->
                                            if (torAvailable) {
                                                if (enabled && p2pEnabled) {
                                                    TorPreferenceManager.set(context, TorMode.OFF)
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        context.getString(R.string.toast_tor_p2p_unsupported),
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                    return@SettingsToggleRow
                                                }

                                                TorPreferenceManager.set(
                                                    context,
                                                    if (enabled) TorMode.ON else TorMode.OFF
                                                )
                                            }
                                        },
                                        enabled = torAvailable && isNetworkAvailable && nostrEnabled,
                                        statusIndicator = if (torMode == TorMode.ON) {
                                            {
                                                val statusColor = when {
                                                    torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF00F5FF) else Color(0xFF248A3D)
                                                    torStatus.running -> Color(0xFFFF9500)
                                                    else -> Color(0xFFFF3B30)
                                                }
                                                Surface(
                                                    color = statusColor,
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(8.dp)
                                                ) {}
                                            }
                                        } else null
                                    )
                                }
                            }

                            // Tor unavailable hint
                            if (!torAvailable) {
                                Text(
                                    text = stringResource(R.string.tor_not_available_in_this_build),
                                    fontSize = 12.sp,
                                    fontFamily = NunitoFontFamily,
                                    color = colorScheme.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                                )
                            }

                            if (showNetworkRequiredDialog) {
                                AlertDialog(
                                    onDismissRequest = { showNetworkRequiredDialog = false },
                                    title = { Text(stringResource(R.string.network_setting_dialog_title)) },
                                    confirmButton = {
                                        TextButton(onClick = { showNetworkRequiredDialog = false }) {
                                            Text(stringResource(android.R.string.ok))
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Tor Status (when enabled)
                    item(key = "tor_status") {
                        if (torMode == TorMode.ON) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val statusColor = when {
                                                torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF00F5FF) else Color(0xFF248A3D)
                                                torStatus.running -> Color(0xFFFF9500)
                                                else -> Color(0xFFFF3B30)
                                            }
                                            Surface(color = statusColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                                            Text(
                                                text = if (torStatus.running) "Connected (${torStatus.bootstrapPercent}%)" else "Disconnected",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                        }
                                        if (torStatus.lastLogLine.isNotEmpty()) {
                                            Text(
                                                text = torStatus.lastLogLine.take(120),
                                                fontSize = 11.sp,
                                                fontFamily = NunitoFontFamily,
                                                color = colorScheme.onSurface.copy(alpha = 0.5f),
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "share_app") {
                        val shareScope = rememberCoroutineScope()

                        val apkSizeBytes = remember { ApkSharingHelper.getApkSizeBytes(context) }
                        val apkSizeText = remember(apkSizeBytes) { FileUtils.formatFileSize(apkSizeBytes) }

                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.about_share_app_header),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    SettingsActionRow(
                                        icon = Icons.Filled.Share,
                                        title = stringResource(R.string.about_share_system_title),
                                        subtitle = stringResource(R.string.about_share_system_desc, apkSizeText),
                                        onClick = {
                                            shareScope.launch(Dispatchers.IO) {
                                                val intent = ApkSharingHelper.createShareIntent(context)
                                                withContext(Dispatchers.Main) {
                                                    if (intent != null) {
                                                        context.startActivity(
                                                            android.content.Intent.createChooser(
                                                                intent,
                                                                context.getString(R.string.about_share_chooser_title, context.getString(R.string.app_name))
                                                            )
                                                        )
                                                    } else {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            context.getString(R.string.about_share_failed),
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            Text(
                                text = stringResource(R.string.about_share_helper_text),
                                fontSize = 12.sp,
                                fontFamily = NunitoFontFamily,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                            )
                        }
                    }

                    // Footer
                    item(key = "footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (onShowDebug != null) {
                                TextButton(onClick = onShowDebug) {
                                    Text(
                                        text = stringResource(R.string.about_debug_settings),
                                        fontSize = 13.sp,
                                        fontFamily = NunitoFontFamily,
                                        color = colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_footer),
                                fontSize = 12.sp,
                                fontFamily = NunitoFontFamily,
                                color = colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }

                // TopBar
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha))
                ) {
                    CloseButton(
                        onClick = onDismiss,
                        modifier = modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Password prompt dialog for password-protected channels
 * Kept as dialog since it requires user input
 */
@Composable
fun PasswordPromptDialog(
    show: Boolean,
    channelName: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show && channelName != null) {
        val colorScheme = MaterialTheme.colorScheme
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.pwd_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.pwd_prompt_message, channelName ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.pwd_label), style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = NunitoFontFamily
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = stringResource(R.string.join),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
