package com.roman.zemzeme.p2p

import android.util.Log
import com.roman.zemzeme.mesh.BluetoothMeshService
import com.roman.zemzeme.nostr.NostrTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified Message Router for Zemzeme
 * 
 * Coordinates message delivery across multiple transports with automatic fallback:
 * 1. BLE Mesh (local proximity) - fastest, no internet required
 * 2. libp2p P2P (direct internet) - direct peer connection
 * 3. Nostr Relays (fallback) - reliable but relay-dependent
 * 
 * The router selects the best available transport based on:
 * - Peer connection status on each transport
 * - Transport priority configuration
 * - Message type requirements
 */
class UnifiedMessageRouter(
    private val meshService: BluetoothMeshService?,
    private val p2pTransport: P2PTransport,
    private val nostrTransport: NostrTransport,
    private val config: P2PConfig
) {
    companion object {
        private const val TAG = "UnifiedMessageRouter"
    }
    
    /**
     * Transport type enum for status tracking
     */
    enum class Transport {
        BLE,
        P2P,
        NOSTR,
        NONE
    }
    
    /**
     * Delivery result with transport used
     */
    data class DeliveryResult(
        val success: Boolean,
        val transport: Transport,
        val error: String? = null
    )
    
    /**
     * Transport status for UI display
     */
    data class TransportStatus(
        val ble: Boolean,
        val p2p: Boolean,
        val nostr: Boolean,
        val p2pPeers: Int = 0,
        val blePeers: Int = 0
    )
    
    // State tracking
    private val _transportStatus = MutableStateFlow(TransportStatus(false, false, false))
    val transportStatus: StateFlow<TransportStatus> = _transportStatus.asStateFlow()
    
    private val _lastUsedTransport = MutableStateFlow(Transport.NONE)
    val lastUsedTransport: StateFlow<Transport> = _lastUsedTransport.asStateFlow()
    
    /**
     * Send a private message using the best available transport.
     * Follows priority: BLE → P2P → Nostr
     */
    suspend fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String,
        senderNickname: String? = null
    ): DeliveryResult {
        val priority = config.getTransportPriority()
        
        for (transport in priority) {
            val result = when (transport) {
                P2PConfig.TransportType.BLE -> tryBleSend(content, recipientPeerID, messageID)
                P2PConfig.TransportType.P2P -> tryP2PSend(content, recipientPeerID, recipientNickname, messageID, senderNickname)
                P2PConfig.TransportType.NOSTR -> tryNostrSend(content, recipientPeerID, recipientNickname, messageID)
            }
            
            if (result.success) {
                _lastUsedTransport.value = result.transport
                Log.d(TAG, "Message sent via ${result.transport} to $recipientPeerID")
                return result
            }
        }
        
        Log.e(TAG, "All transports failed for message to $recipientPeerID")
        return DeliveryResult(false, Transport.NONE, "All transports failed")
    }
    
    /**
     * Try sending via BLE mesh
     */
    private fun tryBleSend(content: String, recipientPeerID: String, messageID: String): DeliveryResult {
        return try {
            val mesh = meshService ?: return DeliveryResult(false, Transport.BLE, "Mesh service not available")
            
            // Check if peer is reachable via BLE
            // The actual BLE send is done through the mesh service's existing APIs
            // For now, we check if the peer is in the mesh network
            // Note: BluetoothMeshService handles encryption and routing internally
            
            // BLE mesh sending is handled by the existing sendPrivateMessage in BluetoothMeshService
            // This router would coordinate with it, but actual implementation depends on
            // whether the existing sendPrivateMessage should be called here or if mesh
            // already handles it.
            
            // For integration, we assume BLE is handled at a lower level in BluetoothMeshService
            // This method returns false to fall through to P2P/Nostr
            DeliveryResult(false, Transport.BLE, "BLE routing handled by mesh service")
        } catch (e: Exception) {
            Log.w(TAG, "BLE send failed: ${e.message}")
            DeliveryResult(false, Transport.BLE, e.message)
        }
    }
    
    /**
     * Try sending via P2P (libp2p)
     */
    private suspend fun tryP2PSend(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String,
        senderNickname: String?
    ): DeliveryResult {
        return try {
            if (!p2pTransport.isRunning()) {
                return DeliveryResult(false, Transport.P2P, "P2P not running")
            }
            
            val result = p2pTransport.sendPrivateMessage(
                content = content,
                recipientZemzemeID = recipientPeerID,
                recipientNickname = recipientNickname,
                messageID = messageID,
                senderNickname = senderNickname
            )
            
            when {
                result.isSuccess && result.getOrNull() == P2PTransport.TransportUsed.P2P -> {
                    DeliveryResult(true, Transport.P2P)
                }
                result.isSuccess && result.getOrNull() == P2PTransport.TransportUsed.NOSTR -> {
                    // P2PTransport fell back to Nostr internally
                    DeliveryResult(true, Transport.NOSTR)
                }
                else -> {
                    DeliveryResult(false, Transport.P2P, result.exceptionOrNull()?.message)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2P send failed: ${e.message}")
            DeliveryResult(false, Transport.P2P, e.message)
        }
    }
    
    /**
     * Try sending via Nostr relays
     */
    private fun tryNostrSend(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String
    ): DeliveryResult {
        // Check if Nostr is enabled in config
        if (!config.nostrEnabled) {
            Log.d(TAG, "Nostr disabled in config, skipping Nostr transport")
            return DeliveryResult(false, Transport.NOSTR, "Nostr disabled")
        }
        
        return try {
            nostrTransport.sendPrivateMessage(
                content = content,
                to = recipientPeerID,
                recipientNickname = recipientNickname,
                messageID = messageID
            )
            DeliveryResult(true, Transport.NOSTR)
        } catch (e: Exception) {
            Log.w(TAG, "Nostr send failed: ${e.message}")
            DeliveryResult(false, Transport.NOSTR, e.message)
        }
    }
    
    /**
     * Publish a message to a topic/channel
     */
    suspend fun publishToTopic(
        topicName: String,
        content: String,
        messageID: String,
        senderNickname: String? = null
    ): DeliveryResult {
        // Topics are primarily handled via P2P GossipSub
        return try {
            if (!p2pTransport.isRunning()) {
                return DeliveryResult(false, Transport.P2P, "P2P not running for topic messaging")
            }
            
            p2pTransport.publishToTopic(
                topicName = topicName,
                content = content,
                messageID = messageID,
                senderNickname = senderNickname
            ).fold(
                onSuccess = { DeliveryResult(true, Transport.P2P) },
                onFailure = { DeliveryResult(false, Transport.P2P, it.message) }
            )
        } catch (e: Exception) {
            DeliveryResult(false, Transport.P2P, e.message)
        }
    }
    
    /**
     * Subscribe to a topic for receiving messages
     */
    suspend fun subscribeTopic(topicName: String): Boolean {
        return p2pTransport.subscribeTopic(topicName).isSuccess
    }
    
    /**
     * Update transport status for UI
     */
    fun updateStatus() {
        val bleActive = meshService != null && config.bleEnabled
        val p2pActive = p2pTransport.isRunning() && config.p2pEnabled
        val p2pPeers = p2pTransport.p2pRepository.connectedPeers.value.size
        
        // Check Nostr enable status from config
        val nostrActive = config.nostrEnabled
        
        _transportStatus.value = TransportStatus(
            ble = bleActive,
            p2p = p2pActive,
            nostr = nostrActive,
            p2pPeers = p2pPeers,
            blePeers = 0 // Would need to get from PeerManager
        )
    }
    
    /**
     * Get a human-readable transport name for UI display
     */
    fun getTransportDisplayName(transport: Transport): String {
        return when (transport) {
            Transport.BLE -> "Bluetooth Mesh"
            Transport.P2P -> "P2P Direct"
            Transport.NOSTR -> "Nostr Relay"
            Transport.NONE -> "Unknown"
        }
    }
    
    /**
     * Get transport icon resource name (for use in UI)
     */
    fun getTransportIconName(transport: Transport): String {
        return when (transport) {
            Transport.BLE -> "bluetooth"
            Transport.P2P -> "wifi" // or "lan" for direct connection
            Transport.NOSTR -> "cloud"
            Transport.NONE -> "help_outline"
        }
    }
}
