package com.roman.zemzeme.mesh
import com.roman.zemzeme.protocol.MessageType

import android.util.Log
import com.roman.zemzeme.model.RoutedPacket
import com.roman.zemzeme.protocol.ZemzemePacket
import com.roman.zemzeme.util.DebugLogger
import com.roman.zemzeme.util.toHexString
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.random.Random

/**
 * Centralized packet relay management
 * 
 * This class handles all relay decisions and logic for zemzeme packets.
 * All packets that aren't specifically addressed to us get processed here.
 */
class PacketRelayManager(private val myPeerID: String) {
    private val debugManager by lazy { try { com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
    companion object {
        private const val TAG = "PacketRelayManager"
    }
    
    private fun isRelayEnabled(): Boolean = try {
        com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance().packetRelayEnabled.value
    } catch (_: Exception) { true }

    // Logging moved to BluetoothPacketBroadcaster per actual transmission target
    
    // Delegate for callbacks
    var delegate: PacketRelayManagerDelegate? = null
    
    // Coroutines
    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Main entry point for relay decisions
     * Only packets that aren't specifically addressed to us should be passed here
     */
    suspend fun handlePacketRelay(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.i(TAG, "Evaluating relay for packet type ${packet.type} from ${peerID} (TTL: ${packet.ttl})")
        
        // Double-check this packet isn't addressed to us
        if (isPacketAddressedToMe(packet)) {
            Log.i(TAG, "Packet addressed to us, skipping relay")
            return
        }
        
        // Skip our own packets
        if (peerID == myPeerID) {
            Log.i(TAG, "Packet from ourselves, skipping relay")
            return
        }
        
        // Check TTL and decrement
        if (packet.ttl == 0u.toUByte()) {
            Log.i(TAG, "TTL expired, not relaying packet")
            return
        }
        
        // Decrement TTL by 1
        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        Log.i(TAG, "Decremented TTL from ${packet.ttl} to ${relayPacket.ttl}")
        
        // Source-based routing: if route is set and includes us, try targeted next-hop forwarding
        val route = relayPacket.route
        if (!route.isNullOrEmpty()) {
            // Check for duplicate hops to prevent routing loops
            if (route.map { it.toHexString() }.toSet().size < route.size) {
                Log.w(TAG, "Packet with duplicate hops dropped")
                return
            }
            val myIdBytes = hexStringToPeerBytes(myPeerID)
            val index = route.indexOfFirst { it.contentEquals(myIdBytes) }
            if (index >= 0) {
                val nextHopIdHex: String? = run {
                    val nextIndex = index + 1
                    if (nextIndex < route.size) {
                        route[nextIndex].toHexString()
                    } else {
                        // We are the last intermediate; try final recipient as next hop
                        relayPacket.recipientID?.toHexString()
                    }
                }
                if (nextHopIdHex != null) {
                    val success = try { delegate?.sendToPeer(nextHopIdHex, RoutedPacket(relayPacket, peerID, routed.relayAddress)) } catch (_: Exception) { false } ?: false
                    if (success) {
                        Log.i(TAG, "📦 Source-route relay: ${peerID.take(8)} -> ${nextHopIdHex.take(8)} (type ${'$'}{packet.type}, TTL ${'$'}{relayPacket.ttl})")
                        return
                    } else {
                        Log.w(TAG, "Source-route next hop ${nextHopIdHex.take(8)} not directly connected; falling back to broadcast")
                    }
                }
            }
        }

        // Apply relay logic based on packet type and debug switch
        val shouldRelay = isRelayEnabled() && shouldRelayPacket(relayPacket, peerID)
        if (shouldRelay) {
            relayPacket(RoutedPacket(relayPacket, peerID, routed.relayAddress))
        } else {
            Log.i(TAG, "Relay decision: NOT relaying packet type ${packet.type}")
        }
    }
    
    /**
     * Check if a packet is specifically addressed to us
     */
    internal fun isPacketAddressedToMe(packet: ZemzemePacket): Boolean {
        val recipientID = packet.recipientID
        
        // No recipient means broadcast (not addressed to us specifically)
        if (recipientID == null) {
            return false
        }
        
        // Check if it's a broadcast recipient
        val broadcastRecipient = delegate?.getBroadcastRecipient()
        if (broadcastRecipient != null && recipientID.contentEquals(broadcastRecipient)) {
            return false
        }
        
        // Check if recipient matches our peer ID
        val recipientIDString = recipientID.toHexString()
        return recipientIDString == myPeerID
    }
    
    /**
     * Determine if we should relay this packet based on type and network conditions
     */
    private fun shouldRelayPacket(packet: ZemzemePacket, fromPeerID: String): Boolean {
        // Always relay if TTL is high enough (indicates important message)
        if (packet.ttl >= 4u) {
            Log.i(TAG, "High TTL (${packet.ttl}), relaying")
            return true
        }
        
        // Get network size for adaptive relay probability
        val networkSize = delegate?.getNetworkSize() ?: 1
        
        // Small networks always relay to ensure connectivity
        if (networkSize <= 3) {
            Log.i(TAG, "Small network (${networkSize} peers), relaying")
            return true
        }
        
        // Apply adaptive relay probability based on network size
        val relayProb = when {
            networkSize <= 10 -> 1.0    // Always relay in small networks
            networkSize <= 30 -> 0.85   // High probability for medium networks
            networkSize <= 50 -> 0.7    // Moderate probability
            networkSize <= 100 -> 0.55  // Lower probability for large networks
            else -> 0.4                 // Lowest probability for very large networks
        }
        
        val shouldRelay = Random.nextDouble() < relayProb
        Log.i(TAG, "Network size: ${networkSize}, Relay probability: ${relayProb}, Decision: ${shouldRelay}")
        
        return shouldRelay
    }
    
    /**
     * Actually broadcast the packet for relay
     */
    private fun relayPacket(routed: RoutedPacket) {
        Log.i(TAG, "🔄 Relaying packet type ${routed.packet.type} with TTL ${routed.packet.ttl}")
        DebugLogger.log(
            action = "RELAY",
            msgId = derivePacketId(routed.packet),
            srcName = null,
            srcId = routed.packet.senderID.toHexString(),
            destName = null,
            destId = routed.packet.recipientID?.toHexString() ?: "broadcast",
            protocol = "BLE_MESH",
            content = "[type=${routed.packet.type}]",
            hopCounter = routed.packet.ttl.toInt(),
            latencyMs = System.currentTimeMillis() - routed.packet.timestamp.toLong()
        )
        delegate?.broadcastPacket(routed)
    }

    /**
     * Derive a deterministic UUID from the packet's senderID + timestamp.
     * Since the wire protocol doesn't carry a message UUID, this produces a
     * stable identifier so the same message is traceable across relay hops.
     */
    private fun derivePacketId(packet: ZemzemePacket): String {
        val buf = ByteBuffer.allocate(packet.senderID.size + 8)
        buf.put(packet.senderID)
        buf.putLong(packet.timestamp.toLong())
        return UUID.nameUUIDFromBytes(buf.array()).toString().uppercase()
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Relay Manager Debug Info ===")
            appendLine("Relay Scope Active: ${relayScope.isActive}")
            appendLine("My Peer ID: ${myPeerID}")
            appendLine("Network Size: ${delegate?.getNetworkSize() ?: "unknown"}")
        }
    }
    
    /**
     * Shutdown the relay manager
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down PacketRelayManager")
        relayScope.cancel()
    }
}

/**
 * Delegate interface for packet relay manager callbacks
 */
interface PacketRelayManagerDelegate {
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Packet operations
    fun broadcastPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}

private fun hexStringToPeerBytes(hex: String): ByteArray {
    val result = ByteArray(8)
    var idx = 0
    var out = 0
    while (idx + 1 < hex.length && out < 8) {
        val b = hex.substring(idx, idx + 2).toIntOrNull(16)?.toByte() ?: 0
        result[out++] = b
        idx += 2
    }
    return result
}
