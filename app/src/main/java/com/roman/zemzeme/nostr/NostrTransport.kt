package com.roman.zemzeme.nostr

import android.content.Context
import android.util.Log
import com.roman.zemzeme.mesh.FragmentManager
import com.roman.zemzeme.model.ReadReceipt
import com.roman.zemzeme.model.NoisePayloadType
import com.roman.zemzeme.model.ZemzemeFilePacket
import com.roman.zemzeme.protocol.MessageType
import com.roman.zemzeme.protocol.ZemzemePacket
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Minimal Nostr transport for offline sending
 * Direct port from iOS NostrTransport for 100% compatibility
 */
class NostrTransport(
    private val context: Context,
    var senderPeerID: String = ""
) {
    
    companion object {
        private const val TAG = "NostrTransport"
        private const val READ_ACK_INTERVAL = com.roman.zemzeme.util.AppConstants.Nostr.READ_ACK_INTERVAL_MS // ~3 per second (0.35s interval like iOS)
        
        @Volatile
        private var INSTANCE: NostrTransport? = null
        
        fun getInstance(context: Context): NostrTransport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrTransport(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Throttle READ receipts to avoid relay rate limits (like iOS)
    private data class QueuedRead(
        val receipt: ReadReceipt,
        val peerID: String
    )
    
    private val readQueue = ConcurrentLinkedQueue<QueuedRead>()
    private var isSendingReadAcks = false
    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // MARK: - Transport Interface Methods
    
    val myPeerID: String get() = senderPeerID
    
    fun sendPrivateMessage(
        content: String,
        to: String,
        recipientNickname: String,
        messageID: String
    ) {
        transportScope.launch {
            sendPrivateMessageAwait(content, to, recipientNickname, messageID)
                .onFailure { e ->
                    Log.e(TAG, "Failed to send private message via Nostr: ${e.message}")
                }
        }
    }

    suspend fun sendPrivateMessageAwait(
        content: String,
        to: String,
        recipientNickname: String,
        messageID: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!NostrRelayManager.isEnabled) {
                throw IllegalStateException("Nostr transport is disabled")
            }

            val relayManager = NostrRelayManager.getInstance(context)
            if (!relayManager.isConnected.value) {
                throw IllegalStateException("No connected Nostr relays")
            }

            val recipientNostrPubkey = resolveNostrPublicKey(to)
                ?: throw IllegalStateException("No Nostr public key found for peerID: $to")

            val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                ?: throw IllegalStateException("No Nostr identity available")

            Log.d(
                TAG,
                "NostrTransport: preparing PM to ${recipientNostrPubkey.take(16)}... for peerID ${to.take(8)}... id=${messageID.take(8)}... nick=${recipientNickname.take(16)}"
            )

            val recipientHex = try {
                val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                if (hrp != "npub") {
                    throw IllegalStateException("Recipient key is not npub (hrp=$hrp)")
                }
                data.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                throw IllegalStateException("Failed to decode recipient npub", e)
            }

            val recipientPeerIDForEmbed = try {
                com.roman.zemzeme.favorites.FavoritesPersistenceService.shared
                    .findPeerIDForNostrPubkey(recipientNostrPubkey)
            } catch (_: Exception) { null }
            if (recipientPeerIDForEmbed.isNullOrBlank()) {
                throw IllegalStateException(
                    "No peerID stored for recipient npub: ${recipientNostrPubkey.take(16)}..."
                )
            }

            val embedded = NostrEmbeddedZemzeme.encodePMForNostr(
                content = content,
                messageID = messageID,
                recipientPeerID = recipientPeerIDForEmbed,
                senderPeerID = senderPeerID
            ) ?: throw IllegalStateException("Failed to embed PM packet")

            val giftWraps = NostrProtocol.createPrivateMessage(
                content = embedded,
                recipientPubkey = recipientHex,
                senderIdentity = senderIdentity
            )
            if (giftWraps.isEmpty()) {
                throw IllegalStateException("No Nostr events generated for private message")
            }

            giftWraps.forEach { event ->
                Log.i(TAG, "NostrTransport: sending PM giftWrap id=${event.id.take(16)}...")
                relayManager.sendEvent(event)
            }
        }
    }
    
    fun sendReadReceipt(receipt: ReadReceipt, to: String) {
        // Enqueue and process with throttling to avoid relay rate limits
        readQueue.offer(QueuedRead(receipt, to))
        processReadQueueIfNeeded()
    }
    
    private fun processReadQueueIfNeeded() {
        if (isSendingReadAcks) return
        if (readQueue.isEmpty()) return
        
        isSendingReadAcks = true
        sendNextReadAck()
    }
    
    private fun sendNextReadAck() {
        val item = readQueue.poll()
        if (item == null) {
            isSendingReadAcks = false
            return
        }
        
        transportScope.launch {
            try {
                var recipientNostrPubkey: String? = null
                
                // Try to resolve from favorites persistence service
                recipientNostrPubkey = resolveNostrPublicKey(item.peerID)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for read receipt to: ${item.peerID}")
                    scheduleNextReadAck()
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available for read receipt")
                    scheduleNextReadAck()
                    return@launch
                }
                
                Log.i(TAG, "NostrTransport: preparing READ ack for id=${item.receipt.originalMessageID.take(8)}... to ${recipientNostrPubkey.take(16)}...")
                
                // Convert recipient npub -> hex
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") {
                        scheduleNextReadAck()
                        return@launch
                    }
                    data.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    scheduleNextReadAck()
                    return@launch
                }
                
                val ack = NostrEmbeddedZemzeme.encodeAckForNostr(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = item.receipt.originalMessageID,
                    recipientPeerID = item.peerID,
                    senderPeerID = senderPeerID
                )
                
                if (ack == null) {
                    Log.e(TAG, "NostrTransport: failed to embed READ ack")
                    scheduleNextReadAck()
                    return@launch
                }
                
                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = ack,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )
                
                giftWraps.forEach { event ->
                    Log.i(TAG, "NostrTransport: sending READ ack giftWrap id=${event.id.take(16)}...")
                    NostrRelayManager.getInstance(context).sendEvent(event)
                }
                
                scheduleNextReadAck()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt via Nostr: ${e.message}")
                scheduleNextReadAck()
            }
        }
    }
    
    private fun scheduleNextReadAck() {
        transportScope.launch {
            delay(READ_ACK_INTERVAL)
            isSendingReadAcks = false
            processReadQueueIfNeeded()
        }
    }
    
    fun sendFavoriteNotification(to: String, isFavorite: Boolean) {
        transportScope.launch {
            sendFavoriteNotificationAwait(to, isFavorite)
                .onFailure { e ->
                    Log.e(TAG, "Failed to send favorite notification via Nostr: ${e.message}")
                }
        }
    }

    suspend fun sendFavoriteNotificationAwait(to: String, isFavorite: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!NostrRelayManager.isEnabled) {
                throw IllegalStateException("Nostr transport is disabled")
            }

            val relayManager = NostrRelayManager.getInstance(context)
            if (!relayManager.isConnected.value) {
                throw IllegalStateException("No connected Nostr relays")
            }

            val recipientNostrPubkey = resolveNostrPublicKey(to)
                ?: throw IllegalStateException("No Nostr public key found for favorite notification to: $to")

            val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                ?: throw IllegalStateException("No Nostr identity available for favorite notification")

            val content = if (isFavorite) {
                "[FAVORITED]:${senderIdentity.npub}"
            } else {
                "[UNFAVORITED]:${senderIdentity.npub}"
            }

            val recipientHex = try {
                val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                if (hrp != "npub") {
                    throw IllegalStateException("Recipient key is not npub (hrp=$hrp)")
                }
                data.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                throw IllegalStateException("Failed to decode recipient npub", e)
            }

            val embedded = NostrEmbeddedZemzeme.encodePMForNostr(
                content = content,
                messageID = UUID.randomUUID().toString(),
                recipientPeerID = to,
                senderPeerID = senderPeerID
            ) ?: throw IllegalStateException("Failed to embed favorite notification")

            val giftWraps = NostrProtocol.createPrivateMessage(
                content = embedded,
                recipientPubkey = recipientHex,
                senderIdentity = senderIdentity
            )
            if (giftWraps.isEmpty()) {
                throw IllegalStateException("No Nostr events generated for favorite notification")
            }

            giftWraps.forEach { event ->
                Log.i(TAG, "NostrTransport: sending favorite giftWrap id=${event.id.take(16)}...")
                relayManager.sendEvent(event)
            }
        }
    }
    
    // ── Media sending ────────────────────────────────────────────────────────

    /**
     * Send a [ZemzemeFilePacket] to a Nostr DM recipient.
     *
     * Files smaller than [NostrEmbeddedZemzeme.NOSTR_INLINE_FILE_THRESHOLD] bytes are wrapped
     * in a single Gift Wrap. Larger files are split into FRAGMENT packets via
     * [FragmentManager], each sent as its own Gift Wrap.
     */
    fun sendMediaMessage(
        filePacket: ZemzemeFilePacket,
        to: String,
        recipientNickname: String,
        messageID: String
    ) {
        transportScope.launch {
            sendMediaMessageAwait(filePacket, to, recipientNickname, messageID)
                .onFailure { e ->
                    Log.e(TAG, "Failed to send media via Nostr: ${e.message}")
                }
        }
    }

    suspend fun sendMediaMessageAwait(
        filePacket: ZemzemeFilePacket,
        to: String,
        recipientNickname: String,
        messageID: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!NostrRelayManager.isEnabled) {
                throw IllegalStateException("Nostr transport is disabled")
            }

            val relayManager = NostrRelayManager.getInstance(context)
            if (!relayManager.isConnected.value) {
                throw IllegalStateException("No connected Nostr relays")
            }

            val recipientNostrPubkey = resolveNostrPublicKey(to)
                ?: throw IllegalStateException("No Nostr public key found for peerID: $to")

            val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                ?: throw IllegalStateException("No Nostr identity available")

            val recipientHex = run {
                val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                if (hrp != "npub") throw IllegalStateException("Recipient key is not npub (hrp=$hrp)")
                data.joinToString("") { "%02x".format(it) }
            }

            val recipientPeerIDForEmbed =
                com.roman.zemzeme.favorites.FavoritesPersistenceService.shared
                    .findPeerIDForNostrPubkey(recipientNostrPubkey)
                    ?: throw IllegalStateException(
                        "No peerID stored for recipient npub: ${recipientNostrPubkey.take(16)}..."
                    )

            val fileBytes = filePacket.encode()
                ?: throw IllegalStateException("Failed to encode ZemzemeFilePacket")

            Log.d(TAG, "NostrTransport: sending media ${filePacket.fileName} (${fileBytes.size} bytes) to ${recipientHex.take(8)}...")

            if (fileBytes.size < NostrEmbeddedZemzeme.NOSTR_INLINE_FILE_THRESHOLD) {
                // ── Single Gift Wrap ─────────────────────────────────────────
                val embedded = NostrEmbeddedZemzeme.encodeFileTransferForNostr(
                    fileBytes, messageID, recipientPeerIDForEmbed, senderPeerID
                ) ?: throw IllegalStateException("Failed to embed file transfer packet")

                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )
                giftWraps.forEach { event ->
                    Log.i(TAG, "NostrTransport: sending media giftWrap id=${event.id.take(16)}...")
                    relayManager.sendEvent(event)
                }
            } else {
                // ── Fragmented: one Gift Wrap per FRAGMENT packet ────────────
                val payload = ByteArray(1 + fileBytes.size)
                payload[0] = NoisePayloadType.FILE_TRANSFER.value.toByte()
                System.arraycopy(fileBytes, 0, payload, 1, fileBytes.size)

                // Use the 4-param convenience constructor so the private hexStringToByteArray
                // inside ZemzemePacket handles the 8-byte senderID encoding correctly.
                val fullPacket = ZemzemePacket(
                    type = MessageType.NOISE_ENCRYPTED.value,
                    ttl = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS,
                    senderID = senderPeerID,
                    payload = payload
                )

                val fragments = FragmentManager().createFragments(fullPacket)
                Log.d(TAG, "NostrTransport: fragmented into ${fragments.size} FRAGMENT packets")

                for (fragment in fragments) {
                    val embedded = NostrEmbeddedZemzeme.encodePacketForNostr(fragment)
                        ?: continue
                    val giftWraps = NostrProtocol.createPrivateMessage(
                        content = embedded,
                        recipientPubkey = recipientHex,
                        senderIdentity = senderIdentity
                    )
                    giftWraps.forEach { event ->
                        Log.i(TAG, "NostrTransport: sending fragment giftWrap id=${event.id.take(16)}...")
                        relayManager.sendEvent(event)
                    }
                }
            }
        }
    }

    /**
     * Geohash-identity variant of [sendMediaMessage].
     * Uses the per-geohash derived identity as sender instead of the main account identity.
     */
    fun sendMediaMessageGeohash(
        filePacket: ZemzemeFilePacket,
        toRecipientHex: String,
        messageID: String,
        sourceGeohash: String? = null
    ) {
        val geohash = sourceGeohash ?: run {
            val selected = try {
                com.roman.zemzeme.geohash.LocationChannelManager.getInstance(context).selectedChannel.value
            } catch (_: Exception) { null }
            if (selected !is com.roman.zemzeme.geohash.ChannelID.Location) {
                Log.w(TAG, "NostrTransport: cannot send geohash media - not in a location channel")
                return
            }
            selected.channel.geohash
        }

        val fromIdentity = try {
            NostrIdentityBridge.deriveIdentity(geohash, context)
        } catch (e: Exception) {
            Log.e(TAG, "NostrTransport: cannot derive geohash identity for $geohash: ${e.message}")
            return
        }

        transportScope.launch {
            try {
                val relayManager = NostrRelayManager.getInstance(context)
                if (!relayManager.isConnected.value) return@launch

                val fileBytes = filePacket.encode() ?: run {
                    Log.e(TAG, "NostrTransport: failed to encode file for geohash media send")
                    return@launch
                }

                Log.d(TAG, "GeoDM: send media ${filePacket.fileName} (${fileBytes.size} bytes) to ${toRecipientHex.take(8)}...")

                if (fileBytes.size < NostrEmbeddedZemzeme.NOSTR_INLINE_FILE_THRESHOLD) {
                    val embedded = NostrEmbeddedZemzeme.encodeFileTransferForNostrNoRecipient(
                        fileBytes, messageID, senderPeerID
                    ) ?: return@launch

                    val giftWraps = NostrProtocol.createPrivateMessage(
                        content = embedded,
                        recipientPubkey = toRecipientHex,
                        senderIdentity = fromIdentity
                    )
                    giftWraps.forEach { event ->
                        NostrRelayManager.registerPendingGiftWrap(event.id)
                        relayManager.sendEvent(event)
                    }
                } else {
                    val payload = ByteArray(1 + fileBytes.size)
                    payload[0] = NoisePayloadType.FILE_TRANSFER.value.toByte()
                    System.arraycopy(fileBytes, 0, payload, 1, fileBytes.size)

                    val fullPacket = ZemzemePacket(
                        type = MessageType.NOISE_ENCRYPTED.value,
                        ttl = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS,
                        senderID = senderPeerID,
                        payload = payload
                    )

                    val fragments = FragmentManager().createFragments(fullPacket)
                    for (fragment in fragments) {
                        val embedded = NostrEmbeddedZemzeme.encodePacketForNostr(fragment) ?: continue
                        val giftWraps = NostrProtocol.createPrivateMessage(
                            content = embedded,
                            recipientPubkey = toRecipientHex,
                            senderIdentity = fromIdentity
                        )
                        giftWraps.forEach { event ->
                            NostrRelayManager.registerPendingGiftWrap(event.id)
                            relayManager.sendEvent(event)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash media message: ${e.message}")
            }
        }
    }

    // ── End media sending ────────────────────────────────────────────────────

    fun sendDeliveryAck(messageID: String, to: String) {
        transportScope.launch {
            sendDeliveryAckAwait(messageID, to)
                .onFailure { e ->
                    Log.e(TAG, "Failed to send delivery ack via Nostr: ${e.message}")
                }
        }
    }

    suspend fun sendDeliveryAckAwait(messageID: String, to: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!NostrRelayManager.isEnabled) {
                throw IllegalStateException("Nostr transport is disabled")
            }

            val relayManager = NostrRelayManager.getInstance(context)
            if (!relayManager.isConnected.value) {
                throw IllegalStateException("No connected Nostr relays")
            }

            val recipientNostrPubkey = resolveNostrPublicKey(to)
                ?: throw IllegalStateException("No Nostr public key found for delivery ack to: $to")

            val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                ?: throw IllegalStateException("No Nostr identity available for delivery ack")

            Log.i(TAG, "NostrTransport: preparing DELIVERED ack for id=${messageID.take(8)}... to ${recipientNostrPubkey.take(16)}...")

            val recipientHex = try {
                val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                if (hrp != "npub") {
                    throw IllegalStateException("Recipient key is not npub (hrp=$hrp)")
                }
                data.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                throw IllegalStateException("Failed to decode recipient npub", e)
            }

            val ack = NostrEmbeddedZemzeme.encodeAckForNostr(
                type = NoisePayloadType.DELIVERED,
                messageID = messageID,
                recipientPeerID = to,
                senderPeerID = senderPeerID
            ) ?: throw IllegalStateException("Failed to embed DELIVERED ack")

            val giftWraps = NostrProtocol.createPrivateMessage(
                content = ack,
                recipientPubkey = recipientHex,
                senderIdentity = senderIdentity
            )
            if (giftWraps.isEmpty()) {
                throw IllegalStateException("No Nostr events generated for delivery ack")
            }

            giftWraps.forEach { event ->
                Log.i(TAG, "NostrTransport: sending DELIVERED ack giftWrap id=${event.id.take(16)}...")
                relayManager.sendEvent(event)
            }
        }
    }
    
    // MARK: - Geohash ACK helpers (for per-geohash identity DMs)
    
    fun sendDeliveryAckGeohash(
        messageID: String,
        toRecipientHex: String,
        fromIdentity: NostrIdentity
    ) {
        transportScope.launch {
            try {
                Log.i(TAG, "GeoDM: send DELIVERED -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}...")
                
                val embedded = NostrEmbeddedZemzeme.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.DELIVERED,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) return@launch
                
                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )
                
                // Register pending gift wrap for deduplication and send all
                giftWraps.forEach { event ->
                    NostrRelayManager.registerPendingGiftWrap(event.id)
                    NostrRelayManager.getInstance(context).sendEvent(event)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash delivery ack: ${e.message}")
            }
        }
    }
    
    fun sendReadReceiptGeohash(
        messageID: String,
        toRecipientHex: String,
        fromIdentity: NostrIdentity
    ) {
        transportScope.launch {
            try {
                Log.i(TAG, "GeoDM: send READ -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}...")
                
                val embedded = NostrEmbeddedZemzeme.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) return@launch
                
                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )
                
                // Register pending gift wrap for deduplication and send all
                giftWraps.forEach { event ->
                    NostrRelayManager.registerPendingGiftWrap(event.id)
                    NostrRelayManager.getInstance(context).sendEvent(event)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash read receipt: ${e.message}")
            }
        }
    }
    
    // MARK: - Geohash DMs (per-geohash identity)
    
    fun sendPrivateMessageGeohash(
        content: String,
        toRecipientHex: String,
        messageID: String,
        sourceGeohash: String? = null
    ) {
        // Use provided geohash or derive from current location
        val geohash = sourceGeohash ?: run {
            val selected = try {
                com.roman.zemzeme.geohash.LocationChannelManager.getInstance(context).selectedChannel.value
            } catch (_: Exception) { null }
            if (selected !is com.roman.zemzeme.geohash.ChannelID.Location) {
                Log.w(TAG, "NostrTransport: cannot send geohash PM - not in a location channel and no geohash provided")
                return
            }
            selected.channel.geohash
        }
        
        val fromIdentity = try {
            NostrIdentityBridge.deriveIdentity(geohash, context)
        } catch (e: Exception) {
            Log.e(TAG, "NostrTransport: cannot derive geohash identity for $geohash: ${e.message}")
            return
        }
        
        transportScope.launch {
            try {
                if (toRecipientHex.isEmpty()) return@launch

                Log.d(
                    TAG,
                    "GeoDM: send PM -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}... geohash=$geohash"
                )

                // Build embedded Zemzeme packet without recipient peer ID
                val embedded = NostrEmbeddedZemzeme.encodePMForNostrNoRecipient(
                    content = content,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                ) ?: run {
                    Log.e(TAG, "NostrTransport: failed to embed geohash PM packet")
                    return@launch
                }

                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )

                giftWraps.forEach { event ->
                    Log.i(TAG, "NostrTransport: sending geohash PM giftWrap id=${event.id.take(16)}...")
                    NostrRelayManager.registerPendingGiftWrap(event.id)
                    NostrRelayManager.getInstance(context).sendEvent(event)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash private message: ${e.message}")
            }
        }
    }
    
    // MARK: - Helper Methods
    
    /**
     * Resolve Nostr public key for a peer ID
     */
    private fun resolveNostrPublicKey(peerID: String): String? {
        try {
            // 1) Fast path: direct peerID→npub mapping (mutual favorites after mesh mapping)
            com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.findNostrPubkeyForPeerID(peerID)?.let { return it }

            // 2) Legacy path: resolve by noise public key association
            val noiseKey = hexStringToByteArray(peerID)
            val favoriteStatus = com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
            if (favoriteStatus?.peerNostrPublicKey != null) return favoriteStatus.peerNostrPublicKey

            // 3) Prefix match on noiseHex from 16-hex peerID
            if (peerID.length == 16) {
                val fallbackStatus = com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                return fallbackStatus?.peerNostrPublicKey
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Nostr public key for $peerID: ${e.message}")
            return null
        }
    }
    
    /**
     * Convert full hex string to byte array
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val clean = if (hexString.length % 2 == 0) hexString else "0$hexString"
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    fun cleanup() {
        transportScope.cancel()
    }
}
