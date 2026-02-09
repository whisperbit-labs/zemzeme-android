package com.roman.zemzeme.ui

import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roman.zemzeme.ui.theme.BASE_FONT_SIZE
import java.util.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roman.zemzeme.R
import com.roman.zemzeme.nostr.NostrRelayManager
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.ui.connectioninfo.ChannelConnectionIndicator
import com.roman.zemzeme.ui.connectioninfo.ConnectionDisplayData

/**
 * GeohashPeopleList - iOS-compatible component for displaying geohash participants
 * Shows peers discovered through Nostr ephemeral events instead of Bluetooth peers
 */

// Shared connection state colors — must match ConnectionIndicator.kt
private val GreenConnected = Color(0xFF4CAF50)
private val PurpleNostr = Color(0xFF9C27B0)

/**
 * Transport type for participants - indicates how they are connected
 */
enum class TransportType {
    NOSTR,      // Connected via Nostr relay
    P2P,        // Connected via libp2p P2P
    BLE_MESH    // Connected via BLE Mesh
}

/**
 * GeoPerson data class - matches iOS GeoPerson structure exactly
 */
data class GeoPerson(
    val id: String,           // pubkey hex (lowercased) - matches iOS
    val displayName: String,  // nickname with #suffix - matches iOS
    val lastSeen: Date,        // activity timestamp - matches iOS
    val transport: TransportType = TransportType.NOSTR  // Transport type
)

@Composable
fun GeohashPeopleList(
    viewModel: ChatViewModel,
    onTapPerson: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    // Observe geohash people from ChatViewModel
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val isTeleported by viewModel.isTeleported.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()

    // Transport state for connection status indicator (same logic as ChatHeader)
    val p2pTopicStates by viewModel.p2pTopicStates.collectAsStateWithLifecycle()
    val transportConfig = remember { P2PConfig(context) }
    val transportToggles by remember(transportConfig) {
        P2PConfig.transportTogglesFlow
    }.collectAsStateWithLifecycle()
    val nostrRelayManager = remember { NostrRelayManager.getInstance(context) }
    val nostrConnected by nostrRelayManager.isConnected.collectAsStateWithLifecycle()

    // Extract geohash for display
    val channelGeohash = when (val ch = selectedLocationChannel) {
        is com.roman.zemzeme.geohash.ChannelID.Location -> ch.channel.geohash
        else -> null
    }

    // Build connection status (same logic as ChatHeader)
    val topicState = channelGeohash?.let { if (transportToggles.p2pEnabled) p2pTopicStates[it] else null }
    val p2pTopicPeers = topicState?.peers?.size ?: 0
    val p2pConnected = transportToggles.p2pEnabled && p2pTopicPeers > 0
    val uniqueIds = geohashPeople.map { it.id }.toSet()
    val dedupedCount = uniqueIds.size
    val hasRecentPeers = dedupedCount > 0

    val connectionStage = when {
        p2pConnected || (nostrConnected && hasRecentPeers) ->
            ConnectionDisplayData.ChannelStage.CONNECTED
        transportToggles.p2pEnabled && (topicState?.providerCount ?: 0) > 0 ->
            ConnectionDisplayData.ChannelStage.CONNECTING
        transportToggles.p2pEnabled ->
            ConnectionDisplayData.ChannelStage.SEARCHING
        nostrConnected && !hasRecentPeers ->
            ConnectionDisplayData.ChannelStage.NOSTR_IDLE
        transportToggles.nostrEnabled ->
            ConnectionDisplayData.ChannelStage.SEARCHING
        else ->
            ConnectionDisplayData.ChannelStage.ERROR
    }

    Column {
        // Header matching iOS style
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.geohash_people_header),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = NunitoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (channelGeohash != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[$channelGeohash]",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = NunitoFontFamily
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }

        // Show connection status when not yet connected (searching, connecting)
        if (connectionStage != ConnectionDisplayData.ChannelStage.CONNECTED) {
            ChannelConnectionIndicator(
                data = ConnectionDisplayData.ChannelConnection(
                    stage = connectionStage,
                    peerCount = dedupedCount,
                    error = topicState?.error
                ),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }

        if (geohashPeople.isEmpty() && connectionStage == ConnectionDisplayData.ChannelStage.CONNECTED) {
            // Empty state - matches iOS "nobody around..."
            Text(
                text = stringResource(R.string.nobody_around),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = NunitoFontFamily,
                    fontSize = BASE_FONT_SIZE.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        } else if (geohashPeople.isNotEmpty()) {
            // Get current geohash identity for "me" detection
            val myHex = remember(selectedLocationChannel) {
                when (val channel = selectedLocationChannel) {
                    is com.roman.zemzeme.geohash.ChannelID.Location -> {
                        try {
                            val identity = com.roman.zemzeme.nostr.NostrIdentityBridge.deriveIdentity(
                                forGeohash = channel.channel.geohash,
                                context = viewModel.getApplication()
                            )
                            identity.publicKeyHex.lowercase()
                        } catch (e: Exception) {
                            Log.e("GeohashPeopleList", "Failed to derive identity: ${e.message}")
                            null
                        }
                    }
                    else -> null
                }
            }

            // Sort: me first, then both P2P+Nostr, then P2P only, then Nostr only, then by lastSeen
            val orderedPeople = remember(geohashPeople, myHex) {
                // Build sets of IDs per transport to detect peers on both
                val p2pIds = geohashPeople.filter { it.transport == TransportType.P2P }.map { it.id }.toSet()
                val nostrIds = geohashPeople.filter { it.transport == TransportType.NOSTR }.map { it.id }.toSet()
                val bothIds = p2pIds.intersect(nostrIds)

                geohashPeople.sortedWith { a, b ->
                    when {
                        myHex != null && a.id == myHex && b.id != myHex -> -1
                        myHex != null && b.id == myHex && a.id != myHex -> 1
                        else -> {
                            // 0 = both P2P+Nostr, 1 = P2P only, 2 = Nostr only, 3 = BLE
                            val rank = { person: GeoPerson -> when {
                                person.id in bothIds -> 0
                                person.transport == TransportType.P2P -> 1
                                person.transport == TransportType.NOSTR -> 2
                                else -> 3
                            }}
                            val byRank = rank(a) - rank(b)
                            if (byRank != 0) {
                                byRank
                            } else {
                                val byLastSeen = b.lastSeen.compareTo(a.lastSeen)
                                if (byLastSeen != 0) {
                                    byLastSeen
                                } else {
                                    val byName = a.displayName.lowercase(Locale.ROOT)
                                        .compareTo(b.displayName.lowercase(Locale.ROOT))
                                    if (byName != 0) byName else a.id.compareTo(b.id)
                                }
                            }
                        }
                    }
                }
            }

            // Compute base name collisions to decide whether to show hash suffix
            val baseNameCounts = remember(geohashPeople) {
                val counts = mutableMapOf<String, Int>()
                geohashPeople.forEach { person ->
                    val (b, _) = com.roman.zemzeme.ui.splitSuffix(person.displayName)
                    counts[b] = (counts[b] ?: 0) + 1
                }
                counts
            }

            val firstID = orderedPeople.firstOrNull()?.id

            orderedPeople.forEach { person ->
                GeohashPersonItem(
                    person = person,
                    isFirst = person.id == firstID,
                    isMe = myHex != null && person.id == myHex,
                    hasUnreadDM = unreadPrivateMessages.contains("nostr_${person.id.take(16)}"),
                    isTeleported = person.id != myHex && viewModel.isPersonTeleported(person.id),
                    isMyTeleported = person.id == myHex && isTeleported,
                    nickname = nickname,
                    colorScheme = colorScheme,
                    viewModel = viewModel,
                    showHashSuffix = (baseNameCounts[com.roman.zemzeme.ui.splitSuffix(person.displayName).first] ?: 0) > 1,
                    p2pConnected = person.transport == TransportType.P2P,
                    onTap = {
                        if (person.id != myHex) {
                            if (person.id.startsWith("p2p:")) {
                                // P2P peer - initialize P2P DM state and open sheet
                                viewModel.startP2PDM(person.id)
                                viewModel.showPrivateChatSheet(person.id)
                            } else {
                                // Nostr peer - use geohash DM
                                viewModel.startGeohashDM(person.id)
                            }
                            onTapPerson()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun GeohashPersonItem(
    person: GeoPerson,
    isFirst: Boolean,
    isMe: Boolean,
    hasUnreadDM: Boolean,
    isTeleported: Boolean,
    isMyTeleported: Boolean,
    nickname: String,
    colorScheme: ColorScheme,
    viewModel: ChatViewModel,
    showHashSuffix: Boolean,
    p2pConnected: Boolean = false,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .padding(top = if (isFirst) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon logic matching iOS exactly
        if (hasUnreadDM) {
            // Unread DM indicator (orange envelope)
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(R.string.cd_unread_message),
                modifier = Modifier.size(12.dp),
                tint = Color(0xFFFF9500) // iOS orange
            )
        } else {
            // Face icon with teleportation state
            val (iconName, iconColor) = when {
                isMe && isMyTeleported -> "face.dashed" to Color(0xFFFF9500) // Orange for teleported me
                isTeleported -> "face.dashed" to colorScheme.onSurface // Regular color for teleported others
                isMe -> "face.smiling" to Color(0xFFFF9500) // Orange for me
                else -> "face.smiling" to colorScheme.onSurface // Regular color for others
            }

            // Use appropriate Material icon (closest match to iOS SF Symbols)
            val icon = when (iconName) {
                "face.dashed" -> Icons.Outlined.Explore
                else -> Icons.Outlined.LocationOn
            }

            Icon(
                imageVector = icon,
                contentDescription = stringResource(if (isTeleported || isMyTeleported) R.string.cd_teleported_user else R.string.cd_user),
                modifier = Modifier.size(12.dp),
                tint = iconColor.copy(alpha = if (iconName == "face.dashed") 0.6f else 1.0f) // Make dashed faces slightly transparent
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Display name with suffix handling
        val (baseNameRaw, suffixRaw) = com.roman.zemzeme.ui.splitSuffix(person.displayName)
        val baseName = truncateNickname(baseNameRaw)
        val suffix = if (showHashSuffix) suffixRaw else ""

        // Get consistent peer color (matches iOS color assignment exactly)
        val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
        val assignedColor = viewModel.colorForNostrPubkey(person.id, isDark)
        val baseColor = when {
            isMe -> Color(0xFFFF9500)
            else -> assignedColor
        }

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Base name with peer-specific color
            Text(
                text = baseName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = NunitoFontFamily,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                ),
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Suffix (collision-resistant #abcd) in lighter shade
            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = NunitoFontFamily,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = baseColor.copy(alpha = 0.6f)
                )
            }

            // "You" indicator for current user
            if (isMe) {
                Text(
                    text = stringResource(R.string.you_suffix),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = NunitoFontFamily,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = baseColor
                )
            }

            // Transport badge - shows connection type per peer
            when (person.transport) {
                TransportType.P2P -> {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = GreenConnected.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "P2P",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp
                            ),
                            color = GreenConnected,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                TransportType.NOSTR -> {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = PurpleNostr.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Nostr",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp
                            ),
                            color = PurpleNostr,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                TransportType.BLE_MESH -> {
                    // No badge for mesh (it's the default/local)
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))
    }
}
