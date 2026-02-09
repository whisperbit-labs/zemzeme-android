package com.roman.zemzeme.ui.connectioninfo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

object ConnectionIndicatorFactory {

    @Composable
    fun PeerIndicator(
        data: ConnectionDisplayData.PeerConnection,
        modifier: Modifier = Modifier
    ) {
        PeerConnectionIndicator(data = data, modifier = modifier)
    }

    @Composable
    fun ChannelIndicator(
        data: ConnectionDisplayData.ChannelConnection,
        modifier: Modifier = Modifier,
        hasP2P: Boolean = false,
        hasNostr: Boolean = false
    ) {
        ChannelConnectionIndicator(data = data, modifier = modifier, hasP2P = hasP2P, hasNostr = hasNostr)
    }
}
