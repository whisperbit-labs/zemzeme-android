package com.roman.zemzeme.p2p

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * P2PAliasRegistry
 * 
 * Maps P2P conversation keys (e.g., "p2p:12D3KooW...") to raw peer IDs and display names.
 * Similar to GeohashAliasRegistry but for P2P peers discovered via libp2p DHT.
 */
object P2PAliasRegistry {
    private const val TAG = "P2PAliasRegistry"
    private const val PREFS_NAME = "p2p_alias_registry"
    private const val KEY_PREFIX_RAW = "raw_"
    private const val KEY_PREFIX_DISPLAY = "display_"
    
    // In-memory caches
    // Thread-safe maps (matches BitChat patterns in NostrRelayManager, PeerManager, etc.)
    private val rawPeerIdMap = ConcurrentHashMap<String, String>()
    private val displayNameMap = ConcurrentHashMap<String, String>()
    
    private var prefs: SharedPreferences? = null
    
    /**
     * Initialize the registry with app context for persistence
     */
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        Log.d(TAG, "Initialized with ${rawPeerIdMap.size} raw IDs and ${displayNameMap.size} display names")
    }
    
    private fun loadFromPrefs() {
        prefs?.all?.forEach { (key, value) ->
            if (value is String) {
                when {
                    key.startsWith(KEY_PREFIX_RAW) -> {
                        val convKey = key.removePrefix(KEY_PREFIX_RAW)
                        rawPeerIdMap[convKey] = value
                    }
                    key.startsWith(KEY_PREFIX_DISPLAY) -> {
                        val convKey = key.removePrefix(KEY_PREFIX_DISPLAY)
                        displayNameMap[convKey] = value
                    }
                }
            }
        }
    }
    
    /**
     * Store the raw peer ID for a conversation key
     * @param convKey The conversation key (e.g., "p2p:12D3KooW...")
     * @param rawPeerId The raw libp2p peer ID (without "p2p:" prefix)
     */
    fun put(convKey: String, rawPeerId: String) {
        rawPeerIdMap[convKey] = rawPeerId
        prefs?.edit()?.putString(KEY_PREFIX_RAW + convKey, rawPeerId)?.apply()
        Log.d(TAG, "put($convKey, $rawPeerId)")
    }
    
    /**
     * Get the raw peer ID for a conversation key
     */
    fun get(convKey: String): String? = rawPeerIdMap[convKey]
    
    /**
     * Check if a conversation key exists in the registry
     */
    fun contains(convKey: String): Boolean = rawPeerIdMap.containsKey(convKey)
    
    /**
     * Set the display name for a P2P peer
     */
    fun setDisplayName(convKey: String, name: String) {
        displayNameMap[convKey] = name
        prefs?.edit()?.putString(KEY_PREFIX_DISPLAY + convKey, name)?.apply()
        Log.d(TAG, "setDisplayName($convKey, $name)")
    }
    
    /**
     * Get the display name for a P2P peer
     * Returns null if no name is cached
     */
    fun getDisplayName(convKey: String): String? = displayNameMap[convKey]
    
    /**
     * Get a snapshot of all mappings (for debugging)
     */
    fun snapshot(): Map<String, String> = rawPeerIdMap.toMap()
    
    /**
     * Get all display names (for debugging)
     */
    fun displayNamesSnapshot(): Map<String, String> = displayNameMap.toMap()
    
    /**
     * Clear all registry data
     */
    fun clear() {
        rawPeerIdMap.clear()
        displayNameMap.clear()
        prefs?.edit()?.clear()?.apply()
        Log.d(TAG, "Cleared all data")
    }
}
