package com.roman.zemzeme.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roman.zemzeme.nostr.GeohashMessageHandler
import com.roman.zemzeme.nostr.GeohashRepository
import com.roman.zemzeme.nostr.NostrDirectMessageHandler
import com.roman.zemzeme.nostr.NostrIdentityBridge
import com.roman.zemzeme.nostr.NostrProtocol
import com.roman.zemzeme.nostr.NostrRelayManager
import com.roman.zemzeme.nostr.NostrSubscriptionManager
import com.roman.zemzeme.nostr.PoWPreferenceManager
import com.roman.zemzeme.nostr.GeohashAliasRegistry
import com.roman.zemzeme.nostr.GeohashConversationRegistry
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.p2p.P2PNodeStatus
import com.roman.zemzeme.p2p.P2PTopicsRepository
import com.roman.zemzeme.p2p.P2PTransport
import com.roman.zemzeme.p2p.TopicMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers

class GeohashViewModel(
    application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val dataManager: DataManager,
    private val notificationManager: NotificationManager
) : AndroidViewModel(application) {

    companion object { private const val TAG = "GeohashViewModel" }

    private val repo = GeohashRepository(application, state, dataManager)
    private val subscriptionManager = NostrSubscriptionManager(application, viewModelScope)
    private val geohashMessageHandler = GeohashMessageHandler(
        application = application,
        state = state,
        messageManager = messageManager,
        repo = repo,
        scope = viewModelScope,
        dataManager = dataManager
    )
    private val dmHandler = NostrDirectMessageHandler(
        application = application,
        state = state,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        scope = viewModelScope,
        repo = repo,
        dataManager = dataManager
    )

    // P2P integration
    private val p2pConfig = P2PConfig(application)
    private val p2pTransport = P2PTransport.getInstance(application)
    private val p2pTopicsRepository = P2PTopicsRepository(application, p2pTransport.p2pRepository)

    private var currentGeohashSubId: String? = null
    private var currentDmSubId: String? = null
    private var geoTimer: Job? = null
    private var globalPresenceJob: Job? = null
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null

    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    
    // P2P topic states for connection status UI
    val p2pTopicStates: StateFlow<Map<String, com.bitchat.android.p2p.TopicState>> = p2pTopicsRepository.topicStates

    fun initialize() {
        subscriptionManager.connect()
        val identity = NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
        if (identity != null) {
            // Use global chat-messages only for full account DMs (mesh context). For geohash DMs, subscribe per-geohash below.
            subscriptionManager.subscribeGiftWraps(
                pubkey = identity.publicKeyHex,
                sinceMs = System.currentTimeMillis() - 172800000L,
                id = "chat-messages",
                handler = { event -> dmHandler.onGiftWrap(event, "", identity) } // geohash="" means global account DM (not geohash identity)
            )
        }
        try {
            locationChannelManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(getApplication())
            viewModelScope.launch {
                locationChannelManager?.selectedChannel?.collect { channel ->
                    state.setSelectedLocationChannel(channel)
                    switchLocationChannel(channel)
                }
            }
            viewModelScope.launch {
                locationChannelManager?.teleported?.collect { teleported ->
                    state.setIsTeleported(teleported)
                }
            }
            
            // Start global presence heartbeat loop
            startGlobalPresenceHeartbeat()
            
            // Collect incoming P2P topic messages for geohash channels
            viewModelScope.launch {
                p2pTopicsRepository.incomingMessages.collect { msg ->
                    if (msg.topicName.startsWith("geo:")) {
                        val geohash = msg.topicName.removePrefix("geo:")
                        handleIncomingP2PGeohashMessage(msg, geohash)
                    }
                }
            }
            
            // Watch for P2P node to become RUNNING and subscribe to current channel
            // This fixes the startup race condition where channel is restored before P2P is ready
            viewModelScope.launch {
                p2pTransport.p2pRepository.nodeStatus.collect { status ->
                    if (status == P2PNodeStatus.RUNNING) {
                        val currentChannel = state.selectedLocationChannel.value
                        if (currentChannel is com.bitchat.android.geohash.ChannelID.Location && p2pConfig.p2pEnabled) {
                            val topicName = "geo:${currentChannel.channel.geohash}"
                            try {
                                // Check if already subscribed to avoid duplicates
                                if (!p2pTopicsRepository.isSubscribed(topicName)) {
                                    Log.d(TAG, "🌐 P2P node ready - subscribing to current channel: $topicName")
                                    p2pTopicsRepository.subscribeTopic(topicName)
                                    p2pTopicsRepository.discoverTopicPeers(topicName)
                                    p2pTopicsRepository.startContinuousRefresh(topicName)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed late P2P subscription: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            // Watch for P2P peer discovery and merge peers into the people list proactively
            viewModelScope.launch {
                p2pTopicsRepository.topicPeers.collect { peersMap ->
                    val currentChannel = state.selectedLocationChannel.value
                    if (currentChannel is com.bitchat.android.geohash.ChannelID.Location) {
                        val topicName = "geo:${currentChannel.channel.geohash}"
                        val peers = peersMap[topicName] ?: emptyList()
                        if (peers.isNotEmpty()) {
                            // Merge P2P peers into geohashPeople with "p2p:" prefix
                            val p2pPeerIds = peers.map { "p2p:$it" }
                            state.mergeP2PPeers(p2pPeerIds)
                            Log.d(TAG, "🔄 Updated people list with ${peers.size} P2P peers")
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize location channel state: ${e.message}")
            state.setSelectedLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
            state.setIsTeleported(false)
        }
    }

    private fun startGlobalPresenceHeartbeat() {
        globalPresenceJob?.cancel()
        globalPresenceJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Reactively restart heartbeat whenever available channels change
            locationChannelManager?.availableChannels?.collectLatest { channels ->
                // Filter for REGION (2), PROVINCE (4), CITY (5) - precision <= 5
                val targetGeohashes = channels.filter { it.level.precision <= 5 }.map { it.geohash }

                if (targetGeohashes.isNotEmpty()) {
                    // Enter heartbeat loop for this set of channels
                    // If channels change (e.g. user moves), collectLatest cancels this loop and starts a new one immediately
                    while (true) {
                        // Randomize loop interval (40-80s, average 60s)
                        val loopInterval = kotlin.random.Random.nextLong(40000L, 80000L)
                        var timeSpent = 0L

                        try {
                            Log.v(TAG, "💓 Broadcasting global presence to ${targetGeohashes.size} channels")
                            targetGeohashes.forEach { geohash ->
                                // Decorrelate individual broadcasts with random delay (1s-5s)
                                val stepDelay = kotlin.random.Random.nextLong(1000L, 10000L)
                                delay(stepDelay)
                                timeSpent += stepDelay
                                
                                broadcastPresence(geohash)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Global presence heartbeat error: ${e.message}")
                        }
                        
                        // Wait remaining time to satisfy target average cadence
                        val remaining = loopInterval - timeSpent
                        if (remaining > 0) {
                            delay(remaining)
                        } else {
                            delay(10000L) // Minimum guard delay
                        }
                    }
                }
            }
        }
    }

    fun panicReset() {
        repo.clearAll()
        GeohashAliasRegistry.clear()
        GeohashConversationRegistry.clear()
        subscriptionManager.disconnect()
        currentGeohashSubId = null
        currentDmSubId = null
        geoTimer?.cancel()
        geoTimer = null
        globalPresenceJob?.cancel()
        globalPresenceJob = null
        try { NostrIdentityBridge.clearAllAssociations(getApplication()) } catch (_: Exception) {}
        initialize()
    }

    private suspend fun broadcastPresence(geohash: String) {
        try {
            val identity = NostrIdentityBridge.deriveIdentity(geohash, getApplication())
            val event = NostrProtocol.createGeohashPresenceEvent(geohash, identity)
            val relayManager = NostrRelayManager.getInstance(getApplication())
            // Presence is lightweight, send to geohash relays
            relayManager.sendEventToGeohash(event, geohash, includeDefaults = false, nRelays = 5)
            Log.v(TAG, "💓 Sent presence heartbeat for $geohash")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send presence for $geohash: ${e.message}")
        }
    }

    fun sendGeohashMessage(content: String, channel: com.bitchat.android.geohash.GeohashChannel, myPeerID: String, nickname: String?) {
        viewModelScope.launch {
            try {
                val tempId = "temp_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000)}"
                val pow = PoWPreferenceManager.getCurrentSettings()
                val localMsg = com.bitchat.android.model.BitchatMessage(
                    id = tempId,
                    sender = nickname ?: myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = "geohash:${channel.geohash}",
                    channel = "#${channel.geohash}",
                    powDifficulty = if (pow.enabled) pow.difficulty else null
                )
                messageManager.addChannelMessage("geo:${channel.geohash}", localMsg)
                val startedMining = pow.enabled && pow.difficulty > 0
                if (startedMining) {
                    com.bitchat.android.ui.PoWMiningTracker.startMiningMessage(tempId)
                }
                
                var sentViaP2P = false
                
                // Try P2P first if enabled and running
                if (p2pConfig.p2pEnabled && 
                    p2pTransport.p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING) {
                    try {
                        val topicName = "geo:${channel.geohash}"
                        // Create a wire message with metadata
                        val wireMessage = buildP2PGeohashMessage(content, channel.geohash, nickname)
                        p2pTopicsRepository.publishToTopic(topicName, wireMessage, nickname)
                        Log.d(TAG, "🌐 Published geohash message via P2P topic: $topicName")
                        sentViaP2P = true
                    } catch (e: Exception) {
                        Log.w(TAG, "P2P topic publish failed, falling back to Nostr: ${e.message}")
                    }
                }
                
                // Send via Nostr (as primary or fallback)
                if (p2pConfig.nostrEnabled) {
                    try {
                        val identity = NostrIdentityBridge.deriveIdentity(forGeohash = channel.geohash, context = getApplication())
                        val teleported = state.isTeleported.value
                        val event = NostrProtocol.createEphemeralGeohashEvent(content, channel.geohash, identity, nickname, teleported)
                        val relayManager = NostrRelayManager.getInstance(getApplication())
                        relayManager.sendEventToGeohash(event, channel.geohash, includeDefaults = false, nRelays = 5)
                        if (!sentViaP2P) {
                            Log.d(TAG, "📡 Sent geohash message via Nostr only")
                        }
                    } finally {
                        // Ensure we stop the per-message mining animation regardless of success/failure
                        if (startedMining) {
                            com.bitchat.android.ui.PoWMiningTracker.stopMiningMessage(tempId)
                        }
                    }
                } else if (!sentViaP2P) {
                    Log.w(TAG, "No transport available for geohash message (P2P: ${p2pConfig.p2pEnabled}, Nostr: ${p2pConfig.nostrEnabled})")
                    if (startedMining) {
                        com.bitchat.android.ui.PoWMiningTracker.stopMiningMessage(tempId)
                    }
                } else {
                    // Sent via P2P only, stop mining tracker
                    if (startedMining) {
                        com.bitchat.android.ui.PoWMiningTracker.stopMiningMessage(tempId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash message: ${e.message}")
            }
        }
    }

    fun beginGeohashSampling(geohashes: List<String>) {
        if (geohashes.isEmpty()) return
        Log.d(TAG, "🌍 Beginning geohash sampling for ${geohashes.size} geohashes")
        
        // Subscribe to events
        viewModelScope.launch {
            geohashes.forEach { geohash ->
                subscriptionManager.subscribeGeohash(
                    geohash = geohash,
                    sinceMs = System.currentTimeMillis() - 86400000L,
                    limit = 200,
                    id = "sampling-$geohash",
                    handler = { event -> geohashMessageHandler.onEvent(event, geohash) }
                )
            }
        }
    }

    fun endGeohashSampling() { 
        Log.d(TAG, "🌍 Ending geohash sampling")
    }
    fun geohashParticipantCount(geohash: String): Int = repo.geohashParticipantCount(geohash)
    fun isPersonTeleported(pubkeyHex: String): Boolean = repo.isPersonTeleported(pubkeyHex)

    fun startGeohashDM(pubkeyHex: String, onStartPrivateChat: (String) -> Unit) {
        val convKey = "nostr_${pubkeyHex.take(16)}"
        repo.putNostrKeyMapping(convKey, pubkeyHex)
        // Record the conversation's geohash using the currently selected location channel (if any)
        val current = state.selectedLocationChannel.value
        val gh = (current as? com.bitchat.android.geohash.ChannelID.Location)?.channel?.geohash
        if (!gh.isNullOrEmpty()) {
            repo.setConversationGeohash(convKey, gh)
            GeohashConversationRegistry.set(convKey, gh)
        }
        onStartPrivateChat(convKey)
        Log.d(TAG, "🗨️ Started geohash DM with ${pubkeyHex} -> ${convKey} (geohash=${gh})")
    }

    fun getNostrKeyMapping(): Map<String, String> = repo.getNostrKeyMapping()

    fun blockUserInGeohash(targetNickname: String) {
        val pubkey = repo.findPubkeyByNickname(targetNickname)
        if (pubkey != null) {
            dataManager.addGeohashBlockedUser(pubkey)
            // Refresh people list and counts to remove blocked entry immediately
            repo.refreshGeohashPeople()
            repo.updateReactiveParticipantCounts()
            val sysMsg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "blocked $targetNickname in geohash channels",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(sysMsg)
        } else {
            val sysMsg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "user '$targetNickname' not found in current geohash",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(sysMsg)
        }
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        locationChannelManager?.select(channel) ?: run { Log.w(TAG, "Cannot select location channel - not initialized") }
    }

    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String = repo.displayNameForNostrPubkeyUI(pubkeyHex)
    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String = repo.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)

    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        val seed = "nostr:${pubkeyHex.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }
    
    /**
     * Trigger manual P2P peer discovery refresh.
     * Call this from UI refresh button to find and connect to more peers.
     */
    fun refreshP2PPeers() {
        val channel = state.selectedLocationChannel.value
        if (channel is com.bitchat.android.geohash.ChannelID.Location &&
            p2pConfig.p2pEnabled &&
            p2pTransport.p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING) {
            viewModelScope.launch(Dispatchers.IO) {
                val topicName = "geo:${channel.channel.geohash}"
                Log.d(TAG, "🔄 Manual P2P refresh triggered for: $topicName")
                p2pTopicsRepository.discoverTopicPeers(topicName)
            }
        } else {
            Log.d(TAG, "🔄 Manual P2P refresh skipped - P2P not enabled or no location channel")
        }
    }
    
    /**
     * Get the current P2P topic state for the selected geohash channel.
     */
    fun getP2PTopicState(): com.bitchat.android.p2p.TopicState? {
        val channel = state.selectedLocationChannel.value
        if (channel is com.bitchat.android.geohash.ChannelID.Location) {
            return p2pTopicsRepository.getTopicState("geo:${channel.channel.geohash}")
        }
        return null
    }
    
    /**
     * Get the connected P2P peers for the current channel.
     */
    fun getP2PTopicPeers(): List<String> {
        val channel = state.selectedLocationChannel.value
        if (channel is com.bitchat.android.geohash.ChannelID.Location) {
            return p2pTopicsRepository.getPeersForTopic("geo:${channel.channel.geohash}")
        }
        return emptyList()
    }

    private fun switchLocationChannel(channel: com.bitchat.android.geohash.ChannelID?) {
        geoTimer?.cancel(); geoTimer = null
        currentGeohashSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashSubId = null }
        currentDmSubId?.let { subscriptionManager.unsubscribe(it); currentDmSubId = null }
        
        // Stop any active P2P continuous refresh when switching channels
        p2pTopicsRepository.stopContinuousRefresh()

        when (channel) {
            is com.bitchat.android.geohash.ChannelID.Mesh -> {
                Log.d(TAG, "📡 Switched to mesh channel")
                repo.setCurrentGeohash(null)
                notificationManager.setCurrentGeohash(null)
                notificationManager.clearMeshMentionNotifications()
                repo.refreshGeohashPeople()
            }
            is com.bitchat.android.geohash.ChannelID.Location -> {
                Log.d(TAG, "📍 Switching to geohash channel: ${channel.channel.geohash}")
                repo.setCurrentGeohash(channel.channel.geohash)
                notificationManager.setCurrentGeohash(channel.channel.geohash)
                notificationManager.clearNotificationsForGeohash(channel.channel.geohash)
                try { messageManager.clearChannelUnreadCount("geo:${channel.channel.geohash}") } catch (_: Exception) { }

                try {
                    val identity = NostrIdentityBridge.deriveIdentity(channel.channel.geohash, getApplication())
                    // We don't update participant here anymore; presence loop handles it via Kind 20001
                    val teleported = state.isTeleported.value
                    if (teleported) repo.markTeleported(identity.publicKeyHex)
                } catch (e: Exception) { Log.w(TAG, "Failed identity setup: ${e.message}") }

                startGeoParticipantsTimer()
                
                viewModelScope.launch {
                    val geohash = channel.channel.geohash
                    val subId = "geohash-$geohash"; currentGeohashSubId = subId
                    
                    // Subscribe via Nostr (if enabled)
                    if (p2pConfig.nostrEnabled) {
                        subscriptionManager.subscribeGeohash(
                            geohash = geohash,
                            sinceMs = System.currentTimeMillis() - 3600000L,
                            limit = 200,
                            id = subId,
                            handler = { event -> geohashMessageHandler.onEvent(event, geohash) }
                        )
                        val dmIdentity = NostrIdentityBridge.deriveIdentity(geohash, getApplication())
                        val dmSubId = "geo-dm-$geohash"; currentDmSubId = dmSubId
                        subscriptionManager.subscribeGiftWraps(
                            pubkey = dmIdentity.publicKeyHex,
                            sinceMs = System.currentTimeMillis() - 172800000L,
                            id = dmSubId,
                            handler = { event -> dmHandler.onGiftWrap(event, geohash, dmIdentity) }
                        )
                        // Also register alias in global registry for routing convenience
                        GeohashAliasRegistry.put("nostr_${dmIdentity.publicKeyHex.take(16)}", dmIdentity.publicKeyHex)
                    }
                    
                    // Subscribe via P2P topic (if enabled and running)
                    if (p2pConfig.p2pEnabled && 
                        p2pTransport.p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING) {
                        try {
                            val topicName = "geo:$geohash"
                            p2pTopicsRepository.subscribeTopic(topicName)
                            Log.d(TAG, "🌐 Subscribed to P2P topic: $topicName")
                            
                            // CRITICAL: Trigger DHT discovery to find and connect to peers
                            // This is what makes the reference app work when pressing refresh!
                            p2pTopicsRepository.discoverTopicPeers(topicName)
                            Log.d(TAG, "🔍 Started P2P peer discovery for: $topicName")
                            
                            // CRITICAL: Start continuous 5-second refresh loop
                            // This matches the reference app's behavior for reliable peer discovery
                            p2pTopicsRepository.startContinuousRefresh(topicName)
                            Log.d(TAG, "🔄 Started continuous P2P refresh for: $topicName")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to subscribe/discover P2P topic: ${e.message}")
                        }
                    }
                }
            }
            null -> {
                Log.d(TAG, "📡 No channel selected")
                repo.setCurrentGeohash(null)
                repo.refreshGeohashPeople()
            }
        }
    }

    private fun startGeoParticipantsTimer() {
        geoTimer = viewModelScope.launch {
            while (repo.getCurrentGeohash() != null) {
                delay(30000)
                repo.refreshGeohashPeople()
            }
        }
    }
    
    // ============== P2P Helper Functions ==============
    
    /**
     * Build a P2P wire message for geohash topic publishing.
     * Format: JSON with content, geohash, sender nickname, timestamp.
     */
    private fun buildP2PGeohashMessage(content: String, geohash: String, nickname: String?): String {
        val timestamp = System.currentTimeMillis()
        val peerID = p2pTransport.getMyPeerID() ?: "unknown"
        val teleported = state.isTeleported.value
        // Simple JSON format compatible with P2P topic messages
        return """{"type":"geohash","content":"${content.replace("\"", "\\\"")}","geohash":"$geohash","sender":"${nickname ?: peerID}","peerID":"$peerID","teleported":$teleported,"timestamp":$timestamp}"""
    }
    
    /**
     * Handle incoming P2P geohash topic message.
     * Parse the wire format and display in the channel.
     */
    private fun handleIncomingP2PGeohashMessage(msg: TopicMessage, geohash: String) {
        try {
            // Parse the JSON message
            val json = org.json.JSONObject(msg.content)
            val content = json.optString("content", "")
            val sender = json.optString("sender", msg.senderID)
            val peerID = json.optString("peerID", msg.senderID)
            val teleported = json.optBoolean("teleported", false)
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            
            // Check if this is our own message (don't display duplicates)
            val myPeerID = p2pTransport.getMyPeerID()
            if (peerID == myPeerID) {
                Log.v(TAG, "Ignoring own P2P message from $peerID")
                return
            }
            
            Log.d(TAG, "🌐 Received P2P geohash message from $sender in geo:$geohash")
            
            val bitchatMessage = com.bitchat.android.model.BitchatMessage(
                id = "p2p_${msg.senderID}_$timestamp",
                sender = sender,
                content = content,
                timestamp = Date(timestamp),
                isRelay = false,
                senderPeerID = "p2p:$peerID",
                channel = "#$geohash",
                powDifficulty = null
            )
            
            messageManager.addChannelMessage("geo:$geohash", bitchatMessage)
            
            // Track this person in the geohash people list
            // Use P2P peer ID as a synthetic participant ID (prefixed to avoid collision with Nostr pubkeys)
            val participantId = "p2p:$peerID"
            repo.updateParticipant(geohash, participantId, Date(timestamp))
            repo.cacheNickname(participantId, sender)
            if (teleported) {
                repo.markTeleported(participantId)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse P2P geohash message: ${e.message}")
        }
    }
}
