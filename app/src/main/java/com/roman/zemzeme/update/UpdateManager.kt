package com.roman.zemzeme.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.roman.zemzeme.util.AppConstants
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Represents the current state of the update process.
 */
sealed class UpdateState {
    /** No update available or not checked yet */
    object Idle : UpdateState()
    
    /** Checking server for new version */
    object Checking : UpdateState()
    
    /** New version available, not yet downloaded */
    data class Available(val info: UpdateInfo) : UpdateState()
    
    /** Downloading APK, progress is 0.0-1.0 */
    data class Downloading(val progress: Float, val info: UpdateInfo) : UpdateState()
    
    /** APK downloaded and ready to install */
    data class ReadyToInstall(val info: UpdateInfo, val apkPath: String) : UpdateState()
    
    /** Installation in progress */
    data class Installing(val info: UpdateInfo) : UpdateState()
    
    /** User action required (system dialog shown) */
    data class PendingUserAction(val info: UpdateInfo) : UpdateState()
    
    /** Installation completed successfully */
    object Success : UpdateState()
    
    /** An error occurred */
    data class Error(val message: String) : UpdateState()
}

/**
 * Source of the update.
 */
enum class UpdateSource {
    PLAY_STORE,
    GITHUB
}

/**
 * Information about an available update.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    val forceUpdate: Boolean,
    val source: UpdateSource = UpdateSource.GITHUB
)

/**
 * Manages self-updates for the application using a dual-source strategy:
 * 1. Google Play In-App Updates (primary, for Play Store installs)
 * 2. GitHub Releases API (fallback, for sideloaded APKs)
 * 
 * Features:
 * - Automatic version checking on app launch
 * - Network connectivity checks before requests
 * - Architecture-aware APK selection (arm64, x86, etc.)
 * - Background download with progress
 * - Persistent downloaded APK (survives app restart)
 * - Silent installation on Android 12+ after first approval
 * - Intent-based installation on Android < 12
 * 
 * Usage:
 * ```
 * val manager = UpdateManager.getInstance(context)
 * // Observe state
 * manager.updateState.collect { state -> ... }
 * // Check for updates (call on app launch)
 * manager.checkForUpdate()
 * // Install when ready
 * manager.installUpdate()
 * ```
 */
class UpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        
        /** Action for the update status broadcast */
        const val ACTION_UPDATE_STATUS = "com.bitchat.android.UPDATE_STATUS"
        
        /** Extra key for session ID in the broadcast */
        const val EXTRA_SESSION_ID = "session_id"
        
        /** Preferences file for persisting update state */
        private const val PREFS_NAME = "update_prefs"
        private const val PREF_CACHED_APK_PATH = "cached_apk_path"
        private const val PREF_CACHED_VERSION_CODE = "cached_version_code"
        private const val PREF_CACHED_VERSION_NAME = "cached_version_name"
        private const val PREF_DISMISSED_READY_VERSION_CODE = "dismissed_ready_version_code"
        private const val PREF_LAST_CHECK_TIME = "last_check_time"
        
        @Volatile
        private var INSTANCE: UpdateManager? = null
        
        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        /** Get existing instance or null if not initialized */
        fun getInstanceOrNull(): UpdateManager? = INSTANCE
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
    
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppConstants.Update.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConstants.Update.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppConstants.Update.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    
    /** Play Core App Update Manager for Play Store updates */
    private val playAppUpdateManager: AppUpdateManager by lazy {
        AppUpdateManagerFactory.create(context)
    }
    
    private var currentUpdateInfo: UpdateInfo? = null
    
    /** Job for periodic update checks */
    private var periodicCheckJob: Job? = null
    
    /** Job for current download operation (allows cancellation) */
    private var downloadJob: Job? = null
    
    /** Whether periodic checks are running */
    private var isPeriodicCheckRunning = false
    
    init {
        // Check if we have a cached APK ready to install
        restoreCachedState()
        // Start periodic update checks
        startPeriodicChecks()
    }
    
    /**
     * Start periodic background update checks.
     * Checks immediately on start, then every CHECK_INTERVAL_MS.
     */
    fun startPeriodicChecks() {
        if (isPeriodicCheckRunning) {
            Log.d(TAG, "Periodic checks already running")
            return
        }
        
        isPeriodicCheckRunning = true
        periodicCheckJob = scope.launch {
            Log.i(TAG, "Starting periodic update checks (interval: ${AppConstants.Update.CHECK_INTERVAL_MS / 1000 / 60} minutes)")
            
            while (isActive) {
                // Check if enough time has passed since last check
                val lastCheckTime = prefs.getLong(PREF_LAST_CHECK_TIME, 0)
                val timeSinceLastCheck = System.currentTimeMillis() - lastCheckTime
                
                if (timeSinceLastCheck >= AppConstants.Update.MIN_CHECK_INTERVAL_MS) {
                    Log.d(TAG, "Performing periodic update check")
                    performUpdateCheck()
                    
                    // Record check time
                    prefs.edit().putLong(PREF_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                } else {
                    Log.d(TAG, "Skipping check, last check was ${timeSinceLastCheck / 1000}s ago")
                }
                
                // Wait for next check interval
                delay(AppConstants.Update.CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop periodic background update checks.
     */
    fun stopPeriodicChecks() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
        isPeriodicCheckRunning = false
        Log.i(TAG, "Stopped periodic update checks")
    }
    
    /**
     * Restore cached update state from preferences.
     * If a downloaded APK exists, set state to ReadyToInstall.
     */
    private fun restoreCachedState() {
        val cachedPath = prefs.getString(PREF_CACHED_APK_PATH, null)
        val cachedVersionCode = prefs.getInt(PREF_CACHED_VERSION_CODE, -1)
        val cachedVersionName = prefs.getString(PREF_CACHED_VERSION_NAME, null)
        
        if (cachedPath != null && cachedVersionCode > 0 && cachedVersionName != null) {
            val apkFile = File(cachedPath)
            if (apkFile.exists() && apkFile.length() > 0) {
                // Compare using semantic versioning to match checkForUpdate logic
                // cachedVersionCode is already in semantic format (major*10000+minor*100+patch)
                val currentVersionName = getCurrentVersionName()
                val currentSemanticCode = parseSemanticVersionCode(currentVersionName)
                
                Log.d(TAG, "Cache check: cached=$cachedVersionName ($cachedVersionCode), current=$currentVersionName ($currentSemanticCode)")
                
                if (cachedVersionCode > currentSemanticCode) {
                    Log.d(TAG, "Restored cached update: $cachedVersionName (code $cachedVersionCode)")
                    val info = UpdateInfo(
                        versionCode = cachedVersionCode,
                        versionName = cachedVersionName,
                        apkUrl = "",
                        releaseNotes = "",
                        forceUpdate = false,
                        source = UpdateSource.GITHUB
                    )
                    currentUpdateInfo = info
                    val dismissedVersionCode = prefs.getInt(PREF_DISMISSED_READY_VERSION_CODE, -1)
                    if (dismissedVersionCode == cachedVersionCode) {
                        Log.d(TAG, "Cached update version $cachedVersionCode was dismissed by user; restoring as banner-only ready state")
                    }
                    _updateState.value = UpdateState.ReadyToInstall(info, cachedPath)
                    return
                } else {
                    Log.d(TAG, "Cached APK is same or older version, clearing cache")
                }
            }
        }
        // Clear stale cache
        clearCachedApk()
    }

    private fun hasCachedApkForVersion(versionCode: Int): Boolean {
        val cachedVersionCode = prefs.getInt(PREF_CACHED_VERSION_CODE, -1)
        val cachedPath = prefs.getString(PREF_CACHED_APK_PATH, null)
        if (cachedVersionCode != versionCode || cachedPath.isNullOrBlank()) {
            return false
        }
        return try {
            val cachedFile = File(cachedPath)
            cachedFile.exists() && cachedFile.length() > 0
        } catch (_: Exception) {
            false
        }
    }
    
    /**
     * Get current app version code.
     */
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version code", e)
            0
        }
    }
    
    /**
     * Get current app version name.
     */
    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * DEBUG ONLY: Force fetch and download the latest GitHub release.
     * This skips version comparison and always fetches/downloads the latest.
     */
    fun testShowUpdateDialog() {
        scope.launch {
            Log.d(TAG, "Debug: Forcing GitHub update check and download")
            _updateState.value = UpdateState.Checking
            
            try {
                // Fetch latest GitHub release (ignore version comparison)
                val githubUpdate = fetchGitHubVersionInfo()
                if (githubUpdate == null) {
                    _updateState.value = UpdateState.Error("Failed to fetch GitHub release info")
                    return@launch
                }
                
                Log.d(TAG, "Debug: Found GitHub release ${githubUpdate.versionName}")
                currentUpdateInfo = githubUpdate
                
                // Start download regardless of current version
                _updateState.value = UpdateState.Available(githubUpdate)
                downloadUpdate(githubUpdate)
            } catch (e: Exception) {
                Log.e(TAG, "Debug test failed", e)
                _updateState.value = UpdateState.Error("Test failed: ${e.message}")
            }
        }
    }
    
    /**
     * Check if the app can request package installations.
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }
    
    /**
     * Check if network connectivity is available.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork?.let { network ->
                connectivityManager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } ?: false
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }
    
    /**
     * Check server for available updates.
     * If an update is available, automatically starts downloading.
     */
    fun checkForUpdate() {
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading || 
            currentState is UpdateState.Installing ||
            currentState is UpdateState.ReadyToInstall) {
            Log.d(TAG, "Update already in progress or ready, skipping check")
            return
        }
        
        scope.launch {
            performUpdateCheck()
        }
    }
    
    /**
     * Internal method to perform the actual update check.
     * Called by both checkForUpdate() and periodic checker.
     * 
     * Strategy:
     * 1. Try Play Store In-App Updates first (for Play Store installs)
     * 2. Fall back to GitHub Releases API (for sideloaded APKs)
     */
    private suspend fun performUpdateCheck() {
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading || 
            currentState is UpdateState.Installing ||
            currentState is UpdateState.ReadyToInstall) {
            Log.d(TAG, "Update already in progress or ready, skipping check")
            return
        }
        
        // Check network connectivity first
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No network connectivity, skipping update check")
            return
        }
        
        try {
            _updateState.value = UpdateState.Checking
            
            // Try Play Store first
            val playStoreUpdate = tryPlayStoreCheck()
            if (playStoreUpdate != null) {
                Log.i(TAG, "Play Store update available: ${playStoreUpdate.versionName}")
                currentUpdateInfo = playStoreUpdate
                _updateState.value = UpdateState.Available(playStoreUpdate)
                // Play Store handles its own download flow
                return
            }
            
            // Fallback to GitHub Releases
            Log.d(TAG, "No Play Store update, checking GitHub Releases")
            val githubUpdate = fetchGitHubVersionInfo()
            if (githubUpdate == null) {
                _updateState.value = UpdateState.Idle
                return
            }
            
            val currentVersionCode = getCurrentVersionCode()
            val currentVersionName = getCurrentVersionName()
            
            // Compare using semantic versioning since GitHub uses tag names like "1.7.0"
            // and app versionCode may not match (e.g., app has versionCode=31 for "1.7.0")
            val currentSemanticCode = parseSemanticVersionCode(currentVersionName)
            
            Log.d(TAG, "Version comparison: app=$currentVersionName ($currentSemanticCode), GitHub=${githubUpdate.versionName} (${githubUpdate.versionCode})")
            
            if (githubUpdate.versionCode <= currentSemanticCode) {
                Log.d(TAG, "No update available. Current semantic: $currentSemanticCode, GitHub: ${githubUpdate.versionCode}")
                _updateState.value = UpdateState.Idle
                return
            }

            val dismissedVersionCode = prefs.getInt(PREF_DISMISSED_READY_VERSION_CODE, -1)
            if (dismissedVersionCode == githubUpdate.versionCode && hasCachedApkForVersion(githubUpdate.versionCode)) {
                Log.d(TAG, "Update ${githubUpdate.versionName} was dismissed by user and is cached; skipping re-prompt")
                currentUpdateInfo = githubUpdate
                _updateState.value = UpdateState.Idle
                return
            }

            if (dismissedVersionCode > 0 && githubUpdate.versionCode > dismissedVersionCode) {
                prefs.edit().remove(PREF_DISMISSED_READY_VERSION_CODE).apply()
            }
             
            Log.i(TAG, "GitHub update available: ${githubUpdate.versionName} (${githubUpdate.versionCode})")
            currentUpdateInfo = githubUpdate
            _updateState.value = UpdateState.Available(githubUpdate)
            
            // Auto-start download for GitHub updates
            downloadUpdate(githubUpdate)
            
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            // Don't set error state for background checks - just log and stay idle
            if (_updateState.value is UpdateState.Checking) {
                _updateState.value = UpdateState.Idle
            }
        }
    }
    
    /**
     * Check Play Store for available updates.
     * Returns UpdateInfo if an update is available, null otherwise.
     */
    private suspend fun tryPlayStoreCheck(): UpdateInfo? = suspendCancellableCoroutine { continuation ->
        try {
            val appUpdateInfoTask = playAppUpdateManager.appUpdateInfo
            appUpdateInfoTask.addOnSuccessListener { appUpdateInfo: AppUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                    
                    val versionCode = appUpdateInfo.availableVersionCode()
                    val info = UpdateInfo(
                        versionCode = versionCode,
                        versionName = "Play Store Update",
                        apkUrl = "",
                        releaseNotes = "",
                        forceUpdate = false,
                        source = UpdateSource.PLAY_STORE
                    )
                    continuation.resume(info)
                } else {
                    continuation.resume(null)
                }
            }.addOnFailureListener { e ->
                Log.d(TAG, "Play Store check failed (expected for sideloaded apps): ${e.message}")
                continuation.resume(null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Play Store not available: ${e.message}")
            continuation.resume(null)
        }
    }
    
    /**
     * Fetch version info from GitHub Releases API.
     * Parses tag_name and assets to find the best APK for this device.
     */
    private suspend fun fetchGitHubVersionInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching version info from ${AppConstants.Update.GITHUB_API_URL}")
        
        val request = Request.Builder()
            .url(AppConstants.Update.GITHUB_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "BitChat-Android/${getCurrentVersionName()}")
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            // Handle rate limiting
            if (response.code == 403) {
                val rateLimitRemaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
                if (rateLimitRemaining == 0) {
                    Log.w(TAG, "GitHub API rate limit exceeded, backing off")
                    return@withContext null
                }
            }
            
            if (!response.isSuccessful) {
                Log.e(TAG, "GitHub API request failed: HTTP ${response.code}")
                return@withContext null
            }
            
            val body = response.body?.string() ?: return@withContext null
            
            try {
                val json = JSONObject(body)
                val tagName = json.getString("tag_name")  // e.g., "1.7.0" or "v1.7.0"
                val releaseNotes = json.optString("body", "")
                
                val assets = json.getJSONArray("assets")
                val apkUrl = findBestApkUrl(assets)
                
                if (apkUrl == null) {
                    Log.e(TAG, "No compatible APK found in GitHub release")
                    return@withContext null
                }
                
                // Parse semantic version for comparison
                // Use major*10000 + minor*100 + patch format for proper ordering
                val versionCode = parseSemanticVersionCode(tagName)
                
                UpdateInfo(
                    versionCode = versionCode,
                    versionName = tagName.removePrefix("v"),
                    apkUrl = apkUrl,
                    releaseNotes = releaseNotes,
                    forceUpdate = false,
                    source = UpdateSource.GITHUB
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse GitHub release info", e)
                null
            }
        }
    }
    
    /**
     * Parse semantic version string to comparable version code.
     * Uses format: major*10000 + minor*100 + patch
     * Example: "1.7.0" or "v1.7.0" -> 10700
     * 
     * This matches the versionCode format used in build.gradle.kts
     * for proper comparison with the app's actual version.
     */
    private fun parseSemanticVersionCode(tagName: String): Int {
        return try {
            val version = tagName.removePrefix("v").trim()
            val parts = version.split(".")
            when (parts.size) {
                1 -> parts[0].toInt() * 10000
                2 -> parts[0].toInt() * 10000 + parts[1].toInt() * 100
                else -> parts[0].toInt() * 10000 + parts[1].toInt() * 100 + (parts.getOrNull(2)?.toIntOrNull() ?: 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse version: $tagName", e)
            0
        }
    }
    
    /**
     * Find the best APK URL from GitHub release assets based on device architecture.
     * Priority: exact arch match > universal > null
     */
    private fun findBestApkUrl(assets: JSONArray): String? {
        val deviceAbis = Build.SUPPORTED_ABIS
        val primaryAbi = deviceAbis.firstOrNull() ?: "arm64-v8a"
        
        Log.d(TAG, "Device ABIs: ${deviceAbis.joinToString()}, primary: $primaryAbi")
        
        var universalUrl: String? = null
        
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            
            if (!name.endsWith(".apk")) continue
            
            val url = asset.getString("browser_download_url")
            
            // Check for exact architecture match
            when {
                primaryAbi == "arm64-v8a" && name.contains(AppConstants.Update.APK_ARM64_PATTERN) -> {
                    Log.d(TAG, "Found exact match for arm64-v8a: $name")
                    return url
                }
                primaryAbi == "armeabi-v7a" && name.contains(AppConstants.Update.APK_ARMV7_PATTERN) -> {
                    Log.d(TAG, "Found exact match for armeabi-v7a: $name")
                    return url
                }
                primaryAbi == "x86_64" && name.contains(AppConstants.Update.APK_X86_64_PATTERN) -> {
                    Log.d(TAG, "Found exact match for x86_64: $name")
                    return url
                }
                primaryAbi == "x86" && name.contains(AppConstants.Update.APK_X86_PATTERN) && !name.contains("x86_64") -> {
                    Log.d(TAG, "Found exact match for x86: $name")
                    return url
                }
                name.contains(AppConstants.Update.APK_UNIVERSAL_PATTERN) -> {
                    universalUrl = url
                }
            }
        }
        
        if (universalUrl != null) {
            Log.d(TAG, "Using universal APK as fallback")
        }
        
        return universalUrl
    }
    
    /**
     * Download the update APK.
     */
    private suspend fun downloadUpdate(info: UpdateInfo) {
        // Check network before download
        if (!isNetworkAvailable()) {
            Log.e(TAG, "No network connectivity, cannot download update")
            _updateState.value = UpdateState.Error("No network connection")
            return
        }
        
        // Cancel any existing download
        downloadJob?.cancel()
        
        downloadJob = scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(0f, info)
                
                val apkFile = downloadApk(info.apkUrl) { progress ->
                    _updateState.value = UpdateState.Downloading(progress, info)
                }
                
                // Cache the downloaded APK info
                prefs.edit()
                    .putString(PREF_CACHED_APK_PATH, apkFile.absolutePath)
                    .putInt(PREF_CACHED_VERSION_CODE, info.versionCode)
                    .putString(PREF_CACHED_VERSION_NAME, info.versionName)
                    .remove(PREF_DISMISSED_READY_VERSION_CODE)
                    .apply()
                
                _updateState.value = UpdateState.ReadyToInstall(info, apkFile.absolutePath)
                Log.i(TAG, "Update downloaded and ready to install: ${info.versionName}")
                
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Download cancelled")
                    _updateState.value = UpdateState.Idle
                } else {
                    Log.e(TAG, "Download failed", e)
                    _updateState.value = UpdateState.Error(e.message ?: "Download failed")
                }
            }
        }
    }
    
    /**
     * Cancel the current download if in progress.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _updateState.value = UpdateState.Idle
        Log.d(TAG, "Download cancelled by user")
    }
    
    /**
     * Download APK from the given URL with proper resource management.
     * Supports resume of partial downloads using HTTP Range header.
     */
    private suspend fun downloadApk(
        url: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        Log.d(TAG, "Downloading APK from $url")
        
        // Check for existing partial download
        val apkFile = File(context.filesDir, "pending_update.apk")
        val existingBytes = if (apkFile.exists()) apkFile.length() else 0L
        
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "BitChat-Android/${getCurrentVersionName()}")
        
        // Add Range header for resume if we have partial data
        if (existingBytes > 0) {
            Log.d(TAG, "Resuming download from byte $existingBytes")
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            // Check if server supports range requests
            val isResuming = response.code == 206  // Partial Content
            
            if (!response.isSuccessful && response.code != 206) {
                // If resume failed, delete partial and start fresh
                if (existingBytes > 0) {
                    Log.w(TAG, "Resume not supported (HTTP ${response.code}), starting fresh download")
                    apkFile.delete()
                    // Retry without Range header
                    return@withContext downloadApkFresh(url, apkFile, onProgress)
                }
                throw IOException("Download failed: HTTP ${response.code}")
            }
            
            val body = response.body ?: throw IOException("Empty response body")
            
            // Calculate total size
            val contentLength = body.contentLength()
            val totalSize = if (isResuming) {
                existingBytes + contentLength
            } else {
                contentLength
            }
            
            Log.d(TAG, "Download: existing=$existingBytes, new=$contentLength, total=$totalSize, resuming=$isResuming")
            
            // If not resuming, delete existing file
            if (!isResuming && apkFile.exists()) {
                apkFile.delete()
            }
            
            // Append to existing file if resuming, otherwise create new
            FileOutputStream(apkFile, isResuming).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = existingBytes
                    var read: Int
                    
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        
                        if (totalSize > 0) {
                            val progress = bytesRead.toFloat() / totalSize.toFloat()
                            onProgress(progress.coerceIn(0f, 1f))
                        } else {
                            onProgress(-1f)
                        }
                    }
                }
            }
            
            Log.d(TAG, "Downloaded APK to ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            apkFile
        }
    }
    
    /**
     * Fresh download without resume (fallback when Range not supported).
     */
    private suspend fun downloadApkFresh(
        url: String,
        apkFile: File,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BitChat-Android/${getCurrentVersionName()}")
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            
            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            
            FileOutputStream(apkFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int
                    
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        
                        if (contentLength > 0) {
                            val progress = bytesRead.toFloat() / contentLength.toFloat()
                            onProgress(progress.coerceIn(0f, 1f))
                        } else {
                            onProgress(-1f)
                        }
                    }
                }
            }
            
            Log.d(TAG, "Downloaded APK to ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            apkFile
        }
    }
    
    /**
     * Install the downloaded update.
     * Call this when user confirms they want to install.
     */
    fun installUpdate() {
        val state = _updateState.value
        val apkPath = when (state) {
            is UpdateState.ReadyToInstall -> state.apkPath
            else -> {
                Log.e(TAG, "Cannot install: no update ready. Current state: $state")
                return
            }
        }
        
        val info = currentUpdateInfo ?: return
        
        // Check install permission first
        if (!canRequestPackageInstalls()) {
            Log.e(TAG, "App does not have permission to install packages")
            _updateState.value = UpdateState.Error("Install permission required")
            return
        }
        
        scope.launch {
            try {
                _updateState.value = UpdateState.Installing(info)
                installApk(File(apkPath))
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed", e)
                _updateState.value = UpdateState.Error(e.message ?: "Installation failed")
            }
        }
    }
    
    /**
     * Legacy method for debug settings - downloads and installs immediately.
     */
    fun checkAndInstallUpdate() {
        val state = _updateState.value
        if (state is UpdateState.ReadyToInstall) {
            // If already downloaded, just install
            installUpdate()
        } else {
            // Otherwise check and download first
            checkForUpdate()
        }
    }
    
    /**
     * Install the APK based on Android version.
     * - Android 12+: PackageInstaller (silent after first approval)
     * - Android < 12: Intent.ACTION_VIEW (requires user interaction)
     */
    private suspend fun installApk(apkFile: File) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Installing APK: ${apkFile.absolutePath}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installViaPackageInstaller(apkFile)
        } else {
            withContext(Dispatchers.Main) {
                installViaIntent(apkFile)
            }
        }
    }
    
    /**
     * Install APK using PackageInstaller API (Android 12+).
     * Supports silent updates after first user approval.
     */
    private fun installViaPackageInstaller(apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        
        params.setAppPackageName(context.packageName)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        
        val sessionId = packageInstaller.createSession(params)
        Log.d(TAG, "Created install session: $sessionId")
        
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }
            
            val intent = Intent(context, UpdateReceiver::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            Log.d(TAG, "Committing install session")
            session.commit(pendingIntent.intentSender)
        }
    }
    
    /**
     * Install APK using Intent (Android < 12).
     * Requires FileProvider for content:// URI.
     */
    private fun installViaIntent(apkFile: File) {
        Log.d(TAG, "Installing via Intent (legacy method)")
        
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        context.startActivity(intent)
        
        // Mark as pending user action since Intent requires user to tap "Install"
        currentUpdateInfo?.let {
            _updateState.value = UpdateState.PendingUserAction(it)
        }
    }
    
    /**
     * Called by UpdateReceiver when installation status is received.
     */
    internal fun onInstallStatusReceived(status: Int, message: String?) {
        Log.d(TAG, "Install status received: $status, message: $message")
        
        val info = currentUpdateInfo
        
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed successfully")
                clearCachedApk()
                _updateState.value = UpdateState.Success
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "User action required for installation")
                if (info != null) {
                    _updateState.value = UpdateState.PendingUserAction(info)
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val errorMsg = message ?: "Installation failed (status: $status)"
                Log.e(TAG, "Update failed: $errorMsg")
                _updateState.value = UpdateState.Error(errorMsg)
            }
            else -> {
                Log.w(TAG, "Unknown install status: $status")
                _updateState.value = UpdateState.Error("Unknown status: $status")
            }
        }
    }
    
    /**
     * Clear cached APK and preferences.
     */
    private fun clearCachedApk() {
        val cachedPath = prefs.getString(PREF_CACHED_APK_PATH, null)
        if (cachedPath != null) {
            File(cachedPath).delete()
        }
        prefs.edit()
            .remove(PREF_CACHED_APK_PATH)
            .remove(PREF_CACHED_VERSION_CODE)
            .remove(PREF_CACHED_VERSION_NAME)
            .remove(PREF_DISMISSED_READY_VERSION_CODE)
            .apply()
    }
    
    /**
     * Dismiss the update (user chose not to install now).
     * The downloaded APK remains cached for later.
     */
    fun dismissUpdate() {
        val state = _updateState.value
        when (state) {
            is UpdateState.ReadyToInstall -> {
                Log.d(TAG, "Update dismissed by user, APK cached for later")
                prefs.edit().putInt(PREF_DISMISSED_READY_VERSION_CODE, state.info.versionCode).apply()
                _updateState.value = UpdateState.Idle
            }
            is UpdateState.PendingUserAction -> {
                Log.d(TAG, "User action cancelled, deferring update")
                currentUpdateInfo?.let {
                    prefs.edit().putInt(PREF_DISMISSED_READY_VERSION_CODE, it.versionCode).apply()
                }
                _updateState.value = UpdateState.Idle
            }
            is UpdateState.Error -> {
                Log.d(TAG, "Error dismissed, resetting to idle")
                _updateState.value = UpdateState.Idle
            }
            else -> {
                // No action needed for other states
            }
        }
    }

    /**
     * Returns true when the ready-to-install dialog should stay hidden for this version.
     *
     * The update remains installable from the compact banner.
     */
    fun isReadyDialogDismissed(versionCode: Int): Boolean {
        return prefs.getInt(PREF_DISMISSED_READY_VERSION_CODE, -1) == versionCode
    }
    
    /**
     * Check if there's a cached update ready to install.
     */
    fun hasCachedUpdate(): Boolean {
        return _updateState.value is UpdateState.ReadyToInstall
    }
    
    /**
     * Reset state to Idle and clear any errors.
     */
    fun resetState() {
        _updateState.value = UpdateState.Idle
    }
}
