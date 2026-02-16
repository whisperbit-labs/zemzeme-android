package com.roman.zemzeme.p2p

import android.content.Context
import android.util.Base64
import android.util.Log
import com.roman.zemzeme.model.ZemzemeFilePacket
import com.roman.zemzeme.nostr.NostrTransport
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2P Transport Layer for Zemzeme
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

    /** Chunk size for P2P media transfers (200 KB of raw binary per chunk). */
    private val MEDIA_CHUNK_SIZE = 200 * 1024

    /** Separate assembler for incoming P2P media chunks. */
    private val chunkAssembler = P2PChunkAssembler()
    
    // Gson is thread-safe with default configuration, but we use lazy init for safety
    private val gson: Gson by lazy { Gson() }
    
    // Repository for P2P operations
    val p2pRepository: P2PLibraryRepository by lazy {
        P2PLibraryRepository(context)
    }
    
    // Nostr transport for fallback
    private val nostrTransport: NostrTransport by lazy {
        NostrTransport.getInstance(context)
    }
    
    // Mapping: Zemzeme Peer ID → libp2p Peer ID
    // In the future this could be extended to resolve via Nostr metadata
    private val peerIdMapping = ConcurrentHashMap<String, String>()
    
    // Callback for incoming P2P messages
    private val messageCallback = AtomicReference<((P2PIncomingMessage) -> Unit)?>(null)
    
    init {
        // Listen for incoming P2P messages
        p2pRepository.incomingMessages
            .onEach { p2pMessage ->
                handleIncomingP2PMessage(p2pMessage)
            }
            .launchIn(scope)
    }
    
    /**
     * Data class for incoming messages from P2P.
     * When [fileBytes] is non-null the message carries media; [content] will be empty.
     */
    data class P2PIncomingMessage(
        val senderPeerID: String,
        val content: String,
        val type: P2PMessageType,
        val topicName: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val senderNickname: String? = null,
        // ── media payload (null = text message) ─────────────────────────
        val fileBytes: ByteArray? = null,
        val fileName: String? = null,
        val contentType: String? = null
    )
    
    enum class P2PMessageType {
        DIRECT_MESSAGE,
        TOPIC_MESSAGE,
        CHANNEL_MESSAGE
    }
    
    /**
     * P2P wire message format.
     * Wraps Zemzeme message content with type information.
     * All media fields are optional (null = text message).
     */
    data class P2PWireMessage(
        val type: String,               // "dm", "channel", "topic"
        val content: String,            // Text content, or "" for media messages
        val messageID: String,          // Unique message ID
        val senderNickname: String?,    // Optional sender nickname
        val timestamp: Long,            // Unix timestamp
        // ── media fields (null for text messages) ──────────────────────
        val contentType: String? = null,  // e.g. "image/jpeg", "audio/mp4"
        val fileName: String? = null,
        val fileSize: Long? = null,
        val chunkId: String? = null,      // Stable UUID across all chunks for one file
        val chunkIndex: Int? = null,      // 0-based chunk index
        val totalChunks: Int? = null,
        val fileData: String? = null      // Base64-encoded chunk bytes
    )
    
    // ============== Initialization ==============
    
    /**
     * Start the P2P transport layer.
     * This should be called when Zemzeme mesh service starts.
     * 
     * @param privateKeyBase64 Optional P2P key (derived from Zemzeme identity)
     */
    suspend fun start(privateKeyBase64: String? = null): Result<Unit> {
        Log.i(TAG, "Starting P2P transport...")
        return p2pRepository.startNode(privateKeyBase64)
    }
    
    /**
     * Stop the P2P transport layer.
     */
    suspend fun stop(): Result<Unit> {
        Log.i(TAG, "Stopping P2P transport...")
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
     * @param recipientZemzemeID Zemzeme peer ID of recipient
     * @param recipientNickname Recipient's nickname
     * @param messageID Unique message ID
     * @param senderNickname Sender's nickname
     * @return Result indicating success and which transport was used
     */
    suspend fun sendPrivateMessage(
        content: String,
        recipientZemzemeID: String,
        recipientNickname: String,
        messageID: String,
        senderNickname: String? = null
    ): Result<TransportUsed> = withContext(Dispatchers.IO) {
        
        // Check if we have a P2P mapping for this peer and if P2P is available
        val p2pPeerID = peerIdMapping[recipientZemzemeID]
        
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
                Log.i(TAG, "Message sent via P2P to $recipientZemzemeID")
                return@withContext Result.success(TransportUsed.P2P)
            } else {
                Log.w(TAG, "P2P send failed, falling back to Nostr: ${p2pResult.exceptionOrNull()?.message}")
            }
        }
        
        // Fall back to Nostr
        try {
            nostrTransport.sendPrivateMessage(
                content = content,
                to = recipientZemzemeID,
                recipientNickname = recipientNickname,
                messageID = messageID
            )
            Log.i(TAG, "Message sent via Nostr to $recipientZemzemeID")
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
    suspend fun sendDirectMessage(
        rawPeerID: String,
        content: String,
        senderNickname: String,
        messageID: String
    ): Boolean {
        if (!isRunning()) {
            Log.w(TAG, "sendDirectMessage failed: P2P not running")
            return false
        }
        
        Log.i(TAG, "Sending P2P DM to ${rawPeerID.take(12)}... (content: ${content.take(30)}...)")
        
        return try {
            val wireMessage = P2PWireMessage(
                type = "dm",
                content = content,
                messageID = messageID,
                senderNickname = senderNickname,
                timestamp = System.currentTimeMillis()
            )
            
            // Use IO dispatcher for async network operations (prevents UI freeze)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // First: try to connect to peer if not already connected
                if (!p2pRepository.isConnected(rawPeerID)) {
                    Log.i(TAG, "Not connected to ${rawPeerID.take(12)}..., attempting connection via DHT...")
                    val connectResult = p2pRepository.connectToPeer(rawPeerID)
                    if (connectResult.isFailure) {
                        Log.w(TAG, "Failed to connect to peer: ${connectResult.exceptionOrNull()?.message}")
                        // Try sending anyway - sometimes connections are established dynamically
                    } else {
                        Log.i(TAG, "Connected to ${rawPeerID.take(12)}...")
                    }
                } else {
                    Log.i(TAG, "Already connected to ${rawPeerID.take(12)}...")
                }
                
                // Now send the message
                val result = p2pRepository.sendMessage(rawPeerID, gson.toJson(wireMessage))
                if (result.isSuccess) {
                    Log.i(TAG, "P2P DM sent successfully to ${rawPeerID.take(12)}...")
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
    
    // ============== Media Sending ==============

    /**
     * Send a [ZemzemeFilePacket] to a P2P peer (DM) or channel topic, asynchronously.
     *
     * The file is Base64-encoded and split into ≤200 KB chunks, each wrapped in a
     * [P2PWireMessage] with media fields.  For DMs ([channelOrNull] == null) the chunks
     * are sent directly to [rawPeerID]; for channel sends they are published to the
     * channel's GossipSub topic.
     *
     * @param rawPeerID     libp2p Peer ID without the "p2p:" prefix (for DMs).
     * @param filePacket    The file to send.
     * @param channelOrNull Channel/topic name, or null for a private DM.
     * @param messageID     Stable ID for this media message.
     * @param senderNickname Our display nickname to embed in each chunk.
     * @param onComplete    Called on the IO dispatcher with true on success, false on failure.
     */
    fun sendMediaAsync(
        rawPeerID: String,
        filePacket: ZemzemeFilePacket,
        channelOrNull: String?,
        messageID: String,
        senderNickname: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        scope.launch {
            val success = sendMedia(rawPeerID, filePacket, channelOrNull, messageID, senderNickname)
            onComplete?.invoke(success)
        }
    }

    private suspend fun sendMedia(
        rawPeerID: String,
        filePacket: ZemzemeFilePacket,
        channelOrNull: String?,
        messageID: String,
        senderNickname: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isRunning()) {
            Log.w(TAG, "sendMedia failed: P2P not running")
            return@withContext false
        }

        val fileBytes = filePacket.encode() ?: run {
            Log.e(TAG, "sendMedia: failed to encode ZemzemeFilePacket")
            return@withContext false
        }

        // Split into ≤200 KB chunks
        val chunkId = UUID.randomUUID().toString()
        val totalChunks = (fileBytes.size + MEDIA_CHUNK_SIZE - 1) / MEDIA_CHUNK_SIZE

        Log.i(TAG, "sendMedia: ${filePacket.fileName} ${fileBytes.size} bytes → $totalChunks chunk(s) chunkId=${chunkId.take(8)}")

        for (i in 0 until totalChunks) {
            val start = i * MEDIA_CHUNK_SIZE
            val end = minOf(start + MEDIA_CHUNK_SIZE, fileBytes.size)
            val chunkBytes = fileBytes.copyOfRange(start, end)
            val chunkB64 = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

            val wireMessage = P2PWireMessage(
                type = if (channelOrNull != null) "channel" else "dm",
                content = "",
                messageID = messageID,
                senderNickname = senderNickname,
                timestamp = System.currentTimeMillis(),
                contentType = filePacket.mimeType,
                fileName = filePacket.fileName,
                fileSize = filePacket.fileSize,
                chunkId = chunkId,
                chunkIndex = i,
                totalChunks = totalChunks,
                fileData = chunkB64
            )

            val json = gson.toJson(wireMessage)

            val result = if (channelOrNull != null) {
                p2pRepository.publishToTopic(channelOrNull, json)
            } else {
                // Ensure connection before sending
                if (!p2pRepository.isConnected(rawPeerID)) {
                    p2pRepository.connectToPeer(rawPeerID)
                }
                p2pRepository.sendMessage(rawPeerID, json)
            }

            if (result.isFailure) {
                Log.w(TAG, "sendMedia chunk $i failed: ${result.exceptionOrNull()?.message}")
                return@withContext false
            }

            Log.d(TAG, "sendMedia chunk $i/$totalChunks sent (${chunkBytes.size} bytes)")
        }

        Log.i(TAG, "sendMedia: all $totalChunks chunk(s) sent for ${filePacket.fileName}")
        true
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
     * Register a mapping between Zemzeme peer ID and libp2p Peer ID.
     * Called when we discover a peer's P2P identity (e.g., via Nostr metadata or handshake).
     */
    fun registerPeerMapping(zemzemePeerID: String, libp2pPeerID: String) {
        peerIdMapping[zemzemePeerID] = libp2pPeerID
        Log.i(TAG, "Registered peer mapping: $zemzemePeerID → $libp2pPeerID")
    }
    
    /**
     * Try to connect to a peer via P2P using their libp2p Peer ID.
     */
    suspend fun connectToPeer(zemzemePeerID: String): Result<Unit> {
        val p2pPeerID = peerIdMapping[zemzemePeerID]
            ?: return Result.failure(Exception("No P2P ID known for peer $zemzemePeerID"))
        
        return p2pRepository.connectToPeer(p2pPeerID)
    }
    
    /**
     * Get the libp2p Peer ID for a Zemzeme peer, if known.
     */
    fun getP2PPeerID(zemzemePeerID: String): String? {
        return peerIdMapping[zemzemePeerID]
    }
    
    // ============== Message Callbacks ==============
    
    /**
     * Set callback for incoming P2P messages.
     */
    fun setMessageCallback(callback: (P2PIncomingMessage) -> Unit) {
        messageCallback.set(callback)
    }
    
    private fun handleIncomingP2PMessage(message: P2PMessage) {
        Log.i(TAG, "handleIncomingP2PMessage: isTopicMessage=${message.isTopicMessage}, topicName=${message.topicName}, content=${message.content.take(30)}...")

        try {
            val wireMessage = gson.fromJson(message.content, P2PWireMessage::class.java)

            // ── Media chunk ──────────────────────────────────────────────────
            if (wireMessage.contentType != null && wireMessage.fileData != null) {
                val chunkId = wireMessage.chunkId ?: run {
                    Log.w(TAG, "Media message missing chunkId, dropping")
                    return
                }
                val chunkIndex = wireMessage.chunkIndex ?: 0
                val totalChunks = wireMessage.totalChunks ?: 1
                val contentType = wireMessage.contentType
                val fileName = wireMessage.fileName ?: "file"

                val chunkBytes = try {
                    Base64.decode(wireMessage.fileData, Base64.DEFAULT)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to Base64-decode media chunk: ${e.message}")
                    return
                }

                val assembled = chunkAssembler.addChunk(
                    chunkId, chunkIndex, totalChunks, contentType, fileName, chunkBytes
                )

                if (assembled != null) {
                    val msgType = when (wireMessage.type) {
                        "channel" -> P2PMessageType.CHANNEL_MESSAGE
                        "topic", "geohash" -> P2PMessageType.TOPIC_MESSAGE
                        else -> P2PMessageType.DIRECT_MESSAGE
                    }
                    val incomingMedia = P2PIncomingMessage(
                        senderPeerID = message.senderPeerID,
                        content = "",
                        type = msgType,
                        topicName = message.topicName,
                        timestamp = wireMessage.timestamp,
                        senderNickname = wireMessage.senderNickname,
                        fileBytes = assembled.bytes,
                        fileName = assembled.fileName,
                        contentType = assembled.contentType
                    )
                    Log.i(TAG, "P2P media assembled (${assembled.bytes.size} bytes) from ${message.senderPeerID}")
                    messageCallback.get()?.invoke(incomingMedia)
                }
                return
            }

            // ── Text message ─────────────────────────────────────────────────
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
                timestamp = wireMessage.timestamp,
                senderNickname = wireMessage.senderNickname
            )

            Log.i(TAG, "Parsed wire message: type=${wireMessage.type} -> ${incomingMessage.type}")
            messageCallback.get()?.invoke(incomingMessage)
            Log.i(TAG, "Received P2P message from ${message.senderPeerID}: ${wireMessage.content.take(50)}...")

        } catch (e: Exception) {
            // Message not in our wire format (raw text fallback)
            val computedType = if (message.isTopicMessage) P2PMessageType.TOPIC_MESSAGE else P2PMessageType.DIRECT_MESSAGE
            Log.i(TAG, "JSON parse failed, using raw: isTopicMessage=${message.isTopicMessage} -> $computedType")

            val incomingMessage = P2PIncomingMessage(
                senderPeerID = message.senderPeerID,
                content = message.content,
                type = computedType,
                topicName = message.topicName,
                timestamp = message.timestamp
            )

            messageCallback.get()?.invoke(incomingMessage)
            Log.i(TAG, "Received raw P2P message from ${message.senderPeerID}")
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
