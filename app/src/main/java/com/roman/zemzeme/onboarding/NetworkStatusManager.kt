package com.roman.zemzeme.onboarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings.Global
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages network connectivity state detection and monitoring.
 * Exposes companion-level StateFlows so any composable can collect them directly
 * (same pattern as P2PConfig.transportTogglesFlow).
 *
 * Uses both registerDefaultNetworkCallback AND a CONNECTIVITY_ACTION BroadcastReceiver
 * as fallback — some devices/OEMs don't fire the callback reliably when cellular
 * data is toggled off.
 */
class NetworkStatusManager(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStatusManager"

        private val _networkStatusFlow = MutableStateFlow(NetworkStatus.CONNECTED)
        val networkStatusFlow: StateFlow<NetworkStatus> = _networkStatusFlow.asStateFlow()

        private val _airplaneModeFlow = MutableStateFlow(false)
        val airplaneModeFlow: StateFlow<Boolean> = _airplaneModeFlow.asStateFlow()
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityReceiver: BroadcastReceiver? = null

    init {
        refreshStatus()
    }

    private fun isAirplaneModeOn(): Boolean =
        Global.getInt(context.contentResolver, Global.AIRPLANE_MODE_ON, 0) != 0

    /**
     * Re-evaluate network + airplane-mode state and update the companion flows.
     */
    fun refreshStatus() {
        _airplaneModeFlow.value = isAirplaneModeOn()

        val result = try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                Log.d(TAG, "refreshStatus: DISCONNECTED (no active network)")
                NetworkStatus.DISCONNECTED
            } else {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities == null) {
                    Log.d(TAG, "refreshStatus: DISCONNECTED (no capabilities)")
                    NetworkStatus.DISCONNECTED
                } else {
                    val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

                    if (!hasTransport) {
                        NetworkStatus.DISCONNECTED
                    } else {
                        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                        val status = when {
                            hasInternet && validated -> NetworkStatus.CONNECTED
                            hasInternet -> NetworkStatus.CONNECTED_NO_INTERNET
                            else -> NetworkStatus.DISCONNECTED
                        }
                        Log.d(TAG, "refreshStatus: $status (hasTransport=$hasTransport hasInternet=$hasInternet validated=$validated)")
                        status
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh network status: ${e.message}")
            NetworkStatus.DISCONNECTED
        }
        _networkStatusFlow.value = result
    }

    /**
     * Start monitoring network changes. Call stopMonitoring() to clean up.
     */
    fun startMonitoring() {
        stopMonitoring()

        // Primary: NetworkCallback via registerDefaultNetworkCallback
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                refreshStatus()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                refreshStatus()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                refreshStatus()
            }

            override fun onUnavailable() {
                Log.d(TAG, "Network unavailable")
                refreshStatus()
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            Log.d(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }

        // Fallback: BroadcastReceiver for CONNECTIVITY_ACTION + AIRPLANE_MODE_CHANGED
        // Catches cellular data toggle, airplane mode, and other connectivity changes
        // that registerDefaultNetworkCallback may miss on some devices.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                Log.d(TAG, "Broadcast received: ${intent?.action}")
                refreshStatus()
            }
        }
        try {
            val filter = IntentFilter().apply {
                @Suppress("DEPRECATION")
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            context.registerReceiver(receiver, filter)
            connectivityReceiver = receiver
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register connectivity receiver: ${e.message}")
        }
    }

    /**
     * Stop monitoring network changes. Safe to call multiple times.
     */
    fun stopMonitoring() {
        networkCallback?.let { cb ->
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) { }
        }
        networkCallback = null

        connectivityReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) { }
        }
        connectivityReceiver = null
    }
}
