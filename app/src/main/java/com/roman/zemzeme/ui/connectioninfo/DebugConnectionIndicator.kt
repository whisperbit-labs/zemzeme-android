package com.roman.zemzeme.ui.connectioninfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roman.zemzeme.R

/**
 * Debug-only connection indicators.
 *
 * These composables show detailed connection info (IP, port, protocol, direction).
 * This entire file can be deleted when debug UI is no longer needed —
 * just update [ConnectionIndicatorFactory] to remove the debug branch.
 */

private val GreenConnected = Color(0xFF4CAF50)
private val OrangeSearching = Color(0xFFFF9500)
private val RedError = Color(0xFFFF3B30)

/**
 * Detailed peer connection indicator for debug mode.
 * Shows IP:port, protocol label (QUIC/TCP/WebTransport), and direction (inbound/outbound).
 */
@Composable
fun DebugPeerConnectionIndicator(
    data: ConnectionDisplayData.PeerConnection,
    modifier: Modifier = Modifier
) {
    val info = data.connectionInfo

    if (info != null) {
        // Full connection details available
        val protocolLabel = when {
            info.transport.contains("webtransport") -> "WebTransport"
            info.transport.contains("quic") -> "QUIC/UDP"
            info.transport.startsWith("tcp") -> "TCP"
            info.transport.isNotBlank() -> info.transport.uppercase()
            else -> "?"
        }
        val directionArrow = if (info.direction == "inbound") "\u2193in" else "\u2191out"

        Column(modifier = modifier) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(GreenConnected, CircleShape)
                )
                Text(
                    text = "${info.ip}:${info.port}",
                    fontFamily = NunitoFontFamily,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = GreenConnected
                )
            }
            Text(
                text = "$protocolLabel $directionArrow",
                fontFamily = NunitoFontFamily,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    } else if (data.isConnected) {
        // Connected but no detailed info available
        val label = when {
            data.isP2P -> stringResource(R.string.debug_p2p_no_info)
            data.isNostr -> stringResource(R.string.debug_nostr_relay)
            data.isDirect -> stringResource(R.string.debug_ble_direct)
            else -> stringResource(R.string.connection_connected)
        }
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(OrangeSearching, CircleShape)
            )
            Text(
                text = label,
                fontFamily = NunitoFontFamily,
                fontSize = 9.sp,
                color = OrangeSearching
            )
        }
    }
}

/**
 * Detailed channel/group connection indicator for debug mode.
 * Shows full connection lifecycle: stage, mesh peers, DHT providers, errors.
 */
@Composable
fun DebugChannelConnectionIndicator(
    data: ConnectionDisplayData.ChannelConnection,
    modifier: Modifier = Modifier
) {
    val (dotColor, stageLabel) = when (data.stage) {
        ConnectionDisplayData.ChannelStage.SEARCHING ->
            OrangeSearching to stringResource(R.string.debug_p2p_searching)
        ConnectionDisplayData.ChannelStage.CONNECTING ->
            OrangeSearching to stringResource(R.string.debug_p2p_connecting)
        ConnectionDisplayData.ChannelStage.CONNECTED ->
            GreenConnected to pluralStringResource(R.plurals.connection_peers_connected, data.peerCount, data.peerCount)
        ConnectionDisplayData.ChannelStage.NOSTR_IDLE ->
            Color(0xFF9C27B0) to stringResource(R.string.debug_nostr_no_messages)
        ConnectionDisplayData.ChannelStage.ERROR ->
            RedError to stringResource(R.string.debug_error_fmt, data.error ?: stringResource(R.string.debug_connection_failed))
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = stageLabel,
            fontFamily = NunitoFontFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = dotColor,
            maxLines = 1
        )
    }
}
