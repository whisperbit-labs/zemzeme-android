package com.roman.zemzeme.p2p

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P2P Configuration Manager for Zemzeme
 * 
 * Manages P2P settings including:
 * - Bootstrap nodes (IPFS public + optional custom)
 * - Connection limits
 * - Transport priorities
 * - DHT configuration
 */
class P2PConfig(private val context: Context) {

    /**
     * Snapshot of runtime transport toggles.
     */
    data class TransportToggles(
        val bleEnabled: Boolean,
        val p2pEnabled: Boolean,
        val nostrEnabled: Boolean
    )
    
    companion object {
        private const val TAG = "P2PConfig"
        private const val PREFS_NAME = "p2p_config"
        
        // Default IPFS bootstrap nodes (same as in mobile_go_libp2p)
        val DEFAULT_BOOTSTRAP_NODES = listOf(
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN",
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa",
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmbLHAnMoJPWSCR5Zhtx6BHJX9KiKNN6tpvbUcqanj75Nb",
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt",
            "/ip4/104.131.131.82/tcp/4001/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ",
            "/ip4/104.131.131.82/udp/4001/quic/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"
        )
        
        // Keys for SharedPreferences
        private const val KEY_P2P_ENABLED = "p2p_enabled"
        private const val KEY_NOSTR_ENABLED = "nostr_enabled"
        private const val KEY_BLE_ENABLED = "ble_enabled"
        private const val KEY_CUSTOM_BOOTSTRAP_NODES = "custom_bootstrap_nodes"
        private const val KEY_USE_DEFAULT_BOOTSTRAP = "use_default_bootstrap"
        private const val KEY_CONNECTION_LIMIT = "connection_limit"
        private const val KEY_DHT_SERVER_MODE = "dht_server_mode"
        private const val KEY_PREFER_P2P = "prefer_p2p"
        private const val KEY_AUTO_START = "auto_start"

        private const val DEFAULT_BLE_ENABLED = true
        private const val DEFAULT_P2P_ENABLED = true
        private const val DEFAULT_NOSTR_ENABLED = false

        private val transportFlowLock = Any()
        private var transportPrefs: SharedPreferences? = null
        private var transportListenerRegistered = false

        private val transportKeys = setOf(
            KEY_BLE_ENABLED,
            KEY_P2P_ENABLED,
            KEY_NOSTR_ENABLED
        )

        private val _transportTogglesFlow = MutableStateFlow(
            TransportToggles(
                bleEnabled = DEFAULT_BLE_ENABLED,
                p2pEnabled = DEFAULT_P2P_ENABLED,
                nostrEnabled = DEFAULT_NOSTR_ENABLED
            )
        )
        val transportTogglesFlow: StateFlow<TransportToggles> = _transportTogglesFlow.asStateFlow()

        fun getCurrentTransportToggles(): TransportToggles = _transportTogglesFlow.value

        private val transportPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == null || key in transportKeys) {
                _transportTogglesFlow.value = readTransportToggles(prefs)
            }
        }

        private fun normalizeMutuallyExclusive(toggles: TransportToggles): TransportToggles {
            // P2P and Nostr cannot be enabled at the same time.
            return if (toggles.p2pEnabled && toggles.nostrEnabled) {
                toggles.copy(nostrEnabled = false)
            } else {
                toggles
            }
        }

        private fun readTransportToggles(prefs: SharedPreferences): TransportToggles {
            val raw = TransportToggles(
                bleEnabled = prefs.getBoolean(KEY_BLE_ENABLED, DEFAULT_BLE_ENABLED),
                p2pEnabled = prefs.getBoolean(KEY_P2P_ENABLED, DEFAULT_P2P_ENABLED),
                nostrEnabled = prefs.getBoolean(KEY_NOSTR_ENABLED, DEFAULT_NOSTR_ENABLED)
            )

            val normalized = normalizeMutuallyExclusive(raw)
            if (normalized != raw) {
                prefs.edit().putBoolean(KEY_NOSTR_ENABLED, normalized.nostrEnabled).apply()
                Log.w(TAG, "P2P and Nostr were both enabled; forcing Nostr off")
            }
            return normalized
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val transportToggleWriteLock = Any()

    init {
        registerTransportListenerIfNeeded()
        publishTransportToggles()
    }

    private fun registerTransportListenerIfNeeded() {
        synchronized(transportFlowLock) {
            if (transportPrefs !== prefs) {
                transportPrefs?.let { previous ->
                    runCatching { previous.unregisterOnSharedPreferenceChangeListener(transportPreferenceListener) }
                }
                transportPrefs = prefs
                transportListenerRegistered = false
            }

            if (!transportListenerRegistered) {
                prefs.registerOnSharedPreferenceChangeListener(transportPreferenceListener)
                transportListenerRegistered = true
            }
        }
    }

    private fun publishTransportToggles() {
        _transportTogglesFlow.value = getTransportToggles()
    }

    private fun normalizeMutuallyExclusive(toggles: TransportToggles): TransportToggles {
        return if (toggles.p2pEnabled && toggles.nostrEnabled) {
            toggles.copy(nostrEnabled = false)
        } else {
            toggles
        }
    }

    private fun saveTransportToggles(target: TransportToggles) {
        val normalized = normalizeMutuallyExclusive(target)
        prefs.edit()
            .putBoolean(KEY_BLE_ENABLED, normalized.bleEnabled)
            .putBoolean(KEY_P2P_ENABLED, normalized.p2pEnabled)
            .putBoolean(KEY_NOSTR_ENABLED, normalized.nostrEnabled)
            .apply()
        publishTransportToggles()
    }
    
    // ============== P2P Enabled ==============
    
    /**
     * Whether P2P networking is enabled.
     * Default: true
     */
    var p2pEnabled: Boolean
        get() = getTransportToggles().p2pEnabled
        set(value) {
            synchronized(transportToggleWriteLock) {
                val current = getTransportToggles()
                val target = if (value) {
                    current.copy(p2pEnabled = true, nostrEnabled = false)
                } else {
                    current.copy(p2pEnabled = false)
                }
                saveTransportToggles(target)
                Log.d(TAG, "P2P enabled: ${target.p2pEnabled}, Nostr enabled: ${target.nostrEnabled}")
            }
        }
    
    /**
     * Whether to auto-start P2P when app launches.
     * Default: true
     */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_START, value).apply()
        }
    
    // ============== Nostr Enabled ==============
    
    /**
     * Whether Nostr relay networking is enabled.
     * Disable this to test P2P in isolation.
     * Default: false
     */
    var nostrEnabled: Boolean
        get() = getTransportToggles().nostrEnabled
        set(value) {
            synchronized(transportToggleWriteLock) {
                val current = getTransportToggles()
                val target = if (value) {
                    current.copy(nostrEnabled = true, p2pEnabled = false)
                } else {
                    current.copy(nostrEnabled = false)
                }
                saveTransportToggles(target)
                Log.d(TAG, "Nostr enabled: ${target.nostrEnabled}, P2P enabled: ${target.p2pEnabled}")
            }
        }
    
    // ============== BLE Enabled ==============
    
    /**
     * Whether Bluetooth Low Energy (BLE) mesh is enabled.
     * When disabled, the app will not require Bluetooth and will not use BLE mesh.
     * Default: true
     */
    var bleEnabled: Boolean
        get() = getTransportToggles().bleEnabled
        set(value) {
            synchronized(transportToggleWriteLock) {
                val current = getTransportToggles()
                saveTransportToggles(current.copy(bleEnabled = value))
                Log.d(TAG, "BLE enabled: $value")
            }
        }
    
    // ============== Bootstrap Nodes ==============
    
    /**
     * Whether to use default IPFS bootstrap nodes.
     * Default: true
     */
    var useDefaultBootstrap: Boolean
        get() = prefs.getBoolean(KEY_USE_DEFAULT_BOOTSTRAP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_DEFAULT_BOOTSTRAP, value).apply()
            Log.d(TAG, "Use default bootstrap: $value")
        }
    
    /**
     * Custom bootstrap nodes added by user.
     */
    var customBootstrapNodes: List<String>
        get() {
            val json = prefs.getString(KEY_CUSTOM_BOOTSTRAP_NODES, null)
            return if (json != null) {
                try {
                    gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_BOOTSTRAP_NODES, gson.toJson(value)).apply()
            Log.d(TAG, "Custom bootstrap nodes: ${value.size}")
        }
    
    /**
     * Add a custom bootstrap node.
     */
    fun addBootstrapNode(multiaddr: String): Boolean {
        if (!isValidMultiaddr(multiaddr)) {
            Log.w(TAG, "Invalid multiaddr: $multiaddr")
            return false
        }
        
        val current = customBootstrapNodes.toMutableList()
        if (!current.contains(multiaddr)) {
            current.add(multiaddr)
            customBootstrapNodes = current
            return true
        }
        return false
    }
    
    /**
     * Remove a custom bootstrap node.
     */
    fun removeBootstrapNode(multiaddr: String): Boolean {
        val current = customBootstrapNodes.toMutableList()
        val removed = current.remove(multiaddr)
        if (removed) {
            customBootstrapNodes = current
        }
        return removed
    }
    
    /**
     * Get all active bootstrap nodes (default + custom).
     */
    fun getActiveBootstrapNodes(): List<String> {
        val nodes = mutableListOf<String>()
        
        if (useDefaultBootstrap) {
            nodes.addAll(DEFAULT_BOOTSTRAP_NODES)
        }
        
        nodes.addAll(customBootstrapNodes)
        
        return nodes.distinct()
    }
    
    // ============== Connection Settings ==============
    
    /**
     * Maximum number of P2P connections.
     * Default: 50
     */
    var connectionLimit: Int
        get() = prefs.getInt(KEY_CONNECTION_LIMIT, 50)
        set(value) {
            prefs.edit().putInt(KEY_CONNECTION_LIMIT, value.coerceIn(10, 200)).apply()
        }
    
    /**
     * Whether to run DHT in server mode (better for connectivity, uses more battery).
     * Default: false (client mode for mobile)
     */
    var dhtServerMode: Boolean
        get() = prefs.getBoolean(KEY_DHT_SERVER_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DHT_SERVER_MODE, value).apply()
        }
    
    // ============== Transport Priority ==============
    
    /**
     * Whether to prefer P2P over Nostr when both are available.
     * Default: true
     */
    var preferP2P: Boolean
        get() = prefs.getBoolean(KEY_PREFER_P2P, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PREFER_P2P, value).apply()
        }
    
    /**
     * Get transport priority order.
     *
     * Canonical production order is always:
     * BLE -> P2P -> Nostr
     */
    fun getTransportPriority(): List<TransportType> {
        return listOf(TransportType.BLE, TransportType.P2P, TransportType.NOSTR)
    }

    /**
     * Get current transport toggle state from preferences.
     */
    fun getTransportToggles(): TransportToggles {
        return readTransportToggles(prefs)
    }

    /**
     * Get enabled transports in canonical priority order.
     */
    fun getEnabledTransportPriority(): List<TransportType> {
        val toggles = getTransportToggles()
        return getTransportPriority().filter { transport ->
            when (transport) {
                TransportType.BLE -> toggles.bleEnabled
                TransportType.P2P -> toggles.p2pEnabled
                TransportType.NOSTR -> toggles.nostrEnabled
            }
        }
    }
    
    enum class TransportType {
        BLE,
        P2P,
        NOSTR
    }
    
    // ============== Validation ==============
    
    /**
     * Basic validation for multiaddr format.
     */
    private fun isValidMultiaddr(multiaddr: String): Boolean {
        // Basic validation - must start with / and contain /p2p/ for peer ID
        return multiaddr.startsWith("/") && (
            multiaddr.contains("/p2p/") || 
            multiaddr.contains("/ipfs/") ||
            multiaddr.startsWith("/dnsaddr/")
        )
    }
    
    // ============== Debug ==============
    
    fun getDebugInfo(): String = buildString {
        appendLine("=== P2P Config ===")
        appendLine("P2P Enabled: $p2pEnabled")
        appendLine("Nostr Enabled: $nostrEnabled")
        appendLine("Auto-start: $autoStart")
        appendLine("Use default bootstrap: $useDefaultBootstrap")
        appendLine("Custom bootstrap nodes: ${customBootstrapNodes.size}")
        appendLine("Active bootstrap nodes: ${getActiveBootstrapNodes().size}")
        appendLine("Connection limit: $connectionLimit")
        appendLine("DHT server mode: $dhtServerMode")
        appendLine("Prefer P2P: $preferP2P")
    }
    
    /**
     * Reset all P2P config to defaults.
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        publishTransportToggles()
        Log.d(TAG, "P2P config reset to defaults")
    }
}
