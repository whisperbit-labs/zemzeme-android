package com.roman.zemzeme

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.roman.zemzeme.mesh.BluetoothMeshService
import com.roman.zemzeme.onboarding.AppLockSetupScreen
import com.roman.zemzeme.onboarding.BluetoothCheckScreen
import com.roman.zemzeme.onboarding.BluetoothStatus
import com.roman.zemzeme.onboarding.BluetoothStatusManager
import com.roman.zemzeme.onboarding.BatteryOptimizationManager
import com.roman.zemzeme.onboarding.PermissionType
import com.roman.zemzeme.onboarding.InitializationErrorScreen
import com.roman.zemzeme.onboarding.InitializingScreen
import com.roman.zemzeme.onboarding.LocationCheckScreen
import com.roman.zemzeme.onboarding.LocationStatus
import com.roman.zemzeme.onboarding.LocationStatusManager
import com.roman.zemzeme.onboarding.NetworkStatus
import com.roman.zemzeme.onboarding.NetworkStatusManager
import com.roman.zemzeme.onboarding.OnboardingCoordinator
import com.roman.zemzeme.onboarding.OnboardingState
import com.roman.zemzeme.onboarding.PermissionExplanationScreen
import com.roman.zemzeme.onboarding.PermissionManager
import com.roman.zemzeme.ui.AppLockScreen
import com.roman.zemzeme.ui.ChatScreen
import com.roman.zemzeme.ui.ChatViewModel
import com.roman.zemzeme.ui.HomeScreen
import com.roman.zemzeme.ui.OrientationAwareActivity
import com.roman.zemzeme.ui.theme.ZemzemeTheme
import androidx.activity.compose.BackHandler
import com.roman.zemzeme.nostr.PoWPreferenceManager
import com.roman.zemzeme.security.AppLockManager
import com.roman.zemzeme.security.AppLockPreferenceManager
import com.roman.zemzeme.services.VerificationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : OrientationAwareActivity() {

    private lateinit var permissionManager: PermissionManager
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private lateinit var bluetoothStatusManager: BluetoothStatusManager
    private lateinit var networkStatusManager: NetworkStatusManager
    private lateinit var locationStatusManager: LocationStatusManager
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager

    // True while we are launching a child activity (camera, image picker, audio recorder, etc.).
    // Used in onStop() to distinguish "user left the app" from "we opened a picker".
    private var startingChildActivity = false

    // Tracks permission types that have been requested at least once,
    // so we can detect when Android permanently denied them (won't show dialog again)
    private val previouslyRequestedPermissionTypes = mutableSetOf<PermissionType>()

    // Core mesh service - provided by the foreground service holder
    private lateinit var meshService: BluetoothMeshService
    private val mainViewModel: MainViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels { 
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(application, meshService) as T
            }
        }
    }
    
    private val forceFinishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == com.roman.zemzeme.util.AppConstants.UI.ACTION_FORCE_FINISH) {
                android.util.Log.i("MainActivity", "Received force finish broadcast, closing UI")
                finishAffinity()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register receiver for force finish signal from shutdown coordinator
        val filter = android.content.IntentFilter(com.roman.zemzeme.util.AppConstants.UI.ACTION_FORCE_FINISH)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                forceFinishReceiver,
                filter,
                com.roman.zemzeme.util.AppConstants.UI.PERMISSION_FORCE_FINISH,
                null,
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                forceFinishReceiver,
                filter,
                com.roman.zemzeme.util.AppConstants.UI.PERMISSION_FORCE_FINISH,
                null
            )
        }
        
        // Check if this is a quit request from the notification
        if (intent.getBooleanExtra("ACTION_QUIT_APP", false)) {
            android.util.Log.d("MainActivity", "Quit request received in onCreate, finishing activity")
            finish()
            return
        }

        com.roman.zemzeme.service.AppShutdownCoordinator.cancelPendingShutdown()

        // Initialize app-lock preferences (needed before any lock state check)
        AppLockPreferenceManager.init(applicationContext)
        AppLockManager.initLockState()

        // Enable edge-to-edge display for modern Android look
        enableEdgeToEdge()

        // Initialize permission management
        permissionManager = PermissionManager(this)
        // Ensure foreground service is running and get mesh instance from holder
        try { com.roman.zemzeme.service.MeshForegroundService.start(applicationContext) } catch (_: Exception) { }
        meshService = com.roman.zemzeme.service.MeshServiceHolder.getOrCreate(applicationContext)
        bluetoothStatusManager = BluetoothStatusManager(
            activity = this,
            context = this,
            onBluetoothEnabled = ::handleBluetoothEnabled,
            onBluetoothDisabled = ::handleBluetoothDisabled
        )
        networkStatusManager = NetworkStatusManager(this)
        locationStatusManager = LocationStatusManager(
            activity = this,
            context = this,
            onLocationEnabled = ::handleLocationEnabled,
            onLocationDisabled = ::handleLocationDisabled
        )
        batteryOptimizationManager = BatteryOptimizationManager(
            activity = this,
            context = this,
            onBatteryOptimizationDisabled = {
                Log.d("MainActivity", "Battery optimization disabled by user")
            },
            onBatteryOptimizationFailed = { message ->
                Log.w("MainActivity", "Battery optimization failed: $message")
            }
        )
        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager,
            onOnboardingComplete = ::handleOnboardingComplete,
            onOnboardingFailed = ::handleOnboardingFailed
        )
        
        setContent {
            val isLocked by AppLockManager.isLocked.collectAsState()
            val currentOnboardingState by mainViewModel.onboardingState.collectAsState()

            ZemzemeTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Only render chat content when not locked — prevents flash of content
                        if (!isLocked || currentOnboardingState != OnboardingState.COMPLETE) {
                            OnboardingFlowScreen(modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                            )
                        }
                        if (isLocked && currentOnboardingState == OnboardingState.COMPLETE) {
                            AppLockScreen(
                                activity = this@MainActivity,
                                onUnlocked = { AppLockManager.unlock() }
                            )
                        }
                    }
                }
            }
        }
        
        // Collect state changes in a lifecycle-aware manner
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.onboardingState.collect { state ->
                    handleOnboardingStateChange(state)
                }
            }
        }
        
        // Only start onboarding process if we're in the initial CHECKING state
        // This prevents restarting onboarding on configuration changes
        if (mainViewModel.onboardingState.value == OnboardingState.CHECKING) {
            checkOnboardingStatus()
        }
    }
    
    @Composable
    private fun OnboardingFlowScreen(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val onboardingState by mainViewModel.onboardingState.collectAsState()
        val bluetoothStatus by mainViewModel.bluetoothStatus.collectAsState()
        val locationStatus by mainViewModel.locationStatus.collectAsState()
        val errorMessage by mainViewModel.errorMessage.collectAsState()
        val isBluetoothLoading by mainViewModel.isBluetoothLoading.collectAsState()
        val isLocationLoading by mainViewModel.isLocationLoading.collectAsState()

        DisposableEffect(context, bluetoothStatusManager, networkStatusManager) {

            val receiver = bluetoothStatusManager.monitorBluetoothState(
                context = context,
                bluetoothStatusManager = bluetoothStatusManager,
                onBluetoothStateChanged = { status ->
                    if (status == BluetoothStatus.ENABLED && onboardingState == OnboardingState.BLUETOOTH_CHECK) {
                        checkBluetoothAndProceed()
                    }
                }
            )

            // Start network connectivity monitoring (updates NetworkStatusManager.networkStatusFlow directly)
            networkStatusManager.startMonitoring()

            onDispose {
                try {
                    context.unregisterReceiver(receiver)
                    Log.d("BluetoothStatusUI", "BroadcastReceiver unregistered")
                } catch (e: IllegalStateException) {
                    Log.w("BluetoothStatusUI", "Receiver was not registered")
                }
                networkStatusManager.stopMonitoring()
            }
        }

        when (onboardingState) {
            OnboardingState.PERMISSION_REQUESTING -> {
                InitializingScreen(modifier)
            }
            
            OnboardingState.BLUETOOTH_CHECK -> {
                BluetoothCheckScreen(
                    modifier = modifier,
                    status = bluetoothStatus,
                    onEnableBluetooth = {
                        mainViewModel.updateBluetoothLoading(true)
                        bluetoothStatusManager.requestEnableBluetooth()
                    },
                    onRetry = {
                        checkBluetoothAndProceed()
                    },
                    onSkip = {
                        // Disable BLE in config and proceed (for emulator testing)
                        val p2pConfig = com.roman.zemzeme.p2p.P2PConfig(context)
                        p2pConfig.bleEnabled = false
                        Log.d("MainActivity", "BLE disabled via skip, proceeding without Bluetooth")
                        checkLocationAndProceed()
                    },
                    isLoading = isBluetoothLoading
                )
            }
            
            OnboardingState.LOCATION_CHECK -> {
                LocationCheckScreen(
                    modifier = modifier,
                    status = locationStatus,
                    onEnableLocation = {
                        mainViewModel.updateLocationLoading(true)
                        locationStatusManager.requestEnableLocation()
                    },
                    onRetry = {
                        checkLocationAndProceed()
                    },
                    isLoading = isLocationLoading
                )
            }
            
            OnboardingState.PERMISSION_EXPLANATION -> {
                PermissionExplanationScreen(
                    modifier = modifier,
                    permissionManager = permissionManager,
                    onRequestPermissionForCategory = { category ->
                        previouslyRequestedPermissionTypes.add(category.type)

                        when (category.type) {
                            PermissionType.BATTERY_OPTIMIZATION ->
                                batteryOptimizationManager.requestDisableBatteryOptimization()
                            else -> {
                                if (category.type == PermissionType.BACKGROUND_LOCATION) {
                                    onboardingCoordinator.requestBackgroundLocation()
                                } else {
                                    onboardingCoordinator.requestSpecificPermissions(category.permissions)
                                }
                            }
                        }
                    },
                    onOpenSettings = {
                        onboardingCoordinator.openAppSettings()
                    },
                    onContinue = {
                        mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
                        onboardingCoordinator.startOnboarding()
                    }
                )
            }

            OnboardingState.APP_LOCK_SETUP -> {
                AppLockSetupScreen(
                    modifier = modifier,
                    onComplete = {
                        AppLockPreferenceManager.markSetupPromptShown()
                        AppLockManager.initLockState()
                        mainViewModel.updateOnboardingState(OnboardingState.COMPLETE)
                    }
                )
            }

            OnboardingState.CHECKING, OnboardingState.INITIALIZING, OnboardingState.COMPLETE -> {
                val isLockedInner by AppLockManager.isLocked.collectAsState()

                if (isLockedInner) {
                    // Render nothing while locked — AppLockScreen overlays once state reaches COMPLETE.
                    // This prevents HomeScreen from flashing during the CHECKING/INITIALIZING phase.
                    Box(modifier = modifier)
                } else {
                    val isInChat by mainViewModel.isInChat.collectAsState()

                    if (isInChat) {
                        BackHandler {
                            // Let ChatViewModel handle internal navigation first (close sheets, exit channels)
                            val handled = chatViewModel.handleBackPressed()
                            if (!handled) {
                                chatViewModel.setOnChatScreen(false)
                                mainViewModel.exitChat()
                            }
                        }
                        ChatScreen(
                            viewModel = chatViewModel,
                            isBluetoothEnabled = bluetoothStatus == BluetoothStatus.ENABLED,
                            onBackToHome = { chatViewModel.setOnChatScreen(false); mainViewModel.exitChat() }
                        )
                    } else {
                        HomeScreen(
                            chatViewModel = chatViewModel,
                            onGroupSelected = { chatViewModel.setOnChatScreen(true); mainViewModel.enterChat() },
                            onSettingsClick = { chatViewModel.showAppInfo() },
                            onRefreshAccount = { chatViewModel.panicClearAllData() },
                            onCityChosen = { geohash ->
                                // Reverse geocode the chosen geohash to get city name
                                lifecycleScope.launch {
                                    val cityName = try {
                                        val (lat, lon) = com.roman.zemzeme.geohash.Geohash.decodeToCenter(geohash)
                                        val geocoder = com.roman.zemzeme.geohash.GeocoderFactory.get(context)
                                        val addresses = geocoder.getFromLocation(lat, lon, 1)
                                        addresses.firstOrNull()?.locality
                                            ?: addresses.firstOrNull()?.subAdminArea
                                            ?: addresses.firstOrNull()?.adminArea
                                            ?: addresses.firstOrNull()?.countryName
                                    } catch (_: Exception) { null }
                                    val nickname = cityName ?: geohash
                                    chatViewModel.addGeographicGroup(geohash, nickname)
                                }
                            }
                        )
                    }
                }
            }
            
            OnboardingState.ERROR -> {
                InitializationErrorScreen(
                    modifier = modifier,
                    errorMessage = errorMessage,
                    onRetry = {
                        mainViewModel.updateOnboardingState(OnboardingState.CHECKING)
                        checkOnboardingStatus()
                    },
                    onOpenSettings = {
                        onboardingCoordinator.openAppSettings()
                    }
                )
            }
        }
    }
    
    private fun handleOnboardingStateChange(state: OnboardingState) {

        when (state) {
            OnboardingState.COMPLETE -> {
                // App is fully initialized, mesh service is running
                android.util.Log.d("MainActivity", "Onboarding completed - app ready")
            }
            OnboardingState.ERROR -> {
                android.util.Log.e("MainActivity", "Onboarding error state reached")
            }
            else -> {}
        }
    }
    
    private fun checkOnboardingStatus() {
        Log.d("MainActivity", "Checking onboarding status")
        
        lifecycleScope.launch {
            // Small delay to show the checking state
            delay(500)
            
            // First check Bluetooth status (always required)
            checkBluetoothAndProceed()
        }
    }
    
    /**
     * Check Bluetooth status and proceed with onboarding flow
     */
    private fun checkBluetoothAndProceed() {
        // Log.d("MainActivity", "Checking Bluetooth status")
        
        // For first-time users, skip Bluetooth check and go straight to permissions
        // We'll check Bluetooth after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            Log.d("MainActivity", "First-time launch, skipping Bluetooth check - will check after permissions")
            proceedWithPermissionCheck()
            return
        }
        
        // Developer bypass: Skip Bluetooth check if BLE is disabled in transport config
        // This allows testing P2P/Nostr on emulators without Bluetooth
        val p2pConfig = com.roman.zemzeme.p2p.P2PConfig(this)
        if (!p2pConfig.bleEnabled) {
            Log.d("MainActivity", "BLE disabled in config, skipping Bluetooth check")
            checkLocationAndProceed()
            return
        }
        
        // For existing users, check Bluetooth status first
        bluetoothStatusManager.logBluetoothStatus()
        mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())
        
        when (mainViewModel.bluetoothStatus.value) {
            BluetoothStatus.ENABLED -> {
                // Bluetooth is enabled, check location services next
                checkLocationAndProceed()
            }
            BluetoothStatus.DISABLED -> {
                // Show Bluetooth enable screen (should have permissions as existing user)
                Log.d("MainActivity", "Bluetooth disabled, showing enable screen")
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                // Device doesn't support Bluetooth
                android.util.Log.e("MainActivity", "Bluetooth not supported")
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
        }
    }
    
    /**
     * Proceed with permission checking 
     */
    private fun proceedWithPermissionCheck() {
        Log.d("MainActivity", "Proceeding with permission check")

        lifecycleScope.launch {
            delay(200) // Small delay for smooth transition

            if (permissionManager.isFirstTimeLaunch()) {
                Log.d("MainActivity", "First time launch, showing permission explanation")
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (permissionManager.areRequiredPermissionsGranted()) {
                Log.d("MainActivity", "Existing user with required permissions")
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            } else {
                Log.d("MainActivity", "Existing user missing permissions, showing explanation")
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
        }
    }
    
    /**
     * Handle Bluetooth enabled callback
     */
    private fun handleBluetoothEnabled() {
        Log.d("MainActivity", "Bluetooth enabled by user")
        mainViewModel.updateBluetoothLoading(false)
        mainViewModel.updateBluetoothStatus(BluetoothStatus.ENABLED)
        checkLocationAndProceed()
    }

    /**
     * Check Location services status and proceed with onboarding flow
     */
    private fun checkLocationAndProceed() {
        Log.d("MainActivity", "Checking location services status")
        
        // For first-time users, skip location check and go straight to permissions
        // We'll check location after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            Log.d("MainActivity", "First-time launch, skipping location check - will check after permissions")
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check location status
        locationStatusManager.logLocationStatus()
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())
        
        when (mainViewModel.locationStatus.value) {
            LocationStatus.ENABLED -> {
                // Location services enabled, check battery optimization next
                checkBatteryOptimizationAndProceed()
            }
            LocationStatus.DISABLED -> {
                // Show location enable screen (should have permissions as existing user)
                Log.d("MainActivity", "Location services disabled, showing enable screen")
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            LocationStatus.NOT_AVAILABLE -> {
                // Device doesn't support location services (very unusual)
                Log.e("MainActivity", "Location services not available")
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }

    /**
     * Handle Location enabled callback
     */
    private fun handleLocationEnabled() {
        Log.d("MainActivity", "Location services enabled by user")
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(LocationStatus.ENABLED)
        checkBatteryOptimizationAndProceed()
    }

    /**
     * Handle Location disabled callback
     */
    private fun handleLocationDisabled(message: String) {
        Log.w("MainActivity", "Location services disabled or failed: $message")
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())

        when {
            mainViewModel.locationStatus.value == LocationStatus.NOT_AVAILABLE -> {
                // Show permanent error for devices without location services
                mainViewModel.updateErrorMessage(message)
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
            }
            else -> {
                // Stay on location check screen for retry
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
            }
        }
    }
    
    /**
     * Handle Bluetooth disabled callback
     */
    private fun handleBluetoothDisabled(message: String) {
        Log.w("MainActivity", "Bluetooth disabled or failed: $message")
        mainViewModel.updateBluetoothLoading(false)
        mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())

        // If the app is fully running, stay on ChatScreen — the inline banner will appear
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            return
        }

        when {
            mainViewModel.bluetoothStatus.value == BluetoothStatus.NOT_SUPPORTED -> {
                // Show permanent error for unsupported devices
                mainViewModel.updateErrorMessage(message)
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
            }
            message.contains("Permission") && permissionManager.isFirstTimeLaunch() -> {
                // During first-time onboarding, if Bluetooth enable fails due to permissions,
                // proceed to permission explanation screen where user will grant permissions first
                Log.d("MainActivity", "Bluetooth enable requires permissions, proceeding to permission explanation")
                proceedWithPermissionCheck()
            }
            message.contains("Permission") -> {
                // For existing users, redirect to permission explanation to grant missing permissions
                Log.d("MainActivity", "Bluetooth enable requires permissions, showing permission explanation")
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
            else -> {
                // Stay on Bluetooth check screen for retry
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
            }
        }
    }
    
    private fun handleOnboardingComplete() {
        Log.d("MainActivity", "Onboarding completed, checking Bluetooth and Location before initializing app")

        val p2pConfig = com.roman.zemzeme.p2p.P2PConfig(this)
        val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
        val currentLocationStatus = locationStatusManager.checkLocationStatus()

        // Skip Bluetooth check if BLE is disabled in config (for emulator testing)
        val bluetoothOk = !p2pConfig.bleEnabled || currentBluetoothStatus == BluetoothStatus.ENABLED

        when {
            !bluetoothOk -> {
                Log.d("MainActivity", "Permissions granted, but Bluetooth still disabled. Showing Bluetooth enable screen.")
                mainViewModel.updateBluetoothStatus(currentBluetoothStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
            currentLocationStatus != LocationStatus.ENABLED -> {
                Log.d("MainActivity", "Permissions granted, but Location services still disabled. Showing Location enable screen.")
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            else -> {
                Log.d("MainActivity", "Bluetooth/Location checks passed, proceeding to initialization")
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            }
        }
    }
    
    private fun handleOnboardingFailed(message: String) {
        Log.e("MainActivity", "Onboarding failed: $message")
        mainViewModel.updateErrorMessage(message)
        mainViewModel.updateOnboardingState(OnboardingState.ERROR)
    }
    
    /**
     * Battery optimization is now handled on the permission explanation screen.
     * Just proceed to permission check directly.
     */
    private fun checkBatteryOptimizationAndProceed() {
        proceedWithPermissionCheck()
    }
    
    private fun initializeApp() {
        Log.d("MainActivity", "Starting app initialization")
        
        lifecycleScope.launch {
            try {
                // Initialize the app with a proper delay to ensure Bluetooth stack is ready
                // This solves the issue where app needs restart to work on first install
                delay(1000) // Give the system time to process permission grants
                
                Log.d("MainActivity", "Permissions verified, initializing chat system")
                
                // Initialize PoW preferences early in the initialization process
                PoWPreferenceManager.init(this@MainActivity)
                Log.d("MainActivity", "PoW preferences initialized")
                
                // Initialize Location Notes Manager (extracted to separate file)
                com.roman.zemzeme.nostr.LocationNotesInitializer.initialize(this@MainActivity)
                
                // Ensure all permissions are still granted (user might have revoked in settings)
                if (!permissionManager.areAllPermissionsGranted()) {
                    val missing = permissionManager.getMissingPermissions()
                    Log.w("MainActivity", "Permissions revoked during initialization: $missing")
                    handleOnboardingFailed("Some permissions were revoked. Please grant all permissions to continue.")
                    return@launch
                }

                // Set up mesh service delegate and start services
                meshService.delegate = chatViewModel
                meshService.startServices()
                
                Log.d("MainActivity", "Mesh service started successfully")
                
                // Handle any notification intent
                handleNotificationIntent(intent)
                handleVerificationIntent(intent)
                
                // Small delay to ensure mesh service is fully initialized
                delay(500)
                Log.d("MainActivity", "App initialization complete")
                // Show app lock setup once on first install if not yet configured
                if (!AppLockPreferenceManager.isEnabled() &&
                    !AppLockPreferenceManager.hasShownSetupPrompt()
                ) {
                    mainViewModel.updateOnboardingState(OnboardingState.APP_LOCK_SETUP)
                } else {
                    mainViewModel.updateOnboardingState(OnboardingState.COMPLETE)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to initialize app", e)
                handleOnboardingFailed("Failed to initialize the app: ${e.message}")
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Check if this is a quit request from the notification
        if (intent.getBooleanExtra("ACTION_QUIT_APP", false)) {
            android.util.Log.d("MainActivity", "Quit request received, finishing activity")
            finish()
            return
        }

        com.roman.zemzeme.service.AppShutdownCoordinator.cancelPendingShutdown()
        
        // Handle notification intents when app is already running
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            handleNotificationIntent(intent)
            handleVerificationIntent(intent)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Check Bluetooth and Location status on resume and handle accordingly
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Reattach mesh delegate to new ChatViewModel instance after Activity recreation
            try { meshService.delegate = chatViewModel } catch (_: Exception) { }

            // Check if Bluetooth was disabled while app was backgrounded
            // Skip this check if BLE is disabled in config (for emulator testing)
            val p2pConfig = com.roman.zemzeme.p2p.P2PConfig(this)
            if (p2pConfig.bleEnabled) {
                val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
                if (currentBluetoothStatus != BluetoothStatus.ENABLED) {
                    Log.w("MainActivity", "Bluetooth disabled while app was backgrounded")
                    mainViewModel.updateBluetoothStatus(currentBluetoothStatus)
                    mainViewModel.updateBluetoothLoading(false)
                    // Stay on ChatScreen — the inline BLE banner will inform the user
                    return
                }
            }
            
            // Refresh network + airplane-mode state on resume
            networkStatusManager.refreshStatus()

            // Check if location services were disabled while app was backgrounded
            val currentLocationStatus = locationStatusManager.checkLocationStatus()
            if (currentLocationStatus != LocationStatus.ENABLED) {
                Log.w("MainActivity", "Location services disabled while app was backgrounded")
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }
    
    // Intercept every startActivityForResult call (including those from registerForActivityResult /
    // ActivityResultLauncher) so we know we're opening a child activity, not going to background.
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: android.os.Bundle?) {
        startingChildActivity = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onPause() {
        super.onPause()
        // Only set background state if app is fully initialized
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Detach UI delegate so the foreground service can own DM notifications while UI is closed
            try { meshService.delegate = null } catch (_: Exception) { }
        }
    }

    override fun onStop() {
        super.onStop()
        if (startingChildActivity) {
            // We launched a child activity (camera, image picker, etc.) — do not lock.
            startingChildActivity = false
        } else {
            // User genuinely left the app (Home, Recents, screen-off, etc.) — lock.
            AppLockManager.lock()
        }
    }
    
    /**
     * Handle intents from notification clicks - open specific private chat or geohash chat
     */
    private fun handleNotificationIntent(intent: Intent) {
        val shouldOpenPrivateChat = intent.getBooleanExtra(
            com.roman.zemzeme.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, 
            false
        )
        
        val shouldOpenGeohashChat = intent.getBooleanExtra(
            com.roman.zemzeme.ui.NotificationManager.EXTRA_OPEN_GEOHASH_CHAT,
            false
        )
        
        when {
            shouldOpenPrivateChat -> {
                val peerID = intent.getStringExtra(com.roman.zemzeme.ui.NotificationManager.EXTRA_PEER_ID)
                val senderNickname = intent.getStringExtra(com.roman.zemzeme.ui.NotificationManager.EXTRA_SENDER_NICKNAME)

                if (peerID != null) {
                    Log.d("MainActivity", "Opening private chat with $senderNickname (peerID: $peerID) from notification")

                    // Navigate into chat and open the private chat sheet with this peer
                    chatViewModel.setOnChatScreen(true)
                    mainViewModel.enterChat()
                    chatViewModel.showMeshPeerList()
                    chatViewModel.showPrivateChatSheet(peerID)

                    // Clear notifications for this sender since user is now viewing the chat
                    chatViewModel.clearNotificationsForSender(peerID)
                }
            }
            
            shouldOpenGeohashChat -> {
                val geohash = intent.getStringExtra(com.roman.zemzeme.ui.NotificationManager.EXTRA_GEOHASH)

                if (geohash != null) {
                    Log.d("MainActivity", "Opening geohash chat #$geohash from notification")

                    // Navigate into chat
                    chatViewModel.setOnChatScreen(true)
                    mainViewModel.enterChat()

                    // Switch to the geohash channel - create appropriate geohash channel level
                    val level = when (geohash.length) {
                        7 -> com.roman.zemzeme.geohash.GeohashChannelLevel.BLOCK
                        6 -> com.roman.zemzeme.geohash.GeohashChannelLevel.NEIGHBORHOOD
                        5 -> com.roman.zemzeme.geohash.GeohashChannelLevel.CITY
                        4 -> com.roman.zemzeme.geohash.GeohashChannelLevel.PROVINCE
                        2 -> com.roman.zemzeme.geohash.GeohashChannelLevel.REGION
                        else -> com.roman.zemzeme.geohash.GeohashChannelLevel.CITY // Default fallback
                    }
                    val geohashChannel = com.roman.zemzeme.geohash.GeohashChannel(level, geohash)
                    val channelId = com.roman.zemzeme.geohash.ChannelID.Location(geohashChannel)
                    chatViewModel.selectLocationChannel(channelId)
                    
                    // Update current geohash state for notifications
                    chatViewModel.setCurrentGeohash(geohash)
                    
                    // Clear notifications for this geohash since user is now viewing it
                    chatViewModel.clearNotificationsForGeohash(geohash)
                }
            }
        }
    }

    private fun handleVerificationIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "zemzeme" || uri.host != "verify") return

        chatViewModel.showVerificationSheet()
        val qr = VerificationService.verifyScannedQR(uri.toString())
        if (qr != null) {
            chatViewModel.beginQRVerification(qr)
        }
    }

    
    override fun onDestroy() {
        super.onDestroy()
        
        try { unregisterReceiver(forceFinishReceiver) } catch (_: Exception) { }
        
        // Cleanup location status manager
        try {
            locationStatusManager.cleanup()
            Log.d("MainActivity", "Location status manager cleaned up successfully")
        } catch (e: Exception) {
            Log.w("MainActivity", "Error cleaning up location status manager: ${e.message}")
        }
        
        // Do not stop mesh here; ForegroundService owns lifecycle for background reliability
    }
}
