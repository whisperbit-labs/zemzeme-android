package com.roman.zemzeme.nostr

import android.app.Application
import android.util.Log
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.ui.ChatState
import com.roman.zemzeme.ui.MessageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * GeohashMessageHandler
 * - Processes kind=20000 Nostr events for geohash channels
 * - Updates repository for participants + nicknames
 * - Emits messages to MessageManager
 */
class GeohashMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val repo: GeohashRepository,
    private val scope: CoroutineScope,
    private val dataManager: com.roman.zemzeme.ui.DataManager
) {
    companion object {
        private const val TAG = "GeohashMessageHandler"

        private fun decodeBase64Url(input: String): ByteArray? = try {
            val padded = input.replace("-", "+").replace("_", "/")
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (_: Exception) { null }
    }

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    @Synchronized
    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    fun onEvent(event: NostrEvent, subscribedGeohash: String) {
        scope.launch(Dispatchers.Default) {
            try {
                if (event.kind != NostrKind.EPHEMERAL_EVENT && event.kind != NostrKind.GEOHASH_PRESENCE) return@launch
                val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
                if (tagGeo == null || !tagGeo.equals(subscribedGeohash, true)) return@launch
                if (dedupe(event.id)) return@launch

                // PoW validation (if enabled) - apply to chat messages primarily
                if (event.kind == NostrKind.EPHEMERAL_EVENT) {
                    val pow = PoWPreferenceManager.getCurrentSettings()
                    if (pow.enabled && pow.difficulty > 0) {
                        if (!NostrProofOfWork.validateDifficulty(event, pow.difficulty)) return@launch
                    }
                }

                // Blocked users check (use injected DataManager which has loaded state)
                if (dataManager.isGeohashUserBlocked(event.pubkey)) return@launch

                // Ignore our own geohash events entirely so local identity never appears in people lists.
                val isOwnEvent = try {
                    NostrIdentityBridge.deriveIdentity(subscribedGeohash, application)
                        .publicKeyHex
                        .equals(event.pubkey, ignoreCase = true)
                } catch (_: Exception) {
                    false
                }
                if (isOwnEvent) return@launch
                
                // Update repository (participants, nickname, teleport)
                // Update repository on a background-safe path; repository will post updates to LiveData
                
                // Update participant count (last seen) on BOTH Presence (20001) and Chat (20000) events
                if (event.kind == NostrKind.GEOHASH_PRESENCE || event.kind == NostrKind.EPHEMERAL_EVENT) {
                    repo.updateParticipant(subscribedGeohash, event.pubkey, Date(event.createdAt * 1000L))
                }
                
                event.tags.find { it.size >= 2 && it[0] == "n" }?.let { repo.cacheNickname(event.pubkey, it[1]) }
                event.tags.find { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }?.let { repo.markTeleported(event.pubkey) }
                // Register a geohash DM alias for this participant so MessageRouter can route DMs via Nostr
                try {
                    com.roman.zemzeme.nostr.GeohashAliasRegistry.put("nostr_${event.pubkey.take(16)}", event.pubkey)
                } catch (_: Exception) { }

                // Stop here for presence events - they don't produce chat messages
                if (event.kind == NostrKind.GEOHASH_PRESENCE) return@launch

                val isTeleportPresence = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" } &&
                                         event.content.trim().isEmpty()
                if (isTeleportPresence) return@launch

                val senderName = repo.displayNameForNostrPubkeyUI(event.pubkey)

                // Media transfer embedded as bitchat1:<base64url> in event content
                if (event.content.startsWith("bitchat1:")) {
                    val b64 = event.content.removePrefix("bitchat1:")
                    val fileBytes = decodeBase64Url(b64)
                    if (fileBytes != null) {
                        val filePacket = com.roman.zemzeme.model.ZemzemeFilePacket.decode(fileBytes)
                        if (filePacket != null) {
                            val savedPath = com.roman.zemzeme.features.file.FileUtils.saveIncomingFile(application, filePacket)
                            val msgType = com.roman.zemzeme.features.file.FileUtils.messageTypeForMime(filePacket.mimeType)
                            val mediaMsg = ZemzemeMessage(
                                id = event.id,
                                sender = senderName,
                                content = savedPath,
                                type = msgType,
                                timestamp = Date(event.createdAt * 1000L),
                                isRelay = false,
                                originalSender = repo.displayNameForNostrPubkey(event.pubkey),
                                senderPeerID = "nostr:${event.pubkey.take(8)}",
                                channel = "#$subscribedGeohash"
                            )
                            withContext(Dispatchers.Main) { messageManager.addChannelMessage("geo:$subscribedGeohash", mediaMsg) }
                            return@launch
                        }
                    }
                    Log.w(TAG, "Failed to decode geohash media event ${event.id.take(8)}")
                    return@launch
                }

                val hasNonce = try { NostrProofOfWork.hasNonce(event) } catch (_: Exception) { false }
                val msg = ZemzemeMessage(
                    id = event.id,
                    sender = senderName,
                    content = event.content,
                    timestamp = Date(event.createdAt * 1000L),
                    isRelay = false,
                    originalSender = repo.displayNameForNostrPubkey(event.pubkey),
                    senderPeerID = "nostr:${event.pubkey.take(8)}",
                    mentions = null,
                    channel = "#$subscribedGeohash",
                    powDifficulty = try {
                        if (hasNonce) NostrProofOfWork.calculateDifficulty(event.id).takeIf { it > 0 } else null
                    } catch (_: Exception) { null }
                )
                withContext(Dispatchers.Main) { messageManager.addChannelMessage("geo:$subscribedGeohash", msg) }
            } catch (e: Exception) {
                Log.e(TAG, "onEvent error: ${e.message}")
            }
        }
    }
}
