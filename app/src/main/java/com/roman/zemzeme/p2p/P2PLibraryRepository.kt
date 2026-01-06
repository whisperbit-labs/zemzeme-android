package com.roman.zemzeme.p2p

import android.content.Context
import android.util.Log
import golib.Golib
import golib.MobileConnectionHandler
import golib.MobileMessageHandler
import golib.MobileTopicMessageHandler
import golib.MobileTopicPeerHandler
import golib.P2PNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2P message data class for incoming messages
 */
data class P2PMessage(
    val senderPeerID: String,
    val content: String,
    val isTopicMessage: Boolean = false,
    val topicName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * P2P peer info
 */
data class P2PPeer(
    val peerID: String,
    val isConnected: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * P2P connection status
 */
enum class P2PNodeStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Repository for P2P operations using the Go libp2p library (golib.aar).
 * 
 * This serves as the bridge between BitChat Android and the Go-based libp2p implementation.
 * It manages:
 * - Node lifecycle (start/stop)
 * - Direct peer messaging
 * - Topic-based messaging (for channels/geohash)
 * - Peer discovery via DHT
 * 
 * Modeled after the P2PRepository pattern from mobile_go_libp2p Android demo.
 */
class P2PLibraryRepository(
    private val context: Context
) {
    companion object {
        private const val TAG = "P2PLibraryRepository"
        private const val PREFS_NAME = "p2p_prefs"
        private const val KEY_PRIVATE_KEY = "p2p_private_key"
        private const val KEY_PEER_ID = "p2p_peer_id"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var node: P2PNode? = null
    
    // Preferences for key storage
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // State flows
    private val _nodeStatus = MutableStateFlow(P2PNodeStatus.STOPPED)
    val nodeStatus: StateFlow<P2PNodeStatus> = _nodeStatus.asStateFlow()
    
    private val _peerID = MutableStateFlow<String?>(null)
    val peerID: StateFlow<String?> = _peerID.asStateFlow()
    
    private val _multiaddrs = MutableStateFlow("")
    val multiaddrs: StateFlow<String> = _multiaddrs.asStateFlow()
    
    private val _dhtStatus = MutableStateFlow("")
    val dhtStatus: StateFlow<String> = _dhtStatus.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<List<P2PPeer>>(emptyList())
    val connectedPeers: StateFlow<List<P2PPeer>> = _connectedPeers.asStateFlow()
    
    private val _incomingMessages = MutableSharedFlow<P2PMessage>(replay = 0, extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<P2PMessage> = _incomingMessages.asSharedFlow()
    
    // Track subscribed topics
    private val subscribedTopics = mutableSetOf<String>()
    
    // DHT status refresh job
    private var dhtRefreshJob: kotlinx.coroutines.Job? = null
    
    // ============== Lifecycle ==============
    
    /**
     * Start the P2P node with the stored (or newly generated) identity.
     * 
     * @param privateKeyBase64 Optional external private key (e.g., derived from BitChat identity)
     */
    suspend fun startNode(privateKeyBase64: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        if (_nodeStatus.value == P2PNodeStatus.RUNNING) {
            Log.d(TAG, "P2P node already running, skipping start")
            return@withContext Result.success(Unit)
        }
        
        _nodeStatus.value = P2PNodeStatus.STARTING
        Log.i(TAG, "🚀 Starting P2P node...")
        
        try {
            // Verify golib is available
            Log.d(TAG, "Checking golib availability...")
            try {
                val golibClass = Class.forName("golib.Golib")
                Log.d(TAG, "✓ golib.Golib class found: ${golibClass.name}")
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "❌ golib.Golib class NOT FOUND - golib.aar may not be properly linked", e)
                _nodeStatus.value = P2PNodeStatus.ERROR
                return@withContext Result.failure(Exception("golib library not found"))
            }
            
            // Get or generate private key
            // NOTE: We ignore the external privateKeyBase64 parameter because BitChat's 
            // identity manager generates raw Ed25519 keys, but golib expects protobuf-marshaled 
            // libp2p keys (via crypto.MarshalPrivateKey). We use our own key management instead.
            val privateKey = getStoredPrivateKey() ?: generateAndStoreNewKey()
            
            Log.d(TAG, "Private key obtained (length: ${privateKey.length})")
            
            // Create node with port 0 (random available port)
            Log.d(TAG, "Creating P2P node with Golib.newP2PNode()...")
            val newNode = Golib.newP2PNode(privateKey, 0)
            Log.d(TAG, "✓ P2P node created successfully")
            
            // Set up message handler
            newNode.setMessageHandler(object : MobileMessageHandler {
                override fun onMessage(peerID: String?, message: String?) {
                    if (peerID != null && message != null) {
                        scope.launch {
                            _incomingMessages.emit(P2PMessage(
                                senderPeerID = peerID,
                                content = message,
                                isTopicMessage = false
                            ))
                        }
                        Log.d(TAG, "Message from $peerID: ${message.take(50)}...")
                    }
                }
            })
            
            // Set up connection handler
            newNode.setConnectionHandler(object : MobileConnectionHandler {
                override fun onConnected(peerID: String?) {
                    peerID?.let { id ->
                        updatePeerState(id, true)
                        Log.d(TAG, "Peer connected: $id")
                    }
                }
                
                override fun onDisconnected(peerID: String?) {
                    peerID?.let { id ->
                        updatePeerState(id, false)
                        Log.d(TAG, "Peer disconnected: $id")
                    }
                }
            })
            
            // Set up topic message handler
            newNode.setTopicMessageHandler(object : MobileTopicMessageHandler {
                override fun onTopicMessage(topicName: String?, senderID: String?, message: String?, timestamp: Long) {
                    if (topicName != null && senderID != null && message != null) {
                        scope.launch {
                            _incomingMessages.emit(P2PMessage(
                                senderPeerID = senderID,
                                content = message,
                                isTopicMessage = true,
                                topicName = topicName,
                                timestamp = timestamp
                            ))
                        }
                        Log.d(TAG, "Topic message [$topicName] from $senderID: ${message.take(50)}...")
                    }
                }
            })
            
            // Set up topic peer handler
            newNode.setTopicPeerHandler(object : MobileTopicPeerHandler {
                override fun onTopicPeerUpdate(topicName: String?, peerID: String?, action: String?) {
                    Log.d(TAG, "Topic peer update [$topicName]: $peerID $action")
                }
            })
            
            // Start the node
            Log.d(TAG, "Starting P2P node network...")
            newNode.start()
            Log.d(TAG, "✓ P2P node network started")
            node = newNode
            
            // Update state
            _peerID.value = newNode.peerID
            _multiaddrs.value = newNode.multiaddrs
            _nodeStatus.value = P2PNodeStatus.RUNNING
            
            // Store peer ID for reference
            prefs.edit().putString(KEY_PEER_ID, newNode.peerID).apply()
            
            // Get initial DHT status
            try {
                val initialDhtStatus = newNode.dhtStatus ?: "DHT initializing..."
                _dhtStatus.value = initialDhtStatus
                Log.i(TAG, "   Initial DHT Status: $initialDhtStatus")
            } catch (e: Exception) {
                Log.w(TAG, "   Could not get initial DHT status: ${e.message}")
            }
            
            // Start periodic DHT status refresh
            startDhtStatusRefresh()
            
            Log.i(TAG, "✅ P2P node started successfully")
            Log.i(TAG, "   Peer ID: ${newNode.peerID}")
            Log.i(TAG, "   Multiaddrs: ${newNode.multiaddrs}")
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start P2P node: ${e.message}", e)
            Log.e(TAG, "   Exception type: ${e.javaClass.simpleName}")
            e.cause?.let { cause ->
                Log.e(TAG, "   Cause: ${cause.message}")
            }
            _nodeStatus.value = P2PNodeStatus.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Stop the P2P node gracefully.
     */
    suspend fun stopNode(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Cancel DHT refresh job
            dhtRefreshJob?.cancel()
            dhtRefreshJob = null
            
            node?.stop()
            node = null
            
            _nodeStatus.value = P2PNodeStatus.STOPPED
            _connectedPeers.value = emptyList()
            subscribedTopics.clear()
            _dhtStatus.value = ""
            
            Log.d(TAG, "P2P node stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop P2P node", e)
            Result.failure(e)
        }
    }
    
    /**
     * Start periodic DHT status refresh to keep UI updated.
     * Runs every 10 seconds while the node is running.
     */
    private fun startDhtStatusRefresh() {
        dhtRefreshJob?.cancel()
        dhtRefreshJob = scope.launch {
            while (true) {
                delay(10_000L) // 10 seconds
                
                if (_nodeStatus.value != P2PNodeStatus.RUNNING) {
                    Log.d(TAG, "DHT refresh: Node not running, stopping refresh")
                    break
                }
                
                try {
                    val currentNode = node
                    if (currentNode != null) {
                        val status = currentNode.dhtStatus ?: "DHT status unavailable"
                        _dhtStatus.value = status
                        Log.d(TAG, "DHT Status refresh: $status")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DHT status refresh failed: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Started periodic DHT status refresh (10s interval)")
    }
    
    // ============== Peer Operations ==============
    
    /**
     * Connect to a peer by their Peer ID (uses DHT lookup).
     */
    suspend fun connectToPeer(peerID: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.connectToPeerByID(peerID)
            updatePeerState(peerID, true)
            Log.d(TAG, "Connected to peer: $peerID")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to peer $peerID", e)
            Result.failure(e)
        }
    }
    
    /**
     * Connect to a peer using their full multiaddr.
     */
    suspend fun connectToMultiaddr(multiaddr: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.connectToPeer(multiaddr)
            Log.d(TAG, "Connected to multiaddr: $multiaddr")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to multiaddr $multiaddr", e)
            Result.failure(e)
        }
    }
    
    /**
     * Find peer info by ID (without connecting).
     * Returns list of addresses if found.
     */
    suspend fun findPeerInfo(peerID: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            val result = n.findPeerInfo(peerID)
            
            if (result.startsWith("FOUND|")) {
                val addrs = result.removePrefix("FOUND|")
                    .split(",")
                    .filter { it.isNotBlank() }
                Result.success(addrs)
            } else {
                val reason = result.removePrefix("NOTFOUND|")
                Result.failure(Exception(reason))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find peer $peerID", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send a direct message to a connected peer.
     */
    suspend fun sendMessage(peerID: String, message: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.sendMessage(peerID, message)
            Log.d(TAG, "Sent message to $peerID: ${message.take(50)}...")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to $peerID", e)
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from a peer.
     */
    suspend fun disconnectPeer(peerID: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.disconnect(peerID)
            updatePeerState(peerID, false)
            Log.d(TAG, "Disconnected from peer: $peerID")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect from $peerID", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if we have an active stream with a peer.
     */
    fun hasActiveStream(peerID: String): Boolean {
        return node?.hasActiveStream(peerID) ?: false
    }
    
    /**
     * Check if connected to a specific peer.
     */
    fun isConnected(peerID: String): Boolean {
        return node?.isConnected(peerID) ?: false
    }
    
    // ============== Topic Operations ==============
    
    /**
     * Subscribe to a topic for pub/sub messaging.
     * Topics are used for channels (like #general) or geohash-based messaging.
     */
    suspend fun subscribeTopic(topicName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.subscribeTopic(topicName)
            subscribedTopics.add(topicName)
            Log.d(TAG, "Subscribed to topic: $topicName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to topic $topicName", e)
            Result.failure(e)
        }
    }
    
    /**
     * Unsubscribe from a topic.
     */
    suspend fun unsubscribeTopic(topicName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.unsubscribeTopic(topicName)
            subscribedTopics.remove(topicName)
            Log.d(TAG, "Unsubscribed from topic: $topicName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unsubscribe from topic $topicName", e)
            Result.failure(e)
        }
    }
    
    /**
     * Publish a message to a topic.
     */
    suspend fun publishToTopic(topicName: String, message: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.publishToTopic(topicName, message)
            Log.d(TAG, "Published to topic $topicName: ${message.take(50)}...")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish to topic $topicName", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get peers in a topic mesh.
     */
    fun getTopicPeers(topicName: String): List<String> {
        val peersStr = node?.getTopicPeers(topicName) ?: return emptyList()
        return peersStr.split("\n").filter { it.isNotBlank() }
    }
    
    /**
     * Get list of currently subscribed topics.
     */
    fun getSubscribedTopics(): List<String> = subscribedTopics.toList()
    
    // ============== DHT / Network Status ==============
    
    /**
     * Get current DHT status as a string.
     */
    fun getDHTStatus(): String {
        return node?.dhtStatus ?: "Node not started"
    }
    
    /**
     * Refresh DHT routing table.
     */
    suspend fun refreshDHT(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.refreshDHT()
            _dhtStatus.value = n.dhtStatus
            Log.d(TAG, "DHT refreshed: ${_dhtStatus.value}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh DHT", e)
            Result.failure(e)
        }
    }
    
    /**
     * Announce ourselves on the DHT.
     */
    suspend fun announce(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val n = node ?: return@withContext Result.failure(Exception("Node not started"))
            n.announce()
            Log.d(TAG, "Announced on DHT")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to announce", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get our public address as observed by bootstrap peers.
     */
    fun getMyPublicAddress(): String {
        return node?.getMyPublicAddress() ?: "Not available"
    }
    
    // ============== Key Management ==============
    
    private fun getStoredPrivateKey(): String? {
        return prefs.getString(KEY_PRIVATE_KEY, null)
    }
    
    private fun generateAndStoreNewKey(): String {
        val newKey = Golib.generateNewKey()
        prefs.edit().putString(KEY_PRIVATE_KEY, newKey).apply()
        Log.d(TAG, "Generated and stored new P2P key")
        return newKey
    }
    
    /**
     * Set a specific private key (e.g., derived from BitChat identity).
     * Must be called before startNode() to take effect.
     */
    fun setPrivateKey(privateKeyBase64: String) {
        prefs.edit().putString(KEY_PRIVATE_KEY, privateKeyBase64).apply()
    }
    
    /**
     * Get the stored Peer ID (may return null if node hasn't been started).
     */
    fun getStoredPeerID(): String? {
        return prefs.getString(KEY_PEER_ID, null)
    }
    
    /**
     * Clear all P2P keys (for identity reset).
     */
    fun clearKeys() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared P2P keys")
    }
    
    // ============== Internal ==============
    
    private fun updatePeerState(peerID: String, connected: Boolean) {
        val current = _connectedPeers.value.toMutableList()
        val index = current.indexOfFirst { it.peerID == peerID }
        
        if (connected) {
            if (index >= 0) {
                current[index] = P2PPeer(peerID, true, System.currentTimeMillis())
            } else {
                current.add(P2PPeer(peerID, true, System.currentTimeMillis()))
            }
        } else {
            if (index >= 0) {
                current.removeAt(index)
            }
        }
        
        _connectedPeers.value = current
    }
}
