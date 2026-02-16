package com.roman.zemzeme.features.media

import android.content.Context
import android.util.Log
import com.roman.zemzeme.features.file.FileUtils
import com.roman.zemzeme.model.DeliveryStatus
import com.roman.zemzeme.model.ZemzemeFilePacket
import com.roman.zemzeme.model.ZemzemeMessage
import java.util.Date
import java.util.UUID

/**
 * Shared receive-side media handler for P2P and Nostr transports.
 *
 * Decodes raw TLV bytes as a [ZemzemeFilePacket], saves the file to disk, and
 * returns a [ZemzemeMessage] ready to be inserted into the conversation.
 * Called by both the P2P and Nostr incoming message handlers.
 */
object MediaReceiveHandler {

    private const val TAG = "MediaReceiveHandler"

    /**
     * Decode [fileBytes] as TLV, save to device storage, and build a [ZemzemeMessage].
     *
     * @param context         App/activity context used for file operations.
     * @param fileBytes       Raw TLV-encoded bytes (output of [ZemzemeFilePacket.encode]).
     * @param senderKey       Conversation key for the sender: "p2p:<id>" or "nostr_<pubkey>".
     * @param senderNickname  Display name shown in the chat bubble.
     * @param timestamp       Timestamp to stamp the message with.
     * @param channelOrNull   Channel name for channel messages; null for private DMs.
     * @return A ready-to-insert [ZemzemeMessage], or null if decode/save fails.
     */
    fun handle(
        context: Context,
        fileBytes: ByteArray,
        senderKey: String,
        senderNickname: String,
        timestamp: Date = Date(),
        channelOrNull: String? = null
    ): ZemzemeMessage? {
        val file = ZemzemeFilePacket.decode(fileBytes)
        if (file == null) {
            Log.w(TAG, "Failed to decode ZemzemeFilePacket from $senderKey")
            return null
        }

        val savedPath = FileUtils.saveIncomingFile(context, file)
        val msgType = FileUtils.messageTypeForMime(file.mimeType)
        Log.d(TAG, "Saved incoming ${file.mimeType} (${file.fileSize} bytes) from $senderKey → $savedPath")

        return ZemzemeMessage(
            id = UUID.randomUUID().toString().uppercase(),
            sender = senderNickname,
            content = savedPath,
            type = msgType,
            timestamp = timestamp,
            isRelay = false,
            isPrivate = channelOrNull == null,
            senderPeerID = senderKey,
            channel = channelOrNull,
            deliveryStatus = DeliveryStatus.Delivered(to = "me", at = Date())
        )
    }
}
