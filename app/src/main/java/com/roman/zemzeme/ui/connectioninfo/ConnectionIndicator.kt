package com.roman.zemzeme.ui.connectioninfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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

private val GreenConnected = Color(0xFF4CAF50)
private val OrangeSearching = Color(0xFFFF9500)
private val BlueConnecting = Color(0xFF2196F3)
private val RedError = Color(0xFFFF3B30)
private val PurpleNostr = Color(0xFF9C27B0)

@Composable
fun PeerConnectionIndicator(
    data: ConnectionDisplayData.PeerConnection,
    modifier: Modifier = Modifier
) {
    if (!data.isConnected && !data.isP2P && !data.isNostr) return

    val (dotColor, label) = when {
        data.isConnected && data.isP2P -> GreenConnected to stringResource(R.string.connection_connected)
        data.isConnected && data.isNostr -> GreenConnected to stringResource(R.string.connection_connected)
        data.isConnected && data.isDirect -> GreenConnected to stringResource(R.string.connection_direct)
        data.isConnected -> GreenConnected to stringResource(R.string.connection_connected)
        data.isP2P -> OrangeSearching to stringResource(R.string.connection_not_connected)
        data.isNostr -> OrangeSearching to stringResource(R.string.connection_not_connected)
        else -> return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(6.dp).background(dotColor, CircleShape))
        Text(text = label, fontFamily = NunitoFontFamily, fontSize = 10.sp, color = dotColor)
    }
}

@Composable
fun ChannelConnectionIndicator(
    data: ConnectionDisplayData.ChannelConnection,
    modifier: Modifier = Modifier,
    hasP2P: Boolean = false,
    hasNostr: Boolean = false
) {
    val (dotColor, label) = when (data.stage) {
        ConnectionDisplayData.ChannelStage.SEARCHING ->
            OrangeSearching to stringResource(R.string.connection_searching_peers)
        ConnectionDisplayData.ChannelStage.CONNECTING ->
            BlueConnecting to stringResource(R.string.connection_connecting_peers)
        ConnectionDisplayData.ChannelStage.CONNECTED ->
            GreenConnected to pluralStringResource(R.plurals.connection_peers_connected, data.peerCount, data.peerCount)
        ConnectionDisplayData.ChannelStage.NOSTR_IDLE ->
            BlueConnecting to stringResource(R.string.connection_no_new_messages)
        ConnectionDisplayData.ChannelStage.ERROR ->
            RedError to stringResource(R.string.connection_error)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(6.dp).background(dotColor, CircleShape))
        Text(text = label, fontFamily = NunitoFontFamily, fontSize = 10.sp, color = dotColor)

        // Transport badges removed from header — per-peer transport info is shown in the peers list
    }
}
