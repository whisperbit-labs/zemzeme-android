package com.roman.zemzeme.ui

import android.util.Log
import com.roman.zemzeme.mesh.BluetoothMeshService
import com.roman.zemzeme.model.DeliveryStatus
import com.roman.zemzeme.model.ZemzemeFilePacket
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.model.ZemzemeMessageType
import com.roman.zemzeme.nostr.NostrTransport
import com.roman.zemzeme.p2p.P2PAliasRegistry
import com.roman.zemzeme.p2p.P2PTransport
import java.util.Date
import java.security.MessageDigest

/**
 * Handles media file sending operations (voice notes, images, generic files).
 * Routes to BLE mesh, P2P, or Nostr depending on the target peer ID prefix.
 */
class MediaSendingManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val getMeshService: () -> BluetoothMeshService,
    private val getP2PTransport: (() -> P2PTransport)? = null,
    private val getNostrTransport: (() -> NostrTransport)? = null
) {
    // Helper to get current mesh service (may change after panic clear)
    private val meshService: BluetoothMeshService
        get() = getMeshService()
    companion object {
        private const val TAG = "MediaSendingManager"
        private const val MAX_FILE_SIZE = com.roman.zemzeme.util.AppConstants.Media.MAX_FILE_SIZE_BYTES // 50MB limit
    }

    // Track in-flight transfer progress: transferId -> messageId and reverse
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    /**
     * Send a voice note (audio file)
     */
    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            val filePacket = ZemzemeFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = "audio/mp4",
                content = file.readBytes()
            )

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, ZemzemeMessageType.Audio)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, ZemzemeMessageType.Audio)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice note: ${e.message}")
        }
    }

    /**
     * Send an image file
     */
    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            Log.d(TAG, "🔄 Starting image send: $filePath")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            val filePacket = ZemzemeFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = "image/jpeg",
                content = file.readBytes()
            )

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, ZemzemeMessageType.Image)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, ZemzemeMessageType.Image)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Image send failed completely", e)
            Log.e(TAG, "❌ Image path: $filePath")
            Log.e(TAG, "❌ Error details: ${e.message}")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Send a generic file
     */
    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            Log.d(TAG, "🔄 Starting file send: $filePath")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            // Use the real MIME type based on extension; fallback to octet-stream
            val mimeType = try { 
                com.roman.zemzeme.features.file.FileUtils.getMimeTypeFromExtension(file.name) 
            } catch (_: Exception) { 
                "application/octet-stream" 
            }
            Log.d(TAG, "🏷️ MIME type: $mimeType")

            // Try to preserve the original file name if our copier prefixed it earlier
            val originalName = run {
                val name = file.name
                val base = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "").let { if (it.isNotBlank()) ".${it}" else "" }
                val stripped = Regex("^send_\\d+_(.+)$").matchEntire(base)?.groupValues?.getOrNull(1) ?: base
                stripped + ext
            }
            Log.d(TAG, "📝 Original filename: $originalName")

            val filePacket = ZemzemeFilePacket(
                fileName = originalName,
                fileSize = file.length(),
                mimeType = mimeType,
                content = file.readBytes()
            )
            Log.d(TAG, "📦 Created file packet successfully")

            val messageType = when {
                mimeType.lowercase().startsWith("image/") -> ZemzemeMessageType.Image
                mimeType.lowercase().startsWith("audio/") -> ZemzemeMessageType.Audio
                else -> ZemzemeMessageType.File
            }

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, messageType)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, messageType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: File send failed completely", e)
            Log.e(TAG, "❌ File path: $filePath")
            Log.e(TAG, "❌ Error details: ${e.message}")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Send a file privately (encrypted).
     * Routes to P2P, Nostr, or BLE mesh depending on the [toPeerID] prefix.
     */
    private fun sendPrivateFile(
        toPeerID: String,
        filePacket: ZemzemeFilePacket,
        filePath: String,
        messageType: ZemzemeMessageType
    ) {
        // ── P2P path ─────────────────────────────────────────────────────────
        if (toPeerID.startsWith("p2p:")) {
            val p2pTransport = getP2PTransport?.invoke() ?: run {
                Log.e(TAG, "❌ P2P transport unavailable for P2P media send to $toPeerID")
                return
            }
            val rawPeer = toPeerID.removePrefix("p2p:")
            val messageID = java.util.UUID.randomUUID().toString().uppercase()
            val recipientNickname = P2PAliasRegistry.getDisplayName(toPeerID)
            val myPeerID = try { meshService.myPeerID } catch (_: Exception) { "unknown" }

            Log.d(TAG, "📤 P2P FILE_TRANSFER (private): name='${filePacket.fileName}', to=$toPeerID msgId=${messageID.take(8)}…")

            val msg = ZemzemeMessage(
                id = messageID,
                sender = state.getNicknameValue() ?: "me",
                content = filePath,
                type = messageType,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                recipientNickname = recipientNickname,
                senderPeerID = myPeerID
            )
            messageManager.addPrivateMessage(toPeerID, msg)
            messageManager.updateMessageDeliveryStatus(msg.id, DeliveryStatus.PartiallyDelivered(0, 100))

            p2pTransport.sendMediaAsync(
                rawPeerID = rawPeer,
                filePacket = filePacket,
                channelOrNull = null,
                messageID = messageID,
                senderNickname = state.getNicknameValue() ?: "Zemzeme"
            ) { success ->
                if (success) {
                    messageManager.updateMessageDeliveryStatus(
                        msg.id, DeliveryStatus.Delivered(to = toPeerID, at = Date())
                    )
                }
            }
            return
        }

        // ── Nostr path ───────────────────────────────────────────────────────
        if (toPeerID.startsWith("nostr_") || toPeerID.startsWith("nostr:")) {
            val nostrTransport = getNostrTransport?.invoke() ?: run {
                Log.e(TAG, "❌ Nostr transport unavailable for Nostr media send to $toPeerID")
                return
            }
            val messageID = java.util.UUID.randomUUID().toString().uppercase()
            val myPeerID = try { meshService.myPeerID } catch (_: Exception) { "unknown" }

            Log.d(TAG, "📤 Nostr FILE_TRANSFER (private): name='${filePacket.fileName}', to=$toPeerID msgId=${messageID.take(8)}…")

            val msg = ZemzemeMessage(
                id = messageID,
                sender = state.getNicknameValue() ?: "me",
                content = filePath,
                type = messageType,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                recipientNickname = null,
                senderPeerID = myPeerID
            )
            messageManager.addPrivateMessage(toPeerID, msg)
            messageManager.updateMessageDeliveryStatus(msg.id, DeliveryStatus.PartiallyDelivered(0, 100))

            nostrTransport.sendMediaMessage(filePacket, toPeerID, "", messageID)
            return
        }

        // ── BLE mesh path (existing) ──────────────────────────────────────────
        val payload = filePacket.encode()
        if (payload == null) {
            Log.e(TAG, "❌ Failed to encode file packet for private send")
            return
        }
        Log.d(TAG, "🔒 Encoded private packet: ${payload.size} bytes")

        val transferId = sha256Hex(payload)
        val contentHash = sha256Hex(filePacket.content)

        Log.d(TAG, "📤 FILE_TRANSFER send (private): name='${filePacket.fileName}', size=${filePacket.fileSize}, mime='${filePacket.mimeType}', sha256=$contentHash, to=${toPeerID.take(8)} transferId=${transferId.take(16)}…")

        val msg = ZemzemeMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(),
            sender = state.getNicknameValue() ?: "me",
            content = filePath,
            type = messageType,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = try { meshService.getPeerNicknames()[toPeerID] } catch (_: Exception) { null },
            senderPeerID = meshService.myPeerID
        )

        messageManager.addPrivateMessage(toPeerID, msg)

        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = msg.id
            messageTransferMap[msg.id] = transferId
        }

        // Seed progress so delivery icons render for media
        messageManager.updateMessageDeliveryStatus(
            msg.id,
            DeliveryStatus.PartiallyDelivered(0, 100)
        )

        Log.d(TAG, "📤 Calling meshService.sendFilePrivate to $toPeerID")
        meshService.sendFilePrivate(toPeerID, filePacket)
        Log.d(TAG, "✅ File send completed successfully")
    }

    /**
     * Send a file publicly (broadcast or channel)
     */
    private fun sendPublicFile(
        channelOrNull: String?,
        filePacket: ZemzemeFilePacket,
        filePath: String,
        messageType: ZemzemeMessageType
    ) {
        val payload = filePacket.encode()
        if (payload == null) {
            Log.e(TAG, "❌ Failed to encode file packet for broadcast send")
            return
        }
        Log.d(TAG, "🔓 Encoded broadcast packet: ${payload.size} bytes")
        
        val transferId = sha256Hex(payload)
        val contentHash = sha256Hex(filePacket.content)
        
        Log.d(TAG, "📤 FILE_TRANSFER send (broadcast): name='${filePacket.fileName}', size=${filePacket.fileSize}, mime='${filePacket.mimeType}', sha256=$contentHash, transferId=${transferId.take(16)}…")

        val message = ZemzemeMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(), // Generate unique ID for each message
            sender = state.getNicknameValue() ?: meshService.myPeerID,
            content = filePath,
            type = messageType,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = meshService.myPeerID,
            channel = channelOrNull
        )
        
        if (!channelOrNull.isNullOrBlank()) {
            channelManager.addChannelMessage(channelOrNull, message, meshService.myPeerID)
        } else {
            messageManager.addMessage(message)
        }
        
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = message.id
            messageTransferMap[message.id] = transferId
        }
        
        // Seed progress so animations start immediately
        messageManager.updateMessageDeliveryStatus(
            message.id,
            com.roman.zemzeme.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )
        
        // Also publish via P2P topic if P2P is running (reaches P2P-only subscribers)
        val p2pTransport = getP2PTransport?.invoke()
        if (p2pTransport != null && p2pTransport.isRunning() && !channelOrNull.isNullOrBlank()) {
            val topicMessageID = java.util.UUID.randomUUID().toString().uppercase()
            p2pTransport.sendMediaAsync(
                rawPeerID = "",  // unused for channel sends
                filePacket = filePacket,
                channelOrNull = channelOrNull,
                messageID = topicMessageID,
                senderNickname = state.getNicknameValue() ?: "Zemzeme"
            )
            Log.d(TAG, "📤 Also publishing media to P2P topic $channelOrNull")
        }

        Log.d(TAG, "📤 Calling meshService.sendFileBroadcast")
        meshService.sendFileBroadcast(filePacket)
        Log.d(TAG, "✅ File broadcast completed successfully")
    }

    /**
     * Cancel a media transfer by message ID
     */
    fun cancelMediaSend(messageId: String) {
        val transferId = synchronized(transferMessageMap) { messageTransferMap[messageId] }
        if (transferId != null) {
            val cancelled = meshService.cancelFileTransfer(transferId)
            if (cancelled) {
                // Try to remove cached local file for this message (if any)
                runCatching { findMessagePathById(messageId)?.let { java.io.File(it).delete() } }

                // Remove the message from chat upon explicit cancel
                messageManager.removeMessageById(messageId)
                synchronized(transferMessageMap) {
                    transferMessageMap.remove(transferId)
                    messageTransferMap.remove(messageId)
                }
            }
        }
    }

    private fun findMessagePathById(messageId: String): String? {
        // Search main timeline
        state.getMessagesValue().firstOrNull { it.id == messageId }?.content?.let { return it }
        // Search private chats
        state.getPrivateChatsValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        // Search channel messages
        state.getChannelMessagesValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        return null
    }

    /**
     * Update progress for a transfer
     */
    fun updateTransferProgress(transferId: String, messageId: String) {
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = messageId
            messageTransferMap[messageId] = transferId
        }
    }

    /**
     * Handle transfer progress events
     */
    fun handleTransferProgressEvent(evt: com.roman.zemzeme.mesh.TransferProgressEvent) {
        val msgId = synchronized(transferMessageMap) { transferMessageMap[evt.transferId] }
        if (msgId != null) {
            if (evt.completed) {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.roman.zemzeme.model.DeliveryStatus.Delivered(to = "mesh", at = java.util.Date())
                )
                synchronized(transferMessageMap) {
                    val msgIdRemoved = transferMessageMap.remove(evt.transferId)
                    if (msgIdRemoved != null) messageTransferMap.remove(msgIdRemoved)
                }
            } else {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.roman.zemzeme.model.DeliveryStatus.PartiallyDelivered(evt.sent, evt.total)
                )
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
