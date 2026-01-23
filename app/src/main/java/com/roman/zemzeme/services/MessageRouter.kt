package com.roman.zemzeme.services

import android.content.Context
import android.util.Log
import com.roman.zemzeme.mesh.BluetoothMeshService
import com.roman.zemzeme.model.ReadReceipt
import com.roman.zemzeme.nostr.NostrTransport
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.p2p.P2PTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Routes messages between BLE mesh and Nostr transports, matching iOS behavior.
 */
class MessageRouter private constructor(
    private val context: Context,
    private var mesh: BluetoothMeshService,
    private val nostr: NostrTransport
) {
    companion object {
        private const val TAG = "MessageRouter"
        private const val BLE_HANDSHAKE_FALLBACK_TIMEOUT_MS = 3500L
        @Volatile private var INSTANCE: MessageRouter? = null
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: BluetoothMeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    MessageRouter(context.applicationContext, mesh, nostr).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            return instance
        }
    }

    private data class QueuedMessage(
        val content: String,
        val recipientNickname: String,
        val messageID: String,
        val queuedAtMs: Long = System.currentTimeMillis()
    )

    // Outbox: peerID -> queued messages
    private val outbox = mutableMapOf<String, MutableList<QueuedMessage>>()
    private val outboxLock = Any()
    private val flushInProgressPeers = mutableSetOf<String>()
    private val pendingBleFallbackJobs = mutableMapOf<String, Job>()

    private val transportConfig by lazy { P2PConfig(context.applicationContext) }
    private val p2pTransport by lazy { P2PTransport.getInstance(context.applicationContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Listener for favorites changes to flush outbox when npub mapping appears/changes
    private val favoriteListener = object: com.bitchat.android.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            // Also try 16-hex short id commonly used in UI if any client used that
            val shortId = noiseKeyHex.take(16)
            flushOutboxFor(shortId)
        }
        override fun onAllCleared() {
            // Nothing special; leave queued items until routing becomes possible
        }
    }

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        val toggles = currentTransportToggles()

        // First: if this is a P2P peer (starts with p2p:), route via P2PTransport
        if (toPeerID.startsWith("p2p:")) {
            if (!toggles.p2pEnabled) {
                Log.d(TAG, "P2P disabled; dropping explicit P2P DM target ${toPeerID.take(16)}...")
                return
            }
            val rawPeerId = toPeerID.removePrefix("p2p:")
            Log.d(TAG, "Routing PM via P2P direct to ${rawPeerId.take(12)}... id=${messageID.take(8)}...")
            scope.launch {
                try {
                    val success = p2pTransport.sendDirectMessage(rawPeerId, content, recipientNickname, messageID)
                    if (!success) {
                        Log.w(TAG, "P2P direct message send failed for ${rawPeerId.take(12)}...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "P2P direct message error: ${e.message}")
                }
            }
            return
        }
        
        // Second: if this is a geohash DM alias (nostr_<pub16>), route via Nostr using global registry
        if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            if (!toggles.nostrEnabled) {
                Log.d(TAG, "Nostr disabled; cannot route geohash alias DM ${toPeerID.take(16)}...")
                return
            }
            Log.d(TAG, "Routing PM via Nostr (geohash) to alias ${toPeerID.take(12)}... id=${messageID.take(8)}...")
            val recipientHex = com.bitchat.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                // Resolve the conversation's source geohash, so we can send from anywhere
                val sourceGeohash = com.bitchat.android.nostr.GeohashConversationRegistry.get(toPeerID)

                // If repository knows the source geohash, pass it so NostrTransport derives the correct identity
                nostr.sendPrivateMessageGeohash(content, recipientHex, messageID, sourceGeohash)
                return
            }
        }

        val hasBleLink = toggles.bleEnabled && mesh.getPeerInfo(toPeerID)?.isConnected == true
        val hasBleSession = hasBleLink && mesh.hasEstablishedSession(toPeerID)
        if (hasBleSession) {
            Log.d(TAG, "Routing PM via BLE mesh to ${toPeerID.take(12)}... msg_id=${messageID.take(8)}...")
            mesh.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
            return
        }

        // BLE is preferred. If peer is connected but Noise isn't established, queue + handshake
        // and allow a bounded fallback timer to attempt P2P/Nostr later.
        if (hasBleLink) {
            Log.d(TAG, "Queueing PM for BLE handshake ${toPeerID.take(8)}... msg_id=${messageID.take(8)}...")
            queueMessage(toPeerID, content, recipientNickname, messageID)
            mesh.initiateNoiseHandshake(toPeerID)
            scheduleBleHandshakeFallback(toPeerID, messageID)
            return
        }

        // Next fallback: mapped P2P identity if available and enabled.
        if (toggles.p2pEnabled && p2pTransport.isRunning()) {
            val mappedP2PPeer = p2pTransport.getP2PPeerID(toPeerID)
            if (!mappedP2PPeer.isNullOrBlank()) {
                Log.d(TAG, "Routing PM via mapped P2P to ${mappedP2PPeer.take(12)}... msg_id=${messageID.take(8)}...")
                scope.launch {
                    val p2pSent = runCatching {
                        p2pTransport.sendDirectMessage(mappedP2PPeer, content, recipientNickname, messageID)
                    }.getOrElse { e ->
                        Log.e(TAG, "Mapped P2P send error: ${e.message}")
                        false
                    }

                    if (p2pSent) {
                        return@launch
                    }

                    val latestToggles = currentTransportToggles()
                    if (latestToggles.nostrEnabled && canSendViaNostr(toPeerID)) {
                        Log.d(TAG, "Mapped P2P failed; attempting Nostr for ${toPeerID.take(12)}...")
                        val nostrSent = nostr.sendPrivateMessageAwait(content, toPeerID, recipientNickname, messageID).isSuccess
                        if (nostrSent) {
                            return@launch
                        }
                    }

                    queueMessage(toPeerID, content, recipientNickname, messageID)
                    if (canAttemptMeshHandshake(toPeerID)) {
                        mesh.initiateNoiseHandshake(toPeerID)
                    }
                }
                return
            }
        }

        if (toggles.nostrEnabled && canSendViaNostr(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr to ${toPeerID.take(32)}... msg_id=${messageID.take(8)}...")
            scope.launch {
                val sent = nostr.sendPrivateMessageAwait(content, toPeerID, recipientNickname, messageID).isSuccess
                if (!sent) {
                    queueMessage(toPeerID, content, recipientNickname, messageID)
                    if (canAttemptMeshHandshake(toPeerID)) {
                        mesh.initiateNoiseHandshake(toPeerID)
                    }
                }
            }
        } else {
            Log.d(TAG, "Queued PM for ${toPeerID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            queueMessage(toPeerID, content, recipientNickname, messageID)
            if (canAttemptMeshHandshake(toPeerID)) {
                Log.d(TAG, "Initiating noise handshake after queueing PM for ${toPeerID.take(8)}…")
                mesh.initiateNoiseHandshake(toPeerID)
            }
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        val toggles = currentTransportToggles()
        if (toggles.bleEnabled && (mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID)) {
            Log.d(TAG, "Routing READ via mesh to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, toPeerID, mesh.getPeerNicknames()[toPeerID] ?: mesh.myPeerID)
        } else if (toggles.nostrEnabled) {
            Log.d(TAG, "Routing READ via Nostr to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            nostr.sendReadReceipt(receipt, toPeerID)
        } else {
            Log.d(TAG, "Dropping READ receipt for ${toPeerID.take(8)}… (no enabled transport)")
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        val toggles = currentTransportToggles()
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a geohash alias
        if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            if (!toggles.nostrEnabled) {
                Log.d(TAG, "Nostr disabled; dropping geohash DELIVERED ack for ${toPeerID.take(12)}…")
                return
            }
            val recipientHex = com.bitchat.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, try { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        if (toggles.nostrEnabled && !((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID))) {
            nostr.sendDeliveryAck(messageID, toPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        val toggles = currentTransportToggles()
        if (toggles.bleEnabled && mesh.getPeerInfo(toPeerID)?.isConnected == true) {
            val myNpub = try { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub } catch (_: Exception) { null }
            val content = if (isFavorite) "[FAVORITED]:${myNpub ?: ""}" else "[UNFAVORITED]:${myNpub ?: ""}"
            val nickname = mesh.getPeerNicknames()[toPeerID] ?: toPeerID
            mesh.sendPrivateMessage(content, toPeerID, nickname)
        } else if (toggles.nostrEnabled) {
            nostr.sendFavoriteNotification(toPeerID, isFavorite)
        } else {
            Log.d(TAG, "Dropping favorite notification for ${toPeerID.take(12)}… (no enabled transport)")
        }
    }

    // Flush any queued messages for a specific peerID
    fun flushOutboxFor(peerID: String) {
        scope.launch {
            flushOutboxForInternal(peerID)
        }
    }

    private suspend fun flushOutboxForInternal(peerID: String) {
        val canFlush = synchronized(outboxLock) {
            if (flushInProgressPeers.contains(peerID)) {
                false
            } else {
                flushInProgressPeers.add(peerID)
                true
            }
        }
        if (!canFlush) {
            return
        }

        try {
            val queued = synchronized(outboxLock) { outbox[peerID]?.toList() ?: emptyList() }
            if (queued.isEmpty()) {
                return
            }

            val toggles = currentTransportToggles()
            Log.d(TAG, "Flushing outbox for ${peerID.take(8)}... count=${queued.size}")

            for (queuedMessage in queued) {
                val meshTarget = resolveEstablishedMeshTarget(peerID, toggles)
                if (meshTarget != null) {
                    mesh.sendPrivateMessage(
                        queuedMessage.content,
                        meshTarget,
                        queuedMessage.recipientNickname,
                        queuedMessage.messageID
                    )
                    markMessageSent(peerID, queuedMessage.messageID)
                    continue
                }

                val mappedP2PPeer = if (toggles.p2pEnabled && p2pTransport.isRunning()) {
                    p2pTransport.getP2PPeerID(peerID)
                } else {
                    null
                }

                if (!mappedP2PPeer.isNullOrBlank()) {
                    val p2pSent = runCatching {
                        p2pTransport.sendDirectMessage(
                            mappedP2PPeer,
                            queuedMessage.content,
                            queuedMessage.recipientNickname,
                            queuedMessage.messageID
                        )
                    }.getOrElse { e ->
                        Log.e(TAG, "Outbox P2P send error for ${peerID.take(12)}...: ${e.message}")
                        false
                    }

                    if (p2pSent) {
                        markMessageSent(peerID, queuedMessage.messageID)
                        continue
                    }
                }

                if (toggles.nostrEnabled && canSendViaNostr(peerID)) {
                    val nostrSent = nostr.sendPrivateMessageAwait(
                        queuedMessage.content,
                        peerID,
                        queuedMessage.recipientNickname,
                        queuedMessage.messageID
                    ).isSuccess
                    if (nostrSent) {
                        markMessageSent(peerID, queuedMessage.messageID)
                    }
                }
            }
        } finally {
            synchronized(outboxLock) {
                flushInProgressPeers.remove(peerID)
            }
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        val keys = synchronized(outboxLock) { outbox.keys.toList() }
        keys.forEach { flushOutboxFor(it) }
    }

    private fun currentTransportToggles(): P2PConfig.TransportToggles {
        // Ensures flow is initialized from SharedPreferences even if this is the first caller.
        transportConfig.getTransportToggles()
        val toggles = P2PConfig.getCurrentTransportToggles()
        return if (toggles.p2pEnabled && toggles.nostrEnabled) {
            // Defensive normalization. Config should already prevent this.
            toggles.copy(nostrEnabled = false)
        } else {
            toggles
        }
    }

    private fun fallbackJobKey(peerID: String, messageID: String): String = "$peerID|$messageID"

    private fun scheduleBleHandshakeFallback(peerID: String, messageID: String) {
        val key = fallbackJobKey(peerID, messageID)
        synchronized(outboxLock) {
            pendingBleFallbackJobs.remove(key)?.cancel()
            pendingBleFallbackJobs[key] = scope.launch {
                delay(BLE_HANDSHAKE_FALLBACK_TIMEOUT_MS)
                try {
                    attemptFallbackForQueuedMessage(peerID, messageID)
                } finally {
                    synchronized(outboxLock) {
                        pendingBleFallbackJobs.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun attemptFallbackForQueuedMessage(peerID: String, messageID: String) {
        val queued = synchronized(outboxLock) {
            outbox[peerID]?.firstOrNull { it.messageID == messageID }
        } ?: return

        val toggles = currentTransportToggles()

        val meshTarget = resolveEstablishedMeshTarget(peerID, toggles)
        if (meshTarget != null) {
            mesh.sendPrivateMessage(queued.content, meshTarget, queued.recipientNickname, queued.messageID)
            markMessageSent(peerID, messageID)
            return
        }

        if (toggles.p2pEnabled && p2pTransport.isRunning()) {
            val mappedP2PPeer = p2pTransport.getP2PPeerID(peerID)
            if (!mappedP2PPeer.isNullOrBlank()) {
                val p2pSent = runCatching {
                    p2pTransport.sendDirectMessage(mappedP2PPeer, queued.content, queued.recipientNickname, queued.messageID)
                }.getOrElse { e ->
                    Log.e(TAG, "BLE fallback P2P send error for ${peerID.take(12)}...: ${e.message}")
                    false
                }

                if (p2pSent) {
                    markMessageSent(peerID, messageID)
                    return
                }
            }
        }

        if (toggles.nostrEnabled && canSendViaNostr(peerID)) {
            val nostrSent = nostr.sendPrivateMessageAwait(
                queued.content,
                peerID,
                queued.recipientNickname,
                queued.messageID
            ).isSuccess
            if (nostrSent) {
                markMessageSent(peerID, messageID)
            }
        }
    }

    private fun resolveEstablishedMeshTarget(peerID: String, toggles: P2PConfig.TransportToggles): String? {
        if (!toggles.bleEnabled) {
            return null
        }

        if (mesh.getPeerInfo(peerID)?.isConnected == true && mesh.hasEstablishedSession(peerID)) {
            return peerID
        }

        if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
            val meshPeer = resolveMeshPeerForNoiseHex(peerID)
            if (meshPeer != null && mesh.getPeerInfo(meshPeer)?.isConnected == true && mesh.hasEstablishedSession(meshPeer)) {
                return meshPeer
            }
        }

        return null
    }

    private fun markMessageSent(peerID: String, messageID: String) {
        synchronized(outboxLock) {
            val current = outbox[peerID] ?: return
            current.removeAll { it.messageID == messageID }
            if (current.isEmpty()) {
                outbox.remove(peerID)
            }

            val key = fallbackJobKey(peerID, messageID)
            pendingBleFallbackJobs.remove(key)?.cancel()
        }
    }

    private fun canSendViaNostr(peerID: String): Boolean {
        if (!currentTransportToggles().nostrEnabled) {
            return false
        }
        return try {
            // Full Noise key hex
            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val noiseKey = hexToBytes(peerID)
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                // Ephemeral 16-hex mesh ID: resolve via prefix match in favorites
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun canAttemptMeshHandshake(peerID: String): Boolean {
        return !(peerID.startsWith("p2p:") || peerID.startsWith("nostr_"))
    }

    private fun queueMessage(peerID: String, content: String, recipientNickname: String, messageID: String) {
        synchronized(outboxLock) {
            val q = outbox.getOrPut(peerID) { mutableListOf() }
            val alreadyQueued = q.any { it.messageID == messageID }
            if (!alreadyQueued) {
                q.add(
                    QueuedMessage(
                        content = content,
                        recipientNickname = recipientNickname,
                        messageID = messageID
                    )
                )
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun resolveMeshPeerForNoiseHex(noiseHex: String): String? {
        return try {
            mesh.getPeerNicknames().keys.firstOrNull { pid ->
                val info = mesh.getPeerInfo(pid)
                val keyHex = info?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
                keyHex != null && keyHex.equals(noiseHex, ignoreCase = true)
            }
        } catch (_: Exception) { null }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
            noiseHex?.let { flushOutboxFor(it) }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
        noiseHex?.let { flushOutboxFor(it) }
    }
}
