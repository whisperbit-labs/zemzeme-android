package com.roman.zemzeme.p2p

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists favorite state for P2P conversation keys.
 */
object P2PFavoritesRegistry {
    private const val TAG = "P2PFavoritesRegistry"
    private const val PREFS_NAME = "p2p_favorites_registry"
    private const val KEY_PREFIX_OUR_FAVORITE = "our_"
    private const val KEY_PREFIX_THEY_FAVORITED_US = "their_"

    private val ourFavorites = ConcurrentHashMap<String, Boolean>()
    private val theyFavoritedUs = ConcurrentHashMap<String, Boolean>()

    private val initLock = Any()
    @Volatile
    private var initialized = false
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
            initialized = true
            Log.d(TAG, "Initialized with ${ourFavorites.size} favorites and ${theyFavoritedUs.size} inbound statuses")
        }
    }

    fun isFavorite(peerID: String): Boolean {
        val key = normalizeConversationKey(peerID)
        return ourFavorites[key] == true
    }

    fun setFavorite(peerID: String, isFavorite: Boolean) {
        val key = normalizeConversationKey(peerID)
        ourFavorites[key] = isFavorite
        prefs?.edit()?.putBoolean(KEY_PREFIX_OUR_FAVORITE + key, isFavorite)?.apply()
    }

    fun didPeerFavoriteUs(peerID: String): Boolean {
        val key = normalizeConversationKey(peerID)
        return theyFavoritedUs[key] == true
    }

    fun setPeerFavoritedUs(peerID: String, favoritedUs: Boolean) {
        val key = normalizeConversationKey(peerID)
        theyFavoritedUs[key] = favoritedUs
        prefs?.edit()?.putBoolean(KEY_PREFIX_THEY_FAVORITED_US + key, favoritedUs)?.apply()
    }

    fun isMutual(peerID: String): Boolean {
        val key = normalizeConversationKey(peerID)
        return (ourFavorites[key] == true) && (theyFavoritedUs[key] == true)
    }

    fun clear() {
        ourFavorites.clear()
        theyFavoritedUs.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private fun normalizeConversationKey(peerID: String): String {
        val trimmed = peerID.trim()
        return if (trimmed.startsWith("p2p:")) trimmed else "p2p:$trimmed"
    }

    private fun loadFromPrefs() {
        val all = prefs?.all ?: return
        for ((rawKey, rawValue) in all) {
            val value = rawValue as? Boolean ?: continue
            when {
                rawKey.startsWith(KEY_PREFIX_OUR_FAVORITE) -> {
                    val key = rawKey.removePrefix(KEY_PREFIX_OUR_FAVORITE)
                    ourFavorites[key] = value
                }
                rawKey.startsWith(KEY_PREFIX_THEY_FAVORITED_US) -> {
                    val key = rawKey.removePrefix(KEY_PREFIX_THEY_FAVORITED_US)
                    theyFavoritedUs[key] = value
                }
            }
        }
    }
}
