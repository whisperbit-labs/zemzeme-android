package com.roman.zemzeme.ui

import android.app.Application
import android.content.pm.ApplicationInfo
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
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class GeohashViewModel(
    application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val dataManager: DataManager,
    private val notificationManager: NotificationManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "GeohashViewModel"
        private const val P2P_META_PREFIX = "meta:"
    }

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
    // Use singleton to prevent duplicate message collectors when multiple ViewModels exist
    private val p2pTopicsRepository = P2PTopicsRepository.getInstance(application, p2pTransport.p2pRepository)

    private var currentGeohashSubId: String? = null
    private var currentDmSubId: String? = null
    private var geoTimer: Job? = null
    private var globalPresenceJob: Job? = null
    private var locationChannelManager: com.roman.zemzeme.geohash.LocationChannelManager? = null
    private var locationSelectedChannelJob: Job? = null
    private var locationTeleportedJob: Job? = null
    
    // P2P watcher jobs for cleanup (prevents accumulated collectors)
    private var p2pNodeStatusJob: Job? = null
    private var p2pTopicStatesJob: Job? = null
    private var p2pMessagesJob: Job? = null
    private val p2pMessageSequence = AtomicLong(0L)
    private val lastConnectedP2PPeerCounts = ConcurrentHashMap<String, Int>()
    private val selfFilteredTopicSyncCount = AtomicLong(0L)
    private val lastSelfFilterLogAtMs = AtomicLong(0L)
    private val isDebuggableBuild: Boolean by lazy {
        (getApplication<Application>().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val selectedLocationChannel: StateFlow<com.roman.zemzeme.geohash.ChannelID?> = state.selectedLocationChannel
    
    // P2P topic states for connection status UI
    val p2pTopicStates: StateFlow<Map<String, com.roman.zemzeme.p2p.TopicState>> = p2pTopicsRepository.topicStates

    fun initialize() {
        // Cancel location-state collectors before re-registering them.
        // Without this, repeated initialize()/panicReset() can stack collectors.
        locationSelectedChannelJob?.cancel()
        locationSelectedChannelJob = null
        locationTeleportedJob?.cancel()
        locationTeleportedJob = null

        // Cancel any existing P2P watcher jobs to prevent duplicate collectors
        // This fixes the 4x message duplication bug when initialize() is called multiple times
        p2pMessagesJob?.cancel()
        p2pMessagesJob = null
        p2pNodeStatusJob?.cancel()
        p2pNodeStatusJob = null
        p2pTopicStatesJob?.cancel()
        p2pTopicStatesJob = null
        lastConnectedP2PPeerCounts.clear()
        selfFilteredTopicSyncCount.set(0L)
        lastSelfFilterLogAtMs.set(0L)

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
            locationChannelManager = com.roman.zemzeme.geohash.LocationChannelManager.getInstance(getApplication())
            locationSelectedChannelJob = viewModelScope.launch {
                locationChannelManager?.selectedChannel?.collect { channel ->
                    state.setSelectedLocationChannel(channel)
                    switchLocationChannel(channel)
                }
            }
            locationTeleportedJob = viewModelScope.launch {
                locationChannelManager?.teleported?.collect { teleported ->
                    state.setIsTeleported(teleported)
                }
            }
            
            // Start global presence heartbeat loop
            startGlobalPresenceHeartbeat()
            
            // Collect incoming P2P topic messages for geohash channels
            p2pMessagesJob = viewModelScope.launch(Dispatchers.Default) {
                p2pTopicsRepository.incomingMessages.collect { msg ->
                    try {
                        val topicName = msg.topicName
                        if (topicName.isNotBlank()) {
                            val isMeta = topicName.startsWith(P2P_META_PREFIX)
                            val geohash = if (isMeta) topicName.removePrefix(P2P_META_PREFIX) else topicName
                            if (geohash.isNotBlank()) {
                                handleIncomingP2PTopicMessage(msg, geohash, isMeta)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process P2P topic message: ${e.message}", e)
                    }
                }
            }
            
            // Watch for P2P node to become RUNNING and subscribe ALL groups
            // This fixes the startup race condition where channel is restored before P2P is ready
            p2pNodeStatusJob = viewModelScope.launch {
                p2pTransport.p2pRepository.nodeStatus.collect { status ->
                    if (status == P2PNodeStatus.RUNNING && p2pConfig.p2pEnabled) {
                        // Collect all group geohashes (custom + geographic)
                        val allGroups = mutableSetOf<String>()
                        allGroups.addAll(state.getCustomGroupsValue())
                        allGroups.addAll(state.getGeographicGroupsValue())
                        // Also include the current location channel if any
                        val currentChannel = state.selectedLocationChannel.value
                        if (currentChannel is com.roman.zemzeme.geohash.ChannelID.Location) {
                            allGroups.add(currentChannel.channel.geohash)
                        }

                        Log.d(TAG, "P2P node ready - subscribing to ${allGroups.size} groups: $allGroups")
                        for (geohash in allGroups) {
                            try {
                                val topicName = p2pMainTopicName(geohash)
                                if (!p2pTopicsRepository.isSubscribed(topicName)) {
                                    p2pTopicsRepository.subscribeTopic(topicName)
                                }
                                p2pTopicsRepository.discoverTopicPeers(topicName)
                                p2pTopicsRepository.startContinuousRefresh(topicName)
                                Log.d(TAG, "Subscribed to P2P topic for group: $geohash")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed P2P subscription for $geohash: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            // Watch live topic states (updated every refresh cycle) and keep participant
            // counts aligned with currently connected P2P peers.
            p2pTopicStatesJob = viewModelScope.launch {
                p2pTopicsRepository.topicStates.collect { topicStates ->
                    val currentChannel = state.selectedLocationChannel.value
                    if (currentChannel is com.roman.zemzeme.geohash.ChannelID.Location) {
                        val geohash = currentChannel.channel.geohash
                        val topicName = p2pMainTopicName(geohash)

                        val mainPeers = topicStates[topicName]?.peers ?: emptyList()
                        val myPeerID = p2pTransport.getMyPeerID()?.trim()
                        val normalizedPeers = mainPeers
                            .asSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .sorted()
                            .toList()
                        val connectedPeers = if (myPeerID.isNullOrBlank()) {
                            normalizedPeers
                        } else {
                            normalizedPeers.filter { it != myPeerID }
                        }

                        if (!myPeerID.isNullOrBlank() && normalizedPeers.size != connectedPeers.size) {
                            logSelfFilteredFromTopicSync(
                                geohash = geohash,
                                myPeerID = myPeerID,
                                rawPeerCount = normalizedPeers.size,
                                filteredPeerCount = connectedPeers.size
                            )
                        }

                        // Keep participant state synchronized with actual connected peers.
                        repo.syncConnectedP2PPeers(geohash, connectedPeers, Date())

                        val previousCount = lastConnectedP2PPeerCounts[geohash] ?: 0
                        val currentCount = connectedPeers.size
                        lastConnectedP2PPeerCounts[geohash] = currentCount

                        if (currentCount > 0) {
                            Log.d(TAG, "🔄 Synced $currentCount connected P2P peers for geo:$geohash")
                        }

                        // Announce presence once when connectivity becomes active.
                        if (previousCount == 0 && currentCount > 0) {
                            val myNickname = state.getNicknameValue() ?: "anon"
                            p2pTopicsRepository.broadcastPresence(topicName, myNickname)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize location channel state: ${e.message}")
            state.setSelectedLocationChannel(com.roman.zemzeme.geohash.ChannelID.Mesh)
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
        locationSelectedChannelJob?.cancel()
        locationSelectedChannelJob = null
        locationTeleportedJob?.cancel()
        locationTeleportedJob = null
        // Cancel P2P watcher jobs (also done in initialize(), but explicit here for clarity)
        p2pMessagesJob?.cancel()
        p2pMessagesJob = null
        p2pNodeStatusJob?.cancel()
        p2pNodeStatusJob = null
        p2pTopicStatesJob?.cancel()
        p2pTopicStatesJob = null
        lastConnectedP2PPeerCounts.clear()
        selfFilteredTopicSyncCount.set(0L)
        lastSelfFilterLogAtMs.set(0L)
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

    fun sendGeohashMessage(content: String, channel: com.roman.zemzeme.geohash.GeohashChannel, myPeerID: String, nickname: String?) {
        viewModelScope.launch {
            try {
                val tempId = "temp_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(1000)}"
                val pow = PoWPreferenceManager.getCurrentSettings()
                val localMsg = com.roman.zemzeme.model.ZemzemeMessage(
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
                    com.roman.zemzeme.ui.PoWMiningTracker.startMiningMessage(tempId)
                }
                
                var sentViaP2P = false
                
                // Try P2P first if enabled and running
                if (p2pConfig.p2pEnabled && 
                    p2pTransport.p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING) {
                    try {
                        val topicName = p2pMainTopicName(channel.geohash)
                        p2pTopicsRepository.publishToTopic(topicName, content, nickname)
                        Log.d(TAG, "Published geohash message via P2P topic: $topicName")
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
                            com.roman.zemzeme.ui.PoWMiningTracker.stopMiningMessage(tempId)
                        }
                    }
                } else if (!sentViaP2P) {
                    Log.w(TAG, "No transport available for geohash message (P2P: ${p2pConfig.p2pEnabled}, Nostr: ${p2pConfig.nostrEnabled})")
                    if (startedMining) {
                        com.roman.zemzeme.ui.PoWMiningTracker.stopMiningMessage(tempId)
                    }
                } else {
                    // Sent via P2P only, stop mining tracker
                    if (startedMining) {
                        com.roman.zemzeme.ui.PoWMiningTracker.stopMiningMessage(tempId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash message: ${e.message}")
            }
        }
    }

    fun sendGeohashMedia(
        filePath: String,
        mimeType: String,
        msgType: com.roman.zemzeme.model.ZemzemeMessageType,
        channel: com.roman.zemzeme.geohash.GeohashChannel,
        myPeerID: String,
        nickname: String?
    ) {
        viewModelScope.launch {
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "sendGeohashMedia: file not found: $filePath")
                    return@launch
                }
                val messageID = java.util.UUID.randomUUID().toString().uppercase()

                // Show locally immediately
                val localMsg = com.roman.zemzeme.model.ZemzemeMessage(
                    id = messageID,
                    sender = nickname ?: myPeerID,
                    content = filePath,
                    type = msgType,
                    timestamp = java.util.Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    channel = "#${channel.geohash}"
                )
                withContext(Dispatchers.Main) {
                    messageManager.addChannelMessage("geo:${channel.geohash}", localMsg)
                }

                // Encode as ZemzemeFilePacket TLV
                val filePacket = com.roman.zemzeme.model.ZemzemeFilePacket(
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = mimeType,
                    content = file.readBytes()
                )
                val fileBytes = filePacket.encode() ?: run {
                    Log.e(TAG, "sendGeohashMedia: failed to encode file packet")
                    return@launch
                }

                // Embed as bitchat1: base64url in Nostr ephemeral event content
                val b64 = android.util.Base64.encodeToString(
                    fileBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                val eventContent = "bitchat1:$b64"

                // Try P2P topic first (primary transport for geohash channels)
                var sentViaP2P = false
                if (p2pConfig.p2pEnabled &&
                    p2pTransport.p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING) {
                    try {
                        val topicName = p2pMainTopicName(channel.geohash)
                        p2pTopicsRepository.publishToTopic(topicName, eventContent, nickname)
                        Log.d(TAG, "📤 Published geohash media via P2P topic: ${file.name} (${fileBytes.size} bytes)")
                        sentViaP2P = true
                    } catch (e: Exception) {
                        Log.w(TAG, "P2P topic media publish failed, trying Nostr: ${e.message}")
                    }
                }

                // Send via Nostr (as primary or fallback)
                if (p2pConfig.nostrEnabled) {
                    val identity = NostrIdentityBridge.deriveIdentity(
                        forGeohash = channel.geohash,
                        context = getApplication()
                    )
                    val teleported = state.isTeleported.value
                    val event = NostrProtocol.createEphemeralGeohashEvent(
                        eventContent, channel.geohash, identity, nickname, teleported
                    )
                    val relayManager = NostrRelayManager.getInstance(getApplication())
                    relayManager.sendEventToGeohash(event, channel.geohash, includeDefaults = false, nRelays = 5)
                    if (!sentViaP2P) Log.d(TAG, "📤 Sent geohash media via Nostr only: ${file.name}")
                } else if (!sentViaP2P) {
                    Log.w(TAG, "sendGeohashMedia: no transport available")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendGeohashMedia failed: ${e.message}")
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
        val gh = (current as? com.roman.zemzeme.geohash.ChannelID.Location)?.channel?.geohash
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
            val sysMsg = com.roman.zemzeme.model.ZemzemeMessage(
                sender = "system",
                content = "blocked $targetNickname in geohash channels",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(sysMsg)
        } else {
            val sysMsg = com.roman.zemzeme.model.ZemzemeMessage(
                sender = "system",
                content = "user '$targetNickname' not found in current geohash",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(sysMsg)
        }
    }

    fun selectLocationChannel(channel: com.roman.zemzeme.geohash.ChannelID) {
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
        if (channel is com.roman.zemzeme.geohash.ChannelID.Location &&
            p2pConfig.p2pEnabled &&
            p2pTransport.p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING) {
            viewModelScope.launch(Dispatchers.IO) {
                val geohash = channel.channel.geohash
                val topicName = p2pMainTopicName(geohash)
                Log.d(TAG, "Manual P2P refresh triggered for: $topicName")
                p2pTopicsRepository.discoverTopicPeers(topicName)
            }
        } else {
            Log.d(TAG, "🔄 Manual P2P refresh skipped - P2P not enabled or no location channel")
        }
    }
    
    /**
     * Get the current P2P topic state for the selected geohash channel.
     */
    fun getP2PTopicState(): com.roman.zemzeme.p2p.TopicState? {
        val channel = state.selectedLocationChannel.value
        if (channel is com.roman.zemzeme.geohash.ChannelID.Location) {
            return p2pTopicsRepository.getTopicState(p2pMainTopicName(channel.channel.geohash))
        }
        return null
    }
    
    /**
     * Get the connected P2P peers for the current channel.
     */
    fun getP2PTopicPeers(): List<String> {
        val channel = state.selectedLocationChannel.value
        if (channel is com.roman.zemzeme.geohash.ChannelID.Location) {
            return p2pTopicsRepository.getPeersForTopic(p2pMainTopicName(channel.channel.geohash))
        }
        return emptyList()
    }
    
    /**
     * Get the display name for a P2P peer ID from cached geohash participants.
     * @param p2pPeerId The P2P peer ID (e.g., "p2p:12D3KooW...")
     * @return The cached display name, or null if not found
     */
    fun getP2PDisplayName(p2pPeerId: String): String? {
        // Look up in geohash people list
        val people = state.geohashPeople.value
        val person = people.find { it.id == p2pPeerId }
        if (person != null) {
            return person.displayName
        }
        
        // Fallback: check repo's nickname cache
        return repo.getCachedNickname(p2pPeerId)
    }

    /**
     * Get all cached P2P and Nostr nicknames.
     * Used by ChatViewModel to bridge nicknames into BluetoothMeshService.
     */
    fun getAllP2PNicknames(): Map<String, String> = repo.getAllNicknames()

    /**
     * Broadcast P2P presence for the current geohash channel.
     */
    fun broadcastP2PPresenceForCurrentChannel(nickname: String, force: Boolean = false) {
        val channel = state.selectedLocationChannel.value
        if (channel is com.roman.zemzeme.geohash.ChannelID.Location &&
            p2pConfig.p2pEnabled &&
            p2pTransport.p2pRepository.nodeStatus.value == P2PNodeStatus.RUNNING) {
            val geohash = channel.channel.geohash
            val topicName = p2pMainTopicName(geohash)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    p2pTopicsRepository.broadcastPresence(topicName, nickname, force)
                    Log.d(TAG, "Broadcast P2P presence to $topicName: $nickname")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to broadcast P2P presence: ${e.message}")
                }
            }
        }
    }

    /**
     * Ensure we are subscribed to DMs for a specific conversation geohash.
     * Used by ChatViewModel when opening a private chat.
     */
    fun ensureGeohashDMSubscription(convKey: String) {
        try {
            val gh = repo.getConversationGeohash(convKey)
            if (!gh.isNullOrEmpty()) {
                val identity = NostrIdentityBridge.deriveIdentity(gh, getApplication())
                val subId = "geo-dm-$gh"
                if (currentDmSubId != subId) {
                    currentDmSubId?.let { subscriptionManager.unsubscribe(it) }
                    currentDmSubId = subId
                    subscriptionManager.subscribeGiftWraps(
                        pubkey = identity.publicKeyHex,
                        sinceMs = System.currentTimeMillis() - 172800000L,
                        id = subId,
                        handler = { event -> dmHandler.onGiftWrap(event, gh, identity) }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureGeohashDMSubscription failed: ${e.message}")
        }
    }

    private fun switchLocationChannel(channel: com.roman.zemzeme.geohash.ChannelID?) {
        val previousGeohash = repo.getCurrentGeohash()

        geoTimer?.cancel(); geoTimer = null
        currentGeohashSubId?.let { subscriptionManager.unsubscribe(it); currentGeohashSubId = null }
        currentDmSubId?.let { subscriptionManager.unsubscribe(it); currentDmSubId = null }
        
        // Stop any active P2P continuous refresh when switching channels
        p2pTopicsRepository.stopContinuousRefresh()

        when (channel) {
            is com.roman.zemzeme.geohash.ChannelID.Mesh -> {
                Log.d(TAG, "📡 Switched to mesh channel")
                if (previousGeohash != null) {
                    repo.syncConnectedP2PPeers(previousGeohash, emptyList())
                    lastConnectedP2PPeerCounts.remove(previousGeohash)
                }
                repo.setCurrentGeohash(null)
                notificationManager.setCurrentGeohash(null)
                notificationManager.clearMeshMentionNotifications()
                try { messageManager.clearMeshUnreadCount() } catch (_: Exception) { }
                repo.refreshGeohashPeople()
            }
            is com.roman.zemzeme.geohash.ChannelID.Location -> {
                Log.d(TAG, "📍 Switching to geohash channel: ${channel.channel.geohash}")
                if (previousGeohash != null && previousGeohash != channel.channel.geohash) {
                    repo.syncConnectedP2PPeers(previousGeohash, emptyList())
                    lastConnectedP2PPeerCounts.remove(previousGeohash)
                }
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
                            val topicName = p2pMainTopicName(geohash)
                            p2pTopicsRepository.subscribeTopic(topicName)
                            Log.d(TAG, "Subscribed to P2P topic: $topicName")

                            // CRITICAL: Trigger DHT discovery to find and connect to peers
                            p2pTopicsRepository.discoverTopicPeers(topicName)
                            Log.d(TAG, "Started P2P peer discovery for: $topicName")

                            // CRITICAL: Start continuous 5-second refresh loop
                            p2pTopicsRepository.startContinuousRefresh(topicName)
                            Log.d(TAG, "Started continuous P2P refresh for: $topicName")

                            // BROADCAST PRESENCE: Announce ourselves to existing peers
                            val myNickname = state.getNicknameValue() ?: "anon"
                            p2pTopicsRepository.broadcastPresence(topicName, myNickname)
                            Log.d(TAG, "Broadcast P2P presence to: $topicName")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to subscribe/discover P2P topic: ${e.message}")
                        }
                    }
                }
            }
            null -> {
                Log.d(TAG, "📡 No channel selected")
                if (previousGeohash != null) {
                    repo.syncConnectedP2PPeers(previousGeohash, emptyList())
                    lastConnectedP2PPeerCounts.remove(previousGeohash)
                }
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
    private fun p2pMainTopicName(geohash: String): String = geohash

    private fun p2pMetaTopicName(geohash: String): String = "$P2P_META_PREFIX$geohash"

    private fun logSelfFilteredFromTopicSync(
        geohash: String,
        myPeerID: String,
        rawPeerCount: Int,
        filteredPeerCount: Int
    ) {
        if (!isDebuggableBuild) {
            return
        }

        val hit = selfFilteredTopicSyncCount.incrementAndGet()
        val now = System.currentTimeMillis()
        val lastLoggedAt = lastSelfFilterLogAtMs.get()
        val shouldLogNow = hit <= 3L || now - lastLoggedAt >= 30_000L

        if (!shouldLogNow) {
            return
        }

        lastSelfFilterLogAtMs.set(now)
        Log.d(
            TAG,
            "debug:self-filter topic-sync hit=$hit geo=$geohash raw=$rawPeerCount filtered=$filteredPeerCount me=${myPeerID.take(12)}"
        )
    }

    private fun normalizeP2PTimestamp(timestamp: Long): Long {
        return when {
            timestamp <= 0L -> System.currentTimeMillis()
            timestamp < 10_000_000_000L -> timestamp * 1000
            else -> timestamp
        }
    }

    private fun resolveP2PDisplayName(peerID: String, participantId: String): String {
        return repo.getCachedNickname(participantId)
            ?: if (peerID.isNotBlank()) "anon${peerID.takeLast(4)}" else "anon"
    }

    private fun buildP2PMessageId(peerID: String, timestamp: Long, content: String): String {
        val contentHash = content.hashCode().toUInt().toString(16)
        val sequence = p2pMessageSequence.incrementAndGet()
        return "p2p_${peerID}_${timestamp}_${contentHash}_$sequence"
    }

    private suspend fun handleIncomingP2PTopicMessage(msg: TopicMessage, geohash: String, isMeta: Boolean) {
        val myPeerID = p2pTransport.getMyPeerID()
        val rawPeerID = msg.senderID
        if (rawPeerID == myPeerID) {
            Log.v(TAG, "Ignoring own P2P message from $rawPeerID")
            return
        }

        val rawContent = msg.content
        val json = try { org.json.JSONObject(rawContent) } catch (_: Exception) { null }

        if (json != null) {
            val type = json.optString("type", "")
            if (type == "presence") {
                val peerID = json.optString("peerID", rawPeerID).ifBlank { rawPeerID }
                if (peerID == myPeerID) {
                    return
                }
                val participantId = "p2p:$peerID"
                val sender = json.optString("nickname", json.optString("sender", peerID))
                val timestamp = normalizeP2PTimestamp(json.optLong("timestamp", msg.timestamp))

                if (sender.isNotBlank()) {
                    repo.cacheNickname(participantId, sender)
                }
                repo.updateParticipant(geohash, participantId, Date(timestamp))

                if (p2pTopicsRepository.shouldRespondToPresence(peerID)) {
                    val myNickname = state.getNicknameValue() ?: "anon"
                    val topicName = p2pMainTopicName(geohash)
                    viewModelScope.launch(Dispatchers.IO) {
                        delay(500 + kotlin.random.Random.nextLong(500))
                        p2pTopicsRepository.broadcastPresence(topicName, myNickname)
                    }
                }
                Log.d(TAG, "Received P2P presence from $sender in geo:$geohash")
                return
            }

            val peerID = json.optString("peerID", rawPeerID).ifBlank { rawPeerID }
            val participantId = "p2p:$peerID"
            val senderFromPayload = json.optString("sender", json.optString("nickname", ""))
            val timestamp = normalizeP2PTimestamp(json.optLong("timestamp", msg.timestamp))
            val content = json.optString("content", "")

            if (content.isBlank() && type.isNotBlank()) {
                return
            }

            val displayName = if (senderFromPayload.isNotBlank()) {
                senderFromPayload
            } else {
                resolveP2PDisplayName(peerID, participantId)
            }

            if (senderFromPayload.isNotBlank()) {
                repo.cacheNickname(participantId, senderFromPayload)
            }

            val messageContent = if (content.isNotBlank()) content else rawContent
            val teleported = json.optBoolean("teleported", false)
            val messageId = buildP2PMessageId(peerID, timestamp, messageContent)

            Log.d(TAG, "Received P2P geohash message from $displayName in geo:$geohash")

            val zemzemeMessage = com.roman.zemzeme.model.ZemzemeMessage(
                id = messageId,
                sender = displayName,
                content = messageContent,
                timestamp = Date(timestamp),
                isRelay = false,
                senderPeerID = "p2p:$peerID",
                channel = "#$geohash",
                powDifficulty = null
            )

            withContext(Dispatchers.Main) {
                messageManager.addChannelMessage("geo:$geohash", zemzemeMessage)
            }

            repo.updateParticipant(geohash, participantId, Date(timestamp))
            if (teleported) {
                repo.markTeleported(participantId)
            }
            return
        }

        if (isMeta) {
            return
        }

        val timestamp = normalizeP2PTimestamp(msg.timestamp)
        val participantId = "p2p:$rawPeerID"
        val displayName = resolveP2PDisplayName(rawPeerID, participantId)
        val messageId = buildP2PMessageId(rawPeerID, timestamp, rawContent)

        // Handle media transfer embedded as bitchat1:<base64url>
        if (rawContent.startsWith("bitchat1:")) {
            val b64 = rawContent.removePrefix("bitchat1:")
            val fileBytes = try {
                val padded = b64.replace("-", "+").replace("_", "/")
                    .let { it + "=".repeat((4 - it.length % 4) % 4) }
                android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
            } catch (_: Exception) { null }
            val filePacket = fileBytes?.let { com.roman.zemzeme.model.ZemzemeFilePacket.decode(it) }
            if (filePacket != null) {
                val savedPath = com.roman.zemzeme.features.file.FileUtils.saveIncomingFile(getApplication(), filePacket)
                val msgType = com.roman.zemzeme.features.file.FileUtils.messageTypeForMime(filePacket.mimeType)
                Log.d(TAG, "📥 Received P2P geohash media from $displayName: ${filePacket.fileName}")
                val mediaMsg = com.roman.zemzeme.model.ZemzemeMessage(
                    id = messageId,
                    sender = displayName,
                    content = savedPath,
                    type = msgType,
                    timestamp = Date(timestamp),
                    isRelay = false,
                    senderPeerID = "p2p:$rawPeerID",
                    channel = "#$geohash"
                )
                withContext(Dispatchers.Main) { messageManager.addChannelMessage("geo:$geohash", mediaMsg) }
                repo.updateParticipant(geohash, participantId, Date(timestamp))
            } else {
                Log.w(TAG, "Failed to decode P2P geohash media from $displayName")
            }
            return
        }

        Log.d(TAG, "Received P2P geohash message from $displayName in geo:$geohash")

        val zemzemeMessage = com.roman.zemzeme.model.ZemzemeMessage(
            id = messageId,
            sender = displayName,
            content = rawContent,
            timestamp = Date(timestamp),
            isRelay = false,
            senderPeerID = "p2p:$rawPeerID",
            channel = "#$geohash",
            powDifficulty = null
        )

        withContext(Dispatchers.Main) {
            messageManager.addChannelMessage("geo:$geohash", zemzemeMessage)
        }
        repo.updateParticipant(geohash, participantId, Date(timestamp))
    }
}
