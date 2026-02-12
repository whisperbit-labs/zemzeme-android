package com.roman.zemzeme.mesh

import android.util.Log
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.model.ZemzemeMessageType
import com.roman.zemzeme.model.IdentityAnnouncement
import com.roman.zemzeme.model.RoutedPacket
import com.roman.zemzeme.protocol.ZemzemePacket
import com.roman.zemzeme.protocol.MessageType
import com.roman.zemzeme.util.toHexString
import com.roman.zemzeme.util.DebugLogger
import kotlinx.coroutines.*
import java.util.*
import kotlin.random.Random

/**
 * Handles processing of different message types
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class MessageHandler(private val myPeerID: String, private val appContext: android.content.Context) {
    
    companion object {
        private const val TAG = "MessageHandler"
    }
    
    // Delegate for callbacks
    var delegate: MessageHandlerDelegate? = null
    
    // Reference to PacketProcessor for recursive packet handling
    var packetProcessor: PacketProcessor? = null
    
    // Coroutines
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Handle Noise encrypted transport message - SIMPLIFIED iOS-compatible version
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.i(TAG, "Processing Noise encrypted message from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own messages
        if (peerID == myPeerID) return
        
        // Check if this message is for us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.i(TAG, "🔐 Encrypted message not for me (for $recipientID, I am $myPeerID)")
            return
        }
        
        try {
            // Decrypt the message using the Noise service
            val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
            if (decryptedData == null) {
                Log.w(TAG, "Failed to decrypt Noise message from $peerID - may need handshake")
                return
            }
            
            if (decryptedData.isEmpty()) {
                Log.w(TAG, "Decrypted data is empty from $peerID")
                return
            }
            
            // NEW: Use NoisePayload system exactly like iOS
            val noisePayload = com.roman.zemzeme.model.NoisePayload.decode(decryptedData)
            if (noisePayload == null) {
                Log.w(TAG, "Failed to parse NoisePayload from $peerID")
                return
            }
            
            Log.i(TAG, "🔓 Decrypted NoisePayload type ${noisePayload.type} from $peerID")
            
            when (noisePayload.type) {
                com.roman.zemzeme.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                    // Decode TLV private message exactly like iOS
                    val privateMessage = com.roman.zemzeme.model.PrivateMessagePacket.decode(noisePayload.data)
                    if (privateMessage != null) {
                        Log.i(TAG, "🔓 Decrypted TLV PM from $peerID: ${privateMessage.content.take(30)}...")

                        // Handle favorite/unfavorite notifications embedded as PMs
                        val pmContent = privateMessage.content
                        if (pmContent.startsWith("[FAVORITED]") || pmContent.startsWith("[UNFAVORITED]")) {
                            handleFavoriteNotificationFromMesh(pmContent, peerID)
                            // Acknowledge delivery for UX parity
                            sendDeliveryAck(privateMessage.messageID, peerID)
                            return
                        }
                        
                        // Create ZemzemeMessage - preserve source packet timestamp
                        val message = ZemzemeMessage(
                            id = privateMessage.messageID,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = privateMessage.content,
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            originalSender = null,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID,
                            mentions = null // TODO: Parse mentions if needed
                        )
                        
                        // Notify delegate
                        val receiveLatency = System.currentTimeMillis() - packet.timestamp.toLong()
                        DebugLogger.log(
                            action = "RECEIVE",
                            msgId = privateMessage.messageID,
                            srcName = delegate?.getPeerNickname(peerID),
                            srcId = peerID,
                            destName = delegate?.getMyNickname(),
                            destId = myPeerID,
                            protocol = "NOISE_ENCRYPTED",
                            content = privateMessage.content,
                            hopCounter = packet.ttl.toInt(),
                            latencyMs = receiveLatency
                        )
                        delegate?.onMessageReceived(message)

                        // Send delivery ACK exactly like iOS
                        sendDeliveryAck(privateMessage.messageID, peerID)
                    }
                }
                
                com.roman.zemzeme.model.NoisePayloadType.FILE_TRANSFER -> {
                    // Handle encrypted file transfer; generate unique message ID
                    val file = com.roman.zemzeme.model.ZemzemeFilePacket.decode(noisePayload.data)
                    if (file != null) {
                        Log.i(TAG, "🔓 Decrypted encrypted file from $peerID: name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}'")
                        val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                        val savedPath = com.roman.zemzeme.features.file.FileUtils.saveIncomingFile(appContext, file)
                        val message = ZemzemeMessage(
                            id = uniqueMsgId,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = savedPath,
                            type = com.roman.zemzeme.features.file.FileUtils.messageTypeForMime(file.mimeType),
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID
                        )

                        Log.i(TAG, "📄 Saved encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                        DebugLogger.log(
                            action = "RECEIVE",
                            msgId = uniqueMsgId,
                            srcName = delegate?.getPeerNickname(peerID),
                            srcId = peerID,
                            destName = delegate?.getMyNickname(),
                            destId = myPeerID,
                            protocol = "NOISE_ENCRYPTED",
                            content = "[FILE:${file.fileName}]",
                            hopCounter = packet.ttl.toInt(),
                            latencyMs = System.currentTimeMillis() - packet.timestamp.toLong()
                        )
                        delegate?.onMessageReceived(message)

                        // Send delivery ACK with generated message ID
                        sendDeliveryAck(uniqueMsgId, peerID)
                    } else {
                        Log.w(TAG, "⚠️ Failed to decode encrypted file transfer from $peerID")
                    }
                }
                
                com.roman.zemzeme.model.NoisePayloadType.DELIVERED -> {
                    // Handle delivery ACK exactly like iOS
                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.i(TAG, "📬 Delivery ACK received from $peerID for message $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onDeliveryAckReceived(messageID, peerID)
                }
                
                com.roman.zemzeme.model.NoisePayloadType.READ_RECEIPT -> {
                    // Handle read receipt exactly like iOS
                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.i(TAG, "👁️ Read receipt received from $peerID for message $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onReadReceiptReceived(messageID, peerID)
                }
                com.roman.zemzeme.model.NoisePayloadType.VERIFY_CHALLENGE -> {
                    Log.i(TAG, "🔐 Verify challenge received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyChallengeReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
                com.roman.zemzeme.model.NoisePayloadType.VERIFY_RESPONSE -> {
                    Log.i(TAG, "🔐 Verify response received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyResponseReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Noise encrypted message from $peerID: ${e.message}")
        }
    }
    
    /**
     * Send delivery ACK for a received private message - exactly like iOS
     */
    private suspend fun sendDeliveryAck(messageID: String, senderPeerID: String) {
        try {
            // Create ACK payload: [type byte] + [message ID] - exactly like iOS
            val ackPayload = com.roman.zemzeme.model.NoisePayload(
                type = com.roman.zemzeme.model.NoisePayloadType.DELIVERED,
                data = messageID.toByteArray(Charsets.UTF_8)
            )
            
            // Encrypt the payload
            val encryptedPayload = delegate?.encryptForPeer(ackPayload.encode(), senderPeerID)
            if (encryptedPayload == null) {
                Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
                return
            }
            
            // Create NOISE_ENCRYPTED packet exactly like iOS
                val packet = ZemzemePacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(senderPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS messageTTL
                )
            
            delegate?.sendPacket(packet)
            Log.i(TAG, "📤 Sent delivery ACK to $senderPeerID for message $messageID")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery ACK to $senderPeerID: ${e.message}")
        }
    }
    
    /**
     * Handle announce message with TLV decoding and signature verification - exactly like iOS
     */
    suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (peerID == myPeerID) return false

        // Ignore stale announcements older than STALE_PEER_TIMEOUT
        val now = System.currentTimeMillis()
        val age = now - packet.timestamp.toLong()
        if (age > com.roman.zemzeme.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
            Log.w(TAG, "Ignoring stale ANNOUNCE from ${peerID.take(8)} (age=${age}ms > ${com.roman.zemzeme.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
            return false
        }
        
        // Try to decode as iOS-compatible IdentityAnnouncement with TLV format
        val announcement = IdentityAnnouncement.decode(packet.payload)
        if (announcement == null) {
            Log.w(TAG, "Failed to decode announce from $peerID as iOS-compatible TLV format")
            return false
        }
        
        // Verify packet signature using the announced signing public key
        var verified = false
        if (packet.signature != null) {
            // Verify that the packet was signed by the signing private key corresponding to the announced signing public key
            verified = delegate?.verifyEd25519Signature(packet.signature!!, packet.toBinaryDataForSigning()!!, announcement.signingPublicKey) ?: false
            if (!verified) {
                Log.w(TAG, "⚠️ Signature verification for announce failed ${peerID.take(8)}")
            }
        }

        // Check for existing peer with different noise public key
        // If existing peer has a different noise public key, do not consider this verified
        val existingPeer = delegate?.getPeerInfo(peerID)
        
        if (existingPeer != null && existingPeer.noisePublicKey != null && existingPeer.noisePublicKey?.contentEquals(announcement.noisePublicKey) == false) {
            Log.w(TAG, "⚠️ Announce key mismatch for ${peerID.take(8)}... — keeping unverified")
            verified = false
        }

        // Require verified announce; ignore otherwise (no backward compatibility)
        if (!verified) {
            Log.w(TAG, "❌ Ignoring unverified announce from ${peerID.take(8)}...")
            return false
        }
        
        // Successfully decoded TLV format exactly like iOS
        Log.i(TAG, "✅ Verified announce from $peerID: nickname=${announcement.nickname}, " +
                "noisePublicKey=${announcement.noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., " +
                "signingPublicKey=${announcement.signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}...")
        
        // Extract nickname and public keys from TLV data
        val nickname = announcement.nickname
        val noisePublicKey = announcement.noisePublicKey
        val signingPublicKey = announcement.signingPublicKey
        
        // Update peer info with verification status through new method
        val isFirstAnnounce = delegate?.updatePeerInfo(
            peerID = peerID,
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerified = true
        ) ?: false

        // Update peer ID binding with noise public key for identity management
        delegate?.updatePeerIDBinding(
            newPeerID = peerID,
            nickname = nickname,
            publicKey = noisePublicKey,
            previousPeerID = null
        )
        
        // Update mesh graph from gossip neighbors (only if TLV present)
        try {
            val neighborsOrNull = com.roman.zemzeme.services.meshgraph.GossipTLV.decodeNeighborsFromAnnouncementPayload(packet.payload)
            com.roman.zemzeme.services.meshgraph.MeshGraphService.getInstance()
                .updateFromAnnouncement(peerID, nickname, neighborsOrNull, packet.timestamp)
        } catch (_: Exception) { }

        Log.i(TAG, "✅ Processed verified TLV announce: stored identity for $peerID")
        return isFirstAnnounce
    }
    
    /**
     * Handle Noise handshake - SIMPLIFIED iOS-compatible version
     * Single handshake type (0x10) with response determined by payload analysis
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.i(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own handshake messages
        if (peerID == myPeerID) return
        
        // Check if handshake is addressed to us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.i(TAG, "Handshake not for me (for $recipientID, I am $myPeerID)")
            return
        }
        
        try {
            // Process handshake message through delegate (simplified approach)
            val response = delegate?.processNoiseHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                Log.i(TAG, "Generated handshake response for $peerID (${response.size} bytes)")
                
                // Send response using same packet type (simplified iOS approach)
                val responsePacket = ZemzemePacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    signature = null,
                    ttl = com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS
                )
                
                delegate?.sendPacket(responsePacket)
                Log.i(TAG, "📤 Sent handshake response to $peerID")
            }
            
            // Check if session is now established
            val hasSession = delegate?.hasNoiseSession(peerID) ?: false
            if (hasSession) {
                Log.i(TAG, "✅ Noise session established with $peerID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
        }
    }
    
    /**
     * Handle broadcast or private message
     */
    suspend fun handleMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return
        val senderNickname = delegate?.getPeerNickname(peerID)
        if (senderNickname != null) {
            Log.i(TAG, "Received message from $senderNickname")
            delegate?.updatePeerNickname(peerID, senderNickname)
        }
        
        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(delegate?.getBroadcastRecipient()) }
        
        if (recipientID == null) {
            // BROADCAST MESSAGE
            handleBroadcastMessage(routed)
        } else if (recipientID.toHexString() == myPeerID) {
            // PRIVATE MESSAGE FOR US
            handlePrivateMessage(packet, peerID)
        }
        // Message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Handle broadcast message with verification enforcement
     */
    private suspend fun handleBroadcastMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        // Enforce: only accept public messages from verified peers we know
        val peerInfo = delegate?.getPeerInfo(peerID)
        if (peerInfo == null || !peerInfo.isVerifiedNickname) {
            Log.w(TAG, "🚫 Dropping public message from unverified or unknown peer ${peerID.take(8)}...")
            return
        }
        
        try {
            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            val isFileTransfer = com.roman.zemzeme.protocol.MessageType.fromValue(packet.type) == com.roman.zemzeme.protocol.MessageType.FILE_TRANSFER
            val file = com.roman.zemzeme.model.ZemzemeFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.i(TAG, "📥 FILE_TRANSFER decode success (broadcast): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.roman.zemzeme.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = ZemzemeMessage(
                    id = java.util.UUID.randomUUID().toString().uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.roman.zemzeme.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong())
                )
                Log.i(TAG, "📄 Saved incoming file to $savedPath")
                DebugLogger.log(
                    action = "RECEIVE",
                    msgId = message.id,
                    srcName = delegate?.getPeerNickname(peerID),
                    srcId = peerID,
                    destName = "broadcast",
                    destId = "all",
                    protocol = "BLE_MESH",
                    content = "[FILE:${file.fileName}]",
                    hopCounter = packet.ttl.toInt(),
                    latencyMs = System.currentTimeMillis() - packet.timestamp.toLong()
                )
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "⚠️ FILE_TRANSFER decode failed (broadcast) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            // Fallback: plain text
            val textContent = String(packet.payload, Charsets.UTF_8)
            val message = ZemzemeMessage(
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = textContent,
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong())
            )
            DebugLogger.log(
                action = "RECEIVE",
                msgId = message.id,
                srcName = delegate?.getPeerNickname(peerID),
                srcId = peerID,
                destName = "broadcast",
                destId = "all",
                protocol = "BLE_MESH",
                content = textContent,
                hopCounter = packet.ttl.toInt(),
                latencyMs = System.currentTimeMillis() - packet.timestamp.toLong()
            )
            delegate?.onMessageReceived(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process broadcast message: ${e.message}")
        }
    }
    
    /**
     * Handle (decrypted) private message addressed to us
     */
    private suspend fun handlePrivateMessage(packet: ZemzemePacket, peerID: String) {
        try {
            // Verify signature if present
            if (packet.signature != null && delegate?.verifySignature(packet, peerID) == false) {
                Log.w(TAG, "Invalid signature for private message from $peerID")
                return
            }

            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            val isFileTransfer = com.roman.zemzeme.protocol.MessageType.fromValue(packet.type) == com.roman.zemzeme.protocol.MessageType.FILE_TRANSFER
            val file = com.roman.zemzeme.model.ZemzemeFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.i(TAG, "📥 FILE_TRANSFER decode success (private): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.roman.zemzeme.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = ZemzemeMessage(
                    id = java.util.UUID.randomUUID().toString().uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.roman.zemzeme.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong()),
                    isPrivate = true,
                    recipientNickname = delegate?.getMyNickname()
                )
                Log.i(TAG, "📄 Saved incoming file to $savedPath")
                DebugLogger.log(
                    action = "RECEIVE",
                    msgId = message.id,
                    srcName = delegate?.getPeerNickname(peerID),
                    srcId = peerID,
                    destName = delegate?.getMyNickname(),
                    destId = myPeerID,
                    protocol = "BLE_MESH",
                    content = "[FILE:${file.fileName}]",
                    hopCounter = packet.ttl.toInt(),
                    latencyMs = System.currentTimeMillis() - packet.timestamp.toLong()
                )
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "⚠️ FILE_TRANSFER decode failed (private) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            // Fallback: plain text
            val pmTextContent = String(packet.payload, Charsets.UTF_8)
            val message = ZemzemeMessage(
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = pmTextContent,
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong())
            )
            DebugLogger.log(
                action = "RECEIVE",
                msgId = message.id,
                srcName = delegate?.getPeerNickname(peerID),
                srcId = peerID,
                destName = delegate?.getMyNickname(),
                destId = myPeerID,
                protocol = "BLE_MESH",
                content = pmTextContent,
                hopCounter = packet.ttl.toInt(),
                latencyMs = System.currentTimeMillis() - packet.timestamp.toLong()
            )
            delegate?.onMessageReceived(message)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process private message from $peerID: ${e.message}")
        }
    }

    
    
    /**
     * Handle leave message
     */
    suspend fun handleLeave(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val content = String(packet.payload, Charsets.UTF_8)
        
        if (content.startsWith("#")) {
            // Channel leave
            delegate?.onChannelLeave(content, peerID)
        } else {
            // Peer disconnect
            delegate?.removePeer(peerID)
        }
        
        // Leave message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Message Handler Debug Info ===")
            appendLine("Handler Scope Active: ${handlerScope.isActive}")
            appendLine("My Peer ID: $myPeerID")
        }
    }
    
    /**
     * Convert hex string peer ID to binary data (8 bytes) - same as iOS implementation
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
     * Shutdown the handler
     */
    fun shutdown() {
        handlerScope.cancel()
    }

    /**
     * Handle favorite/unfavorite notification received over mesh as a private message.
     * Content format: "[FAVORITED]:npub..." or "[UNFAVORITED]:npub..."
     */
    private fun handleFavoriteNotificationFromMesh(content: String, fromPeerID: String) {
        try {
            val isFavorite = content.startsWith("[FAVORITED]")
            val npub = content.substringAfter(":", "").trim().takeIf { it.startsWith("npub1") }

            // Update mutual favorite status in persistence
            // Resolve full Noise key if available via delegate peer info
            val peerInfo = delegate?.getPeerInfo(fromPeerID)
            val noiseKey = peerInfo?.noisePublicKey
            if (noiseKey != null) {
                com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, isFavorite)
                if (npub != null) {
                    // Index by noise key and current mesh peerID for fast Nostr routing
                    com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, npub)
                    com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(fromPeerID, npub)
                }

                // Determine iOS-style guidance text
                val rel = com.roman.zemzeme.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                val guidance = if (isFavorite) {
                    if (rel?.isFavorite == true) {
                        " — mutual! You can continue DMs via Nostr when out of mesh."
                    } else {
                        " — favorite back to continue DMs later."
                    }
                } else {
                    ". DMs over Nostr will pause unless you both favorite again."
                }

                // Emit system message via delegate callback
                val action = if (isFavorite) "favorited" else "unfavorited"
                val sys = com.roman.zemzeme.model.ZemzemeMessage(
                    sender = "system",
                    content = "${peerInfo.nickname} $action you$guidance",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                delegate?.onMessageReceived(sys)
            }
        } catch (_: Exception) {
            // Best-effort; ignore errors
        }
    }
}

/**
 * Delegate interface for message handler callbacks
 */
interface MessageHandlerDelegate {
    // Peer management
    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean
    fun removePeer(peerID: String)
    fun updatePeerNickname(peerID: String, nickname: String)
    fun getPeerNickname(peerID: String): String?
    fun getNetworkSize(): Int
    fun getMyNickname(): String?
    fun getPeerInfo(peerID: String): PeerInfo?
    fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean): Boolean
    
    // Packet operations
    fun sendPacket(packet: ZemzemePacket)
    fun relayPacket(routed: RoutedPacket)
    fun getBroadcastRecipient(): ByteArray
    
    // Cryptographic operations
    fun verifySignature(packet: ZemzemePacket, peerID: String): Boolean
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray?
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray?
    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean
    
    // Noise protocol operations
    fun hasNoiseSession(peerID: String): Boolean
    fun initiateNoiseHandshake(peerID: String)
    fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray?
    fun updatePeerIDBinding(newPeerID: String, nickname: String,
                           publicKey: ByteArray, previousPeerID: String?)
    
    // Message operations
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?

    // Callbacks
    fun onMessageReceived(message: ZemzemeMessage)
    fun onChannelLeave(channel: String, fromPeer: String)
    fun onDeliveryAckReceived(messageID: String, peerID: String)
    fun onReadReceiptReceived(messageID: String, peerID: String)
    fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long)
    fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long)
}
