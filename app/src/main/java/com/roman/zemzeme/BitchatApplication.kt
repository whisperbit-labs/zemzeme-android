package com.roman.zemzeme

import android.app.Application
import com.roman.zemzeme.iconswitch.IconSwitchTaskDescription
import com.roman.zemzeme.nostr.RelayDirectory
import com.roman.zemzeme.ui.theme.ThemePreferenceManager
import com.roman.zemzeme.net.ArtiTorManager

/**
 * Main application class for zemzeme Android
 */
class ZemzemeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.roman.zemzeme.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.roman.zemzeme.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.roman.zemzeme.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.roman.zemzeme.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.roman.zemzeme.nostr.GeohashAliasRegistry.initialize(this)
            com.roman.zemzeme.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize P2P Alias Registry for P2P peer display name persistence
        try {
            com.roman.zemzeme.p2p.P2PAliasRegistry.initialize(this)
            com.roman.zemzeme.p2p.P2PFavoritesRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.roman.zemzeme.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.roman.zemzeme.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // Update recent-apps card with current dynamic icon/name
        registerActivityLifecycleCallbacks(IconSwitchTaskDescription())

        // TorManager already initialized above
    }
}
