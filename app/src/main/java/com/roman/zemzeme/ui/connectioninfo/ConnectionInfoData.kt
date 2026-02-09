package com.roman.zemzeme.ui.connectioninfo

import com.roman.zemzeme.p2p.PeerConnectionInfo

/**
 * Display data for connection indicators.
 */
sealed interface ConnectionDisplayData {

    data class PeerConnection(
        val isConnected: Boolean,
        val isP2P: Boolean = false,
        val isNostr: Boolean = false,
        val isDirect: Boolean = false,
        val connectionInfo: PeerConnectionInfo? = null
    ) : ConnectionDisplayData

    enum class ChannelStage {
        SEARCHING,      // P2P: searching for peers via DHT
        CONNECTING,     // P2P: found peers, establishing connections
        CONNECTED,      // Active peers (P2P topic peers or Nostr senders in last 5 min)
        NOSTR_IDLE,     // Nostr relay connected but no recent messages from peers
        ERROR           // Connection error
    }

    data class ChannelConnection(
        val stage: ChannelStage = ChannelStage.SEARCHING,
        val peerCount: Int = 0,
        val error: String? = null
    ) : ConnectionDisplayData {
        val isConnected: Boolean get() = stage == ChannelStage.CONNECTED
    }
}
