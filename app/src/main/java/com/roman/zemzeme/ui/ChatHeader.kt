package com.roman.zemzeme.ui


import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roman.zemzeme.R
import com.roman.zemzeme.core.ui.utils.singleOrTripleClickable
import com.roman.zemzeme.geohash.ChannelID
import com.roman.zemzeme.geohash.LocationChannelManager
import com.roman.zemzeme.net.ArtiTorManager
import com.roman.zemzeme.net.TorMode
import com.roman.zemzeme.nostr.NostrRelayManager
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.p2p.TopicConnectionState
import com.roman.zemzeme.ui.connectioninfo.ConnectionDisplayData
import com.roman.zemzeme.ui.connectioninfo.ConnectionIndicatorFactory

/**
 * Header components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */




@Composable
fun TorStatusDot(
    modifier: Modifier = Modifier
) {
    val torProvider = remember { ArtiTorManager.getInstance() }
    val torStatus by torProvider.statusFlow.collectAsState()
    
    if (torStatus.mode != TorMode.OFF) {
        val dotColor = when {
            torStatus.running && torStatus.bootstrapPercent < 100 -> Color(0xFFFF9500) // Orange - bootstrapping
            torStatus.running && torStatus.bootstrapPercent >= 100 -> Color(0xFF00F5FF) // Green - connected
            else -> Color.Red // Red - error/disconnected
        }
        Canvas(
            modifier = modifier
        ) {
            val radius = size.minDimension / 2
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(size.width / 2, size.height / 2)
            )
        }
    }
}

@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val (icon, color, contentDescription) = when (sessionState) {
        "uninitialized" -> Triple(
            Icons.Outlined.NoEncryption,
            Color(0x87878700), // Grey - ready to establish
            stringResource(R.string.cd_ready_for_handshake)
        )
        "handshaking" -> Triple(
            Icons.Outlined.Sync,
            Color(0x87878700), // Grey - in progress
            stringResource(R.string.cd_handshake_in_progress)
        )
        "established" -> Triple(
            Icons.Filled.Lock,
            Color(0xFFFF9500), // Orange - secure
            stringResource(R.string.cd_encrypted)
        )
        else -> { // "failed" or any other state
            Triple(
                Icons.Outlined.Warning,
                Color(0xFFFF4444), // Red - error
                stringResource(R.string.cd_handshake_failed)
            )
        }
    }
    
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = color
    )
}

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    
    // Auto-scroll to end when text changes (simulates cursor following)
    LaunchedEffect(value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.at_symbol),
            fontSize = 20.sp,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = colorScheme.primary,
                fontFamily = NunitoFontFamily,
                fontSize = 20.sp
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 160.dp)
                .horizontalScroll(scrollState)
        )
    }
}

/**
 * P2P connection status indicator dot.
 * Shows: Orange = connecting, Green = connected
 */
@Composable
fun P2PConnectionDot(
    connectionState: TopicConnectionState,
    modifier: Modifier = Modifier
) {
    val dotColor = when (connectionState) {
        TopicConnectionState.CONNECTING -> Color(0xFFFF9500) // Orange
        TopicConnectionState.CONNECTED -> Color(0xFF00F5FF) // Green
        TopicConnectionState.NO_PEERS -> Color(0xFFFF9500)
        TopicConnectionState.ERROR -> Color.Red
    }
    
    Canvas(
        modifier = modifier.size(8.dp)
    ) {
        drawCircle(
            color = dotColor,
            radius = size.minDimension / 2,
            center = Offset(size.width / 2, size.height / 2)
        )
    }
}

@Composable
fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    isConnected: Boolean,
    selectedLocationChannel: ChannelID?,
    geohashPeople: List<GeoPerson>,
    topicConnectionState: TopicConnectionState? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    // Connection-state-aware colors — must match ConnectionIndicator colors
    val greenConnected = colorScheme.primary
    val orangeSearching = Color(0xFFFF9500)
    val blueConnecting = Color(0xFF2196F3)
    val redError = Color(0xFFFF3B30)

    // Compute channel-aware people count, display text, and color
    val (displayText, countColor) = when (selectedLocationChannel) {
        is ChannelID.Location -> {
            val totalCount = geohashPeople.size
            val text = "$totalCount"

            // Color matches the connection indicator:
            // Green = connected with peers, Blue = connecting/idle, Orange = searching, Red = error
            val color = when {
                topicConnectionState == TopicConnectionState.CONNECTED && totalCount > 0 -> greenConnected
                topicConnectionState == TopicConnectionState.CONNECTED -> blueConnecting
                topicConnectionState == TopicConnectionState.ERROR -> redError
                topicConnectionState == TopicConnectionState.CONNECTING -> blueConnecting
                topicConnectionState == TopicConnectionState.NO_PEERS -> orangeSearching
                topicConnectionState == null -> Color.Gray
                else -> orangeSearching
            }
            Pair(text, color)
        }
        is ChannelID.Mesh,
        null -> {
            // Mesh channel: show Bluetooth-connected peers (excluding self)
            val count = connectedPeers.size
            Pair("$count", if (isConnected && count > 0) greenConnected else Color.Gray)
        }
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() }.padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Group,
            contentDescription = when (selectedLocationChannel) {
                is ChannelID.Location -> stringResource(R.string.cd_geohash_participants)
                else -> stringResource(R.string.cd_connected_peers)
            },
            modifier = Modifier.size(24.dp),
            tint = countColor
        )
        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = displayText,
            fontSize = 20.sp,
            color = countColor,
            fontWeight = FontWeight.Medium
        )

        if (joinedChannels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.channel_count_prefix) + "${joinedChannels.size}",
                fontSize = 20.sp,
                color = if (isConnected) Color(0xFF00F5FF) else Color.Red,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChatHeaderContent(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onBackToHome: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    when {
        currentChannel != null -> {
            // Channel header — collect connection state from all transports
            val context = LocalContext.current
            val p2pTopicStates by viewModel.p2pTopicStates.collectAsStateWithLifecycle()
            val topicState = p2pTopicStates[currentChannel]
            val transportConfig = remember { P2PConfig(context) }
            val transportToggles by remember(transportConfig) {
                P2PConfig.transportTogglesFlow
            }.collectAsStateWithLifecycle()
            val chP2pEnabled = transportToggles.p2pEnabled
            val chNostrRelayManager = remember { NostrRelayManager.getInstance(context) }
            val chNostrConnected by chNostrRelayManager.isConnected.collectAsStateWithLifecycle()
            val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()

            val p2pTopicPeers = topicState?.peers?.size ?: 0
            val p2pConnected = chP2pEnabled && p2pTopicPeers > 0

            // Deduplicate peers by ID
            val uniqueIds = geohashPeople.map { it.id }.toSet()
            val dedupedCount = uniqueIds.size
            val chHasP2P = geohashPeople.any { it.transport == TransportType.P2P }
            val chHasNostr = geohashPeople.any { it.transport == TransportType.NOSTR }
            val hasRecentPeers = dedupedCount > 0

            val chStage = when {
                p2pConnected || (chNostrConnected && hasRecentPeers) ->
                    ConnectionDisplayData.ChannelStage.CONNECTED
                chP2pEnabled && (topicState?.providerCount ?: 0) > 0 ->
                    ConnectionDisplayData.ChannelStage.CONNECTING
                chP2pEnabled ->
                    ConnectionDisplayData.ChannelStage.SEARCHING
                chNostrConnected && !hasRecentPeers ->
                    ConnectionDisplayData.ChannelStage.NOSTR_IDLE
                transportToggles.nostrEnabled ->
                    ConnectionDisplayData.ChannelStage.SEARCHING
                else ->
                    ConnectionDisplayData.ChannelStage.ERROR
            }

            ChannelHeader(
                channel = currentChannel,
                onBackClick = onBackClick,
                onLeaveChannel = { viewModel.leaveChannel(currentChannel) },
                onSidebarClick = onSidebarClick,
                channelConnectionData = ConnectionDisplayData.ChannelConnection(
                    stage = chStage,
                    peerCount = dedupedCount,
                    error = topicState?.error
                ),
                hasP2P = chHasP2P || p2pConnected,
                hasNostr = chHasNostr || chNostrConnected
            )
        }
        else -> {
            // Main header
            MainHeader(
                onBackToHome = onBackToHome,
                onSidebarClick = onSidebarClick,
                viewModel = viewModel
            )
        }
    }
}



@Composable
private fun ChannelHeader(
    channel: String,
    onBackClick: () -> Unit,
    onLeaveChannel: () -> Unit,
    onSidebarClick: () -> Unit,
    channelConnectionData: ConnectionDisplayData.ChannelConnection? = null,
    hasP2P: Boolean = false,
    hasNostr: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: back arrow + channel name + connection indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(24.dp),
                    tint = colorScheme.primary
                )
            }

            Column {
                Text(
                    text = stringResource(R.string.chat_channel_prefix, channel),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Default,
                    letterSpacing = 0.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.clickable { onSidebarClick() }
                )

                // Connection indicator below channel name
                if (channelConnectionData != null) {
                    ConnectionIndicatorFactory.ChannelIndicator(
                        data = channelConnectionData,
                        hasP2P = hasP2P,
                        hasNostr = hasNostr
                    )
                }
            }
        }

        // Right: leave button
        TextButton(onClick = onLeaveChannel) {
            Text(
                text = stringResource(R.string.chat_leave),
                fontSize = 16.sp,
                color = Color.Red
            )
        }
    }
}

@Composable
private fun MainHeader(
    onBackToHome: () -> Unit,
    onSidebarClick: () -> Unit,
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val p2pTopicStates by viewModel.p2pTopicStates.collectAsStateWithLifecycle()

    // Derive combined connection state (P2P + Nostr) for PeerCounter color
    // Must match the same logic used in LocationChannelsButton for the connection indicator
    val context = LocalContext.current
    val transportConfig = remember { P2PConfig(context) }
    val transportToggles by remember(transportConfig) {
        P2PConfig.transportTogglesFlow
    }.collectAsStateWithLifecycle()
    val nostrRelayManager = remember { NostrRelayManager.getInstance(context) }
    val nostrConnected by nostrRelayManager.isConnected.collectAsStateWithLifecycle()
    val nostrRelays by nostrRelayManager.relays.collectAsStateWithLifecycle()
    val p2pEnabled = transportToggles.p2pEnabled
    val nostrEnabled = transportToggles.nostrEnabled

    val nostrIsUp = nostrEnabled && (nostrConnected || nostrRelays.any { it.isConnected })

    val topicConnectionState = when (val channel = selectedLocationChannel) {
        is ChannelID.Location -> {
            val topicState = p2pTopicStates[channel.channel.geohash]
            val p2pPeers = topicState?.peers?.size ?: 0
            val p2pConnected = p2pEnabled && p2pPeers > 0
            val hasRecentPeers = geohashPeople.isNotEmpty()

            when {
                p2pConnected || (nostrIsUp && hasRecentPeers) ->
                    TopicConnectionState.CONNECTED
                nostrIsUp && !hasRecentPeers ->
                    TopicConnectionState.CONNECTING // Blue: connected but no peers yet
                p2pEnabled && (topicState?.providerCount ?: 0) > 0 ->
                    TopicConnectionState.CONNECTING // Blue: connecting
                p2pEnabled || nostrEnabled ->
                    TopicConnectionState.NO_PEERS // Orange: searching
                else -> TopicConnectionState.ERROR
            }
        }
        else -> null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: back button + channel name
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconButton(
                onClick = onBackToHome,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(24.dp),
                    tint = colorScheme.primary
                )
            }

            LocationChannelsButton(
                viewModel = viewModel
            )
        }

        // Right: status indicators and actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Unread private messages badge
            if (hasUnreadPrivateMessages.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = stringResource(R.string.cd_unread_private_messages),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { viewModel.openLatestUnreadPrivateChat() },
                    tint = Color(0xFFFF9500)
                )
            }

            // Status dots: Tor + PoW
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TorStatusDot(modifier = Modifier.size(12.dp))
                PoWStatusIndicator(
                    modifier = Modifier,
                    style = PoWIndicatorStyle.COMPACT
                )
            }

            // Peer counter
            PeerCounter(
                connectedPeers = connectedPeers.filter { it != viewModel.meshService.myPeerID },
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                isConnected = isConnected,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                topicConnectionState = topicConnectionState,
                onClick = onSidebarClick
            )
        }
    }
}

@Composable
private fun LocationChannelsButton(
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val context = LocalContext.current

    // Ensure transport toggle flow is initialized from persisted prefs.
    val transportConfig = remember { P2PConfig(context) }
    val transportToggles by remember(transportConfig) {
        P2PConfig.transportTogglesFlow
    }.collectAsStateWithLifecycle()
    val p2pEnabled = transportToggles.p2pEnabled
    val nostrEnabled = transportToggles.nostrEnabled

    val nostrRelayManager = remember { NostrRelayManager.getInstance(context) }
    val nostrConnected by nostrRelayManager.isConnected.collectAsStateWithLifecycle()
    val nostrRelays by nostrRelayManager.relays.collectAsStateWithLifecycle()

    // Get current channel selection from location manager
    val selectedChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val teleported by viewModel.isTeleported.collectAsStateWithLifecycle()

    // Get P2P topic states for geohash channels
    val p2pTopicStates by viewModel.p2pTopicStates.collectAsStateWithLifecycle()

    // Get group nicknames and location names for display
    val groupNicknames by viewModel.groupNicknames.collectAsStateWithLifecycle()
    val locationChannelManager = remember {
        try { LocationChannelManager.getInstance(context) } catch (_: Exception) { null }
    }
    val locationNames by locationChannelManager?.locationNames?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyMap()) }

    val (badgeText, badgeColor) = when (selectedChannel) {
        is ChannelID.Mesh -> {
            "bluetooth" to colorScheme.onSurface
        }
        is ChannelID.Location -> {
            val geohash = (selectedChannel as ChannelID.Location).channel.geohash
            val level = (selectedChannel as ChannelID.Location).channel.level
            // Try group nickname first, then location name, then fallback to geohash
            val displayName = groupNicknames[geohash]
                ?: locationNames[level]
                ?: geohash
            displayName to colorScheme.onSurface
        }
        null -> "bluetooth" to colorScheme.onSurface
    }

    // Get P2P connection state for current geohash channel
    val p2pConnectionState = when (val channel = selectedChannel) {
        is ChannelID.Location -> {
            if (p2pEnabled) {
                val topicName = channel.channel.geohash
                p2pTopicStates[topicName]?.connectionState
            } else {
                null
            }
        }
        else -> null
    }

    val nostrConnectionState = when (selectedChannel) {
        is ChannelID.Location -> {
            if (nostrEnabled) {
                when {
                    nostrConnected || nostrRelays.any { it.isConnected } -> TopicConnectionState.CONNECTED
                    nostrRelays.any { it.lastError != null } -> TopicConnectionState.ERROR
                    else -> TopicConnectionState.CONNECTING
                }
            } else {
                null
            }
        }
        else -> null
    }

    val transportConnectionState = p2pConnectionState ?: nostrConnectionState
    val needsRefresh = p2pConnectionState == TopicConnectionState.ERROR

    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = badgeText,
                fontSize = 22.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
                maxLines = 1
            )

            // P2P refresh button (visible only on error)
            if (needsRefresh) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh_p2p),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            Log.d("ChatHeader", "P2P refresh button clicked")
                            viewModel.refreshP2PConnection()
                        },
                    tint = Color(0xFFFF9500) // Orange to indicate action needed
                )
            }
        }

        // Subtitle: Group ID + connection indicator for groups, peer status for mesh
        if (selectedChannel is ChannelID.Location) {
            val geohash = (selectedChannel as ChannelID.Location).channel.geohash
            val topicState = p2pTopicStates[geohash]
            val people by viewModel.geohashPeople.collectAsStateWithLifecycle()

            // Show geohash ID between title and connection indicator
            Text(
                text = stringResource(R.string.label_group_id_fmt, geohash),
                fontSize = 11.sp,
                fontFamily = NunitoFontFamily,
                color = colorScheme.onSurface.copy(alpha = if (isDark) 0.6f else 0.45f)
            )

            // Deduplicate: same peer can appear via P2P and Nostr
            val uniquePeerIds = people.map { it.id }.toSet()
            val dedupedCount = uniquePeerIds.size
            val hasP2PPeers = people.any { it.transport == TransportType.P2P }
            val hasNostrPeers = people.any { it.transport == TransportType.NOSTR }

            val p2pTopicPeers = topicState?.peers?.size ?: 0
            val p2pConnected = p2pEnabled && p2pTopicPeers > 0
            val nostrIsUp = nostrConnectionState == TopicConnectionState.CONNECTED
            val hasRecentPeers = dedupedCount > 0

            val channelStage = when {
                p2pConnected || (nostrIsUp && hasRecentPeers) ->
                    ConnectionDisplayData.ChannelStage.CONNECTED
                p2pEnabled && (topicState?.providerCount ?: 0) > 0 ->
                    ConnectionDisplayData.ChannelStage.CONNECTING
                p2pEnabled ->
                    ConnectionDisplayData.ChannelStage.SEARCHING
                nostrIsUp && !hasRecentPeers ->
                    ConnectionDisplayData.ChannelStage.NOSTR_IDLE
                nostrEnabled ->
                    ConnectionDisplayData.ChannelStage.SEARCHING
                else ->
                    ConnectionDisplayData.ChannelStage.ERROR
            }

            // Connection status on its own line
            ConnectionIndicatorFactory.ChannelIndicator(
                data = ConnectionDisplayData.ChannelConnection(
                    stage = channelStage,
                    peerCount = dedupedCount,
                    error = topicState?.error
                ),
                hasP2P = hasP2PPeers || p2pConnected,
                hasNostr = hasNostrPeers || nostrIsUp
            )
        } else if (selectedChannel is ChannelID.Mesh || selectedChannel == null) {
            // Mesh: show peer connection status (no group ID)
            val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
            val meshPeerCount = connectedPeers.size
            val meshStage = if (meshPeerCount > 0)
                ConnectionDisplayData.ChannelStage.CONNECTED
            else if (isConnected)
                ConnectionDisplayData.ChannelStage.SEARCHING
            else
                ConnectionDisplayData.ChannelStage.SEARCHING

            ConnectionIndicatorFactory.ChannelIndicator(
                data = ConnectionDisplayData.ChannelConnection(
                    stage = meshStage,
                    peerCount = meshPeerCount
                )
            )
        }
    }
}
