package com.roman.zemzeme.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.Job
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * Structured P2P debug log entry from Go logger.
 */
data class P2PDebugLogEntry(
    val timestamp: Long,
    val level: String,
    val component: String,
    val event: String,
    val message: String,
    val contextJson: String
)

/**
 * Parsed bandwidth summary from Go bandwidth stats JSON.
 */
data class P2PBandwidthSummary(
    val sessionInBytes: Long = 0L,
    val sessionOutBytes: Long = 0L,
    val sessionTotalBytes: Long = 0L,
    val dailyInBytes: Long = 0L,
    val dailyOutBytes: Long = 0L,
    val dailyTotalBytes: Long = 0L,
    val currentRateInBytesPerSec: Double = 0.0,
    val currentRateOutBytesPerSec: Double = 0.0,
    val projectedHourlyTotalBytes: Long = 0L,
    val rawJson: String = "{}"
)

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
        private const val KEY_DAILY_BANDWIDTH_DATE = "daily_bandwidth_date"
        private const val KEY_DAILY_BANDWIDTH_IN = "daily_bandwidth_in"
        private const val KEY_DAILY_BANDWIDTH_OUT = "daily_bandwidth_out"
        private const val KEY_PEER_CACHE = "peer_cache_json"
        private const val KEY_PEER_CACHE_SAVED_AT = "peer_cache_saved_at"
        private const val PEER_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val LOG_FILE_NAME = "p2p_debug.log"
        private const val PEER_CACHE_FIRST_SAVE_DELAY_MS = 2 * 60 * 1000L
        private const val PEER_CACHE_SAVE_INTERVAL_MS = 5 * 60 * 1000L
        private const val STATUS_REFRESH_INTERVAL_MS = 5_000L
        private const val TOPIC_TRACKING_INTERVAL_SECONDS = 10L
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var node: P2PNode? = null
    
    // Mutex for atomic node state transitions (prevents race conditions during startup)
    private val nodeLock = Mutex()
    
    // Preferences for key storage
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // State flows
    private val _nodeStatus = MutableStateFlow(P2PNodeStatus.STOPPED)
    val nodeStatus: StateFlow<P2PNodeStatus> = _nodeStatus.asStateFlow()
    
    private val _peerID = MutableStateFlow<String?>(null)
    val peerID: StateFlow<String?> = _peerID.asStateFlow()
    
    private val _multiaddrs = MutableStateFlow("")
    val multiaddrs: StateFlow<String> = _multiaddrs.asStateFlow()
    
    private val _dhtStatus = MutableStateFlow("")
    val dhtStatus: StateFlow<String> = _dhtStatus.asStateFlow()

    private val _bandwidthStats = MutableStateFlow("{}")
    val bandwidthStats: StateFlow<String> = _bandwidthStats.asStateFlow()

    private val _totalConnectedPeers = MutableStateFlow(0)
    val totalConnectedPeers: StateFlow<Int> = _totalConnectedPeers.asStateFlow()

    private val _nodeStartedAt = MutableStateFlow<Long?>(null)
    val nodeStartedAt: StateFlow<Long?> = _nodeStartedAt.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<List<P2PPeer>>(emptyList())
    val connectedPeers: StateFlow<List<P2PPeer>> = _connectedPeers.asStateFlow()
    
    private val _incomingMessages = MutableSharedFlow<P2PMessage>(replay = 0, extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<P2PMessage> = _incomingMessages.asSharedFlow()
    
    // Track subscribed topics
    private val subscribedTopics = ConcurrentHashMap.newKeySet<String>()

    // Guard connected peers list updates from callback thread races.
    private val connectedPeersLock = Any()
    
    // DHT + bandwidth status refresh job
    private var dhtRefreshJob: Job? = null

    // Periodic peer cache persistence job
    private var peerCacheSaveJob: Job? = null

    // Health monitoring state for auto-recovery
    private val consecutiveZeroPeerChecks = AtomicInteger(0)
    private val lastRecoveryAttemptMs = AtomicLong(0L)
    private var healthCheckJob: Job? = null
    private val recoveryLock = Mutex()
    
    // ============== Lifecycle ==============
    
    /**
     * Start the P2P node with the stored (or newly generated) identity.
     * Uses Mutex to ensure atomic state transitions (prevents race conditions).
     * 
     * @param privateKeyBase64 Optional external private key (e.g., derived from BitChat identity)
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun startNode(privateKeyBase64: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        nodeLock.withLock {
            if (_nodeStatus.value == P2PNodeStatus.RUNNING) {
                Log.d(TAG, "P2P node already running, skipping start")
                return@withLock Result.success(Unit)
            }

            _nodeStatus.value = P2PNodeStatus.STARTING
            Log.i(TAG, "Starting P2P node...")

            var newNode: P2PNode? = null

            try {
                Log.d(TAG, "Checking golib availability...")
                try {
                    val golibClass = Class.forName("golib.Golib")
                    Log.d(TAG, "golib.Golib class found: ${golibClass.name}")
                } catch (e: ClassNotFoundException) {
                    throw IllegalStateException("golib library not found", e)
                }

                initGoLogging()
                configureAndroidDNSForNetwork()

                // NOTE: We intentionally ignore privateKeyBase64 because BitChat's
                // identity manager key format doesn't match libp2p marshaled private keys.
                val privateKey = getStoredPrivateKey() ?: generateAndStoreNewKey()
                Log.d(TAG, "Private key obtained (length: ${privateKey.length})")

                Log.d(TAG, "Creating P2P node with tuned config...")
                newNode = createNodeWithTunedConfig(privateKey)
                Log.d(TAG, "P2P node created successfully")

                importPeerCacheIfFresh(newNode)

                newNode.setMessageHandler(object : MobileMessageHandler {
                    override fun onMessage(peerID: String?, message: String?) {
                        if (peerID != null && message != null) {
                            scope.launch {
                                _incomingMessages.emit(
                                    P2PMessage(
                                        senderPeerID = peerID,
                                        content = message,
                                        isTopicMessage = false
                                    )
                                )
                            }
                            Log.d(TAG, "Message from $peerID: ${message.take(50)}...")
                        }
                    }
                })

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

                Log.d(TAG, "Starting P2P node network...")
                newNode.start()
                Log.d(TAG, "P2P node network started")

                restoreDailyBandwidthCache(newNode)

                node = newNode
                _nodeStartedAt.value = System.currentTimeMillis()

                // topicManager is initialized after start()
                newNode.setTopicMessageHandler(object : MobileTopicMessageHandler {
                    override fun onTopicMessage(topicName: String?, senderID: String?, message: String?, timestamp: Long) {
                        if (topicName != null && senderID != null && message != null) {
                            scope.launch {
                                _incomingMessages.emit(
                                    P2PMessage(
                                        senderPeerID = senderID,
                                        content = message,
                                        isTopicMessage = true,
                                        topicName = topicName,
                                        timestamp = timestamp
                                    )
                                )
                            }
                            Log.d(TAG, "Topic message [$topicName] from $senderID: ${message.take(50)}...")
                        }
                    }
                })

                newNode.setTopicPeerHandler(object : MobileTopicPeerHandler {
                    override fun onTopicPeerUpdate(topicName: String?, peerID: String?, action: String?) {
                        Log.d(TAG, "Topic peer update [$topicName]: $peerID $action")
                    }
                })

                _peerID.value = newNode.peerID
                _multiaddrs.value = newNode.multiaddrs
                _nodeStatus.value = P2PNodeStatus.RUNNING
                prefs.edit().putString(KEY_PEER_ID, newNode.peerID).apply()

                updateMeteredModeForNetwork()
                registerNetworkCallback()
                refreshNodeStatusSnapshot(newNode)
                startDhtStatusRefresh()
                startPeriodicPeerCacheSave()

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

                unregisterNetworkCallbackSafely()
                dhtRefreshJob?.cancel()
                dhtRefreshJob = null
                healthCheckJob?.cancel()
                healthCheckJob = null
                stopPeriodicPeerCacheSave()
                runCatching { newNode?.stop() }
                if (node === newNode) {
                    node = null
                }

                _nodeStartedAt.value = null
                _bandwidthStats.value = "{}"
                synchronized(connectedPeersLock) {
                    _connectedPeers.value = emptyList()
                    _totalConnectedPeers.value = 0
                }
                _nodeStatus.value = P2PNodeStatus.ERROR
                Result.failure(e)
            }
        }
    }
    
    /**
     * Stop the P2P node gracefully.
     */
    suspend fun stopNode(): Result<Unit> = withContext(Dispatchers.IO) {
        nodeLock.withLock {
            try {
                dhtRefreshJob?.cancel()
                dhtRefreshJob = null
                stopPeriodicPeerCacheSave()

                healthCheckJob?.cancel()
                healthCheckJob = null
                consecutiveZeroPeerChecks.set(0)

                unregisterNetworkCallbackSafely()
                persistDailyBandwidthCache()
                savePeerCacheSnapshot()

                node?.stop()
                node = null

                _nodeStatus.value = P2PNodeStatus.STOPPED
                synchronized(connectedPeersLock) {
                    _connectedPeers.value = emptyList()
                    _totalConnectedPeers.value = 0
                }
                _nodeStartedAt.value = null
                _bandwidthStats.value = "{}"
                subscribedTopics.clear()
                _dhtStatus.value = ""

                Log.d(TAG, "P2P node stopped")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop P2P node", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Start periodic status refresh to keep diagnostics updated.
     */
    private fun startDhtStatusRefresh() {
        dhtRefreshJob?.cancel()
        dhtRefreshJob = scope.launch {
            while (true) {
                delay(STATUS_REFRESH_INTERVAL_MS)

                if (_nodeStatus.value != P2PNodeStatus.RUNNING) {
                    Log.d(TAG, "Status refresh: Node not running, stopping refresh")
                    break
                }

                try {
                    refreshNodeStatusSnapshot()
                } catch (e: Exception) {
                    Log.w(TAG, "Status refresh failed: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Started periodic P2P status refresh (${STATUS_REFRESH_INTERVAL_MS}ms interval)")

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
        consecutiveZeroPeerChecks.set(0)

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
            val zeroCheckCount = consecutiveZeroPeerChecks.incrementAndGet()
            Log.w(TAG, "Zero peers detected (check $zeroCheckCount/${AppConstants.P2P.ZERO_PEERS_THRESHOLD_COUNT})")

            if (zeroCheckCount >= AppConstants.P2P.ZERO_PEERS_THRESHOLD_COUNT) {
                attemptRecovery()
            }
        } else {
            // Reset counter if we have peers
            if (consecutiveZeroPeerChecks.get() > 0) {
                Log.d(TAG, "Peers recovered, resetting zero-peer counter")
                consecutiveZeroPeerChecks.set(0)
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
        recoveryLock.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLastRecovery = now - lastRecoveryAttemptMs.get()

            if (timeSinceLastRecovery < AppConstants.P2P.RECOVERY_COOLDOWN_MS) {
                Log.d(TAG, "Recovery cooldown active, skipping (${timeSinceLastRecovery}ms since last attempt)")
                return
            }

            Log.w(TAG, "Attempting P2P auto-recovery (re-bootstrap)")
            lastRecoveryAttemptMs.set(now)
            consecutiveZeroPeerChecks.set(0)

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
                        Log.i(TAG, "DHT refresh recovered connectivity: $peersAfterRefresh peers")
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
                        Log.i(TAG, "DHT announce recovered connectivity: $peersAfterAnnounce peers")
                        _dhtStatus.value = statusAfterAnnounce
                    } else {
                        Log.w(TAG, "Auto-recovery did not restore peers. Manual restart may be needed.")
                        // Note: We don't restart the node automatically to avoid disrupting any
                        // partial connectivity. User can force-stop the app if needed.
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-recovery failed: ${e.message}", e)
            }
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

    /**
     * Force a one-shot refresh of DHT, bandwidth and connected peers diagnostics.
     */
    suspend fun refreshDiagnostics(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentNode = node ?: return@withContext Result.failure(Exception("Node not started"))
            refreshNodeStatusSnapshot(currentNode)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh diagnostics", e)
            Result.failure(e)
        }
    }

    /**
     * Get raw bandwidth stats JSON from Go library.
     */
    fun getBandwidthStatsJson(): String {
        return try {
            val liveJson = node?.bandwidthStats
            if (liveJson.isNullOrBlank()) {
                _bandwidthStats.value
            } else {
                liveJson
            }
        } catch (e: Exception) {
            _bandwidthStats.value
        }
    }

    /**
     * Parse a bandwidth summary from stats JSON with safe fallbacks.
     */
    fun getBandwidthSummary(statsJson: String = getBandwidthStatsJson()): P2PBandwidthSummary {
        val safeJson = if (statsJson.isBlank()) "{}" else statsJson
        return try {
            val json = JSONObject(safeJson)
            P2PBandwidthSummary(
                sessionInBytes = json.optLong("session_in", 0L),
                sessionOutBytes = json.optLong("session_out", 0L),
                sessionTotalBytes = json.optLong("session_total", 0L),
                dailyInBytes = json.optLong("daily_in", 0L),
                dailyOutBytes = json.optLong("daily_out", 0L),
                dailyTotalBytes = json.optLong("daily_total", 0L),
                currentRateInBytesPerSec = json.optDouble("current_rate_in", 0.0),
                currentRateOutBytesPerSec = json.optDouble("current_rate_out", 0.0),
                projectedHourlyTotalBytes = json.optLong("projected_hourly_total", 0L),
                rawJson = safeJson
            )
        } catch (e: Exception) {
            P2PBandwidthSummary(rawJson = safeJson)
        }
    }

    /**
     * Human-readable bytes formatter backed by Go helper.
     */
    fun formatBytes(bytes: Long): String {
        return try {
            Golib.formatBytes(bytes)
        } catch (_: Exception) {
            "$bytes B"
        }
    }

    /**
     * Human-readable bytes/sec formatter backed by Go helper.
     */
    fun formatRate(bytesPerSecond: Double): String {
        return try {
            Golib.formatRate(bytesPerSecond)
        } catch (_: Exception) {
            "${bytesPerSecond.toLong()} B/s"
        }
    }

    /**
     * Fetch Go debug logs. Optional component filter (e.g. "dht", "node", "stream").
     */
    suspend fun getDebugLogs(component: String? = null): Result<List<P2PDebugLogEntry>> = withContext(Dispatchers.IO) {
        try {
            Result.success(readDebugLogs(component))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch debug logs", e)
            Result.failure(e)
        }
    }

    /**
     * Clear Go debug logs from memory and file.
     */
    suspend fun clearDebugLogs(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Golib.clearDebugLogs()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear debug logs", e)
            Result.failure(e)
        }
    }

    /**
     * Export current logs as plain text to cache directory for sharing.
     */
    suspend fun exportDebugLogs(component: String? = null): Result<File> = withContext(Dispatchers.IO) {
        try {
            val entries = readDebugLogs(component)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportFile = File(context.cacheDir, "p2p_logs_$stamp.txt")
            val contents = buildString {
                appendLine("BitChat P2P Debug Logs")
                appendLine("Exported: ${Date()}")
                appendLine("Filter: ${component ?: "all"}")
                appendLine("Entries: ${entries.size}")
                appendLine()

                entries.forEach { entry ->
                    val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
                    appendLine("[$ts] ${entry.level}/${entry.component} ${entry.event}")
                    appendLine(entry.message)
                    if (entry.contextJson.isNotBlank() && entry.contextJson != "{}") {
                        appendLine("ctx=${entry.contextJson}")
                    }
                    appendLine()
                }
            }
            exportFile.writeText(contents)
            Result.success(exportFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export debug logs", e)
            Result.failure(e)
        }
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
            consecutiveZeroChecks = consecutiveZeroPeerChecks.get(),
            lastRecoveryAttemptMs = lastRecoveryAttemptMs.get()
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
            lastRecoveryAttemptMs.set(0L)
            attemptRecovery()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Manual recovery failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun initGoLogging() {
        try {
            val logFilePath = File(context.filesDir, LOG_FILE_NAME).absolutePath
            Golib.initLogging(logFilePath)
            Log.d(TAG, "Initialized Go logging at: $logFilePath")
        } catch (e: Exception) {
            // Logging init is non-blocking for node startup.
            Log.w(TAG, "Failed to initialize Go logging: ${e.message}")
        }
    }

    private fun createNodeWithTunedConfig(privateKey: String): P2PNode {
        return try {
            val config = Golib.defaultConfig()
            val defaultTrackingInterval = config.topicTrackingInterval
            val tunedTrackingInterval = minOf(defaultTrackingInterval, TOPIC_TRACKING_INTERVAL_SECONDS)
            config.topicTrackingInterval = tunedTrackingInterval
            config.validate()

            val configJson = config.toJSON()
            Log.d(
                TAG,
                "Using topic tracking interval=${tunedTrackingInterval}s (default=${defaultTrackingInterval}s)"
            )
            Golib.createP2PNodeWithConfigJSON(privateKey, 0, configJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create tuned node config, falling back to default: ${e.message}")
            Golib.newP2PNode(privateKey, 0)
        }
    }

    private fun currentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun getAndroidDnsServers(network: Network? = null): String {
        return try {
            val active = network ?: connectivityManager.activeNetwork
            val linkProps = active?.let { connectivityManager.getLinkProperties(it) }
            linkProps?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.filter { it.isNotBlank() }
                ?.joinToString(",")
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Android DNS servers: ${e.message}")
            ""
        }
    }

    private fun configureAndroidDNSForNetwork(network: Network? = null) {
        try {
            val dnsServers = getAndroidDnsServers(network)
            if (dnsServers.isNotBlank()) {
                Golib.configureAndroidDNS(dnsServers)
                Log.d(TAG, "Configured Go DNS servers: $dnsServers")
            } else {
                Log.w(TAG, "No Android DNS servers available, keeping existing Go DNS config")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure Go DNS: ${e.message}")
        }
    }

    private fun isCellularNetwork(network: Network? = null): Boolean {
        val active = network ?: connectivityManager.activeNetwork
        val capabilities = active?.let { connectivityManager.getNetworkCapabilities(it) }
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    private fun updateMeteredModeForNetwork(network: Network? = null) {
        try {
            val currentNode = node ?: return
            val metered = isCellularNetwork(network)
            currentNode.setMeteredMode(metered)
            Log.d(TAG, "Updated metered mode: $metered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update metered mode: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallbackSafely()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    configureAndroidDNSForNetwork(network)
                    updateMeteredModeForNetwork(network)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scope.launch {
                    updateMeteredModeForNetwork(network)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scope.launch {
                    configureAndroidDNSForNetwork(network)
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    configureAndroidDNSForNetwork()
                    updateMeteredModeForNetwork()
                }
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            Log.d(TAG, "Registered connectivity callback for DNS/metered updates")
        } catch (e: Exception) {
            networkCallback = null
            Log.w(TAG, "Failed to register connectivity callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallbackSafely() {
        val callback = networkCallback ?: return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
            Log.d(TAG, "Unregistered connectivity callback")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister connectivity callback: ${e.message}")
        } finally {
            networkCallback = null
        }
    }

    private fun refreshNodeStatusSnapshot(currentNode: P2PNode? = node) {
        val activeNode = currentNode ?: return

        val dht = try {
            activeNode.dhtStatus ?: "DHT status unavailable"
        } catch (_: Exception) {
            "DHT status unavailable"
        }
        _dhtStatus.value = dht

        val statsJson = try {
            activeNode.bandwidthStats
        } catch (_: Exception) {
            "{}"
        }
        _bandwidthStats.value = if (statsJson.isBlank()) "{}" else statsJson

        val peersRaw = try {
            activeNode.connectedPeers
        } catch (_: Exception) {
            ""
        }
        val countedByNode = parseConnectedPeersCount(peersRaw)
        val countedByCallbacks = _connectedPeers.value.size
        _totalConnectedPeers.value = maxOf(countedByNode, countedByCallbacks)
    }

    private fun parseConnectedPeersCount(rawPeers: String): Int {
        return rawPeers
            .lineSequence()
            .map { it.trim() }
            .count { it.isNotEmpty() }
    }

    private fun restoreDailyBandwidthCache(p2pNode: P2PNode) {
        try {
            val today = currentDateString()
            val storedDate = prefs.getString(KEY_DAILY_BANDWIDTH_DATE, null)

            if (storedDate == today) {
                val inBytes = prefs.getLong(KEY_DAILY_BANDWIDTH_IN, 0L)
                val outBytes = prefs.getLong(KEY_DAILY_BANDWIDTH_OUT, 0L)
                if (inBytes > 0L || outBytes > 0L) {
                    p2pNode.restoreDailyBandwidth(today, inBytes, outBytes)
                    Log.d(TAG, "Restored daily bandwidth cache: day=$today in=$inBytes out=$outBytes")
                }
            } else if (!storedDate.isNullOrBlank()) {
                prefs.edit()
                    .remove(KEY_DAILY_BANDWIDTH_DATE)
                    .remove(KEY_DAILY_BANDWIDTH_IN)
                    .remove(KEY_DAILY_BANDWIDTH_OUT)
                    .apply()
                Log.d(TAG, "Cleared stale daily bandwidth cache for date: $storedDate")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore daily bandwidth cache: ${e.message}")
        }
    }

    private fun persistDailyBandwidthCache() {
        val currentNode = node ?: return
        try {
            val cacheJson = currentNode.dailyBandwidthCache
            if (cacheJson.isBlank()) {
                return
            }

            val json = JSONObject(cacheJson)
            val day = json.optString("day", "")
            val inBytes = json.optLong("in", 0L)
            val outBytes = json.optLong("out", 0L)
            if (day.isNotBlank()) {
                prefs.edit()
                    .putString(KEY_DAILY_BANDWIDTH_DATE, day)
                    .putLong(KEY_DAILY_BANDWIDTH_IN, inBytes)
                    .putLong(KEY_DAILY_BANDWIDTH_OUT, outBytes)
                    .apply()
                Log.d(TAG, "Persisted daily bandwidth cache: day=$day in=$inBytes out=$outBytes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist daily bandwidth cache: ${e.message}")
        }
    }

    private fun importPeerCacheIfFresh(p2pNode: P2PNode) {
        try {
            val cacheJson = prefs.getString(KEY_PEER_CACHE, null)
            val savedAt = prefs.getLong(KEY_PEER_CACHE_SAVED_AT, 0L)
            if (cacheJson.isNullOrBlank() || savedAt <= 0L) {
                return
            }

            val ageMs = System.currentTimeMillis() - savedAt
            if (ageMs > PEER_CACHE_MAX_AGE_MS) {
                prefs.edit()
                    .remove(KEY_PEER_CACHE)
                    .remove(KEY_PEER_CACHE_SAVED_AT)
                    .apply()
                Log.d(TAG, "Skipping stale peer cache (age=${ageMs}ms)")
                return
            }

            val result = p2pNode.importPeerCache(cacheJson)
            Log.d(TAG, "Imported peer cache result: $result")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to import peer cache: ${e.message}")
        }
    }

    private fun savePeerCacheSnapshot() {
        val currentNode = node ?: return
        try {
            val cacheJson = currentNode.exportPeerCache()
            if (cacheJson.isBlank()) {
                return
            }
            prefs.edit()
                .putString(KEY_PEER_CACHE, cacheJson)
                .putLong(KEY_PEER_CACHE_SAVED_AT, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Saved peer cache snapshot")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save peer cache snapshot: ${e.message}")
        }
    }

    private fun startPeriodicPeerCacheSave() {
        stopPeriodicPeerCacheSave()
        peerCacheSaveJob = scope.launch {
            delay(PEER_CACHE_FIRST_SAVE_DELAY_MS)
            while (_nodeStatus.value == P2PNodeStatus.RUNNING) {
                savePeerCacheSnapshot()
                delay(PEER_CACHE_SAVE_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Started periodic peer cache save job")
    }

    private fun stopPeriodicPeerCacheSave() {
        peerCacheSaveJob?.cancel()
        peerCacheSaveJob = null
    }

    private fun readDebugLogs(component: String? = null): List<P2PDebugLogEntry> {
        val componentFilter = normalizeComponentFilter(component)
        val logsJson = if (componentFilter == null) {
            Golib.getDebugLogs()
        } else {
            Golib.getDebugLogsFiltered(componentFilter)
        }
        return parseDebugLogsJson(logsJson)
    }

    private fun normalizeComponentFilter(component: String?): String? {
        val normalized = component?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            normalized.isBlank() -> null
            normalized == "all" -> null
            else -> normalized
        }
    }

    private fun parseDebugLogsJson(logsJson: String): List<P2PDebugLogEntry> {
        if (logsJson.isBlank()) return emptyList()

        return try {
            val array = JSONArray(logsJson)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val context = obj.opt("ctx")
                    val contextJson = when (context) {
                        is JSONObject -> context.toString()
                        null, JSONObject.NULL -> "{}"
                        else -> context.toString()
                    }
                    add(
                        P2PDebugLogEntry(
                            timestamp = obj.optLong("ts", 0L),
                            level = obj.optString("level", "INFO"),
                            component = obj.optString("component", "unknown"),
                            event = obj.optString("event", ""),
                            message = obj.optString("msg", ""),
                            contextJson = contextJson
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse debug logs JSON: ${e.message}")
            emptyList()
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
        synchronized(connectedPeersLock) {
            val status = _nodeStatus.value
            if (status == P2PNodeStatus.STOPPED || status == P2PNodeStatus.ERROR) {
                return
            }

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
            _totalConnectedPeers.value = current.size
        }
    }
}
