package com.roman.zemzeme.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roman.zemzeme.favorites.FavoritesPersistenceService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.roman.zemzeme.mesh.BluetoothMeshDelegate
import com.roman.zemzeme.mesh.BluetoothMeshService
import com.roman.zemzeme.service.MeshServiceHolder
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.model.ZemzemeMessageType
import com.roman.zemzeme.nostr.NostrIdentityBridge
import com.roman.zemzeme.protocol.ZemzemePacket


import kotlinx.coroutines.launch
import com.roman.zemzeme.util.DebugLogger
import com.roman.zemzeme.util.NotificationIntervalManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.random.Random
import com.roman.zemzeme.services.VerificationService
import com.roman.zemzeme.identity.SecureIdentityStateManager
import com.roman.zemzeme.noise.NoiseSession
import com.roman.zemzeme.nostr.GeohashAliasRegistry
import com.roman.zemzeme.util.dataFromHexString
import com.roman.zemzeme.util.hexEncodedString
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Refactored ChatViewModel - Main coordinator for zemzeme functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    initialMeshService: BluetoothMeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

    // Made var to support mesh service replacement after panic clear
    var meshService: BluetoothMeshService = initialMeshService
        private set
    private val debugManager by lazy { try { com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    private val p2pMessageSequence = AtomicLong(0L)

    companion object {
        private const val TAG = "ChatViewModel"
        // Go P2P library has max 10 topic slots. 1 topic per group.
        const val MAX_P2P_GROUPS = 10
    }

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        val targetPeer = resolveMediaRecipientForSend(toPeerIDOrNull)
        if (toPeerIDOrNull != null && targetPeer == null) return

        if (targetPeer == null) {
            val loc = state.selectedLocationChannel.value
            if (loc is com.roman.zemzeme.geohash.ChannelID.Location) {
                geohashViewModel.sendGeohashMedia(filePath, "audio/mp4", com.roman.zemzeme.model.ZemzemeMessageType.Audio, loc.channel, meshService.myPeerID, state.getNicknameValue())
                return
            }
        }

        mediaSendingManager.sendVoiceNote(targetPeer, channelOrNull, filePath)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        val targetPeer = resolveMediaRecipientForSend(toPeerIDOrNull)
        if (toPeerIDOrNull != null && targetPeer == null) return

        if (targetPeer == null) {
            val loc = state.selectedLocationChannel.value
            if (loc is com.roman.zemzeme.geohash.ChannelID.Location) {
                val file = java.io.File(filePath)
                val mimeType = try { com.roman.zemzeme.features.file.FileUtils.getMimeTypeFromExtension(file.name) } catch (_: Exception) { "application/octet-stream" }
                val msgType = when {
                    mimeType.startsWith("image/") -> com.roman.zemzeme.model.ZemzemeMessageType.Image
                    mimeType.startsWith("audio/") -> com.roman.zemzeme.model.ZemzemeMessageType.Audio
                    else -> com.roman.zemzeme.model.ZemzemeMessageType.File
                }
                geohashViewModel.sendGeohashMedia(filePath, mimeType, msgType, loc.channel, meshService.myPeerID, state.getNicknameValue())
                return
            }
        }

        mediaSendingManager.sendFileNote(targetPeer, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        val targetPeer = resolveMediaRecipientForSend(toPeerIDOrNull)
        if (toPeerIDOrNull != null && targetPeer == null) return

        if (targetPeer == null) {
            val loc = state.selectedLocationChannel.value
            if (loc is com.roman.zemzeme.geohash.ChannelID.Location) {
                geohashViewModel.sendGeohashMedia(filePath, "image/jpeg", com.roman.zemzeme.model.ZemzemeMessageType.Image, loc.channel, meshService.myPeerID, state.getNicknameValue())
                return
            }
        }

        mediaSendingManager.sendImageNote(targetPeer, channelOrNull, filePath)
    }

    private fun resolveMediaRecipientForSend(toPeerIDOrNull: String?): String? {
        if (toPeerIDOrNull == null) return null

        // P2P and Nostr DMs are routed by MediaSendingManager — pass through as-is.
        if (toPeerIDOrNull.startsWith("p2p:") ||
            toPeerIDOrNull.startsWith("nostr_") ||
            toPeerIDOrNull.startsWith("nostr:")
        ) {
            return toPeerIDOrNull
        }

        val canonicalPeerID = try {
            com.roman.zemzeme.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = toPeerIDOrNull,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                nostrPubHexForAlias = { alias -> com.roman.zemzeme.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
            )
        } catch (_: Exception) {
            toPeerIDOrNull
        }

        // BLE session guard only applies to BLE-addressed peers.
        if (!meshService.hasEstablishedSession(canonicalPeerID)) {
            Log.w(TAG, "Blocked media send: no established BLE session for ${canonicalPeerID.take(16)}…")
            return null
        }

        return canonicalPeerID
    }

    fun getCurrentNpub(): String? {
        return try {
            NostrIdentityBridge
                .getCurrentNostrIdentity(getApplication())
                ?.npub
        } catch (_: Exception) {
            null
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String {
        return VerificationService.buildMyQRString(nickname, npub) ?: ""
    }

    // MARK: - State management
    private val state = ChatState(
        scope = viewModelScope,
    )

    // Transfer progress tracking
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    private val identityManager by lazy { SecureIdentityStateManager(getApplication()) }
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)

    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = meshService.hasEstablishedSession(peerID)
        override fun initiateHandshake(peerID: String) = meshService.initiateNoiseHandshake(peerID)
        override fun getMyPeerID(): String = meshService.myPeerID
    }

    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager)
    private val notificationManager = NotificationManager(
      application.applicationContext,
      NotificationManagerCompat.from(application.applicationContext),
      NotificationIntervalManager()
    )

    private val verificationHandler = VerificationHandler(
        context = application.applicationContext,
        scope = viewModelScope,
        getMeshService = { meshService },
        identityManager = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager
    )
    val verifiedFingerprints = verificationHandler.verifiedFingerprints

    // Media file sending manager — routes to P2P / Nostr / BLE based on peer prefix.
    private val mediaSendingManager = MediaSendingManager(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        getMeshService = { meshService },
        getP2PTransport = { com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication()) },
        getNostrTransport = { com.roman.zemzeme.nostr.NostrTransport.getInstance(getApplication()) }
    )
    
    // Delegate handler for mesh callbacks
    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMyPeerID = { meshService.myPeerID },
        getMeshService = { meshService },
        onContactAdd = { peerID -> addContactIfNeeded(peerID) }
    )
    
    // New Geohash architecture ViewModel (replaces God object service usage in UI path)
    val geohashViewModel = GeohashViewModel(
        application = application,
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        dataManager = dataManager,
        notificationManager = notificationManager
    )





    val myPeerID: String get() = meshService.myPeerID

    val messages: StateFlow<List<ZemzemeMessage>> = state.messages
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val nickname: StateFlow<String> = state.nickname
    val isConnected: StateFlow<Boolean> = state.isConnected
    val privateChats: StateFlow<Map<String, List<ZemzemeMessage>>> = state.privateChats
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: StateFlow<Set<String>> = state.joinedChannels
    val currentChannel: StateFlow<String?> = state.currentChannel
    val channelMessages: StateFlow<Map<String, List<ZemzemeMessage>>> = state.channelMessages
    val unreadChannelMessages: StateFlow<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: StateFlow<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: StateFlow<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: StateFlow<String?> = state.passwordPromptChannel
    val hasUnreadChannels = state.hasUnreadChannels
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val showCommandSuggestions: StateFlow<Boolean> = state.showCommandSuggestions
    val commandSuggestions: StateFlow<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: StateFlow<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: StateFlow<List<String>> = state.mentionSuggestions
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: StateFlow<Boolean> = state.showAppInfo
    val showMeshPeerList: StateFlow<Boolean> = state.showMeshPeerList
    val privateChatSheetPeer: StateFlow<String?> = state.privateChatSheetPeer
    val showVerificationSheet: StateFlow<Boolean> = state.showVerificationSheet
    val showSecurityVerificationSheet: StateFlow<Boolean> = state.showSecurityVerificationSheet
    val selectedLocationChannel: StateFlow<com.roman.zemzeme.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: StateFlow<Boolean> = state.isTeleported
    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: StateFlow<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val customGroups: StateFlow<Set<String>> = state.customGroups
    val geographicGroups: StateFlow<Set<String>> = state.geographicGroups
    val groupNicknames: StateFlow<Map<String, String>> = state.groupNicknames
    val unreadMeshCount: StateFlow<Int> = state.unreadMeshCount
    val contacts: StateFlow<Set<String>> = state.contacts

    // P2P topic states for connection status UI (delegated from geohashViewModel)
    val p2pTopicStates: StateFlow<Map<String, com.roman.zemzeme.p2p.TopicState>> get() = geohashViewModel.p2pTopicStates

    init {
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        // Hydrate UI state from process-wide AppStateStore to survive Activity recreation
        viewModelScope.launch {
            try { com.roman.zemzeme.services.AppStateStore.peers.collect { peers ->
                state.setConnectedPeers(peers)
                state.setIsConnected(peers.isNotEmpty())
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.roman.zemzeme.services.AppStateStore.publicMessages.collect { msgs ->
                // Source of truth is AppStateStore; replace to avoid duplicate keys in LazyColumn
                state.setMessages(msgs)
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.roman.zemzeme.services.AppStateStore.privateMessages.collect { byPeer ->
                // Replace with store snapshot
                state.setPrivateChats(byPeer)
                // Recompute unread set using SeenMessageStore for robustness across Activity recreation
                try {
                    val seen = com.roman.zemzeme.services.SeenMessageStore.getInstance(getApplication())
                    val myNick = state.getNicknameValue() ?: meshService.myPeerID
                    val unread = mutableSetOf<String>()
                    byPeer.forEach { (peer, list) ->
                        if (list.any { msg -> msg.sender != myNick && !seen.hasRead(msg.id) }) unread.add(peer)
                    }
                    state.setUnreadPrivateMessages(unread)
                } catch (_: Exception) { }
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.roman.zemzeme.services.AppStateStore.channelMessages.collect { byChannel ->
                // Replace with store snapshot
                state.setChannelMessages(byChannel)
            } } catch (_: Exception) { }
        }
        // Subscribe to BLE transfer progress and reflect in message deliveryStatus
        viewModelScope.launch {
            com.roman.zemzeme.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }
        
        // Removed background location notes subscription. Notes now load only when sheet opens.
    }

    fun cancelMediaSend(messageId: String) {
        // Delegate to MediaSendingManager which tracks transfer IDs and cleans up UI state
        mediaSendingManager.cancelMediaSend(messageId)
    }
    
    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)
        
        // Load data
        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)
        
        // Initialize channel messages
        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }
        
        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()

        // Load custom groups, geographic groups, nicknames, and contacts
        state.setCustomGroups(dataManager.loadCustomGroups())
        state.setGeographicGroups(dataManager.loadGeographicGroups())
        state.setGroupNicknames(dataManager.loadAllGroupNicknames())
        state.setContacts(dataManager.loadContacts())
        // Restore saved contact nicknames into peerNicknames so they show on HomeScreen
        val savedContactNicks = dataManager.loadContactNicknames()
        if (savedContactNicks.isNotEmpty()) {
            val currentNicks = state.peerNicknames.value.toMutableMap()
            savedContactNicks.forEach { (peerID, nick) ->
                if (peerID !in currentNicks) {
                    currentNicks[peerID] = nick
                }
            }
            state.setPeerNicknames(currentNicks)
        }

        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        viewModelScope.launch {
            com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {
                    // Only show debug logs in the Mesh chat timeline to avoid leaking into geohash chats
                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is com.roman.zemzeme.geohash.ChannelID.Mesh) {
                        // Append only latest debug message as system message to avoid flooding
                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }
        
        // Initialize new geohash architecture
        geohashViewModel.initialize()

        // Initialize favorites persistence service
        com.roman.zemzeme.favorites.FavoritesPersistenceService.initialize(getApplication())
        com.roman.zemzeme.p2p.P2PFavoritesRegistry.initialize(getApplication())

        // Load verified fingerprints from secure storage
        verificationHandler.loadVerifiedFingerprints()


        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            val nostrTransport = com.roman.zemzeme.nostr.NostrTransport.getInstance(getApplication())
            nostrTransport.senderPeerID = meshService.myPeerID
        } catch (_: Exception) { }

        // Set up P2P DM message callback to route incoming P2P direct messages to private chat
        try {
            val p2pTransport = com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication())
            p2pTransport.setMessageCallback { incomingMessage ->
                if (incomingMessage.type == com.roman.zemzeme.p2p.P2PTransport.P2PMessageType.DIRECT_MESSAGE) {
                    val senderPeerID = "p2p:${incomingMessage.senderPeerID}"

                    // ── Incoming media ────────────────────────────────────────
                    if (incomingMessage.fileBytes != null) {
                        val resolvedName = incomingMessage.senderNickname?.takeIf { it.isNotBlank() }
                            ?: com.roman.zemzeme.p2p.P2PAliasRegistry.getDisplayName(senderPeerID)
                            ?: "p2p:${incomingMessage.senderPeerID.take(8)}…"
                        val normalizedTs = normalizeP2PTimestamp(incomingMessage.timestamp)
                        val msg = com.roman.zemzeme.features.media.MediaReceiveHandler.handle(
                            context = getApplication(),
                            fileBytes = incomingMessage.fileBytes,
                            senderKey = senderPeerID,
                            senderNickname = resolvedName,
                            timestamp = java.util.Date(normalizedTs)
                        )
                        if (msg != null) {
                            privateChatManager.handleIncomingPrivateMessage(msg)
                            addContactIfNeeded(senderPeerID)
                            Log.d("ChatViewModel", "Received P2P media from $senderPeerID ($resolvedName): ${incomingMessage.contentType}")
                        }
                        return@setMessageCallback
                    }

                    if (handleIncomingP2PFavoriteNotification(senderPeerID, incomingMessage.content)) {
                        return@setMessageCallback
                    }

                    // Cache the sender's nickname from the wire message so subsequent
                    // lookups via P2PAliasRegistry return the real name instead of
                    // the raw peer ID fallback (fixes "p2p:12D3KooW..." display bug).
                    val resolvedName = incomingMessage.senderNickname?.takeIf { it.isNotBlank() }
                    if (resolvedName != null) {
                        com.roman.zemzeme.p2p.P2PAliasRegistry.setDisplayName(senderPeerID, resolvedName)
                        state.updatePeerNickname(senderPeerID, resolvedName)
                    }

                    // Route to private chat
                    val normalizedTimestamp = normalizeP2PTimestamp(incomingMessage.timestamp)
                    val messageId = buildP2PMessageId(incomingMessage.senderPeerID, normalizedTimestamp, incomingMessage.content)
                    val displayName = resolvedName
                        ?: com.roman.zemzeme.p2p.P2PAliasRegistry.getDisplayName(senderPeerID)
                        ?: "p2p:${incomingMessage.senderPeerID.take(8)}…"
                    val message = ZemzemeMessage(
                        id = messageId,
                        sender = displayName,
                        content = incomingMessage.content,
                        timestamp = java.util.Date(normalizedTimestamp),
                        isRelay = false,
                        senderPeerID = senderPeerID
                    )
                    // Add to private chat
                    privateChatManager.handleIncomingPrivateMessage(message)
                    addContactIfNeeded(senderPeerID)
                    Log.d("ChatViewModel", "Received P2P DM from $senderPeerID ($displayName) (content scrubbed for security)")
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to set up P2P DM callback: ${e.message}")
        }

        // Note: Mesh service is now started by MainActivity

        // BLE receives are inserted by MessageHandler path; no VoiceNoteBus for Tor in this branch.
    }
    
    override fun onCleared() {
        super.onCleared()
        // Note: Mesh service lifecycle is now managed by MainActivity
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        meshService.sendBroadcastAnnounce()
        
        // Broadcast presence change to P2P topic (if subscribed to geohash channel)
        val selectedChannel = state.selectedLocationChannel.value
        if (selectedChannel is com.roman.zemzeme.geohash.ChannelID.Location) {
            geohashViewModel.broadcastP2PPresenceForCurrentChannel(newNickname, force = true)
        }
    }

    private fun normalizeP2PTimestamp(timestamp: Long): Long {
        return when {
            timestamp <= 0L -> System.currentTimeMillis()
            timestamp < 10_000_000_000L -> timestamp * 1000
            else -> timestamp
        }
    }

    private fun buildP2PMessageId(peerID: String, timestamp: Long, content: String): String {
        val contentHash = content.hashCode().toUInt().toString(16)
        val sequence = p2pMessageSequence.incrementAndGet()
        return "p2p_dm_${peerID}_${timestamp}_${contentHash}_$sequence"
    }

    private fun normalizeP2PConversationKey(peerID: String): String {
        return if (peerID.startsWith("p2p:")) peerID else "p2p:$peerID"
    }

    private fun handleIncomingP2PFavoriteNotification(senderPeerID: String, content: String): Boolean {
        if (!content.startsWith("[FAVORITED]") && !content.startsWith("[UNFAVORITED]")) {
            return false
        }

        return try {
            val conversationKey = normalizeP2PConversationKey(senderPeerID)
            val isFavorite = content.startsWith("[FAVORITED]")
            com.roman.zemzeme.p2p.P2PFavoritesRegistry.setPeerFavoritedUs(conversationKey, isFavorite)

            val senderName = com.roman.zemzeme.p2p.P2PAliasRegistry.getDisplayName(conversationKey)
                ?: "p2p:${conversationKey.removePrefix("p2p:").take(8)}..."
            val weFavoriteThem = com.roman.zemzeme.p2p.P2PFavoritesRegistry.isFavorite(conversationKey)
            val guidance = if (isFavorite) {
                if (weFavoriteThem) {
                    " - mutual on P2P."
                } else {
                    " - favorite back to mark this P2P contact as mutual."
                }
            } else {
                " - mutual P2P favorite is now off."
            }

            val action = if (isFavorite) "favorited" else "unfavorited"
            val systemMessage = ZemzemeMessage(
                id = java.util.UUID.randomUUID().toString(),
                sender = "system",
                content = "$senderName $action you$guidance",
                timestamp = java.util.Date(),
                isRelay = false,
                isPrivate = true,
                recipientNickname = state.getNicknameValue(),
                senderPeerID = conversationKey
            )
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                privateChatManager.handleIncomingPrivateMessage(systemMessage)
            }
            Log.d(TAG, "Processed P2P favorite notification from $conversationKey, isFavorite=$isFavorite")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process P2P favorite notification: ${e.message}")
            false
        }
    }

    private fun toggleP2PFavorite(peerID: String) {
        val conversationKey = normalizeP2PConversationKey(peerID)
        val currentlyFavorite = com.roman.zemzeme.p2p.P2PFavoritesRegistry.isFavorite(conversationKey)
                || dataManager.favoritePeers.contains(conversationKey)
        val isNowFavorite = !currentlyFavorite

        com.roman.zemzeme.p2p.P2PFavoritesRegistry.setFavorite(conversationKey, isNowFavorite)
        if (isNowFavorite) {
            dataManager.addFavorite(conversationKey)
        } else {
            dataManager.removeFavorite(conversationKey)
        }
        state.setFavoritePeers(dataManager.favoritePeers.toSet())

        sendP2PFavoriteNotification(conversationKey, isNowFavorite)
        Log.d(TAG, "Toggled P2P favorite: peer=$conversationKey, favorite=$isNowFavorite")
    }

    private fun sendP2PFavoriteNotification(peerID: String, isFavorite: Boolean) {
        viewModelScope.launch {
            try {
                val conversationKey = normalizeP2PConversationKey(peerID)
                val rawPeerID = conversationKey.removePrefix("p2p:")
                val myNpub = try {
                    com.roman.zemzeme.nostr.NostrIdentityBridge.getCurrentNostrIdentity(getApplication())?.npub
                } catch (_: Exception) {
                    null
                }
                val content = if (isFavorite) "[FAVORITED]:${myNpub ?: ""}" else "[UNFAVORITED]:${myNpub ?: ""}"
                val senderNickname = state.getNicknameValue() ?: meshService.myPeerID
                val messageID = java.util.UUID.randomUUID().toString()
                val p2pTransport = com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication())
                val sent = p2pTransport.sendDirectMessage(rawPeerID, content, senderNickname, messageID)
                if (!sent) {
                    Log.w(TAG, "Failed to send P2P favorite notification to $conversationKey")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error sending P2P favorite notification: ${e.message}")
            }
        }
    }
    
    /**
     * Ensure Nostr DM subscription for a geohash conversation key if known.
     */
    private fun ensureGeohashDMSubscriptionIfNeeded(convKey: String) {
        geohashViewModel.ensureGeohashDMSubscription(convKey)
    }

    // MARK: - Channel Management (delegated)
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, meshService.myPeerID)
    }
    
    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }
    
    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        meshService.sendMessage("left $channel")
    }
    
    // MARK: - Private Chat Management (delegated)
    
    fun startPrivateChat(peerID: String) {
        // For geohash conversation keys, ensure DM subscription is active
        if (peerID.startsWith("nostr_")) {
            ensureGeohashDMSubscriptionIfNeeded(peerID)
        }
        
        val success = privateChatManager.startPrivateChat(peerID, meshService)
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(peerID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(peerID)

            // Persistently mark all messages in this conversation as read so Nostr fetches
            // after app restarts won't re-mark them as unread.
            try {
                val seen = com.roman.zemzeme.services.SeenMessageStore.getInstance(getApplication())
                val chats = state.getPrivateChatsValue()
                val messages = chats[peerID] ?: emptyList()
                messages.forEach { msg ->
                    try { seen.markRead(msg.id) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }
    
    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        clearMeshMentionNotifications()
        // Ensure sheet is hidden
        hidePrivateChatSheet()
    }

    // MARK: - Open Latest Unread Private Chat

    fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: meshService.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for geohash DMs and ensure DM subscription
                ensureGeohashDMSubscriptionIfNeeded(targetKey)
                targetKey
            } else if (targetKey.startsWith("p2p:")) {
                // P2P peers: use the exact conversation key directly
                // Optionally init P2P DM state for proper display name handling
                startP2PDM(targetKey)
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical = com.roman.zemzeme.services.ConversationAliasResolver.resolveCanonicalPeerID(
                    selectedPeerID = targetKey,
                    connectedPeers = state.getConnectedPeersValue(),
                    meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                    meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                    nostrPubHexForAlias = { alias -> com.roman.zemzeme.nostr.GeohashAliasRegistry.get(alias) },
                    findNoiseKeyForNostr = { key -> com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            showPrivateChatSheet(openPeer)
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    // END - Open Latest Unread Private Chat

    
    // MARK: - Message Sending
    
    fun sendMessage(content: String) {
        if (content.isEmpty()) return
        
        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            commandProcessor.processCommand(content, meshService, meshService.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.roman.zemzeme.geohash.ChannelID.Location) {
                    // Route command-generated public messages via Nostr in geohash channels
                    geohashViewModel.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        meshService.myPeerID,
                        state.getNicknameValue()
                    )
                } else {
                    // Default: route via mesh
                    meshService.sendMessage(messageContent, mentions, channel)
                }
            })
            return
        }
        
        val mentions = messageManager.parseMentions(content, meshService.getPeerNicknames().values.toSet(), state.getNicknameValue())
        // REMOVED: Auto-join mentioned channels feature that was incorrectly parsing hashtags from @mentions
        // This was causing messages like "test @jack#1234 test" to auto-join channel "#1234"
        
        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer = com.roman.zemzeme.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                nostrPubHexForAlias = { alias -> com.roman.zemzeme.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
            ).also { canonical ->
                if (canonical != state.getSelectedPrivateChatPeerValue()) {
                    privateChatManager.startPrivateChat(canonical, meshService)
                    // If we're in the private chat sheet, update its active peer too
                    if (state.getPrivateChatSheetPeerValue() != null) {
                        showPrivateChatSheet(canonical)
                    }
                }
            }
            // Send private message
            val recipientNickname = meshService.getPeerNicknames()[selectedPeer]
            privateChatManager.sendPrivateMessage(
                content,
                selectedPeer,
                recipientNickname,
                state.getNicknameValue(),
                meshService.myPeerID,
                mentions
            ) { messageContent, peerID, recipientNicknameParam, messageId, mentionsParam ->
                DebugLogger.log(
                    action = "SEND",
                    msgId = messageId,
                    srcName = state.getNicknameValue(),
                    srcId = meshService.myPeerID,
                    destName = recipientNicknameParam,
                    destId = peerID,
                    protocol = "PRIVATE",
                    content = messageContent
                )
                // Route via MessageRouter (mesh when connected+established, else Nostr)
                val router = com.roman.zemzeme.services.MessageRouter.getInstance(getApplication(), meshService)
                router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId, mentionsParam)
            }
            // Auto-add as contact when sending a private message
            addContactIfNeeded(selectedPeer)
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.roman.zemzeme.geohash.ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                val geoMsgId = java.util.UUID.randomUUID().toString().uppercase()
                DebugLogger.log(
                    action = "SEND",
                    msgId = geoMsgId,
                    srcName = state.getNicknameValue(),
                    srcId = meshService.myPeerID,
                    destName = selectedLocationChannel.channel.geohash,
                    destId = "geo:${selectedLocationChannel.channel.geohash}",
                    protocol = "NOSTR_GEOHASH",
                    content = content
                )
                geohashViewModel.sendGeohashMessage(content, selectedLocationChannel.channel, meshService.myPeerID, state.getNicknameValue())
            } else {
                // Send public/channel message via mesh
                val message = ZemzemeMessage(
                    sender = state.getNicknameValue() ?: meshService.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = meshService.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    DebugLogger.log(
                        action = "SEND",
                        msgId = message.id,
                        srcName = state.getNicknameValue(),
                        srcId = meshService.myPeerID,
                        destName = currentChannelValue,
                        destId = currentChannelValue,
                        protocol = "BLE_MESH",
                        content = content
                    )
                    channelManager.addChannelMessage(currentChannelValue, message, meshService.myPeerID)

                    // Check if encrypted channel
                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            content,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            meshService.myPeerID,
                            onEncryptedPayload = { encryptedData ->
                                // This would need proper mesh service integration
                                meshService.sendMessage(content, mentions, currentChannelValue)
                            },
                            onFallback = {
                                meshService.sendMessage(content, mentions, currentChannelValue)
                            }
                        )
                    } else {
                        meshService.sendMessage(content, mentions, currentChannelValue)
                    }
                } else {
                    messageManager.addMessage(message)
                    DebugLogger.log(
                        action = "SEND",
                        msgId = message.id,
                        srcName = state.getNicknameValue(),
                        srcId = meshService.myPeerID,
                        destName = "broadcast",
                        destId = "all",
                        protocol = "BLE_MESH",
                        content = content
                    )
                    meshService.sendMessage(content, mentions, null)
                }
            }
        }
    }

    // MARK: - Utility Functions
    
    fun getPeerIDForNickname(nickname: String): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")

        if (peerID.startsWith("p2p:")) {
            toggleP2PFavorite(peerID)
            logCurrentFavoriteState()
            return
        }

        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService
        try {
            var noiseKey: ByteArray? = null
            var nickname: String = meshService.getPeerNicknames()[peerID] ?: peerID

            // Case 1: Live mesh peer with known info
            val peerInfo = meshService.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else {
                // Case 2: Offline favorite entry using 64-hex noise public key as peerID
                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    try {
                        noiseKey = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        // Prefer nickname from favorites store if available
                        val rel = com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey!!)
                        if (rel != null) nickname = rel.peerNickname
                    } catch (_: Exception) { }
                }
            }

            if (noiseKey != null) {
                // Determine current favorite state from DataManager using fingerprint
                val identityManager = com.roman.zemzeme.identity.SecureIdentityStateManager(getApplication())
                val fingerprint = identityManager.generateFingerprint(noiseKey!!)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey!!,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                // Send favorite notification via mesh or Nostr with our npub if available
                try {
                    val myNostr = com.roman.zemzeme.nostr.NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
                    val announcementContent = if (isNowFavorite) "[FAVORITED]:${myNostr?.npub ?: ""}" else "[UNFAVORITED]:${myNostr?.npub ?: ""}"
                    // Prefer mesh if session established, else try Nostr
                    if (meshService.hasEstablishedSession(peerID)) {
                        // Reuse existing private message path for notifications
                        meshService.sendPrivateMessage(
                            announcementContent,
                            peerID,
                            nickname,
                            java.util.UUID.randomUUID().toString()
                        )
                    } else {
                        val nostrTransport = com.roman.zemzeme.nostr.NostrTransport.getInstance(getApplication())
                        nostrTransport.senderPeerID = meshService.myPeerID
                        nostrTransport.sendFavoriteNotification(peerID, isNowFavorite)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Log current state after toggle
        logCurrentFavoriteState()
    }
    
    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "StateFlow favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
            }
        }
    }
    
    // Location notes subscription management moved to LocationNotesViewModelExtensions.kt
    
    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            meshService.getSessionState(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.roman.zemzeme.services.MessageRouter
                    .getInstance(getApplication(), meshService)
                    .onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
        fingerprints.forEach { (peerID, fingerprint) ->
            identityManager.cachePeerFingerprint(peerID, fingerprint)
            val info = try { meshService.getPeerInfo(peerID) } catch (_: Exception) { null }
            val noiseKeyHex = info?.noisePublicKey?.hexEncodedString()
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fingerprint)
            }
            info?.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                identityManager.cacheFingerprintNickname(fingerprint, nickname)
            }
        }

        val meshNicknames = meshService.getPeerNicknames()
        // Merge mesh nicknames on top of existing map (preserves saved contact nicknames)
        val mergedNicknames = state.peerNicknames.value.toMutableMap()
        mergedNicknames.putAll(meshNicknames)
        state.setPeerNicknames(mergedNicknames)

        val rssiValues = meshService.getPeerRSSI()
        state.setPeerRSSI(rssiValues)

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                meshService.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }

        // Flush any pending QR verification once a Noise session is established
        currentPeers.forEach { peerID ->
            if (meshService.getSessionState(peerID) is NoiseSession.NoiseSessionState.Established) {
                verificationHandler.sendPendingVerificationIfNeeded(peerID)
            }
        }
    }

    // MARK: - QR Verification
    
    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && verifiedFingerprints.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray, verifiedFingerprints: Set<String>): Boolean {
        val fingerprint = verificationHandler.fingerprintFromNoiseBytes(noisePublicKey)
        return verifiedFingerprints.contains(fingerprint)
    }

    fun unverifyFingerprint(peerID: String) {
        verificationHandler.unverifyFingerprint(peerID)
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        return verificationHandler.beginQRVerification(qr)
    }

    // MARK: - Debug and Troubleshooting
    
    fun getDebugStatus(): String {
        return meshService.getDebugStatus()
    }
    
    fun setCurrentPrivateChatPeer(peerID: String?) {
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }
    
    fun setCurrentGeohash(geohash: String?) {
        notificationManager.setCurrentGeohash(geohash)
    }

    fun clearNotificationsForSender(peerID: String) {
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearNotificationsForGeohash(geohash: String) {
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    private var reopenSidebarAfterVerification = false

    fun showVerificationSheet(fromSidebar: Boolean = false) {
        if (fromSidebar) {
            reopenSidebarAfterVerification = true
        }
        state.setShowVerificationSheet(true)
    }

    fun hideVerificationSheet() {
        state.setShowVerificationSheet(false)
        if (reopenSidebarAfterVerification) {
            reopenSidebarAfterVerification = false
            state.setShowMeshPeerList(true)
        }
    }

    fun showSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(true)
    }

    fun hideSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(false)
    }

    fun showMeshPeerList() {
        state.setShowMeshPeerList(true)
    }

    fun hideMeshPeerList() {
        state.setShowMeshPeerList(false)
    }

    fun showPrivateChatSheet(peerID: String) {
        state.setPrivateChatSheetPeer(peerID)
    }

    fun hidePrivateChatSheet() {
        state.setPrivateChatSheetPeer(null)
    }

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        return verificationHandler.getPeerFingerprintForDisplay(peerID)
    }

    fun getMyFingerprint(): String {
        return verificationHandler.getMyFingerprint()
    }

    fun resolvePeerDisplayNameForFingerprint(peerID: String): String {
        return verificationHandler.resolvePeerDisplayNameForFingerprint(peerID)
    }

    fun verifyFingerprintValue(fingerprint: String) {
        verificationHandler.verifyFingerprintValue(fingerprint)
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        verificationHandler.unverifyFingerprintValue(fingerprint)
    }

    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, meshService, this)
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }
    
    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: ZemzemeMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyChallenge(peerID, payload)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyResponse(peerID, payload)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        if (peerID.startsWith("p2p:")) {
            return com.roman.zemzeme.p2p.P2PFavoritesRegistry.isFavorite(peerID)
                    || dataManager.favoritePeers.contains(peerID)
        }
        return meshDelegateHandler.isFavorite(peerID)
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
    
    // MARK: - Emergency Clear
    
    fun panicClearAllData() {
        Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")

        // Clear all UI managers
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()

        // Clear all custom and geographic groups and contacts (keep nearby Mesh + location channel)
        state.setCustomGroups(emptySet())
        state.setGeographicGroups(emptySet())
        state.setContacts(emptySet())

        dataManager.clearAllData()

        // Clear process-wide in-memory message store
        com.roman.zemzeme.services.AppStateStore.clear()

        // Clear seen message store
        try {
            com.roman.zemzeme.services.SeenMessageStore.getInstance(getApplication()).clear()
        } catch (_: Exception) { }
        
        // Clear all mesh service data
        clearAllMeshServiceData()
        
        // Clear all cryptographic data
        clearAllCryptographicData()
        
        // Clear all notifications
        notificationManager.clearAllNotifications()

        // Clear all media files
        com.roman.zemzeme.features.file.FileUtils.clearAllMedia(getApplication())
        
        // Clear Nostr/geohash state, keys, connections, bookmarks, and reinitialize from scratch
        try {
            // Clear geohash bookmarks too (panic should remove everything)
            try {
                val store = com.roman.zemzeme.geohash.GeohashBookmarksStore.getInstance(getApplication())
                store.clearAll()
            } catch (_: Exception) { }

            geohashViewModel.panicReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Nostr/geohash: ${e.message}")
        }

        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        
        // Recreate mesh service with fresh identity
        recreateMeshServiceAfterPanic()

        Log.w(TAG, "🚨 PANIC MODE COMPLETED - New identity: ${meshService.myPeerID}")
    }

    /**
     * Recreate the mesh service with a fresh identity after panic clear.
     * This ensures the new cryptographic keys are used for a new peer ID.
     */
    private fun recreateMeshServiceAfterPanic() {
        val oldPeerID = meshService.myPeerID

        // Clear the holder so getOrCreate() returns a fresh instance
        MeshServiceHolder.clear()

        // Create fresh mesh service with new identity (keys were regenerated in clearAllCryptographicData)
        val freshMeshService = MeshServiceHolder.getOrCreate(getApplication())

        // Replace our reference and set up the new service
        meshService = freshMeshService
        meshService.delegate = this

        // Restart mesh operations with new identity
        meshService.startServices()
        meshService.sendBroadcastAnnounce()

        Log.d(
            TAG,
            "Mesh service recreated. Old peerID: $oldPeerID, New peerID: ${meshService.myPeerID}"
        )
    }
    
    /**
     * Clear all mesh service related data
     */
    private fun clearAllMeshServiceData() {
        try {
            // Request mesh service to clear all its internal data
            meshService.clearAllInternalData()
            
            Log.d(TAG, "Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing mesh service data: ${e.message}")
        }
    }
    
    /**
     * Clear all cryptographic data including persistent identity
     */
    private fun clearAllCryptographicData() {
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            meshService.clearAllEncryptionData()
            
            // Clear secure identity state (if used)
            try {
                val identityManager = SecureIdentityStateManager(getApplication())
                identityManager.clearIdentityData()
                // Also clear secure values used by FavoritesPersistenceService (favorites + peerID index)
                try {
                    identityManager.clearSecureValues("favorite_relationships", "favorite_peerid_index")
                } catch (_: Exception) { }
                Log.d(TAG, "Cleared secure identity state and secure favorites store")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }

            // Clear FavoritesPersistenceService persistent relationships
            try {
                FavoritesPersistenceService.shared.clearAllFavorites()
                Log.d(TAG, "Cleared FavoritesPersistenceService relationships")
            } catch (_: Exception) { }

            try {
                com.roman.zemzeme.p2p.P2PAliasRegistry.clear()
                com.roman.zemzeme.p2p.P2PFavoritesRegistry.clear()
                Log.d(TAG, "Cleared P2P alias and favorites registries")
            } catch (_: Exception) { }
             
            Log.d(TAG, "Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cryptographic data: ${e.message}")
        }
    }

    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        return geohashViewModel.geohashParticipantCount(geohash)
    }

    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        geohashViewModel.beginGeohashSampling(geohashes)
    }

    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        // No-op in refactored architecture; sampling subscriptions are short-lived
    }

    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return geohashViewModel.isPersonTeleported(pubkeyHex)
    }

    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        geohashViewModel.startGeohashDM(pubkeyHex) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    /**
     * Start P2P DM with a P2P peer ID (e.g., "p2p:12D3KooW...")
     * This function handles P2P peers discovered via libp2p DHT.
     * Note: This only initializes state, the caller is responsible for opening the sheet.
     */
    fun startP2PDM(p2pPeerId: String) {
        val rawPeerId = p2pPeerId.removePrefix("p2p:")
        
        // Register the P2P peer ID mapping for message routing
        com.roman.zemzeme.p2p.P2PAliasRegistry.put(p2pPeerId, rawPeerId)
        
        // Cache the display name from geohash participants if available
        val displayName = geohashViewModel.getP2PDisplayName(p2pPeerId)
        if (displayName != null) {
            com.roman.zemzeme.p2p.P2PAliasRegistry.setDisplayName(p2pPeerId, displayName)
            // Also update peerNicknames for PrivateChatSheet to use
            state.updatePeerNickname(p2pPeerId, displayName)
        } else {
            // Fallback: use truncated peer ID as display name
            val fallbackName = "p2p:${rawPeerId.take(8)}..."
            com.roman.zemzeme.p2p.P2PAliasRegistry.setDisplayName(p2pPeerId, fallbackName)
        }
        
        Log.d(TAG, "Starting P2P DM with $p2pPeerId (rawPeerId=$rawPeerId, displayName=$displayName)")
        
        // CRITICAL: Set the selected private chat peer for message sending
        // This is what sendMessage() checks to determine the recipient
        state.setSelectedPrivateChatPeer(p2pPeerId)
        
        // Initialize the private chat (creates message list if doesn't exist)
        messageManager.initializePrivateChat(p2pPeerId)
        
        // Clear any unread markers for this peer
        messageManager.clearPrivateUnreadMessages(p2pPeerId)
        
        // Note: Don't call showPrivateChatSheet here - the caller or LaunchedEffect handles that
    }

    /**
     * Get detailed P2P connection info for a peer (on-demand, not polled).
     * Returns null for non-P2P peers or if not connected.
     */
    fun getP2PConnectionInfo(peerID: String): com.roman.zemzeme.p2p.PeerConnectionInfo? {
        if (!peerID.startsWith("p2p:")) return null
        val rawPeerID = peerID.removePrefix("p2p:")
        return try {
            val p2pTransport = com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication())
            p2pTransport.p2pRepository.getConnectionInfo(rawPeerID)
        } catch (e: Exception) {
            null
        }
    }

    fun isP2PPeerConnected(peerID: String): Boolean {
        if (!peerID.startsWith("p2p:")) return false
        val rawPeerID = peerID.removePrefix("p2p:")
        return try {
            val p2pTransport = com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication())
            p2pTransport.p2pRepository.isConnected(rawPeerID)
        } catch (e: Exception) {
            false
        }
    }

    fun selectLocationChannel(channel: com.roman.zemzeme.geohash.ChannelID) {
        geohashViewModel.selectLocationChannel(channel)
    }

    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        geohashViewModel.blockUserInGeohash(targetNickname)
    }

    // MARK: - Group Management (HomeScreen)

    private val geohashBase32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun createGroup(nickname: String): String {
        val geohash = buildString {
            repeat(6) { append(geohashBase32[Random.nextInt(geohashBase32.length)]) }
        }
        dataManager.saveGroupNickname(geohash, nickname)
        val groups = state.getCustomGroupsValue().toMutableSet()
        groups.add(geohash)
        dataManager.saveCustomGroups(groups)
        state.setCustomGroups(groups)
        state.setGroupNicknames(dataManager.loadAllGroupNicknames())
        subscribeP2PTopics(geohash)
        return geohash
    }

    fun joinGroup(geohash: String, nickname: String) {
        dataManager.saveGroupNickname(geohash, nickname)
        val groups = state.getCustomGroupsValue().toMutableSet()
        groups.add(geohash)
        dataManager.saveCustomGroups(groups)
        state.setCustomGroups(groups)
        state.setGroupNicknames(dataManager.loadAllGroupNicknames())
        subscribeP2PTopics(geohash)
    }

    fun renameGroup(geohash: String, newNickname: String) {
        dataManager.saveGroupNickname(geohash, newNickname)
        state.setGroupNicknames(dataManager.loadAllGroupNicknames())
    }

    fun removeGroup(geohash: String) {
        dataManager.removeCustomGroup(geohash)
        state.setCustomGroups(dataManager.loadCustomGroups())
        state.setGroupNicknames(dataManager.loadAllGroupNicknames())
        // Unsubscribe P2P topics to free Go-side topic slots (max 10)
        unsubscribeP2PTopics(geohash)
    }

    fun addGeographicGroup(geohash: String, nickname: String) {
        dataManager.saveGroupNickname(geohash, nickname)
        val groups = state.getGeographicGroupsValue().toMutableSet()
        groups.add(geohash)
        dataManager.saveGeographicGroups(groups)
        state.setGeographicGroups(groups)
        state.setGroupNicknames(dataManager.loadAllGroupNicknames())
        subscribeP2PTopics(geohash)
    }

    fun removeGeographicGroup(geohash: String) {
        dataManager.removeGeographicGroup(geohash)
        state.setGeographicGroups(dataManager.loadGeographicGroups())
        state.setGroupNicknames(dataManager.loadAllGroupNicknames())
        // Unsubscribe P2P topics to free Go-side topic slots (max 10)
        unsubscribeP2PTopics(geohash)
    }

    private fun subscribeP2PTopics(geohash: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val p2pRepo = com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication()).p2pRepository
                if (p2pRepo.nodeStatus.value != com.roman.zemzeme.p2p.P2PNodeStatus.RUNNING) return@launch
                val config = com.roman.zemzeme.p2p.P2PConfig(getApplication())
                if (!config.p2pEnabled) return@launch
                val repo = com.roman.zemzeme.p2p.P2PTopicsRepository.getInstance(getApplication(), p2pRepo)
                repo.subscribeTopic(geohash)
                repo.discoverTopicPeers(geohash)
                repo.startContinuousRefresh(geohash)
                android.util.Log.d("ChatViewModel", "Subscribed P2P topics for new group: $geohash")
            } catch (e: Exception) {
                android.util.Log.w("ChatViewModel", "Failed to subscribe P2P topics: ${e.message}")
            }
        }
    }

    private fun unsubscribeP2PTopics(geohash: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val repo = com.roman.zemzeme.p2p.P2PTopicsRepository.getInstance(
                    getApplication(),
                    com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication()).p2pRepository
                )
                repo.unsubscribeTopic(geohash)
                android.util.Log.d("ChatViewModel", "Unsubscribed P2P topics for deleted group: $geohash")
            } catch (e: Exception) {
                android.util.Log.w("ChatViewModel", "Failed to unsubscribe P2P topics: ${e.message}")
            }
        }
    }

    fun setOnChatScreen(onChatScreen: Boolean) {
        state.setIsOnChatScreen(onChatScreen)
        // Clean up private chat state when leaving the chat screen
        if (!onChatScreen && state.getPrivateChatSheetPeerValue() != null) {
            endPrivateChat()
        }
    }

    fun navigateToMesh() {
        state.setUnreadMeshCount(0)
        selectLocationChannel(com.roman.zemzeme.geohash.ChannelID.Mesh)
    }

    fun navigateToLocationChannel(channelID: com.roman.zemzeme.geohash.ChannelID.Location) {
        messageManager.clearChannelUnreadCount("geo:${channelID.channel.geohash}")
        selectLocationChannel(channelID)
    }

    fun navigateToGeohashGroup(geohash: String) {
        messageManager.clearChannelUnreadCount("geo:$geohash")
        val level = when (geohash.length) {
            8 -> com.roman.zemzeme.geohash.GeohashChannelLevel.BUILDING
            7 -> com.roman.zemzeme.geohash.GeohashChannelLevel.BLOCK
            6 -> com.roman.zemzeme.geohash.GeohashChannelLevel.NEIGHBORHOOD
            5 -> com.roman.zemzeme.geohash.GeohashChannelLevel.CITY
            4 -> com.roman.zemzeme.geohash.GeohashChannelLevel.PROVINCE
            else -> com.roman.zemzeme.geohash.GeohashChannelLevel.NEIGHBORHOOD
        }
        val channel = com.roman.zemzeme.geohash.GeohashChannel(level, geohash)
        selectLocationChannel(com.roman.zemzeme.geohash.ChannelID.Location(channel))
    }

    fun navigateToPrivateChat(peerID: String) {
        showPrivateChatSheet(peerID)
    }

    fun getLastMeshMessage(): ZemzemeMessage? {
        return state.getMessagesValue().lastOrNull()
    }

    fun getLastPrivateMessage(peerID: String): ZemzemeMessage? {
        return state.getPrivateChatsValue()[peerID]?.lastOrNull()
    }

    fun clearMeshHistory() {
        state.setMessages(emptyList())
    }

    fun clearGeohashHistory(geohash: String) {
        val key = "geo:$geohash"
        val updated = state.getChannelMessagesValue().toMutableMap()
        updated.remove(key)
        state.setChannelMessages(updated)
    }

    fun clearPrivateChatHistory(peerID: String) {
        messageManager.clearPrivateMessages(peerID)
    }

    fun deletePrivateChat(peerID: String) {
        val chats = state.getPrivateChatsValue().toMutableMap()
        chats.remove(peerID)
        state.setPrivateChats(chats)
        val unread = state.getUnreadPrivateMessagesValue().toMutableSet()
        unread.remove(peerID)
        state.setUnreadPrivateMessages(unread)
    }

    // MARK: - Contacts Management

    fun addContactIfNeeded(peerID: String) {
        val current = state.getContactsValue()
        if (peerID !in current) {
            val updated = current + peerID
            state.setContacts(updated)
            dataManager.saveContacts(updated)
        }
        // Always update the stored nickname with the latest known name
        val displayName = state.peerNicknames.value[peerID]
            ?: meshService.getPeerNicknames()[peerID]
            ?: state.getPrivateChatsValue()[peerID]?.lastOrNull { it.senderPeerID == peerID }?.sender
        if (displayName != null && !displayName.startsWith("p2p:") && displayName != peerID) {
            dataManager.saveContactNickname(peerID, displayName)
        }
    }

    fun removeContact(peerID: String) {
        val current = state.getContactsValue().toMutableSet()
        current.remove(peerID)
        state.setContacts(current)
        dataManager.saveContacts(current)
        deletePrivateChat(peerID)
    }

    private var enteredViaContact = false

    fun consumeEnteredViaContact(): Boolean {
        val was = enteredViaContact
        enteredViaContact = false
        return was
    }

    fun navigateToContact(peerID: String) {
        enteredViaContact = true
        navigateToMesh()
        showPrivateChatSheet(peerID)
    }

    // MARK: - Navigation Management

    fun showAppInfo() {
        state.setShowAppInfo(true)
    }
    
    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }

    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when {
            // Close app info dialog
            state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }
            // Close password dialog
            state.getShowPasswordPromptValue() -> {
                state.setShowPasswordPrompt(false)
                state.setPasswordPromptChannel(null)
                true
            }
            // Exit private chat
            state.getSelectedPrivateChatPeerValue() != null || state.getPrivateChatSheetPeerValue() != null -> {
                endPrivateChat()
                true
            }
            // Exit channel view
            state.getCurrentChannelValue() != null -> {
                switchToChannel(null)
                true
            }
            // No special navigation state - let system handle (usually exits app)
            else -> false
        }
    }

    // MARK: - iOS-Compatible Color System

    /**
     * Get consistent color for a mesh peer by ID (iOS-compatible)
     */
    fun colorForMeshPeer(peerID: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        // Try to get stable Noise key, fallback to peer ID
        val seed = "noise:${peerID.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }

    /**
     * Get consistent color for a Nostr pubkey (iOS-compatible)
     */
    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        return geohashViewModel.colorForNostrPubkey(pubkeyHex, isDark)
    }

    /**
     * Trigger P2P refresh/recovery.
     * Calls both topic peer discovery and forces DHT recovery if needed.
     */
    fun refreshP2PConnection() {
        viewModelScope.launch {
            try {
                val p2pTransport = com.roman.zemzeme.p2p.P2PTransport.getInstance(getApplication())

                // First try topic peer refresh (lightweight)
                geohashViewModel.refreshP2PPeers()

                // Then force DHT recovery (heavier, re-bootstraps if needed)
                p2pTransport.p2pRepository.forceRecovery()

                Log.d("ChatViewModel", "P2P refresh triggered")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "P2P refresh failed: ${e.message}")
            }
        }
    }

}
