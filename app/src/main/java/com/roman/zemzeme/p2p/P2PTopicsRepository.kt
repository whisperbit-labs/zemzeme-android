package com.roman.zemzeme.p2p

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.roman.zemzeme.util.AppConstants
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Message received on a P2P topic (GossipSub)
 */
data class TopicMessage(
    val topicName: String,
    val senderID: String,
    val content: String,
    val timestamp: Long,
    val isOutgoing: Boolean = false
)

/**
 * Topic peer update event
 */
data class TopicPeerUpdate(
    val topicName: String,
    val peerID: String,
    val action: String // "join", "leave", or "discovered"
)

/**
 * Topic connection state for UI feedback
 */
enum class TopicConnectionState {
    CONNECTING,    // Searching for peers
    CONNECTED,     // Has at least one peer
    NO_PEERS,      // Legacy state (rendered as CONNECTING in UI)
    ERROR          // Connection error
}

/**
 * Current state of a topic subscription
 */
data class TopicState(
    val connectionState: TopicConnectionState = TopicConnectionState.CONNECTING,
    val meshPeerCount: Int = 0,        // Peers in PubSub mesh
    val providerCount: Int = 0,        // DHT providers
    val peers: List<String> = emptyList(),
    val error: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Subscribed topic info
 */
data class TopicInfo(
    val name: String,
    val subscribedAt: Long = System.currentTimeMillis()
)

/**
 * P2P Topics Repository
 *
 * Manages P2P topic (GossipSub) subscriptions for:
 * - Geohash channels (location-based chat)
 * - Custom topic rooms
 *
 * Delegates all P2P operations to P2PLibraryRepository.
 * Adapted from mobile_go_libp2p reference implementation.
 *
 * IMPORTANT: This is a singleton to prevent duplicate message collectors.
 * Multiple instances would each create their own collector on the shared
 * P2PLibraryRepository.incomingMessages flow, causing message duplication.
 */
class P2PTopicsRepository private constructor(
    private val p2pLibraryRepository: P2PLibraryRepository,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "P2PTopicsRepository"
        private const val PREFS_NAME = "p2p_topics"

        @Volatile
        private var instance: P2PTopicsRepository? = null

        fun getInstance(context: Context, p2pLibraryRepository: P2PLibraryRepository): P2PTopicsRepository {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                val sharedPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                instance ?: P2PTopicsRepository(p2pLibraryRepository, sharedPrefs).also {
                    instance = it
                    Log.d(TAG, "P2PTopicsRepository singleton created")
                }
            }
        }
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // State flows
    private val _subscribedTopics = MutableStateFlow<List<TopicInfo>>(emptyList())
    val subscribedTopics: StateFlow<List<TopicInfo>> = _subscribedTopics.asStateFlow()
    
    private val _topicPeers = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val topicPeers: StateFlow<Map<String, List<String>>> = _topicPeers.asStateFlow()
    
    private val _topicMessages = MutableStateFlow<Map<String, List<TopicMessage>>>(emptyMap())
    val topicMessages: StateFlow<Map<String, List<TopicMessage>>> = _topicMessages.asStateFlow()
    
    private val _topicStates = MutableStateFlow<Map<String, TopicState>>(emptyMap())
    val topicStates: StateFlow<Map<String, TopicState>> = _topicStates.asStateFlow()
    
    // Per-topic refresh jobs - prevents orphaned coroutines when switching channels (memory leak fix)
    private val refreshJobs = ConcurrentHashMap<String, Job>()
    
    // Presence tracking to prevent broadcast storms
    private val lastPresenceBroadcast = mutableMapOf<String, Long>() // topic -> timestamp
    private val respondedToPeers = mutableMapOf<String, Long>() // peerID -> timestamp
    private val presenceLock = Any()
    
    // Event flows
    private val _incomingMessages = MutableSharedFlow<TopicMessage>(replay = 0, extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<TopicMessage> = _incomingMessages.asSharedFlow()
    
    private val _peerUpdates = MutableSharedFlow<TopicPeerUpdate>(replay = 0, extraBufferCapacity = 50)
    val peerUpdates: SharedFlow<TopicPeerUpdate> = _peerUpdates.asSharedFlow()
    
    init {
        loadSavedTopics()
        
        // Listen to incoming messages from P2PLibraryRepository
        scope.launch {
            p2pLibraryRepository.incomingMessages.collect { p2pMessage ->
                if (p2pMessage.isTopicMessage && p2pMessage.topicName != null) {
                    val topicMsg = TopicMessage(
                        topicName = p2pMessage.topicName,
                        senderID = p2pMessage.senderPeerID,
                        content = p2pMessage.content,
                        timestamp = p2pMessage.timestamp,
                        isOutgoing = false
                    )
                    addMessageToTopic(p2pMessage.topicName, topicMsg)
                    _incomingMessages.emit(topicMsg)
                }
            }
        }
        
        // AUTO-STARTUP FIX: Watch for P2P node to become RUNNING and re-subscribe saved topics
        scope.launch {
            p2pLibraryRepository.nodeStatus.collect { status ->
                if (status == P2PNodeStatus.RUNNING) {
                    Log.d(TAG, "P2P node is RUNNING - auto-calling onNodeStarted()")
                    onNodeStarted()
                }
            }
        }
    }
    
    /**
     * Initialize and re-subscribe to saved topics when P2P node starts.
     * Call this after P2PLibraryRepository.startNode() succeeds.
     */
    suspend fun onNodeStarted() {
        _subscribedTopics.value.forEach { topic ->
            try {
                initTopicState(topic.name)
                p2pLibraryRepository.subscribeTopic(topic.name)
                startTopicDiscovery(topic.name)
                startContinuousRefresh(topic.name)
                Log.d(TAG, "Re-subscribed to saved topic: ${topic.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-subscribe to ${topic.name}: ${e.message}")
                setTopicError(topic.name, e.message ?: "Failed to subscribe")
            }
        }
    }
    
    /**
     * Clean up when P2P node stops.
     */
    fun onNodeStopped() {
        // Reset all topic states to connecting for next startup
        val states = _topicStates.value.toMutableMap()
        states.keys.forEach { key ->
            states[key] = TopicState(connectionState = TopicConnectionState.CONNECTING)
        }
        _topicStates.value = states
    }
    
    // ============== Topic Operations ==============
    
    /**
     * Subscribe to a topic (e.g., geohash channel).
     */
    suspend fun subscribeTopic(topicName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (p2pLibraryRepository.nodeStatus.value != P2PNodeStatus.RUNNING) {
                return@withContext Result.failure(Exception("P2P node not running"))
            }
            
            // Initialize state
            initTopicState(topicName)
            
            // Subscribe via P2PLibraryRepository
            val result = p2pLibraryRepository.subscribeTopic(topicName)
            if (result.isFailure) {
                setTopicError(topicName, result.exceptionOrNull()?.message ?: "Subscription failed")
                return@withContext result
            }
            
            // Add to subscribed list
            val current = _subscribedTopics.value.toMutableList()
            if (current.none { it.name == topicName }) {
                current.add(TopicInfo(topicName))
                _subscribedTopics.value = current
                saveTopics()
            }
            
            // Initialize message/peer tracking
            initTopicData(topicName)
            
            // CRITICAL: Trigger DHT discovery for this topic
            // This tells the Go library to actively search for other peers
            p2pLibraryRepository.refreshTopicPeers(topicName)
            
            // Start peer discovery polling
            startTopicDiscovery(topicName)
            
            Log.d(TAG, "Subscribed to topic: $topicName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to $topicName: ${e.message}")
            setTopicError(topicName, e.message ?: "Subscription failed")
            Result.failure(e)
        }
    }
    
    /**
     * Unsubscribe from a topic.
     */
    suspend fun unsubscribeTopic(topicName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            p2pLibraryRepository.unsubscribeTopic(topicName)
            
            // Remove from list
            val current = _subscribedTopics.value.toMutableList()
            current.removeAll { it.name == topicName }
            _subscribedTopics.value = current
            saveTopics()
            
            // Cleanup data
            cleanupTopicData(topicName)
            
            Log.d(TAG, "Unsubscribed from topic: $topicName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unsubscribe from $topicName: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Trigger full DHT discovery for a topic.
     * This is the KEY function that finds and connects to peers!
     * Call this when entering a topic chat or when user presses refresh.
     */
    suspend fun discoverTopicPeers(topicName: String) = withContext(Dispatchers.IO) {
        try {
            if (p2pLibraryRepository.nodeStatus.value != P2PNodeStatus.RUNNING) {
                Log.w(TAG, "Cannot discover peers - node not running")
                return@withContext
            }
            
            // Only set state to CONNECTING if not already connected with peers
            val current = _topicStates.value.toMutableMap()
            val state = current[topicName] ?: TopicState()
            if (state.connectionState != TopicConnectionState.CONNECTED || state.peers.isEmpty()) {
                current[topicName] = state.copy(
                    connectionState = TopicConnectionState.CONNECTING,
                    lastUpdated = System.currentTimeMillis()
                )
                _topicStates.value = current
            }
            
            Log.d(TAG, "Starting DHT discovery for topic: $topicName")
            
            // CRITICAL: Trigger Go-side DHT discovery (async, returns immediately)
            p2pLibraryRepository.refreshTopicPeers(topicName)
            
            // Wait for discovery to start finding peers, then refresh UI
            delay(AppConstants.P2P.DISCOVERY_INITIAL_DELAY_MS)
            refreshTopicPeers(topicName)
            
            // Refresh again after more time for connections to establish
            delay(AppConstants.P2P.DISCOVERY_SECONDARY_DELAY_MS)
            refreshTopicPeers(topicName)
            
            // One more refresh after a longer delay for slow connections
            delay(AppConstants.P2P.DISCOVERY_FINAL_DELAY_MS)
            refreshTopicPeers(topicName)
            
            Log.d(TAG, "DHT discovery completed for topic: $topicName")
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed for $topicName: ${e.message}")
            setTopicError(topicName, e.message ?: "Failed to discover peers")
        }
    }
    
    /**
     * Start continuous peer refresh for an active topic.
     * Call this when user enters a topic chat. Refreshes every 5 seconds.
     * Critical for P2P connectivity: DHT takes time to propagate and connections may fail.
     */
    fun startContinuousRefresh(topicName: String) {
        // Cancel any existing refresh job for this specific topic (prevents orphaned jobs)
        refreshJobs[topicName]?.cancel()
        
        Log.d(TAG, "Starting continuous peer refresh for: $topicName")
        
        refreshJobs[topicName] = scope.launch {
            try {
                // Initial discovery trigger
                p2pLibraryRepository.refreshTopicPeers(topicName)
                
                while (isActive) {
                    delay(AppConstants.P2P.TOPIC_DISCOVERY_INTERVAL_MS)
                    try {
                        p2pLibraryRepository.refreshTopicPeers(topicName)
                        refreshTopicPeers(topicName)
                        
                        val state = _topicStates.value[topicName]
                        Log.d(TAG, "Continuous refresh for $topicName: ${state?.meshPeerCount ?: 0} mesh peers, ${state?.providerCount ?: 0} providers")
                    } catch (e: Exception) {
                        Log.w(TAG, "Continuous refresh error for $topicName: ${e.message}")
                    }
                }
            } finally {
                // Clean up when job completes or is cancelled
                refreshJobs.remove(topicName)
            }
        }
    }
    
    /**
     * Stop continuous refresh for a topic or all topics.
     * Call this when user leaves the topic chat.
     * @param topicName If provided, stops refresh for that topic only. If null, stops all.
     */
    fun stopContinuousRefresh(topicName: String? = null) {
        if (topicName != null) {
            refreshJobs[topicName]?.cancel()
            refreshJobs.remove(topicName)
            Log.d(TAG, "Stopped continuous refresh for: $topicName")
        } else {
            val count = refreshJobs.size
            refreshJobs.values.forEach { it.cancel() }
            refreshJobs.clear()
            Log.d(TAG, "Stopped all $count continuous refresh jobs")
        }
    }
    
    /**
     * Publish a message to a topic.
     * Will ensure subscription exists before publishing.
     */
    suspend fun publishToTopic(
        topicName: String,
        content: String,
        senderNickname: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (p2pLibraryRepository.nodeStatus.value != P2PNodeStatus.RUNNING) {
                return@withContext Result.failure(Exception("P2P node not running"))
            }
            
            // ALWAYS ensure we're subscribed on the Go side before publishing
            // This is necessary because:
            // 1. Topics may be loaded from SharedPreferences but not subscribed in Go
            // 2. The Go subscription might have been lost
            // subscribeTopic handles idempotency (will succeed if already subscribed)
            Log.d(TAG, "Ensuring subscription for $topicName before publishing...")
            val subResult = p2pLibraryRepository.subscribeTopic(topicName)
            if (subResult.isFailure) {
                return@withContext Result.failure(
                    Exception("Failed to subscribe to $topicName: ${subResult.exceptionOrNull()?.message}")
                )
            }
            
            // CRITICAL: Trigger DHT discovery to find other peers on this topic!
            // Without this, we can subscribe and publish but won't receive messages from others.
            Log.d(TAG, "Triggering DHT discovery for $topicName...")
            p2pLibraryRepository.refreshTopicPeers(topicName)
            
            // Also ensure we track it in Kotlin
            val current = _subscribedTopics.value.toMutableList()
            if (current.none { it.name == topicName }) {
                current.add(TopicInfo(topicName))
                _subscribedTopics.value = current
                saveTopics()
            }
            
            val result = p2pLibraryRepository.publishToTopic(topicName, content)
            if (result.isFailure) {
                return@withContext result
            }
            
            // Add our message to local list
            val myPeerID = p2pLibraryRepository.peerID.value ?: "unknown"
            val msg = TopicMessage(
                topicName = topicName,
                senderID = myPeerID,
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
            addMessageToTopic(topicName, msg)
            
            Log.d(TAG, "Published to $topicName: ${content.take(50)}...")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish to $topicName: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Get current peers for a topic.
     */
    fun getPeersForTopic(topicName: String): List<String> {
        return _topicPeers.value[topicName] ?: emptyList()
    }
    
    /**
     * Get current state for a topic.
     */
    fun getTopicState(topicName: String): TopicState {
        return _topicStates.value[topicName] ?: TopicState()
    }
    
    // ============== Presence Broadcasting ==============
    
    /**
     * Broadcast our presence (nickname) to a topic.
     * Used for: 1) Joining a topic, 2) Username changes, 3) Responding to new peers
     * 
     * @param topicName The topic to broadcast to
     * @param nickname Our display name
     * @param force If true, bypass cooldown (used for username changes)
     */
    suspend fun broadcastPresence(topicName: String, nickname: String, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val shouldSkip = synchronized(presenceLock) {
            val lastBroadcast = lastPresenceBroadcast[topicName] ?: 0L
            !force && now - lastBroadcast < AppConstants.P2P.PRESENCE_BROADCAST_COOLDOWN_MS
        }

        // Enforce cooldown unless forced (username change)
        if (shouldSkip) {
            Log.d(TAG, "Presence broadcast skipped (cooldown): $topicName")
            return false
        }
        
        val peerID = p2pLibraryRepository.peerID.value ?: return false
        
        // Build lightweight presence message (~100 bytes)
        val message = """{"type":"presence","nickname":"${nickname.replace("\"", "\\\"")}","peerID":"$peerID","timestamp":$now}"""
        
        return try {
            publishToTopic(topicName, message, nickname)
            synchronized(presenceLock) {
                lastPresenceBroadcast[topicName] = now
            }
            Log.d(TAG, "Broadcast presence to $topicName: $nickname")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast presence to $topicName: ${e.message}")
            false
        }
    }
    
    /**
     * Check if we should respond to a peer's presence message.
     * Tracks responses to prevent infinite ping-pong loops in large groups.
     * 
     * @return true if we should respond with our own presence
     */
    fun shouldRespondToPresence(peerID: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(presenceLock) {
            val lastResponse = respondedToPeers[peerID] ?: 0L

            if (now - lastResponse < AppConstants.P2P.PRESENCE_RESPONSE_COOLDOWN_MS) {
                return false // Already responded to this peer recently
            }

            respondedToPeers[peerID] = now

            // Cleanup old entries (>5 minutes)
            val cutoff = now - AppConstants.P2P.PRESENCE_CLEANUP_THRESHOLD_MS
            respondedToPeers.entries.removeAll { it.value < cutoff }

            return true
        }
    }
    
    /**
     * Get our local peer ID for presence checks.
     */
    fun getMyPeerID(): String? = p2pLibraryRepository.peerID.value
    
    /**
     * Get messages for a topic.
     */
    fun getMessagesForTopic(topicName: String): List<TopicMessage> {
        return _topicMessages.value[topicName] ?: emptyList()
    }
    
    /**
     * Check if subscribed to a topic.
     */
    fun isSubscribed(topicName: String): Boolean {
        return _subscribedTopics.value.any { it.name == topicName }
    }
    
    // ============== Internal ==============
    
    private fun initTopicState(topicName: String) {
        val current = _topicStates.value.toMutableMap()
        // Don't overwrite existing state if already connected
        if (current[topicName] == null) {
            current[topicName] = TopicState(connectionState = TopicConnectionState.CONNECTING)
            _topicStates.value = current
        }
    }
    
    private fun initTopicData(topicName: String) {
        val msgs = _topicMessages.value.toMutableMap()
        if (!msgs.containsKey(topicName)) {
            msgs[topicName] = emptyList()
            _topicMessages.value = msgs
        }
        
        val peers = _topicPeers.value.toMutableMap()
        if (!peers.containsKey(topicName)) {
            peers[topicName] = emptyList()
            _topicPeers.value = peers
        }
    }
    
    private fun cleanupTopicData(topicName: String) {
        val msgs = _topicMessages.value.toMutableMap()
        msgs.remove(topicName)
        _topicMessages.value = msgs
        
        val peers = _topicPeers.value.toMutableMap()
        peers.remove(topicName)
        _topicPeers.value = peers
        
        val states = _topicStates.value.toMutableMap()
        states.remove(topicName)
        _topicStates.value = states
    }
    
    private fun setTopicError(topicName: String, error: String) {
        val current = _topicStates.value.toMutableMap()
        val state = current[topicName] ?: TopicState()
        current[topicName] = state.copy(
            connectionState = TopicConnectionState.ERROR,
            error = error,
            lastUpdated = System.currentTimeMillis()
        )
        _topicStates.value = current
    }
    
    private fun updateTopicState(topicName: String, peers: List<String>) {
        val current = _topicStates.value.toMutableMap()
        val state = current[topicName] ?: TopicState()
        
        val newState = when {
            peers.isNotEmpty() -> TopicConnectionState.CONNECTED
            state.connectionState == TopicConnectionState.ERROR -> TopicConnectionState.ERROR
            else -> TopicConnectionState.CONNECTING
        }
        
        current[topicName] = state.copy(
            connectionState = newState,
            meshPeerCount = peers.size,
            peers = peers,
            error = null,
            lastUpdated = System.currentTimeMillis()
        )
        _topicStates.value = current
    }
    
    private fun addMessageToTopic(topicName: String, message: TopicMessage) {
        val current = _topicMessages.value.toMutableMap()
        val msgs = current[topicName]?.toMutableList() ?: mutableListOf()
        msgs.add(message)
        current[topicName] = msgs
        _topicMessages.value = current
    }
    
    private fun startTopicDiscovery(topicName: String) {
        scope.launch {
            try {
                // Periodically trigger discovery and check peers
                repeat(AppConstants.P2P.TOPIC_DISCOVERY_ITERATIONS) { iteration ->
                    delay(AppConstants.P2P.TOPIC_DISCOVERY_INTERVAL_MS)
                    
                    // Re-trigger DHT discovery every other iteration
                    if (iteration % 2 == 0) {
                        p2pLibraryRepository.refreshTopicPeers(topicName)
                    }
                    
                    // Always refresh the peer list
                    refreshTopicPeers(topicName)
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Topic discovery failed for $topicName: ${e.message}")
            }
        }
    }
    
    private suspend fun refreshTopicPeers(topicName: String) {
        try {
            // Get mesh peers (already connected, can exchange messages)
            val peers = p2pLibraryRepository.getTopicPeers(topicName)
            
            // Get topic stats (includes provider count from DHT)
            val statsJson = p2pLibraryRepository.getTopicStats(topicName)
            val providerCount = parseProviderCount(statsJson)
            
            val current = _topicPeers.value.toMutableMap()
            current[topicName] = peers
            _topicPeers.value = current
            
            // Update state with both mesh peers and provider count
            updateTopicStateWithStats(topicName, peers, providerCount)
            
            Log.d(TAG, "Topic $topicName: ${peers.size} mesh peers, $providerCount providers")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh peers for $topicName: ${e.message}")
        }
    }
    
    private fun updateTopicStateWithStats(topicName: String, peers: List<String>, providerCount: Int) {
        val current = _topicStates.value.toMutableMap()
        val state = current[topicName] ?: TopicState()
        
        val newConnectionState = when {
            peers.isNotEmpty() -> TopicConnectionState.CONNECTED
            state.connectionState == TopicConnectionState.ERROR -> TopicConnectionState.ERROR
            else -> TopicConnectionState.CONNECTING
        }
        
        Log.d(TAG, "TopicState update for $topicName: peers=${peers.size} providers=$providerCount state=${state.connectionState}->$newConnectionState")
        
        current[topicName] = state.copy(
            connectionState = newConnectionState,
            meshPeerCount = peers.size,
            providerCount = providerCount,
            peers = peers,
            error = null,
            lastUpdated = System.currentTimeMillis()
        )
        _topicStates.value = current
    }
    
    private fun parseProviderCount(statsJson: String): Int {
        return try {
            val regex = """"providerCount":(\d+)""".toRegex()
            regex.find(statsJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    // ============== Persistence ==============
    
    private fun loadSavedTopics() {
        val json = prefs.getString("topics", "[]") ?: "[]"
        try {
            val topicNames = json.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
                .filter { !it.startsWith("meta:") } // Remove legacy meta topics

            _subscribedTopics.value = topicNames.map { TopicInfo(it) }

            // Initialize states
            val initialStates = mutableMapOf<String, TopicState>()
            topicNames.forEach { name ->
                initialStates[name] = TopicState(connectionState = TopicConnectionState.CONNECTING)
            }
            _topicStates.value = initialStates

            Log.d(TAG, "Loaded ${topicNames.size} saved topics")

            // Persist cleaned list (removes any legacy meta: topics)
            saveTopics()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved topics: ${e.message}")
            _subscribedTopics.value = emptyList()
        }
    }
    
    private fun saveTopics() {
        val topicNames = _subscribedTopics.value.map { "\"${it.name}\"" }
        prefs.edit().putString("topics", "[${topicNames.joinToString(",")}]").apply()
    }
}
