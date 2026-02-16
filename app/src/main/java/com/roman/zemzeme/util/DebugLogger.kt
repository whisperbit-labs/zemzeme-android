package com.roman.zemzeme.util

import android.util.Log
import com.roman.zemzeme.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only structured logger for testing.
 * Logs are only emitted in debug builds and will NOT appear in release.
 *
 * Format:
 * timestamp | action | msg-id=<id> | src=<name:id> | dest=<name:id> | protocol=<type> | content=<> | hop_counter=<n> | latency_ms=<time>
 */
object DebugLogger {

    private const val TAG = "ZemzemeDebug"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    /**
     * Log a structured message event (send, receive, relay, group open, etc.)
     *
     * @param action       The action being performed (e.g. "SEND", "RECEIVE", "RELAY", "GROUP_OPEN")
     * @param msgId        Message or event ID
     * @param srcName      Source user nickname (or null)
     * @param srcId        Source peer ID (or null)
     * @param destName     Destination user nickname (or null)
     * @param destId       Destination peer ID / channel name (or null)
     * @param protocol     Communication type (e.g. "BLE_MESH", "P2P", "NOSTR", "NOISE_ENCRYPTED")
     * @param content      Message content preview (truncated for safety)
     * @param hopCounter   TTL / hop counter (or null)
     * @param latencyMs    Latency in milliseconds (or null)
     */
    fun log(
        action: String,
        msgId: String? = null,
        srcName: String? = null,
        srcId: String? = null,
        destName: String? = null,
        destId: String? = null,
        protocol: String? = null,
        content: String? = null,
        hopCounter: Int? = null,
        latencyMs: Long? = null
    ) {
        if (!BuildConfig.DEBUG) return

        val timestamp = dateFormat.format(Date())
        val contentPreview = content?.take(50)?.replace("\n", " ")

        val line = buildString {
            append("$timestamp | $action")
            append(" | msg-id=${msgId ?: "N/A"}")
            append(" | src=${formatPeer(srcName, srcId)}")
            append(" | dest=${formatPeer(destName, destId)}")
            append(" | protocol=${protocol ?: "N/A"}")
            append(" | content=${contentPreview ?: "N/A"}")
            append(" | hop_counter=${hopCounter ?: "N/A"}")
            append(" | latency_ms=${latencyMs ?: "N/A"}")
        }

        Log.i(TAG, line)
    }

    private fun formatPeer(name: String?, id: String?): String {
        return when {
            name != null && id != null -> "$name:${id.take(16)}"
            name != null -> name
            id != null -> id.take(16)
            else -> "N/A"
        }
    }
}
