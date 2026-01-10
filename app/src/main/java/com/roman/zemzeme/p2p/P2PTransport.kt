package com.roman.zemzeme.p2p

import android.content.Context
import android.util.Log
import com.roman.zemzeme.model.BitchatMessage
import com.roman.zemzeme.model.NoisePayloadType
import com.roman.zemzeme.nostr.NostrTransport
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2P Transport Layer for BitChat
 * 
 * This acts as a bridge between P2P (libp2p) and Nostr transports,
 * providing unified message routing with automatic fallback.
 * 
 * Priority order:
 * 1. BLE Mesh (local proximity) - handled by BluetoothMeshService
 * 2. libp2p P2P (direct internet) - handled by P2PLibraryRepository
 * 3. Nostr Relays (fallback) - handled by NostrTransport
 * 
 * Transport selection logic:
 * - If peer has known libp2p Peer ID and we have P2P connectivity → use P2P
 * - Otherwise → use Nostr relay
 */
class P2PTransport private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "P2PTransport"
        
        @Volatile
        private var instance: P2PTransport? = null
        
        fun getInstance(context: Context): P2PTransport {
            return instance ?: synchronized(this) {
                instance ?: P2PTransport(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    
    // Repository for P2P operations
    val p2pRepository: P2PLibraryRepository by lazy {
        P2PLibraryRepository(context)
    }
    
    // Nostr transport for fallback
    private val nostrTransport: NostrTransport by lazy {
        NostrTransport.getInstance(context)
    }
    
    // Mapping: BitChat Peer ID → libp2p Peer ID
    // In the future this could be extended to resolve via Nostr metadata
    private val peerIdMapping = mutableMapOf<String, String>()
    
    // Callback for incoming P2P messages
    private var messageCallback: ((P2PIncomingMessage) -> Unit)? = null
    
    init {
        // Listen for incoming P2P messages
        p2pRepository.incomingMessages
            .onEach { p2pMessage ->
                handleIncomingP2PMessage(p2pMessage)
            }
            .launchIn(scope)
    }
    
    /**
     * Data class for incoming messages from P2P
     */
    data class P2PIncomingMessage(
        val senderPeerID: String,
        val content: String,
        val type: P2PMessageType,
        val topicName: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    enum class P2PMessageType {
        DIRECT_MESSAGE,
        TOPIC_MESSAGE,
        CHANNEL_MESSAGE
    }
    
    /**
     * P2P wire message format.
     * Wraps BitChat message content with type information.
     */
    data class P2PWireMessage(
        val type: String,               // "dm", "channel", "topic"
        val content: String,            // Actual message content
        val messageID: String,          // Unique message ID
        val senderNickname: String?,    // Optional sender nickname
        val timestamp: Long             // Unix timestamp
    )
    
    // ============== Initialization ==============
    
    /**
     * Start the P2P transport layer.
     * This should be called when BitChat mesh service starts.
     * 
     * @param privateKeyBase64 Optional P2P key (derived from BitChat identity)
     */
    suspend fun start(privateKeyBase64: String? = null): Result<Unit> {
        Log.d(TAG, "Starting P2P transport...")
        return p2pRepository.startNode(privateKeyBase64)
    }
    
    /**
     * Stop the P2P transport layer.
     */
    suspend fun stop(): Result<Unit> {
        Log.d(TAG, "Stopping P2P transport...")
        return p2pRepository.stopNode()
    }
    
    /**
     * Check if P2P transport is running.
     */
    fun isRunning(): Boolean {
        return p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING
    }
    
    /**
     * Get our P2P Peer ID.
     */
    fun getMyPeerID(): String? {
        return p2pRepository.peerID.value
    }
    
    // ============== Message Sending ==============
    
    /**
     * Send a private message to a peer.
     * Tries P2P first, falls back to Nostr if needed.
     * 
     * @param content Message content
     * @param recipientBitchatID BitChat peer ID of recipient
     * @param recipientNickname Recipient's nickname
     * @param messageID Unique message ID
     * @param senderNickname Sender's nickname
     * @return Result indicating success and which transport was used
     */
    suspend fun sendPrivateMessage(
        content: String,
        recipientBitchatID: String,
        recipientNickname: String,
        messageID: String,
        senderNickname: String? = null
    ): Result<TransportUsed> = withContext(Dispatchers.IO) {
        
        // Check if we have a P2P mapping for this peer and if P2P is available
        val p2pPeerID = peerIdMapping[recipientBitchatID]
        
        if (p2pPeerID != null && isRunning()) {
            // Try P2P first
            val wireMessage = P2PWireMessage(
                type = "dm",
                content = content,
                messageID = messageID,
                senderNickname = senderNickname,
                timestamp = System.currentTimeMillis()
            )
            
            val p2pResult = p2pRepository.sendMessage(
                peerID = p2pPeerID,
                message = gson.toJson(wireMessage)
            )
            
            if (p2pResult.isSuccess) {
                Log.d(TAG, "Message sent via P2P to $recipientBitchatID")
                return@withContext Result.success(TransportUsed.P2P)
            } else {
                Log.w(TAG, "P2P send failed, falling back to Nostr: ${p2pResult.exceptionOrNull()?.message}")
            }
        }
        
        // Fall back to Nostr
        try {
            nostrTransport.sendPrivateMessage(
                content = content,
                to = recipientBitchatID,
                recipientNickname = recipientNickname,
                messageID = messageID
            )
            Log.d(TAG, "Message sent via Nostr to $recipientBitchatID")
            Result.success(TransportUsed.NOSTR)
        } catch (e: Exception) {
            Log.e(TAG, "Both P2P and Nostr send failed", e)
            Result.failure(e)
        }
    }
    
    enum class TransportUsed {
        BLE,
        P2P,
        NOSTR
    }
    
    /**
     * Send a direct message to a P2P peer using their raw libp2p Peer ID.
     * This is used by MessageRouter for P2P DMs (peers prefixed with "p2p:").
     * 
     * @param rawPeerID The libp2p Peer ID (without "p2p:" prefix)
     * @param content Message content
     * @param senderNickname Sender's nickname for display
     * @param messageID Unique message ID
     * @return true if message was sent, false otherwise
     */
    fun sendDirectMessage(
        rawPeerID: String,
        content: String,
        senderNickname: String,
        messageID: String
    ): Boolean {
        if (!isRunning()) {
            Log.w(TAG, "sendDirectMessage failed: P2P not running")
            return false
        }
        
        Log.d(TAG, "Sending P2P DM to ${rawPeerID.take(12)}... (content: ${content.take(30)}...)")
        
        return try {
            val wireMessage = P2PWireMessage(
                type = "dm",
                content = content,
                messageID = messageID,
                senderNickname = senderNickname,
                timestamp = System.currentTimeMillis()
            )
            
            // Use blocking call for synchronous result
            kotlinx.coroutines.runBlocking {
                // First: try to connect to peer if not already connected
                if (!p2pRepository.isConnected(rawPeerID)) {
                    Log.d(TAG, "Not connected to ${rawPeerID.take(12)}..., attempting connection via DHT...")
                    val connectResult = p2pRepository.connectToPeer(rawPeerID)
                    if (connectResult.isFailure) {
                        Log.w(TAG, "Failed to connect to peer: ${connectResult.exceptionOrNull()?.message}")
                        // Try sending anyway - sometimes connections are established dynamically
                    } else {
                        Log.d(TAG, "Connected to ${rawPeerID.take(12)}...")
                    }
                } else {
                    Log.d(TAG, "Already connected to ${rawPeerID.take(12)}...")
                }
                
                // Now send the message
                val result = p2pRepository.sendMessage(rawPeerID, gson.toJson(wireMessage))
                if (result.isSuccess) {
                    Log.d(TAG, "P2P DM sent successfully to ${rawPeerID.take(12)}...")
                    true
                } else {
                    Log.w(TAG, "P2P DM failed: ${result.exceptionOrNull()?.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "P2P sendDirectMessage exception: ${e.message}", e)
            false
        }
    }
    
    // ============== Topic/Channel Operations ==============
    
    /**
     * Subscribe to a topic (for channels or geohash-based messaging).
     */
    suspend fun subscribeTopic(topicName: String): Result<Unit> {
        if (!isRunning()) {
            return Result.failure(Exception("P2P not running"))
        }
        return p2pRepository.subscribeTopic(topicName)
    }
    
    /**
     * Publish a message to a topic.
     */
    suspend fun publishToTopic(
        topicName: String,
        content: String,
        messageID: String,
        senderNickname: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isRunning()) {
            return@withContext Result.failure(Exception("P2P not running"))
        }
        
        val wireMessage = P2PWireMessage(
            type = "topic",
            content = content,
            messageID = messageID,
            senderNickname = senderNickname,
            timestamp = System.currentTimeMillis()
        )
        
        p2pRepository.publishToTopic(topicName, gson.toJson(wireMessage))
    }
    
    /**
     * Subscribe to a geohash-based topic.
     * The topic name is derived from the geohash prefix.
     */
    suspend fun subscribeGeohash(geohash: String, precision: Int = 4): Result<Unit> {
        val topicName = "geo-${geohash.take(precision)}"
        return subscribeTopic(topicName)
    }
    
    // ============== Peer ID Mapping ==============
    
    /**
     * Register a mapping between BitChat peer ID and libp2p Peer ID.
     * Called when we discover a peer's P2P identity (e.g., via Nostr metadata or handshake).
     */
    fun registerPeerMapping(bitchatPeerID: String, libp2pPeerID: String) {
        peerIdMapping[bitchatPeerID] = libp2pPeerID
        Log.d(TAG, "Registered peer mapping: $bitchatPeerID → $libp2pPeerID")
    }
    
    /**
     * Try to connect to a peer via P2P using their libp2p Peer ID.
     */
    suspend fun connectToPeer(bitchatPeerID: String): Result<Unit> {
        val p2pPeerID = peerIdMapping[bitchatPeerID]
            ?: return Result.failure(Exception("No P2P ID known for peer $bitchatPeerID"))
        
        return p2pRepository.connectToPeer(p2pPeerID)
    }
    
    /**
     * Get the libp2p Peer ID for a BitChat peer, if known.
     */
    fun getP2PPeerID(bitchatPeerID: String): String? {
        return peerIdMapping[bitchatPeerID]
    }
    
    // ============== Message Callbacks ==============
    
    /**
     * Set callback for incoming P2P messages.
     */
    fun setMessageCallback(callback: (P2PIncomingMessage) -> Unit) {
        messageCallback = callback
    }
    
    private fun handleIncomingP2PMessage(message: P2PMessage) {
        Log.d(TAG, "handleIncomingP2PMessage: isTopicMessage=${message.isTopicMessage}, topicName=${message.topicName}, content=${message.content.take(30)}...")
        
        try {
            val wireMessage = gson.fromJson(message.content, P2PWireMessage::class.java)
            
            val incomingMessage = P2PIncomingMessage(
                senderPeerID = message.senderPeerID,
                content = wireMessage.content,
                type = when (wireMessage.type) {
                    "dm" -> P2PMessageType.DIRECT_MESSAGE
                    "channel" -> P2PMessageType.CHANNEL_MESSAGE
                    "topic", "geohash" -> P2PMessageType.TOPIC_MESSAGE
                    else -> if (message.isTopicMessage) P2PMessageType.TOPIC_MESSAGE else P2PMessageType.DIRECT_MESSAGE
                },
                topicName = message.topicName,
                timestamp = wireMessage.timestamp
            )
            
            Log.d(TAG, "Parsed wire message: type=${wireMessage.type} -> ${incomingMessage.type}")
            messageCallback?.invoke(incomingMessage)
            Log.d(TAG, "Received P2P message from ${message.senderPeerID}: ${wireMessage.content.take(50)}...")
            
        } catch (e: Exception) {
            // Message might not be in our wire format (could be raw text)
            val computedType = if (message.isTopicMessage) P2PMessageType.TOPIC_MESSAGE else P2PMessageType.DIRECT_MESSAGE
            Log.d(TAG, "JSON parse failed, using raw: isTopicMessage=${message.isTopicMessage} -> $computedType")
            
            val incomingMessage = P2PIncomingMessage(
                senderPeerID = message.senderPeerID,
                content = message.content,
                type = computedType,
                topicName = message.topicName,
                timestamp = message.timestamp
            )
            
            messageCallback?.invoke(incomingMessage)
            Log.d(TAG, "Received raw P2P message from ${message.senderPeerID}")
        }
    }
    
    // ============== Status ==============
    
    /**
     * Get transport status for display in UI.
     */
    fun getStatus(): TransportStatus {
        val p2pStatus = p2pRepository.nodeStatus.value
        val connectedPeers = p2pRepository.connectedPeers.value.size
        val dhtStatus = p2pRepository.getDHTStatus()
        
        return TransportStatus(
            isRunning = p2pStatus == P2PNodeStatus.RUNNING,
            nodeStatus = p2pStatus.name,
            myPeerID = p2pRepository.peerID.value,
            connectedPeers = connectedPeers,
            dhtStatus = dhtStatus
        )
    }
    
    data class TransportStatus(
        val isRunning: Boolean,
        val nodeStatus: String,
        val myPeerID: String?,
        val connectedPeers: Int,
        val dhtStatus: String
    )
    
    /**
     * Cleanup when no longer needed.
     */
    fun cleanup() {
        scope.launch {
            stop()
        }
    }
}
