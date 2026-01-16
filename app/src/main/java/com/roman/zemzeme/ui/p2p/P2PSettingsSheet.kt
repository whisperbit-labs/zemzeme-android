package com.roman.zemzeme.ui.p2p

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roman.zemzeme.core.ui.component.button.CloseButton
import com.roman.zemzeme.core.ui.component.sheet.BitchatBottomSheet
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.p2p.P2PTransport
import com.roman.zemzeme.p2p.P2PNodeStatus

/**
 * P2P Settings Sheet - Configuration for libp2p networking
 * 
 * Provides UI for:
 * - Enabling/disabling P2P networking
 * - Managing bootstrap nodes
 * - Transport priority settings
 * - Connection status display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val p2pConfig = remember { P2PConfig(context) }
    val p2pTransport = remember { P2PTransport.getInstance(context) }
    
    // State
    var p2pEnabled by remember { mutableStateOf(p2pConfig.p2pEnabled) }
    var useDefaultBootstrap by remember { mutableStateOf(p2pConfig.useDefaultBootstrap) }
    var customNodes by remember { mutableStateOf(p2pConfig.customBootstrapNodes) }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var newNodeAddress by remember { mutableStateOf("") }
    
    // Collect P2P status
    val nodeStatus by p2pTransport.p2pRepository.nodeStatus.collectAsState()
    val connectedPeers by p2pTransport.p2pRepository.connectedPeers.collectAsState()
    val dhtStatus by p2pTransport.p2pRepository.dhtStatus.collectAsState()

    // Recovery state
    var isRecovering by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Header
                    item(key = "header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "P2P Networking",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onBackground
                            )
                            Text(
                                text = "Direct peer-to-peer connections via libp2p",
                                fontSize = 13.sp,
                                color = colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                    
                    // Status Card
                    item(key = "status") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "STATUS",
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
                                Column(modifier = Modifier.padding(16.dp)) {
                                    // Check health: running but no peers = unhealthy
                                    val isHealthy = p2pTransport.p2pRepository.isHealthy()
                                    val isUnhealthy = nodeStatus == P2PNodeStatus.RUNNING && !isHealthy

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val statusColor = when {
                                            isUnhealthy -> Color(0xFFFF9500) // Orange for unhealthy
                                            nodeStatus == P2PNodeStatus.RUNNING -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                            nodeStatus == P2PNodeStatus.STARTING -> Color(0xFFFF9500)
                                            else -> Color(0xFFFF3B30)
                                        }
                                        Surface(
                                            color = statusColor,
                                            shape = CircleShape,
                                            modifier = Modifier.size(10.dp)
                                        ) {}
                                        Text(
                                            text = when {
                                                isUnhealthy -> "No Peers"
                                                nodeStatus == P2PNodeStatus.RUNNING -> "Connected"
                                                nodeStatus == P2PNodeStatus.STARTING -> "Connecting..."
                                                nodeStatus == P2PNodeStatus.STOPPED -> "Disconnected"
                                                else -> "Error"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = colorScheme.onSurface
                                        )
                                    }

                                    // Show connected peers count
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "${connectedPeers.size} peers connected",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                                    )

                                    // Show DHT status (routing table)
                                    if (dhtStatus.isNotBlank() && nodeStatus == P2PNodeStatus.RUNNING) {
                                        Text(
                                            text = "DHT: $dhtStatus",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurface.copy(alpha = 0.5f),
                                            maxLines = 2
                                        )
                                    }

                                    p2pTransport.getMyPeerID()?.let { peerID ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Peer ID: ${peerID.take(12)}...${peerID.takeLast(6)}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }

                                    // Recovery button when unhealthy
                                    if (isUnhealthy || isRecovering) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                isRecovering = true
                                                coroutineScope.launch {
                                                    try {
                                                        p2pTransport.p2pRepository.forceRecovery()
                                                    } finally {
                                                        isRecovering = false
                                                    }
                                                }
                                            },
                                            enabled = !isRecovering,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFFF9500)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isRecovering) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    color = Color.White,
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Recovering...")
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Recover Connection")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Settings
                    item(key = "settings") {
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
                                    // P2P Enabled Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Default.Wifi,
                                        title = "Enable P2P",
                                        subtitle = "Connect directly to other BitChat peers",
                                        checked = p2pEnabled,
                                        onCheckedChange = { enabled ->
                                            p2pEnabled = enabled
                                            p2pConfig.p2pEnabled = enabled
                                        },
                                        isDark = isDark
                                    )
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    
                                    // Default Bootstrap Nodes Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Default.Hub,
                                        title = "Use Default Nodes",
                                        subtitle = "Connect to public IPFS bootstrap nodes",
                                        checked = useDefaultBootstrap,
                                        onCheckedChange = { enabled ->
                                            useDefaultBootstrap = enabled
                                            p2pConfig.useDefaultBootstrap = enabled
                                        },
                                        isDark = isDark
                                    )
                                }
                            }
                        }
                    }
                    
                    // Custom Bootstrap Nodes
                    item(key = "custom_nodes_header") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CUSTOM BOOTSTRAP NODES",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onBackground.copy(alpha = 0.5f),
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                                IconButton(onClick = { showAddNodeDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add node",
                                        tint = colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    
                    // Custom nodes list
                    if (customNodes.isEmpty()) {
                        item(key = "no_custom_nodes") {
                            Surface(
                                modifier = Modifier
                                    .padding(horizontal = 20.dp)
                                    .fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = "No custom nodes configured. Tap + to add.",
                                    fontSize = 13.sp,
                                    color = colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(customNodes, key = { it }) { node ->
                            Surface(
                                modifier = Modifier
                                    .padding(horizontal = 20.dp)
                                    .fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Hub,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = node.take(50) + if (node.length > 50) "..." else "",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            p2pConfig.removeBootstrapNode(node)
                                            customNodes = p2pConfig.customBootstrapNodes
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove",
                                            tint = colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Debug Info
                    item(key = "debug") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "Active nodes: ${p2pConfig.getActiveBootstrapNodes().size}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
                
                // Top Bar
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    CloseButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
        
        // Add Node Dialog
        if (showAddNodeDialog) {
            AlertDialog(
                onDismissRequest = { showAddNodeDialog = false },
                title = {
                    Text("Add Bootstrap Node")
                },
                text = {
                    Column {
                        Text(
                            text = "Enter multiaddr for the bootstrap node:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newNodeAddress,
                            onValueChange = { newNodeAddress = it },
                            placeholder = { Text("/ip4/...") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newNodeAddress.isNotBlank()) {
                                p2pConfig.addBootstrapNode(newNodeAddress)
                                customNodes = p2pConfig.customBootstrapNodes
                                newNodeAddress = ""
                                showAddNodeDialog = false
                            }
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddNodeDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

/**
 * Settings toggle row component
 */
@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isDark: Boolean,
    enabled: Boolean = true
) {
    val colorScheme = MaterialTheme.colorScheme
    
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
 * P2P Status Indicator - Compact indicator for header/toolbar
 */
@Composable
fun P2PStatusIndicator(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val p2pTransport = remember { P2PTransport.getInstance(context) }
    val nodeStatus by p2pTransport.p2pRepository.nodeStatus.collectAsState()
    val connectedPeers by p2pTransport.p2pRepository.connectedPeers.collectAsState()
    
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    val statusColor = when (nodeStatus) {
        P2PNodeStatus.RUNNING -> if (connectedPeers.isNotEmpty()) {
            if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
        } else {
            Color(0xFFFF9500)
        }
        P2PNodeStatus.STARTING -> Color(0xFFFF9500)
        else -> Color(0xFFFF3B30).copy(alpha = 0.5f)
    }
    
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Surface(
                color = statusColor,
                shape = CircleShape,
                modifier = Modifier.size(8.dp)
            ) {}
            Text(
                text = "P2P",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (connectedPeers.isNotEmpty()) {
                Text(
                    text = "${connectedPeers.size}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
    
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            shape = RoundedCornerShape(8.dp),
            modifier = modifier
        ) {
            content()
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

/**
 * Transport Badge - Shows which transport delivered a message
 */
@Composable
fun TransportBadge(
    transport: String, // "ble", "p2p", "nostr"
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    val (icon, label, color) = when (transport.lowercase()) {
        "ble" -> Triple(Icons.Default.Bluetooth, "BLE", if (isDark) Color(0xFF5AC8FA) else Color(0xFF007AFF))
        "p2p" -> Triple(Icons.Default.Wifi, "P2P", if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D))
        "nostr" -> Triple(Icons.Default.Cloud, "Nostr", if (isDark) Color(0xFFFF9500) else Color(0xFFE65100))
        else -> Triple(Icons.Default.QuestionMark, "?", colorScheme.onSurface.copy(alpha = 0.5f))
    }
    
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}
