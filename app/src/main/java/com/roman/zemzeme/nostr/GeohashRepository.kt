package com.roman.zemzeme.nostr

import android.app.Application
import com.roman.zemzeme.ui.ChatState
import com.roman.zemzeme.ui.GeoPerson
import com.roman.zemzeme.ui.TransportType
import java.util.Date

/**
 * GeohashRepository
 * - Owns geohash participant tracking and nickname caching
 * - Maintains lightweight state for geohash-related UI
 */
class GeohashRepository(
    private val application: Application,
    private val state: ChatState,
    private val dataManager: com.bitchat.android.ui.DataManager
) {
    companion object { private const val TAG = "GeohashRepository" }

    // geohash -> (participant pubkeyHex -> lastSeen)
    private val geohashParticipants: MutableMap<String, MutableMap<String, Date>> = mutableMapOf()


    // pubkeyHex(lowercase) -> nickname (without #hash)
    private val geoNicknames: MutableMap<String, String> = mutableMapOf()

    // conversation key (e.g., "nostr_<pub16>") -> source geohash it belongs to
    private val conversationGeohash: MutableMap<String, String> = mutableMapOf()

    private val lock = Any()

    private inline fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    fun setConversationGeohash(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            withLock { conversationGeohash[convKey] = geohash }
        }
    }

    fun getConversationGeohash(convKey: String): String? = withLock { conversationGeohash[convKey] }

    fun findPubkeyByNickname(targetNickname: String): String? {
        val entries = withLock { geoNicknames.entries.toList() }
        return entries.firstOrNull { (_, nickname) ->
            val base = nickname.split("#").firstOrNull() ?: nickname
            base == targetNickname
        }?.key
    }

    // peerID alias -> nostr pubkey mapping for geohash DMs and temp aliases
    private val nostrKeyMapping: MutableMap<String, String> = mutableMapOf()

    // Current geohash in view
    private var currentGeohash: String? = null

    fun setCurrentGeohash(geo: String?) { withLock { currentGeohash = geo } }
    fun getCurrentGeohash(): String? = withLock { currentGeohash }

    fun clearAll() {
        withLock {
            geohashParticipants.clear()
            geoNicknames.clear()
            nostrKeyMapping.clear()
            conversationGeohash.clear()
            currentGeohash = null
        }
        state.setGeohashPeople(emptyList())
        state.setTeleportedGeo(emptySet())
        state.setGeohashParticipantCounts(emptyMap())
    }

    fun cacheNickname(pubkeyHex: String, nickname: String) {
        val shouldRefresh = withLock {
            val lower = pubkeyHex.lowercase()
            val previous = geoNicknames[lower]
            geoNicknames[lower] = nickname
            previous != nickname && currentGeohash != null
        }
        if (shouldRefresh) {
            refreshGeohashPeople()
        }
    }

    fun getCachedNickname(pubkeyHex: String): String? = withLock { geoNicknames[pubkeyHex.lowercase()] }

    fun getAllNicknames(): Map<String, String> = withLock { geoNicknames.toMap() }

    fun markTeleported(pubkeyHex: String) {
        val set = state.getTeleportedGeoValue().toMutableSet()
        val key = pubkeyHex.lowercase()
        if (!set.contains(key)) {
            set.add(key)
            // Background safe update
            state.postTeleportedGeo(set)
        }
    }

    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return state.getTeleportedGeoValue().contains(pubkeyHex.lowercase())
    }

    fun updateParticipant(geohash: String, participantId: String, lastSeen: Date) {
        val shouldRefresh = withLock {
            val participants = geohashParticipants.getOrPut(geohash) { mutableMapOf() }
            participants[participantId] = lastSeen
            currentGeohash == geohash
        }
        if (shouldRefresh) {
            refreshGeohashPeople()
        }
        updateReactiveParticipantCounts()
    }

    /**
     * Sync currently connected P2P peers for a geohash.
     *
     * This keeps participant counts aligned with live mesh connectivity by:
     * - refreshing lastSeen for connected peers
     * - removing disconnected P2P peers immediately
     */
    fun syncConnectedP2PPeers(geohash: String, peerIds: List<String>, observedAt: Date = Date()) {
        val normalizedPeerIds = peerIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { "p2p:$it" }
            .toSet()

        val shouldRefresh = withLock {
            val participants = geohashParticipants.getOrPut(geohash) { mutableMapOf() }

            val iterator = participants.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith("p2p:") && entry.key !in normalizedPeerIds) {
                    iterator.remove()
                }
            }

            normalizedPeerIds.forEach { participantId ->
                participants[participantId] = observedAt
            }

            currentGeohash == geohash
        }

        if (shouldRefresh) {
            refreshGeohashPeople()
        }
        updateReactiveParticipantCounts()
    }

    fun geohashParticipantCount(geohash: String): Int {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val keys = withLock {
            val participants = geohashParticipants[geohash] ?: return@withLock emptyList<String>()
            val it = participants.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.before(cutoff)) it.remove()
            }
            participants.keys.toList()
        }
        if (keys.isEmpty()) return 0
        return keys.count { !dataManager.isGeohashUserBlocked(it) }
    }

    fun refreshGeohashPeople() {
        val geohash = withLock { currentGeohash }
        if (geohash == null) {
            // Use postValue for thread safety - this can be called from background threads
            state.setGeohashPeople(emptyList())
            return
        }
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val (participantsSnapshot, nicknamesSnapshot) = withLock {
            val participants = geohashParticipants.getOrPut(geohash) { mutableMapOf() }
            val it = participants.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.before(cutoff)) it.remove()
            }
            participants.toMap() to geoNicknames.toMap()
        }
        val myHex = try {
            NostrIdentityBridge.deriveIdentity(geohash, application).publicKeyHex
        } catch (_: Exception) { null }
        val myNickname = state.getNicknameValue() ?: "anon"
        // Exclude blocked users and our own Nostr identity from people list.
        val people = participantsSnapshot
            .filterKeys { participantId ->
                !dataManager.isGeohashUserBlocked(participantId) &&
                    (myHex == null || !participantId.equals(myHex, ignoreCase = true))
            }
            .map { (pubkeyHex, lastSeen) ->
            // Detect transport type: P2P participants have "p2p:" prefix
            val isP2P = pubkeyHex.startsWith("p2p:")
            val transport = if (isP2P) TransportType.P2P else TransportType.NOSTR
            
            // Use our actual nickname for self; otherwise use cached nickname
            // For P2P peers without a cached nickname, generate a friendly "anon" name
            val cached = nicknamesSnapshot[pubkeyHex.lowercase()]
            val base = when {
                myHex != null && myHex.equals(pubkeyHex, true) -> myNickname
                cached != null -> cached
                isP2P -> {
                    val peerIdPart = pubkeyHex.removePrefix("p2p:")
                    "anon${peerIdPart.takeLast(4)}"
                }
                else -> "anon"
            }
            GeoPerson(
                id = if (isP2P) pubkeyHex else pubkeyHex.lowercase(),
                displayName = base, // UI can add #hash if necessary
                lastSeen = lastSeen,
                transport = transport
            )
        }
            .sortedWith(
                compareByDescending<GeoPerson> { it.lastSeen.time }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.id }
            )
        // Use postValue for thread safety - this can be called from background threads
        state.setGeohashPeople(people)
    }

    fun updateReactiveParticipantCounts() {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val snapshot = withLock { geohashParticipants.mapValues { it.value.toMap() } }
        val counts = mutableMapOf<String, Int>()
        for ((gh, participants) in snapshot) {
            val active = participants.filterKeys { !dataManager.isGeohashUserBlocked(it) }
                .values.count { !it.before(cutoff) }
            counts[gh] = active
        }
        // Use postValue for thread safety - this can be called from background threads  
        state.setGeohashParticipantCounts(counts)
    }

    fun putNostrKeyMapping(tempKeyOrPeer: String, pubkeyHex: String) {
        withLock { nostrKeyMapping[tempKeyOrPeer] = pubkeyHex }
    }

    fun getNostrKeyMapping(): Map<String, String> = withLock { nostrKeyMapping.toMap() }

    fun displayNameForNostrPubkey(pubkeyHex: String): String {
        val suffix = pubkeyHex.takeLast(4)
        val lower = pubkeyHex.lowercase()
        // Self nickname if matches current identity of current geohash
        val current = withLock { currentGeohash }
        if (current != null) {
            try {
                val my = NostrIdentityBridge.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    return "${state.getNicknameValue()}#$suffix"
                }
            } catch (_: Exception) {}
        }
        val nick = withLock { geoNicknames[lower] } ?: "anon"
        return "$nick#$suffix"
    }

    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val current = withLock { currentGeohash }
        val (participantsSnapshot, nicknamesSnapshot) = withLock {
            val participants = if (current != null) geohashParticipants[current]?.toMap() ?: emptyMap() else emptyMap()
            val nicknames = geoNicknames.toMap()
            participants to nicknames
        }
        val base: String = try {
            if (current != null) {
                val my = NostrIdentityBridge.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    state.getNicknameValue() ?: "anon"
                } else nicknamesSnapshot[lower] ?: "anon"
            } else nicknamesSnapshot[lower] ?: "anon"
        } catch (_: Exception) { nicknamesSnapshot[lower] ?: "anon" }
        if (current == null) return base
        return try {
            val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
            var count = 0
            for ((k, t) in participantsSnapshot) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                if (t.before(cutoff)) continue
                val name = if (k.equals(lower, true)) base else (nicknamesSnapshot[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participantsSnapshot.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }

    /**
     * Get display name for any geohash (not just current one) for header titles
     */
    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val (participantsSnapshot, nicknamesSnapshot) = withLock {
            val participants = geohashParticipants[sourceGeohash]?.toMap() ?: emptyMap()
            val nicknames = geoNicknames.toMap()
            participants to nicknames
        }
        val base = nicknamesSnapshot[lower] ?: "anon"
        return try {
            val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
            var count = 0
            for ((k, t) in participantsSnapshot) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                if (t.before(cutoff)) continue
                val name = if (k.equals(lower, true)) base else (nicknamesSnapshot[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participantsSnapshot.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }
}
