package com.roman.zemzeme.p2p

import android.content.Context
import android.util.Log
import com.roman.zemzeme.util.AppConstants
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    
    // Mutex for atomic node state transitions (prevents race conditions during startup)
    private val nodeLock = Mutex()
    
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

    // Health monitoring state for auto-recovery
    private var consecutiveZeroPeerChecks = 0
    private var lastRecoveryAttemptMs = 0L
    private var healthCheckJob: kotlinx.coroutines.Job? = null
    
    // ============== Lifecycle ==============
    
    /**
     * Start the P2P node with the stored (or newly generated) identity.
     * Uses Mutex to ensure atomic state transitions (prevents race conditions).
     * 
     * @param privateKeyBase64 Optional external private key (e.g., derived from BitChat identity)
     */
    suspend fun startNode(privateKeyBase64: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        nodeLock.withLock {
            if (_nodeStatus.value == P2PNodeStatus.RUNNING) {
                Log.d(TAG, "P2P node already running, skipping start")
                return@withLock Result.success(Unit)
            }
        
            _nodeStatus.value = P2PNodeStatus.STARTING
            Log.i(TAG, "Starting P2P node...")
        
        try {
            // Verify golib is available
            Log.d(TAG, "Checking golib availability...")
            try {
                val golibClass = Class.forName("golib.Golib")
                Log.d(TAG, "golib.Golib class found: ${golibClass.name}")
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "golib.Golib class NOT FOUND - golib.aar may not be properly linked", e)
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
            Log.d(TAG, "P2P node created successfully")
            
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
            
            // Start the node FIRST - topicManager is created during Start()
            Log.d(TAG, "Starting P2P node network...")
            newNode.start()
            Log.d(TAG, "P2P node network started")
            node = newNode
            
            // CRITICAL: Set up topic handlers AFTER start() because topicManager
            // doesn't exist until Start() is called!
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
            
            Log.i(TAG, "P2P node started successfully")
            Log.i(TAG, "   Peer ID: ${newNode.peerID}")
            Log.i(TAG, "   Multiaddrs: ${newNode.multiaddrs}")
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start P2P node: ${e.message}", e)
            Log.e(TAG, "   Exception type: ${e.javaClass.simpleName}")
            e.cause?.let { cause ->
                Log.e(TAG, "   Cause: ${cause.message}")
            }
            _nodeStatus.value = P2PNodeStatus.ERROR
            Result.failure(e)
        }
        } // nodeLock.withLock
    }
    
    /**
     * Stop the P2P node gracefully.
     */
    suspend fun stopNode(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Cancel DHT refresh job
            dhtRefreshJob?.cancel()
            dhtRefreshJob = null

            // Cancel health monitoring job
            healthCheckJob?.cancel()
            healthCheckJob = null
            consecutiveZeroPeerChecks = 0

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
                delay(AppConstants.P2P.DHT_REFRESH_INTERVAL_MS)

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

        // Start health monitoring for auto-recovery
        startHealthMonitoring()
    }

    /**
     * Start health monitoring that detects when DHT routing table drops to 0 peers
     * and automatically triggers re-bootstrap to recover connectivity.
     *
     * This addresses Issue 2 from bugs.txt: "P2P Connection Loss - Zero Peers, Cannot Recover"
     */
    private fun startHealthMonitoring() {
        healthCheckJob?.cancel()
        consecutiveZeroPeerChecks = 0

        healthCheckJob = scope.launch {
            // Initial delay to let the node stabilize after startup
            delay(AppConstants.P2P.HEALTH_CHECK_INTERVAL_MS)

            while (true) {
                delay(AppConstants.P2P.HEALTH_CHECK_INTERVAL_MS)

                if (_nodeStatus.value != P2PNodeStatus.RUNNING) {
                    Log.d(TAG, "Health check: Node not running, stopping monitoring")
                    break
                }

                try {
                    checkHealthAndRecover()
                } catch (e: Exception) {
                    Log.w(TAG, "Health check failed: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Started P2P health monitoring (${AppConstants.P2P.HEALTH_CHECK_INTERVAL_MS}ms interval)")
    }

    /**
     * Check P2P node health and trigger recovery if needed.
     * Parses DHT status to detect 0 peers in routing table.
     */
    private suspend fun checkHealthAndRecover() {
        val currentNode = node ?: return
        val status = currentNode.dhtStatus ?: return

        // Parse DHT status to extract routing table peer count
        // Expected format contains "routing table: N peers" or similar
        val routingTablePeers = parseRoutingTablePeers(status)
        val connectedPeers = _connectedPeers.value.size

        Log.d(TAG, "Health check: routing_table=$routingTablePeers, connected=$connectedPeers")

        // Check if we have zero peers in both routing table AND connected peers
        if (routingTablePeers == 0 && connectedPeers == 0) {
            consecutiveZeroPeerChecks++
            Log.w(TAG, "Zero peers detected (check $consecutiveZeroPeerChecks/${AppConstants.P2P.ZERO_PEERS_THRESHOLD_COUNT})")

            if (consecutiveZeroPeerChecks >= AppConstants.P2P.ZERO_PEERS_THRESHOLD_COUNT) {
                attemptRecovery()
            }
        } else {
            // Reset counter if we have peers
            if (consecutiveZeroPeerChecks > 0) {
                Log.d(TAG, "Peers recovered, resetting zero-peer counter")
                consecutiveZeroPeerChecks = 0
            }
        }
    }

    /**
     * Parse DHT status string to extract routing table peer count.
     * Returns 0 if parsing fails or peers not found.
     */
    private fun parseRoutingTablePeers(status: String): Int {
        // Try to match patterns like "routing table: 84 peers" or "RT: 84"
        val patterns = listOf(
            Regex("""routing\s*table[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""RT[:\s]+(\d+)"""),
            Regex("""(\d+)\s*peers?\s*in\s*routing""", RegexOption.IGNORE_CASE),
            Regex("""DHT[:\s]+(\d+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(status)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 0
            }
        }

        // If no pattern matches, assume healthy (avoid false positives)
        return -1  // -1 means "unknown", won't trigger recovery
    }

    /**
     * Attempt to recover P2P connectivity by re-bootstrapping.
     * Respects cooldown to avoid excessive recovery attempts.
     */
    private suspend fun attemptRecovery() {
        val now = System.currentTimeMillis()
        val timeSinceLastRecovery = now - lastRecoveryAttemptMs

        if (timeSinceLastRecovery < AppConstants.P2P.RECOVERY_COOLDOWN_MS) {
            Log.d(TAG, "Recovery cooldown active, skipping (${timeSinceLastRecovery}ms since last attempt)")
            return
        }

        Log.w(TAG, "🔄 Attempting P2P auto-recovery (re-bootstrap)...")
        lastRecoveryAttemptMs = now
        consecutiveZeroPeerChecks = 0

        try {
            val currentNode = node
            if (currentNode != null) {
                // Try DHT refresh first (lighter operation)
                Log.d(TAG, "Step 1: Refreshing DHT...")
                currentNode.refreshDHT()
                delay(2000) // Give it time to work

                // Check if that helped
                val statusAfterRefresh = currentNode.dhtStatus ?: ""
                val peersAfterRefresh = parseRoutingTablePeers(statusAfterRefresh)

                if (peersAfterRefresh > 0) {
                    Log.i(TAG, "✅ DHT refresh recovered connectivity: $peersAfterRefresh peers")
                    _dhtStatus.value = statusAfterRefresh
                    return
                }

                // DHT refresh didn't help, try announcing
                Log.d(TAG, "Step 2: Announcing to DHT...")
                currentNode.announce()
                delay(2000)

                // Final check
                val statusAfterAnnounce = currentNode.dhtStatus ?: ""
                val peersAfterAnnounce = parseRoutingTablePeers(statusAfterAnnounce)

                if (peersAfterAnnounce > 0) {
                    Log.i(TAG, "✅ DHT announce recovered connectivity: $peersAfterAnnounce peers")
                    _dhtStatus.value = statusAfterAnnounce
                } else {
                    Log.w(TAG, "⚠️ Auto-recovery did not restore peers. Manual restart may be needed.")
                    // Note: We don't restart the node automatically to avoid disrupting any
                    // partial connectivity. User can force-stop the app if needed.
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-recovery failed: ${e.message}", e)
        }
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
     * Trigger DHT discovery for a topic.
     * This tells the Go library to actively search for peers subscribed to this topic.
     * Call this when subscribing to a new topic to bootstrap peer discovery.
     */
    fun refreshTopicPeers(topicName: String) {
        try {
            node?.refreshTopicPeers(topicName)
            Log.d(TAG, "Triggered topic peer refresh for: $topicName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh topic peers for $topicName: ${e.message}")
        }
    }
    
    /**
     * Get topic statistics (mesh peer count, provider count, etc).
     * Returns JSON string from Go library.
     */
    fun getTopicStats(topicName: String): String {
        return try {
            node?.getTopicStats(topicName) ?: "{}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get topic stats for $topicName: ${e.message}")
            "{}"
        }
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

    // ============== Health Monitoring ==============

    /**
     * Check if the P2P node is healthy (has peers in routing table).
     * Returns true if healthy, false if no peers detected.
     */
    fun isHealthy(): Boolean {
        if (_nodeStatus.value != P2PNodeStatus.RUNNING) return false
        val status = node?.dhtStatus ?: return false
        val routingPeers = parseRoutingTablePeers(status)
        val connectedPeers = _connectedPeers.value.size
        return routingPeers > 0 || connectedPeers > 0 || routingPeers == -1
    }

    /**
     * Get current health status for UI display.
     */
    fun getHealthStatus(): HealthStatus {
        val status = node?.dhtStatus ?: "Node not started"
        val routingPeers = parseRoutingTablePeers(status)
        val connectedPeers = _connectedPeers.value.size

        return HealthStatus(
            isHealthy = routingPeers > 0 || connectedPeers > 0 || routingPeers == -1,
            routingTablePeers = if (routingPeers >= 0) routingPeers else null,
            connectedPeers = connectedPeers,
            consecutiveZeroChecks = consecutiveZeroPeerChecks,
            lastRecoveryAttemptMs = lastRecoveryAttemptMs
        )
    }

    data class HealthStatus(
        val isHealthy: Boolean,
        val routingTablePeers: Int?,
        val connectedPeers: Int,
        val consecutiveZeroChecks: Int,
        val lastRecoveryAttemptMs: Long
    )

    /**
     * Manually trigger P2P recovery.
     * Can be called from UI when user suspects connectivity issues.
     */
    suspend fun forceRecovery(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_nodeStatus.value != P2PNodeStatus.RUNNING) {
            return@withContext Result.failure(Exception("Node not running"))
        }

        try {
            Log.i(TAG, "Manual P2P recovery triggered by user")
            // Reset cooldown to allow immediate recovery
            lastRecoveryAttemptMs = 0L
            attemptRecovery()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Manual recovery failed: ${e.message}", e)
            Result.failure(e)
        }
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
