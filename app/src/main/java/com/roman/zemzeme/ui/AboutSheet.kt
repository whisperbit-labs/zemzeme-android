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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roman.zemzeme.nostr.NostrProofOfWork
import com.roman.zemzeme.nostr.PoWPreferenceManager
import androidx.compose.ui.res.stringResource
import com.roman.zemzeme.R
import com.roman.zemzeme.core.ui.component.button.CloseButton
import com.roman.zemzeme.core.ui.component.sheet.BitchatBottomSheet
import com.roman.zemzeme.net.TorMode
import com.roman.zemzeme.net.TorPreferenceManager
import com.roman.zemzeme.net.ArtiTorManager
import com.roman.zemzeme.service.MeshServiceHolder
import com.roman.zemzeme.update.UpdateManager
import com.roman.zemzeme.update.UpdateState
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.p2p.P2PTransport
import com.roman.zemzeme.p2p.P2PNodeStatus
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
            if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
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
                color = colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.3f),
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
                checkedTrackColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
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
    onShowDebug: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
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
    
    if (isPresented) {
        BitchatBottomSheet(
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
                        // Observe update state for subtle indicator
                        val updateManager = remember { UpdateManager.getInstance(context) }
                        val updateState by updateManager.updateState.collectAsState()
                        
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
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = colorScheme.onBackground
                            )
                            
                            // Version with update status indicator
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.version_prefix, versionName ?: ""),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                                
                                // Subtle update status indicator
                                when (updateState) {
                                    is UpdateState.Downloading -> {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 1.5.dp,
                                                color = Color(0xFFFF9500)
                                            )
                                            Text(
                                                text = "updating...",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFFFF9500)
                                            )
                                        }
                                    }
                                    is UpdateState.Installing -> {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 1.5.dp,
                                                color = Color(0xFF5AC8FA)
                                            )
                                            Text(
                                                text = "installing...",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFF5AC8FA)
                                            )
                                        }
                                    }
                                    is UpdateState.PendingUserAction -> {
                                        Text(
                                            text = "tap to confirm",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFFFF9500)
                                        )
                                    }
                                    is UpdateState.Success -> {
                                        Text(
                                            text = "restart to apply",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF00C851)
                                        )
                                    }
                                    else -> {
                                        // Idle or Error - no indicator shown
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_tagline),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
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
                                text = "THEME",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            val themePref by com.bitchat.android.ui.theme.ThemePreferenceManager.themeFlow.collectAsState()
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
                                        onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.System) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ThemeChip(
                                        label = stringResource(R.string.about_light),
                                        selected = themePref.isLight,
                                        onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Light) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ThemeChip(
                                        label = stringResource(R.string.about_dark),
                                        selected = themePref.isDark,
                                        onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Dark) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Settings Section - Unified Card with Toggles
                    item(key = "settings") {
                        LaunchedEffect(Unit) { PoWPreferenceManager.init(context) }
                        val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                        val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()
                        var backgroundEnabled by remember { mutableStateOf(com.bitchat.android.service.MeshServicePreferences.isBackgroundEnabled(true)) }
                        val torMode by TorPreferenceManager.modeFlow.collectAsState()
                        val torProvider = remember { ArtiTorManager.getInstance() }
                        val torStatus by torProvider.statusFlow.collectAsState()
                        val torAvailable = remember { torProvider.isTorAvailable() }
                        val p2pConfig = remember { P2PConfig(context) }
                        val transportToggles by P2PConfig.transportTogglesFlow.collectAsState()
                        val attachedMeshService by MeshServiceHolder.meshServiceFlow.collectAsState()
                        val transportRuntimeState by produceState<com.bitchat.android.mesh.BluetoothMeshService.TransportRuntimeState?>(
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
                        val torP2PUnsupportedMessage = "P2P over Tor is not supported. Tor was disabled."

                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "SETTINGS",
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
                                            com.bitchat.android.service.MeshServicePreferences.setBackgroundEnabled(enabled)
                                            if (!enabled) {
                                                com.bitchat.android.service.MeshForegroundService.stop(context)
                                            } else {
                                                com.bitchat.android.service.MeshForegroundService.start(context)
                                            }
                                        }
                                    )
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    
                                    // Proof of Work Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Speed,
                                        title = stringResource(R.string.about_pow),
                                        subtitle = stringResource(R.string.about_pow_tip),
                                        checked = powEnabled,
                                        onCheckedChange = { PoWPreferenceManager.setPowEnabled(it) }
                                    )
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    
                                    // Tor Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Security,
                                        title = "Tor Network",
                                        subtitle = stringResource(R.string.about_tor_route),
                                        checked = torMode == TorMode.ON,
                                        onCheckedChange = { enabled ->
                                            if (torAvailable) {
                                                if (enabled && p2pEnabled) {
                                                    TorPreferenceManager.set(context, TorMode.OFF)
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Tor with P2P is not supported. Disable P2P first.",
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
                                        enabled = torAvailable,
                                        statusIndicator = if (torMode == TorMode.ON) {
                                            {
                                                val statusColor = when {
                                                    torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
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
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    
                                    // P2P Network Toggle
                                    
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Wifi,
                                        title = "P2P Network",
                                        subtitle = "Direct peer connections via libp2p",
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
                                                        // P2P and Nostr are mutually exclusive.
                                                        com.bitchat.android.nostr.NostrRelayManager.isEnabled = false
                                                        com.bitchat.android.nostr.NostrRelayManager.getInstance(context).disconnect()
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
                                                    p2pRunning -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
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
                                    
                                    // Nostr Network Toggle (for testing P2P in isolation)
                                    val nostrRelayManager = remember { com.bitchat.android.nostr.NostrRelayManager.getInstance(context) }
                                    val nostrConnected by nostrRelayManager.isConnected.collectAsState()
                                    val effectiveNostrConnected = transportRuntimeState?.nostrConnected ?: nostrConnected
                                    
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Cloud,
                                        title = "Nostr Relays",
                                        subtitle = "Internet relay messaging",
                                        checked = nostrEnabled,
                                        onCheckedChange = { enabled ->
                                            p2pConfig.nostrEnabled = enabled
                                            p2pScope.launch {
                                                val meshService = attachedMeshService
                                                if (meshService != null) {
                                                    val result = meshService.setNostrEnabled(enabled)
                                                    result.onFailure { e ->
                                                        android.util.Log.e("AboutSheet", "Failed to apply Nostr toggle via mesh service: ${e.message}")
                                                    }
                                                } else {
                                                    // Fallback path when mesh service is not attached
                                                    if (enabled) {
                                                        p2pTransport.stop()
                                                        com.bitchat.android.nostr.NostrRelayManager.isEnabled = true
                                                        nostrRelayManager.connect()
                                                    } else {
                                                        com.bitchat.android.nostr.NostrRelayManager.isEnabled = false
                                                        nostrRelayManager.disconnect()
                                                    }
                                                }
                                            }
                                            android.widget.Toast.makeText(context, if (enabled) "Nostr enabled" else "Nostr disabled", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        statusIndicator = if (nostrEnabled) {
                                            {
                                                val statusColor = if (effectiveNostrConnected) {
                                                    if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
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
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    
                                    // BLE Mesh Toggle
                                    val bleRunning = transportRuntimeState?.bleRunning == true
                                    
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Bluetooth,
                                        title = "BLE Mesh",
                                        subtitle = "Bluetooth proximity mesh",
                                        checked = bleEnabled,
                                        onCheckedChange = { enabled ->
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
                                                if (enabled) "BLE Mesh enabled" else "BLE Mesh disabled",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        statusIndicator = if (bleEnabled) {
                                            {
                                                val statusColor = if (bleRunning) {
                                                    if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
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
                                }
                            }
                            
                            // Tor unavailable hint
                            if (!torAvailable) {
                                Text(
                                    text = stringResource(R.string.tor_not_available_in_this_build),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                                )
                            }
                        }
                    }

                    // PoW Difficulty Slider (when enabled)
                    item(key = "pow_slider") {
                        val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                        val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()
                        
                        if (powEnabled) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Difficulty",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                            Text(
                                                text = "$powDifficulty bits • ${NostrProofOfWork.estimateMiningTime(powDifficulty)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        
                                        Slider(
                                            value = powDifficulty.toFloat(),
                                            onValueChange = { PoWPreferenceManager.setPowDifficulty(it.toInt()) },
                                            valueRange = 0f..32f,
                                            steps = 31,
                                            colors = SliderDefaults.colors(
                                                thumbColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                                                activeTrackColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                            )
                                        )
                                        
                                        Text(
                                            text = when {
                                                powDifficulty == 0 -> stringResource(R.string.about_pow_desc_none)
                                                powDifficulty <= 8 -> stringResource(R.string.about_pow_desc_very_low)
                                                powDifficulty <= 12 -> stringResource(R.string.about_pow_desc_low)
                                                powDifficulty <= 16 -> stringResource(R.string.about_pow_desc_medium)
                                                powDifficulty <= 20 -> stringResource(R.string.about_pow_desc_high)
                                                powDifficulty <= 24 -> stringResource(R.string.about_pow_desc_very_high)
                                                else -> stringResource(R.string.about_pow_desc_extreme)
                                            },
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Tor Status (when enabled)
                    item(key = "tor_status") {
                        val torMode by TorPreferenceManager.modeFlow.collectAsState()
                        val torProvider = remember { ArtiTorManager.getInstance() }
                        val torStatus by torProvider.statusFlow.collectAsState()

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
                                                torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
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
                                                fontFamily = FontFamily.Monospace,
                                                color = colorScheme.onSurface.copy(alpha = 0.5f),
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Emergency Warning
                    item(key = "warning") {
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth(),
                            color = colorScheme.error.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.about_emergency_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.error
                                    )
                                    Text(
                                        text = stringResource(R.string.about_emergency_tip),
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
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
                                        fontFamily = FontFamily.Monospace,
                                        color = colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_footer),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
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
                            fontFamily = FontFamily.Monospace
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
