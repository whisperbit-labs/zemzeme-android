package com.roman.zemzeme.mesh

import android.content.Context
import android.util.Log
import com.roman.zemzeme.crypto.EncryptionService
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.protocol.MessagePadding
import com.roman.zemzeme.model.RoutedPacket
import com.roman.zemzeme.model.IdentityAnnouncement
import com.roman.zemzeme.model.NoisePayload
import com.roman.zemzeme.model.NoisePayloadType
import com.roman.zemzeme.protocol.ZemzemePacket
import com.roman.zemzeme.protocol.MessageType
import com.roman.zemzeme.protocol.SpecialRecipients
import com.roman.zemzeme.model.RequestSyncPacket
import com.roman.zemzeme.sync.GossipSyncManager
import com.roman.zemzeme.util.toHexString
import com.roman.zemzeme.services.VerificationService
import com.roman.zemzeme.p2p.P2PTransport
import com.roman.zemzeme.p2p.P2PConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.math.sign
import kotlin.random.Random

/**
 * Bluetooth mesh service - REFACTORED to use component-based architecture
 * 100% compatible with iOS version and maintains exact same UUIDs, packet format, and protocol logic
 * 
 * This is now a coordinator that orchestrates the following components:
 * - PeerManager: Peer lifecycle management
 * - FragmentManager: Message fragmentation and reassembly  
 * - SecurityManager: Security, duplicate detection, encryption
 * - StoreForwardManager: Offline message caching
 * - MessageHandler: Message type processing and relay logic
 * - BluetoothConnectionManager: BLE connections and GATT operations
 * - PacketProcessor: Incoming packet routing
 */
class BluetoothMeshService(private val context: Context) {
    private val debugManager by lazy { try { com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    data class TransportRuntimeState(
        val desiredToggles: P2PConfig.TransportToggles,
        val bleRunning: Boolean,
        val p2pRunning: Boolean,
        val nostrEnabled: Boolean,
        val nostrConnected: Boolean,
        val isActive: Boolean,
        val isApplying: Boolean
    )
    
    companion object {
        private const val TAG = "BluetoothMeshService"
        private val MAX_TTL: UByte = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS
    }
    
    // Core components - each handling specific responsibilities
    private val encryptionService = EncryptionService(context)

    // My peer identification - derived from persisted Noise identity fingerprint (first 16 hex chars)
    val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID, context.applicationContext)
    internal val connectionManager = BluetoothConnectionManager(context, myPeerID, fragmentManager) // Made internal for access
    private val packetProcessor = PacketProcessor(myPeerID)
    private lateinit var gossipSyncManager: GossipSyncManager
    
    // P2P Transport (libp2p) component
    private val p2pTransport: P2PTransport by lazy { P2PTransport.getInstance(context) }
    private val p2pConfig: P2PConfig by lazy { P2PConfig(context) }
    // Service-level notification manager for background (no-UI) DMs
    private val serviceNotificationManager = com.roman.zemzeme.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext),
        com.roman.zemzeme.util.NotificationIntervalManager()
    )
    
    // Service state management
    private var isActive = false
    
    // Delegate for message callbacks (maintains same interface)
    var delegate: BluetoothMeshDelegate? = null
    
    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Tracks whether this instance has been terminated via stopServices()
    private var terminated = false
    private val transportMutationLock = Mutex()

    @Volatile
    private var bleTransportRunning = false

    @Volatile
    private var desiredTransportToggles: P2PConfig.TransportToggles = p2pConfig.getTransportToggles()

    private val _transportRuntimeState = MutableStateFlow(
        TransportRuntimeState(
            desiredToggles = desiredTransportToggles,
            bleRunning = false,
            p2pRunning = p2pTransport.isRunning(),
            nostrEnabled = desiredTransportToggles.nostrEnabled,
            nostrConnected = runCatching {
                com.roman.zemzeme.nostr.NostrRelayManager.getInstance(context).isConnected.value
            }.getOrDefault(false),
            isActive = false,
            isApplying = false
        )
    )
    val transportRuntimeState: StateFlow<TransportRuntimeState> = _transportRuntimeState.asStateFlow()

    private var periodicAnnounceJob: Job? = null
    
    init {
        Log.i(TAG, "Initializing BluetoothMeshService for peer=$myPeerID")
        VerificationService.configure(encryptionService)
        setupDelegates()
        messageHandler.packetProcessor = packetProcessor
        //startPeriodicDebugLogging()

        // Initialize sync manager (needs serviceScope)
        gossipSyncManager = GossipSyncManager(
            myPeerID = myPeerID,
            scope = serviceScope,
            configProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = try {
                    com.roman.zemzeme.ui.debug.DebugPreferenceManager.getSeenPacketCapacity(500)
                } catch (_: Exception) { 500 }

                override fun gcsMaxBytes(): Int = try {
                    com.roman.zemzeme.ui.debug.DebugPreferenceManager.getGcsMaxFilterBytes(400)
                } catch (_: Exception) { 400 }

                override fun gcsTargetFpr(): Double = try {
                    com.roman.zemzeme.ui.debug.DebugPreferenceManager.getGcsFprPercent(1.0) / 100.0
                } catch (_: Exception) { 0.01 }
            }
        )

        // Wire sync manager delegate
        gossipSyncManager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: ZemzemePacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
            override fun sendPacketToPeer(peerID: String, packet: ZemzemePacket) {
                connectionManager.sendPacketToPeer(peerID, packet)
            }
            override fun signPacketForBroadcast(packet: ZemzemePacket): ZemzemePacket {
                return signPacketBeforeBroadcast(packet)
            }
        }
        
        // Inject dynamic direct connection check into PeerManager
        // Matches iOS logic: checks if we have an active hardware mapping for this peer
        peerManager.isPeerDirectlyConnected = { peerID ->
            connectionManager.addressPeerMap.containsValue(peerID)
        }

        val configuredToggles = p2pConfig.getTransportToggles()
        desiredTransportToggles = configuredToggles
        updateActiveStateLocked(desiredTransportToggles)
        publishTransportRuntimeStateLocked(isApplying = false)

        serviceScope.launch {
            p2pTransport.p2pRepository.nodeStatus.collect {
                transportMutationLock.withLock {
                    updateActiveStateLocked()
                    publishTransportRuntimeStateLocked(isApplying = false)
                }
            }
        }

        serviceScope.launch {
            com.roman.zemzeme.nostr.NostrRelayManager.getInstance(context).isConnected.collect {
                transportMutationLock.withLock {
                    publishTransportRuntimeStateLocked(isApplying = false)
                }
            }
        }
        
        Log.d(TAG, "Delegates set up; GossipSyncManager initialized")
    }
    
    /**
     * Start periodic debug logging every 10 seconds
     */
    private fun startPeriodicDebugLogging() {
        serviceScope.launch {
            Log.d(TAG, "Starting periodic debug logging loop")
            while (this@BluetoothMeshService.isActive) {
                try {
                    delay(10000) // 10 seconds
                    if (this@BluetoothMeshService.isActive) { // Double-check before logging
                        val debugInfo = getDebugStatus()
                        Log.d(TAG, "=== PERIODIC DEBUG STATUS ===\n$debugInfo\n=== END DEBUG STATUS ===")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic debug logging: ${e.message}")
                }
            }
            Log.d(TAG, "Periodic debug logging loop ended (isActive=${this@BluetoothMeshService.isActive})")
        }
    }

    /**
     * Send broadcast announcement every 30 seconds
     */
    private fun sendPeriodicBroadcastAnnounce() {
        periodicAnnounceJob?.cancel()
        periodicAnnounceJob = serviceScope.launch {
            Log.d(TAG, "Starting periodic announce loop")
            while (this@BluetoothMeshService.isActive && bleTransportRunning) {
                try {
                    delay(30000) // 30 seconds
                    sendBroadcastAnnounce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic broadcast announce: ${e.message}")
                }
            }
            Log.d(TAG, "Periodic announce loop ended (isActive=${this@BluetoothMeshService.isActive}, bleRunning=$bleTransportRunning)")
        }
    }

    private fun stopPeriodicBroadcastAnnounce() {
        periodicAnnounceJob?.cancel()
        periodicAnnounceJob = null
    }

    private fun isP2PEnabledAtRuntime(): Boolean {
        val status = p2pTransport.p2pRepository.nodeStatus.value
        return p2pTransport.isRunning() || status == com.roman.zemzeme.p2p.P2PNodeStatus.STARTING
    }

    // P2P and Nostr can both be enabled simultaneously.

    private fun publishTransportRuntimeStateLocked(isApplying: Boolean) {
        val relayConnected = runCatching {
            com.roman.zemzeme.nostr.NostrRelayManager.getInstance(context).isConnected.value
        }.getOrDefault(false)

        _transportRuntimeState.value = TransportRuntimeState(
            desiredToggles = desiredTransportToggles,
            bleRunning = bleTransportRunning,
            p2pRunning = isP2PEnabledAtRuntime(),
            nostrEnabled = desiredTransportToggles.nostrEnabled,
            nostrConnected = relayConnected,
            isActive = isActive,
            isApplying = isApplying
        )
    }

    private fun updateActiveStateLocked(toggles: P2PConfig.TransportToggles = desiredTransportToggles) {
        val p2pExpectedOrRunning = (toggles.p2pEnabled && p2pConfig.autoStart) || isP2PEnabledAtRuntime()
        isActive = bleTransportRunning || p2pExpectedOrRunning || toggles.nostrEnabled
    }

    private fun isTransportStateConvergedLocked(target: P2PConfig.TransportToggles): Boolean {
        val p2pShouldRun = target.p2pEnabled && p2pConfig.autoStart
        val p2pRunning = isP2PEnabledAtRuntime()

        val bleConverged = if (target.bleEnabled) bleTransportRunning else !bleTransportRunning
        val p2pConverged = if (p2pShouldRun) p2pRunning else !p2pRunning
        val nostrConverged = com.roman.zemzeme.nostr.NostrRelayManager.isEnabled == target.nostrEnabled

        return bleConverged && p2pConverged && nostrConverged
    }

    private suspend fun applyTransportSettingsLocked(
        target: P2PConfig.TransportToggles,
        sendBleLeaveOnDisable: Boolean
    ) {
        val normalizedTarget = target

        if (normalizedTarget.p2pEnabled && com.roman.zemzeme.net.TorPreferenceManager.get(context) == com.roman.zemzeme.net.TorMode.ON) {
            com.roman.zemzeme.net.TorPreferenceManager.set(context, com.roman.zemzeme.net.TorMode.OFF)
            Log.w(TAG, "Disabled Tor because P2P over Tor is not supported")
        }

        if (p2pConfig.bleEnabled != normalizedTarget.bleEnabled) {
            p2pConfig.bleEnabled = normalizedTarget.bleEnabled
        }
        if (p2pConfig.p2pEnabled != normalizedTarget.p2pEnabled) {
            p2pConfig.p2pEnabled = normalizedTarget.p2pEnabled
        }
        if (p2pConfig.nostrEnabled != normalizedTarget.nostrEnabled) {
            p2pConfig.nostrEnabled = normalizedTarget.nostrEnabled
        }

        desiredTransportToggles = normalizedTarget

        applyNostrEnabledLocked(normalizedTarget.nostrEnabled)

        if (normalizedTarget.bleEnabled) {
            startBleTransportLocked()
        } else {
            stopBleTransportLocked(sendLeave = sendBleLeaveOnDisable, cancelScope = false)
        }

        if (normalizedTarget.p2pEnabled && p2pConfig.autoStart) {
            startP2PTransportLocked()
        } else {
            stopP2PTransportLocked()
        }

        updateActiveStateLocked(normalizedTarget)
    }

    private suspend fun startBleTransportLocked(): Boolean {
        if (!p2pConfig.bleEnabled) {
            bleTransportRunning = false
            return false
        }
        if (bleTransportRunning) {
            return true
        }

        val started = connectionManager.startServices()
        if (started) {
            bleTransportRunning = true
            sendPeriodicBroadcastAnnounce()
            gossipSyncManager.start()
            Log.i(TAG, "BLE transport started")
        } else {
            bleTransportRunning = false
            Log.w(TAG, "BLE transport failed to start (Bluetooth may be unavailable)")
        }
        return started
    }

    private suspend fun stopBleTransportLocked(sendLeave: Boolean, cancelScope: Boolean) {
        if (!bleTransportRunning) {
            if (cancelScope) {
                connectionManager.stopServices(cancelScope = true)
            }
            return
        }

        if (sendLeave) {
            runCatching { sendLeaveAnnouncement() }
        }

        stopPeriodicBroadcastAnnounce()
        runCatching { gossipSyncManager.stop() }
        connectionManager.stopServices(cancelScope = cancelScope)
        bleTransportRunning = false
        Log.i(TAG, "BLE transport stopped")
    }

    private suspend fun startP2PTransportLocked() {
        if (!p2pConfig.p2pEnabled || !p2pConfig.autoStart) {
            return
        }
        if (p2pTransport.isRunning()) {
            return
        }

        try {
            val identityManager = com.roman.zemzeme.identity.SecureIdentityStateManager(context)
            val p2pKey = identityManager.getP2PPrivateKey()
            p2pTransport.start(p2pKey).onSuccess {
                Log.i(TAG, "P2P transport started with Peer ID: ${p2pTransport.getMyPeerID()}")
                p2pTransport.getMyPeerID()?.let { peerID ->
                    identityManager.saveP2PPeerID(peerID)
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to start P2P transport: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting P2P transport: ${e.message}", e)
        }
    }

    private suspend fun stopP2PTransportLocked() {
        if (!p2pTransport.isRunning() &&
            p2pTransport.p2pRepository.nodeStatus.value == com.roman.zemzeme.p2p.P2PNodeStatus.STOPPED
        ) {
            return
        }

        runCatching { p2pTransport.stop() }
            .onSuccess { Log.d(TAG, "P2P transport stopped") }
            .onFailure { e -> Log.w(TAG, "Error stopping P2P transport: ${e.message}") }
    }

    private fun applyNostrEnabledLocked(enabled: Boolean) {
        com.roman.zemzeme.nostr.NostrRelayManager.isEnabled = enabled
        val relayManager = com.roman.zemzeme.nostr.NostrRelayManager.getInstance(context)
        if (enabled) {
            relayManager.connect()
            Log.d(TAG, "Nostr transport enabled")
        } else {
            relayManager.disconnect()
            Log.d(TAG, "Nostr transport disabled")
        }
    }
    
    /**
     * Setup delegate connections between components
     */
    private fun setupDelegates() {
        Log.d(TAG, "Setting up component delegates")
        // Provide nickname resolver to BLE broadcaster and debug manager
        try {
            val resolver: (String) -> String? = { pid -> peerManager.getPeerNickname(pid) }
            connectionManager.setNicknameResolver(resolver)
            debugManager?.setNicknameResolver(resolver)
        } catch (_: Exception) { }
        // PeerManager delegates to main mesh service delegate
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) {
                // Update process-wide state first
                try { com.roman.zemzeme.services.AppStateStore.setPeers(peerIDs) } catch (_: Exception) { }
                // Then notify UI delegate if attached
                delegate?.didUpdatePeerList(peerIDs)
            }
            override fun onPeerRemoved(peerID: String) {
                try { gossipSyncManager.removeAnnouncementForPeer(peerID) } catch (_: Exception) { }
                // Remove from mesh graph topology to prevent routing through stale peers
                try { com.roman.zemzeme.services.meshgraph.MeshGraphService.getInstance().removePeer(peerID) } catch (_: Exception) { }

                // Also drop any Noise session state for this peer when they go offline
                try {
                    encryptionService.removePeer(peerID)
                    Log.i(TAG, "Removed Noise session for offline peer $peerID")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove Noise session for $peerID: ${e.message}")
                }
            }
        }
        
        // SecurityManager delegate for key exchange notifications
        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {
                // Send announcement and cached messages after key exchange
                serviceScope.launch {
                    Log.i(TAG, "Key exchange completed with $peerID; sending follow-ups")
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    
                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }
            
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {
                // Send Noise handshake response
                val responsePacket = ZemzemePacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = MAX_TTL
                )
                // Sign the handshake response
                val signedPacket = signPacketBeforeBroadcast(responsePacket)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.i(TAG, "Sent Noise handshake response to $peerID (${response.size} bytes)")
            }
            
            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }
        }
        
        // StoreForwardManager delegates
        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return delegate?.isFavorite(peerID) ?: false
            }
            
            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }
            
            override fun sendPacket(packet: ZemzemePacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
        }
        
        // MessageHandler delegates
        messageHandler.delegate = object : MessageHandlerDelegate {
            // Peer management
            override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
                return peerManager.addOrUpdatePeer(peerID, nickname)
            }
            
            override fun removePeer(peerID: String) {
                peerManager.removePeer(peerID)
            }
            
            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }
            
            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }
            
            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }
            
            override fun getMyNickname(): String? {
                return delegate?.getNickname()
            }
            
            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }
            
            override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean): Boolean {
                return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
            }
            
            // Packet operations
            override fun sendPacket(packet: ZemzemePacket) {
                // Sign the packet before broadcasting
                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            }
            
            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }
            
            // Cryptographic operations
            override fun verifySignature(packet: ZemzemePacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }
            
            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }
            
            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }
            
            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
            }
            
            // Noise protocol operations
            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }
            
            override fun initiateNoiseHandshake(peerID: String) {
                try {
                    // Initiate proper Noise handshake with specific peer
                    val handshakeData = encryptionService.initiateHandshake(peerID)

                    if (handshakeData != null) {
                        val packet = ZemzemePacket(
                            version = 1u,
                            type = MessageType.NOISE_HANDSHAKE.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(peerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = handshakeData,
                            ttl = MAX_TTL
                        )

                        // Sign the handshake packet before broadcasting
                        val signedPacket = signPacketBeforeBroadcast(packet)
                        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                        Log.d(TAG, "Initiated Noise handshake with $peerID (${handshakeData.size} bytes)")
                    } else {
                        Log.w(TAG, "Failed to generate Noise handshake data for $peerID")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate Noise handshake with $peerID: ${e.message}")
                }
            }
            
            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? {
                return try {
                    encryptionService.processHandshakeMessage(payload, peerID)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process handshake message from $peerID: ${e.message}")
                    null
                }
            }
            
            override fun updatePeerIDBinding(newPeerID: String, nickname: String,
                                           publicKey: ByteArray, previousPeerID: String?) {

                Log.d(TAG, "Updating peer ID binding: $newPeerID (was: $previousPeerID) with nickname: $nickname and public key: ${publicKey.toHexString().take(16)}...")
                // Update peer mapping in the PeerManager for peer ID rotation support
                peerManager.addOrUpdatePeer(newPeerID, nickname)
                
                // Store fingerprint for the peer via centralized fingerprint manager
                val fingerprint = peerManager.storeFingerprintForPeer(newPeerID, publicKey)

                // Index existing Nostr mapping by the new peerID if we have it
                try {
                    com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.findNostrPubkey(publicKey)?.let { npub ->
                        com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(newPeerID, npub)
                    }
                } catch (_: Exception) { }
                
                // If there was a previous peer ID, remove it to avoid duplicates
                previousPeerID?.let { oldPeerID ->
                    peerManager.removePeer(oldPeerID)
                }
                
                Log.d(TAG, "Updated peer ID binding: $newPeerID (was: $previousPeerID), fingerprint: ${fingerprint.take(16)}...")
            }
            
            // Message operations  
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }
            
            // Callbacks
            override fun onMessageReceived(message: ZemzemeMessage) {
                // Always reflect into process-wide store so UI can hydrate after recreation
                try {
                    when {
                        message.isPrivate -> {
                            val peer = message.senderPeerID ?: ""
                            if (peer.isNotEmpty()) com.roman.zemzeme.services.AppStateStore.addPrivateMessage(peer, message)
                        }
                        message.channel != null -> {
                            com.roman.zemzeme.services.AppStateStore.addChannelMessage(message.channel, message)
                        }
                        else -> {
                            com.roman.zemzeme.services.AppStateStore.addPublicMessage(message)
                        }
                    }
                } catch (_: Exception) { }
                // And forward to UI delegate if attached
                delegate?.didReceiveMessage(message)

                // If no UI delegate attached (app closed), show DM notification via service manager
                if (delegate == null && message.isPrivate) {
                    try {
                        val senderPeerID = message.senderPeerID
                        if (senderPeerID != null) {
                            val nick = try { peerManager.getPeerNickname(senderPeerID) } catch (_: Exception) { null } ?: senderPeerID
                            val preview = com.roman.zemzeme.ui.NotificationTextUtils.buildPrivateMessagePreview(message)
                            serviceNotificationManager.setAppBackgroundState(true)
                            serviceNotificationManager.showPrivateMessageNotification(senderPeerID, nick, preview)
                        }
                    } catch (_: Exception) { }
                }
            }
            
            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }
            
            override fun onDeliveryAckReceived(messageID: String, peerID: String) {
                delegate?.didReceiveDeliveryAck(messageID, peerID)
            }
            
            override fun onReadReceiptReceived(messageID: String, peerID: String) {
                delegate?.didReceiveReadReceipt(messageID, peerID)
            }

            override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
            }

            override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
            }
        }
        
        // PacketProcessor delegates
        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: ZemzemePacket, peerID: String): Boolean {
                return securityManager.validatePacket(packet, peerID)
            }
            
            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }
            
            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }
            
            // Network information for relay manager
            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }
            
            override fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
                return runBlocking { securityManager.handleNoiseHandshake(routed) }
            }
            
            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }
            
            override fun handleAnnounce(routed: RoutedPacket) {
                serviceScope.launch {
                    // Process the announce
                    val isFirst = messageHandler.handleAnnounce(routed)

                    // Map device address -> peerID based on TTL (max TTL = direct neighbor)
                    // Matches iOS logic: any announce with max TTL on a link defines the direct peer
                    val deviceAddress = routed.relayAddress
                    val pid = routed.peerID
                    if (deviceAddress != null && pid != null) {
                        // Check if this is a direct connection (MAX TTL)
                        // Note: packet.ttl is UByte, compare with AppConstants.MESSAGE_TTL_HOPS
                        val isDirect = routed.packet.ttl == com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS
                        
                        if (isDirect) {
                            // Bind or rebind this device address to the announcing peer
                            connectionManager.addressPeerMap[deviceAddress] = pid
                            Log.d(TAG, "Mapped device $deviceAddress to peer $pid (TTL=${routed.packet.ttl})")

                            // Mark as directly connected - refresh UI state
                            try { peerManager.refreshPeerList() } catch (_: Exception) { }

                            // Initial sync for this direct peer
                            try { gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000) } catch (_: Exception) { }
                        }
                    }
                    // Track for sync
                    try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                }
            }
            
            override fun handleMessage(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleMessage(routed) }
                // Track broadcast messages for sync
                try {
                    val pkt = routed.packet
                    val isBroadcast = (pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && pkt.type == MessageType.MESSAGE.value) {
                        gossipSyncManager.onPublicPacketSeen(pkt)
                    }
                } catch (_: Exception) { }
            }
            
            override fun handleLeave(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleLeave(routed) }
            }
            
            override fun handleFragment(packet: ZemzemePacket): ZemzemePacket? {
                // Track broadcast fragments for gossip sync
                try {
                    val isBroadcast = (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && packet.type == MessageType.FRAGMENT.value) {
                        gossipSyncManager.onPublicPacketSeen(packet)
                    }
                } catch (_: Exception) { }
                return fragmentManager.handleFragment(packet)
            }
            
            override fun sendAnnouncementToPeer(peerID: String) {
                this@BluetoothMeshService.sendAnnouncementToPeer(peerID)
            }
            
            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }
            
            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }

            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return connectionManager.sendToPeer(peerID, routed)
            }
            
            override fun handleRequestSync(routed: RoutedPacket) {
                // Decode request and respond with missing packets
                val fromPeer = routed.peerID ?: return
                val req = RequestSyncPacket.decode(routed.packet.payload) ?: return
                gossipSyncManager.handleRequestSync(fromPeer, req)
            }
        }
        
        // BluetoothConnectionManager delegates
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(packet: ZemzemePacket, peerID: String, device: android.bluetooth.BluetoothDevice?) {
            // Log incoming for debug graphs (do not double-count anywhere else)
            try {
                com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance().logIncoming(
                    packet = packet,
                    fromPeerID = peerID,
                    fromNickname = null,
                    fromDeviceAddress = device?.address,
                    myPeerID = myPeerID
                )
            } catch (_: Exception) { }
            packetProcessor.processPacket(RoutedPacket(packet, peerID, device?.address))
        }
            
            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                // Send initial announcements after services are ready
                serviceScope.launch {
                    Log.d(TAG, "Device connected: ${device.address}; scheduling announce")
                    delay(200)
                    sendBroadcastAnnounce()
                }
                // Verbose debug: device connected
                try {
                    val addr = device.address
                    val peer = connectionManager.addressPeerMap[addr]
                    val nick = peer?.let { peerManager.getPeerNickname(it) } ?: "unknown"
                    com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance()
                        .logPeerConnection(peer ?: "unknown", nick, addr, isInbound = !connectionManager.isClientConnection(addr)!!)
                } catch (_: Exception) { }
            }

            override fun onDeviceDisconnected(device: android.bluetooth.BluetoothDevice) {
                Log.d(TAG, "Device disconnected: ${device.address}")
                val addr = device.address
                // Remove mapping and, if that was the last direct path for the peer, clear direct flag
                val peer = connectionManager.addressPeerMap[addr]
                // ConnectionTracker has already removed the address mapping; be defensive either way
                connectionManager.addressPeerMap.remove(addr)

                // refresh peer list on disconnect. 
                try { peerManager.refreshPeerList() } catch (_: Exception) { }

                if (peer != null) {
                    // Verbose debug: device disconnected
                    try {
                        val nick = peerManager.getPeerNickname(peer) ?: "unknown"
                        com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance()
                            .logPeerDisconnection(peer, nick, addr)
                    } catch (_: Exception) { }
                }
            }
            
            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                // Find the peer ID for this device address and update RSSI in PeerManager
                connectionManager.addressPeerMap[deviceAddress]?.let { peerID ->
                    peerManager.updatePeerRSSI(peerID, rssi)
                }
            }
        }
    }
    
    /**
     * Start the mesh service
     */
    fun startServices() {
        if (terminated) {
            // This instance's scope was cancelled previously; refuse to start to avoid using dead scopes.
            Log.e(TAG, "Mesh service instance was terminated; create a new instance instead of restarting")
            return
        }

        val configuredToggles = p2pConfig.getTransportToggles()
        desiredTransportToggles = configuredToggles

        serviceScope.launch {
            val result = applyPendingTransportSettings(sendBleLeaveOnDisable = false)
            result.onFailure { e ->
                Log.e(TAG, "Failed to apply transport settings on start: ${e.message}", e)
            }
        }
    }

    private suspend fun applyPendingTransportSettings(sendBleLeaveOnDisable: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            transportMutationLock.withLock {
                if (terminated) {
                    publishTransportRuntimeStateLocked(isApplying = false)
                    Result.failure(IllegalStateException("Service terminated"))
                } else {
                    if (isTransportStateConvergedLocked(desiredTransportToggles)) {
                        updateActiveStateLocked(desiredTransportToggles)
                        publishTransportRuntimeStateLocked(isApplying = false)
                        return@withContext Result.success(Unit)
                    }

                    publishTransportRuntimeStateLocked(isApplying = true)

                    try {
                        var applied: P2PConfig.TransportToggles? = null
                        while (applied != desiredTransportToggles) {
                            val target = desiredTransportToggles
                            applyTransportSettingsLocked(target, sendBleLeaveOnDisable)
                            applied = target
                        }

                        if (!this@BluetoothMeshService.isActive) {
                            Log.w(TAG, "All transports are disabled or unavailable; mesh marked inactive")
                        }

                        publishTransportRuntimeStateLocked(isApplying = false)
                        Result.success(Unit)
                    } catch (e: Exception) {
                        Log.e(TAG, "Transport apply failed: ${e.message}", e)
                        publishTransportRuntimeStateLocked(isApplying = false)
                        Result.failure(e)
                    }
                }
            }
        }

    suspend fun applyTransportSettings(settings: P2PConfig.TransportToggles): Result<Unit> {
        if (terminated) {
            return Result.failure(IllegalStateException("Service terminated"))
        }

        desiredTransportToggles = settings
        return applyPendingTransportSettings(sendBleLeaveOnDisable = true)
    }

    suspend fun setBleEnabled(enabled: Boolean): Result<Unit> {
        if (terminated) {
            return Result.failure(IllegalStateException("Service terminated"))
        }

        val target = desiredTransportToggles.copy(bleEnabled = enabled)
        return applyTransportSettings(target)
    }

    suspend fun setP2PEnabled(enabled: Boolean): Result<Unit> {
        if (terminated) {
            return Result.failure(IllegalStateException("Service terminated"))
        }

        val target = desiredTransportToggles.copy(p2pEnabled = enabled)
        return applyTransportSettings(target)
    }

    suspend fun setNostrEnabled(enabled: Boolean): Result<Unit> {
        if (terminated) {
            return Result.failure(IllegalStateException("Service terminated"))
        }

        val target = desiredTransportToggles.copy(nostrEnabled = enabled)
        return applyTransportSettings(target)
    }
    
    /**
     * Stop all mesh services
     */
    fun stopServices() {
        if (terminated) {
            Log.w(TAG, "Mesh service already terminated, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping Bluetooth mesh service")

        serviceScope.launch {
            transportMutationLock.withLock {
                Log.d(TAG, "Stopping subcomponents and cancelling scope...")
                stopBleTransportLocked(sendLeave = true, cancelScope = true)
                stopP2PTransportLocked()
                applyNostrEnabledLocked(false)

                peerManager.shutdown()
                fragmentManager.shutdown()
                securityManager.shutdown()
                storeForwardManager.shutdown()
                messageHandler.shutdown()
                packetProcessor.shutdown()

                this@BluetoothMeshService.isActive = false
                publishTransportRuntimeStateLocked(isApplying = false)

                // Mark this instance as terminated and cancel its scope so it won't be reused
                terminated = true
                serviceScope.cancel()
                Log.i(TAG, "BluetoothMeshService terminated and scope cancelled")
            }
        }
    }

    /**
     * Whether this instance can be safely reused. Returns false after stopServices() or if
     * any critical internal scope has been cancelled.
     */
    fun isReusable(): Boolean {
        val reusable = !terminated && serviceScope.isActive && connectionManager.isReusable()
        if (!reusable) {
            Log.d(TAG, "isReusable=false (terminated=$terminated, scopeActive=${serviceScope.isActive}, connReusable=${connectionManager.isReusable()})")
        }
        return reusable
    }
    
    /**
     * Send public message
     */
    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return
        
        serviceScope.launch {
            val packet = ZemzemePacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = content.toByteArray(Charsets.UTF_8),
                signature = null,
                ttl = MAX_TTL
            )

            // Sign the packet before broadcasting
            val signedPacket = signPacketBeforeBroadcast(packet)
            connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            // Track our own broadcast message for sync
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    /**
     * Send a file over mesh as a broadcast MESSAGE (public mesh timeline/channels).
     */
    fun sendFileBroadcast(file: com.roman.zemzeme.model.ZemzemeFilePacket) {
        try {
            Log.d(TAG, "📤 sendFileBroadcast: name=${file.fileName}, size=${file.fileSize}")
            val payload = file.encode()
            if (payload == null) {
                Log.e(TAG, "❌ Failed to encode file packet in sendFileBroadcast")
                return
            }
            Log.d(TAG, "📦 Encoded payload: ${payload.size} bytes")
        serviceScope.launch {
            val packet = ZemzemePacket(
                version = 2u,  // FILE_TRANSFER uses v2 for 4-byte payload length to support large files
                type = MessageType.FILE_TRANSFER.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = MAX_TTL
            )
            val signed = signPacketBeforeBroadcast(packet)
            // Use a stable transferId based on the file TLV payload for progress tracking
            val transferId = sha256Hex(payload)
            connectionManager.broadcastPacket(RoutedPacket(signed, transferId = transferId))
            try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
        }
            } catch (e: Exception) {
            Log.e(TAG, "❌ sendFileBroadcast failed: ${e.message}", e)
            Log.e(TAG, "❌ File: name=${file.fileName}, size=${file.fileSize}")
        }
    }

    /**
     * Send a file as an encrypted private message using Noise protocol
     */
    fun sendFilePrivate(recipientPeerID: String, file: com.roman.zemzeme.model.ZemzemeFilePacket) {
        try {
            Log.d(TAG, "📤 sendFilePrivate (ENCRYPTED): to=$recipientPeerID, name=${file.fileName}, size=${file.fileSize}")
            
            serviceScope.launch {
                // Check if we have an established Noise session
                if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                    try {
                        // Encode the file packet as TLV
                        val filePayload = file.encode()
                        if (filePayload == null) {
                            Log.e(TAG, "❌ Failed to encode file packet for private send")
                            return@launch
                        }
                        Log.d(TAG, "📦 Encoded file TLV: ${filePayload.size} bytes")
                        
                        // Create NoisePayload wrapper (type byte + file TLV data) - same as iOS
                        val noisePayload = com.roman.zemzeme.model.NoisePayload(
                            type = com.roman.zemzeme.model.NoisePayloadType.FILE_TRANSFER,
                            data = filePayload
                        )
                        
                        // Encrypt the payload using Noise
                        val encrypted = encryptionService.encrypt(noisePayload.encode(), recipientPeerID)
                        Log.d(TAG, "🔐 Encrypted file payload: ${encrypted.size} bytes")
                        
                        // Create NOISE_ENCRYPTED packet (not FILE_TRANSFER!)
                        val packet = ZemzemePacket(
                            version = 1u,
                            type = MessageType.NOISE_ENCRYPTED.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(recipientPeerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = encrypted,
                            signature = null,
                            ttl = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS
                        )
                        
                        // Sign and send the encrypted packet
                        val signed = signPacketBeforeBroadcast(packet)
                        // Use a stable transferId based on the unencrypted file TLV payload for progress tracking
                        val transferId = sha256Hex(filePayload)
                        connectionManager.broadcastPacket(RoutedPacket(signed, transferId = transferId))
                        Log.d(TAG, "✅ Sent encrypted file to $recipientPeerID")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to encrypt file for $recipientPeerID: ${e.message}", e)
                    }
                } else {
                    // No session - initiate handshake but don't queue file
                    Log.w(TAG, "⚠️ No Noise session with $recipientPeerID for file transfer, initiating handshake")
                    messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendFilePrivate failed: ${e.message}", e)
            Log.e(TAG, "❌ File: to=$recipientPeerID, name=${file.fileName}, size=${file.fileSize}")
        }
    }

    fun cancelFileTransfer(transferId: String): Boolean {
        return connectionManager.cancelTransfer(transferId)
    }

    // Local helper to hash payloads to a stable hex ID for progress mapping
    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { bytes.size.toString(16) }
    
    /**
     * Send private message - SIMPLIFIED iOS-compatible version 
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null, mentions: List<String>? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return
        if (recipientNickname.isEmpty()) return
        
        serviceScope.launch {
            val finalMessageID = messageID ?: java.util.UUID.randomUUID().toString()
            
            Log.d(TAG, "📨 Sending PM to $recipientPeerID (content scrubbed for security)")
            
            // Check if we have an established Noise session
            if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                try {
                    // Create TLV-encoded private message exactly like iOS
                    val privateMessage = com.roman.zemzeme.model.PrivateMessagePacket(
                        messageID = finalMessageID,
                        content = content,
                        mentions = mentions
                    )
                    
                    val tlvData = privateMessage.encode()
                    if (tlvData == null) {
                        Log.e(TAG, "Failed to encode private message with TLV")
                        return@launch
                    }
                    
                    // Create message payload with NoisePayloadType prefix: [type byte] + [TLV data]
                    val messagePayload = com.roman.zemzeme.model.NoisePayload(
                        type = com.roman.zemzeme.model.NoisePayloadType.PRIVATE_MESSAGE,
                        data = tlvData
                    )
                    
                    // Encrypt the payload
                    val encrypted = encryptionService.encrypt(messagePayload.encode(), recipientPeerID)
                    
                    // Create NOISE_ENCRYPTED packet exactly like iOS
                    val packet = ZemzemePacket(
                        version = 1u,
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encrypted,
                        signature = null,
                        ttl = MAX_TTL
                    )
                    
                    // Sign the packet before broadcasting
                    val signedPacket = signPacketBeforeBroadcast(packet)
                    connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                    Log.d(TAG, "📤 Sent encrypted private message to $recipientPeerID (${encrypted.size} bytes)")
                    
                    // FIXED: Don't send didReceiveMessage for our own sent messages
                    // This was causing self-notifications - iOS doesn't do this
                    // The UI handles showing sent messages through its own message sending logic
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt private message for $recipientPeerID: ${e.message}")
                }
            } else {
                // Fire and forget - initiate handshake but don't queue exactly like iOS
                Log.d(TAG, "🤝 No session with $recipientPeerID, initiating handshake")
                messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
                
                // FIXED: Don't send didReceiveMessage for our own sent messages
                // The UI will handle showing the message in the chat interface
            }
        }
    }
    
    /**
     * Send read receipt for a received private message - NEW NoisePayloadType implementation
     * Uses same encryption approach as iOS SimplifiedBluetoothService
     */
    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        serviceScope.launch {
            Log.d(TAG, "📖 Sending read receipt for message $messageID to $recipientPeerID")

            // Route geohash read receipts via MessageRouter instead of here
            val geo = runCatching { com.roman.zemzeme.services.MessageRouter.tryGetInstance() }.getOrNull()
            val isGeoAlias = try {
                val map = com.roman.zemzeme.nostr.GeohashAliasRegistry.snapshot()
                map.containsKey(recipientPeerID)
            } catch (_: Exception) { false }
            if (isGeoAlias && geo != null) {
                geo.sendReadReceipt(com.roman.zemzeme.model.ReadReceipt(messageID), recipientPeerID)
                return@launch
            }

            try {
                // Avoid duplicate read receipts: check persistent store first
                val seenStore = try { com.roman.zemzeme.services.SeenMessageStore.getInstance(context.applicationContext) } catch (_: Exception) { null }
                if (seenStore?.hasRead(messageID) == true) {
                    Log.d(TAG, "Skipping read receipt for $messageID - already marked read")
                    return@launch
                }

                // Create read receipt payload using NoisePayloadType exactly like iOS
                val readReceiptPayload = com.roman.zemzeme.model.NoisePayload(
                    type = com.roman.zemzeme.model.NoisePayloadType.READ_RECEIPT,
                    data = messageID.toByteArray(Charsets.UTF_8)
                )
                
                // Encrypt the payload
                val encrypted = encryptionService.encrypt(readReceiptPayload.encode(), recipientPeerID)
                
                // Create NOISE_ENCRYPTED packet exactly like iOS
                val packet = ZemzemePacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS messageTTL
                )
                
                // Sign the packet before broadcasting
                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.d(TAG, "📤 Sent read receipt to $recipientPeerID for message $messageID")

                // Persist as read after successful send
                try { seenStore?.markRead(messageID) } catch (_: Exception) { }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt to $recipientPeerID: ${e.message}")
            }
        }
    }

    // MARK: QR Verification over Noise

    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = VerificationService.buildVerifyChallenge(noiseKeyHex, nonceA)
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_CHALLENGE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify challenge")
    }

    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = VerificationService.buildVerifyResponse(noiseKeyHex, nonceA) ?: return
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_RESPONSE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify response")
    }

    private fun sendNoisePayloadToPeer(payload: NoisePayload, recipientPeerID: String, label: String) {
        serviceScope.launch {
            try {
                val encrypted = encryptionService.encrypt(payload.encode(), recipientPeerID)
                val packet = ZemzemePacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS
                )

                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.d(TAG, "📤 Sent $label to $recipientPeerID (${payload.data.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send $label to $recipientPeerID: ${e.message}")
            }
        }
    }
    
    /**
     * Send broadcast announce with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendBroadcastAnnounce() {
        if (!bleTransportRunning) {
            Log.d(TAG, "Skipping broadcast announce: BLE transport is not running")
            return
        }
        Log.d(TAG, "Sending broadcast announce")
        serviceScope.launch {
            val nickname = try { com.roman.zemzeme.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { myPeerID }
            
            // Get the static public key for the announcement
            val staticKey = encryptionService.getStaticPublicKey()
            if (staticKey == null) {
                Log.e(TAG, "No static public key available for announcement")
                return@launch
            }
            
            // Get the signing public key for the announcement
            val signingKey = encryptionService.getSigningPublicKey()
            if (signingKey == null) {
                Log.e(TAG, "No signing public key available for announcement")
                return@launch
            }
            
            // Create iOS-compatible IdentityAnnouncement with TLV encoding
            val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
            var tlvPayload = announcement.encode()
            if (tlvPayload == null) {
                Log.e(TAG, "Failed to encode announcement as TLV")
                return@launch
            }

            // Append gossip TLV containing up to 10 direct neighbors (compact IDs)
            try {
                val directPeers = getDirectPeerIDsForGossip()
                if (directPeers.isNotEmpty()) {
                    val gossip = com.roman.zemzeme.services.meshgraph.GossipTLV.encodeNeighbors(directPeers)
                    tlvPayload = tlvPayload + gossip
                }
                // Always update our own node in the mesh graph with the neighbor list we used
                try {
                    com.roman.zemzeme.services.meshgraph.MeshGraphService.getInstance()
                        .updateFromAnnouncement(myPeerID, nickname, directPeers, System.currentTimeMillis().toULong())
                } catch (_: Exception) { }
            } catch (_: Exception) { }
            
            val announcePacket = ZemzemePacket(
                type = MessageType.ANNOUNCE.value,
                ttl = MAX_TTL,
                senderID = myPeerID,
                payload = tlvPayload
            )
            
            // Sign the packet using our signing key (exactly like iOS)
            val signedPacket = encryptionService.signData(announcePacket.toBinaryDataForSigning()!!)?.let { signature ->
                announcePacket.copy(signature = signature)
            } ?: announcePacket
            
            connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            Log.d(TAG, "Sent iOS-compatible signed TLV announce (${tlvPayload.size} bytes)")
            // Track announce for sync
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }
    
    /**
     * Send announcement to specific peer with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        
        val nickname = try { com.roman.zemzeme.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { myPeerID }
        
        // Get the static public key for the announcement
        val staticKey = encryptionService.getStaticPublicKey()
        if (staticKey == null) {
            Log.e(TAG, "No static public key available for peer announcement")
            return
        }
        
        // Get the signing public key for the announcement
        val signingKey = encryptionService.getSigningPublicKey()
        if (signingKey == null) {
            Log.e(TAG, "No signing public key available for peer announcement")
            return
        }
        
        // Create iOS-compatible IdentityAnnouncement with TLV encoding
        val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
        var tlvPayload = announcement.encode()
        if (tlvPayload == null) {
            Log.e(TAG, "Failed to encode peer announcement as TLV")
            return
        }

        // Append gossip TLV containing up to 10 direct neighbors (compact IDs)
        try {
            val directPeers = getDirectPeerIDsForGossip()
            if (directPeers.isNotEmpty()) {
                val gossip = com.roman.zemzeme.services.meshgraph.GossipTLV.encodeNeighbors(directPeers)
                tlvPayload = tlvPayload + gossip
            }
            // Always update our own node in the mesh graph with the neighbor list we used
            try {
                com.roman.zemzeme.services.meshgraph.MeshGraphService.getInstance()
                    .updateFromAnnouncement(myPeerID, nickname, directPeers, System.currentTimeMillis().toULong())
            } catch (_: Exception) { }
        } catch (_: Exception) { }
        
        val packet = ZemzemePacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = tlvPayload
        )
        
        // Sign the packet using our signing key (exactly like iOS)
        val signedPacket = encryptionService.signData(packet.toBinaryDataForSigning()!!)?.let { signature ->
            packet.copy(signature = signature)
        } ?: packet
        
        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
        peerManager.markPeerAsAnnouncedTo(peerID)
        Log.d(TAG, "Sent iOS-compatible signed TLV peer announce to $peerID (${tlvPayload.size} bytes)")

        // Track announce for sync
        try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
    }

    /**
     * Collect up to 10 direct neighbors for gossip TLV.
     */
    private fun getDirectPeerIDsForGossip(): List<String> {
        return try {
            // Prefer verified peers that are currently marked as direct
            val verified = peerManager.getVerifiedPeers()
            val direct = verified.filter { it.value.isDirectConnection }.keys.toList()
            direct.take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Send leave announcement
     */
    private fun sendLeaveAnnouncement() {
        val packet = ZemzemePacket(
            type = MessageType.LEAVE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = byteArrayOf()
        )
        
        // Sign the packet before broadcasting
        val signedPacket = signPacketBeforeBroadcast(packet)
        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
    }
    
    /**
     * Get peer nicknames
     */
    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()
    
    /**
     * Get peer RSSI values  
     */
    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()
    
    /**
     * Check if we have an established Noise session with a peer  
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): com.roman.zemzeme.noise.NoiseSession.NoiseSessionState {
        return encryptionService.getSessionState(peerID)
    }
    
    /**
     * Initiate Noise handshake with a specific peer (public API)
     */
    fun initiateNoiseHandshake(peerID: String) {
        // Delegate to the existing implementation in the MessageHandler delegate
        messageHandler.delegate?.initiateNoiseHandshake(peerID)
    }
    
    /**
     * Get peer fingerprint for identity management
     */
    fun getPeerFingerprint(peerID: String): String? {
        return peerManager.getFingerprintForPeer(peerID)
    }

    /**
     * Get current active peer count (for status/notifications)
     */
    fun getActivePeerCount(): Int {
        return try { peerManager.getActivePeerCount() } catch (_: Exception) { 0 }
    }

    /**
     * Get peer info for verification purposes
     */
    fun getPeerInfo(peerID: String): PeerInfo? {
        return peerManager.getPeerInfo(peerID)
    }

    /**
     * Update peer information with verification data
     */
    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return encryptionService.getIdentityFingerprint()
    }

    fun getStaticNoisePublicKey(): ByteArray? {
        return encryptionService.getStaticPublicKey()
    }
    
    /**
     * Check if encryption icon should be shown for a peer
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get all peers with established encrypted sessions
     */
    fun getEncryptedPeers(): List<String> {
        // SIMPLIFIED: Return empty list for now since we don't have direct access to sessionManager
        // This method is not critical for the session retention fix
        return emptyList()
    }
    
    /**
     * Get device address for a specific peer ID
     */
    fun getDeviceAddressForPeer(peerID: String): String? {
        return connectionManager.addressPeerMap.entries.find { it.value == peerID }?.key
    }
    
    /**
     * Get all device addresses mapped to their peer IDs
     */
    fun getDeviceAddressToPeerMapping(): Map<String, String> {
        return connectionManager.addressPeerMap.toMap()
    }
    
    /**
     * Print device addresses for all connected peers
     */
    fun printDeviceAddressesForPeers(): String {
        return peerManager.getDebugInfoWithDeviceAddresses(connectionManager.addressPeerMap)
    }

    /**
     * Get debug status information
     */
    fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Bluetooth Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine()
            appendLine(connectionManager.getDebugInfo())
            appendLine()
            appendLine(peerManager.getDebugInfo(connectionManager.addressPeerMap))
            appendLine()
            appendLine(peerManager.getFingerprintDebugInfo())
            appendLine()
            appendLine(fragmentManager.getDebugInfo())
            appendLine()
            appendLine(securityManager.getDebugInfo())
            appendLine()
            appendLine(storeForwardManager.getDebugInfo())
            appendLine()
            appendLine(messageHandler.getDebugInfo())
            appendLine()
            appendLine(packetProcessor.getDebugInfo())
        }
    }
    
    /**
     * Convert hex string peer ID to binary data (8 bytes) - exactly same as iOS
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
        var tempID = hexString
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        return result
    }
    
    /**
     * Sign packet before broadcasting using our signing private key
     */
    private fun signPacketBeforeBroadcast(packet: ZemzemePacket): ZemzemePacket {
        return try {
            // Optionally compute and attach a source route for addressed packets
            val withRoute = try {
                val rec = packet.recipientID
                if (rec != null && !rec.contentEquals(SpecialRecipients.BROADCAST)) {
                    val dest = rec.joinToString("") { b -> "%02x".format(b) }
                    val path = com.roman.zemzeme.services.meshgraph.RoutePlanner.shortestPath(myPeerID, dest)
                    if (path != null && path.size >= 3) {
                        // Exclude first (sender) and last (recipient); only intermediates
                        val intermediates = path.subList(1, path.size - 1)
                        val hopsBytes = intermediates.map { hexStringToByteArray(it) }
                        Log.d(TAG, "✅ Signed packet type ${packet.type} (route ${hopsBytes.size} hops: $intermediates)")
                        // Attach route and upgrade to v2 (required for HAS_ROUTE flag)
                        packet.copy(route = hopsBytes, version = 2u)
                    } else packet.copy(route = null)
                } else packet
            } catch (_: Exception) { packet }

            // Get the canonical packet data for signing (without signature)
            val packetDataForSigning = withRoute.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "Failed to encode packet type ${packet.type} for signing, sending unsigned")
                return withRoute
            }
            
            // Sign the packet data using our signing key
            val signature = encryptionService.signData(packetDataForSigning)
            if (signature != null) {
                Log.d(TAG, "✅ Signed packet type ${packet.type} (signature ${signature.size} bytes)")
                withRoute.copy(signature = signature)
            } else {
                Log.w(TAG, "Failed to sign packet type ${packet.type}, sending unsigned")
                withRoute
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error signing packet type ${packet.type}: ${e.message}, sending unsigned")
            packet
        }
    }
    
    // MARK: - Panic Mode Support
    
    /**
     * Clear all internal mesh service data (for panic mode)
     */
    fun clearAllInternalData() {
        Log.w(TAG, "🚨 Clearing all mesh service internal data")
        try {
            // Stop services to cease broadcasting old ID immediately
            stopServices()
            
            // Clear all managers
            fragmentManager.clearAllFragments()
            storeForwardManager.clearAllCache()
            securityManager.clearAllData()
            peerManager.clearAllPeers()
            peerManager.clearAllFingerprints()
            Log.d(TAG, "✅ Cleared all mesh service internal data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service internal data: ${e.message}")
        }
    }
    
    /**
     * Clear all encryption and cryptographic data (for panic mode)
     */
    fun clearAllEncryptionData() {
        Log.w(TAG, "🚨 Clearing all encryption data")
        try {
            // Clear encryption service persistent identity (includes Ed25519 signing keys)
            encryptionService.clearPersistentIdentity()
            Log.d(TAG, "✅ Cleared all encryption data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing encryption data: ${e.message}")
        }
    }
}

/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: ZemzemeMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long)
    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
}
