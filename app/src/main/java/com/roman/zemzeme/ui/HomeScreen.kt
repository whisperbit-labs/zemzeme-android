package com.roman.zemzeme.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roman.zemzeme.geohash.ChannelID
import com.roman.zemzeme.geohash.GeohashChannelLevel
import com.roman.zemzeme.geohash.LocationChannelManager
import com.roman.zemzeme.model.ZemzemeMessage
import androidx.compose.ui.res.stringResource
import com.roman.zemzeme.BuildConfig
import com.roman.zemzeme.R
import com.roman.zemzeme.nostr.NostrRelayManager
import com.roman.zemzeme.p2p.P2PConfig
import com.roman.zemzeme.p2p.TopicConnectionState
import com.roman.zemzeme.ui.connectioninfo.ConnectionDisplayData
import com.roman.zemzeme.ui.connectioninfo.ConnectionIndicatorFactory
import com.roman.zemzeme.ui.theme.extendedColors
import java.text.SimpleDateFormat
import java.util.Locale

// ── State holders for long-press flow ──

private data class ActionTarget(
    val type: String,   // "group" | "dm"
    val key: String,
    val displayName: String
)

private enum class PendingAction { DELETE, CLEAR, RENAME }

// ── HomeScreen ──

@Composable
fun HomeScreen(
    chatViewModel: ChatViewModel,
    onGroupSelected: () -> Unit,
    onSettingsClick: () -> Unit,
    onRefreshAccount: () -> Unit,
    onCityChosen: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val extended = MaterialTheme.extendedColors
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current

    // Observed state
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val customGroups by chatViewModel.customGroups.collectAsStateWithLifecycle()
    val geographicGroups by chatViewModel.geographicGroups.collectAsStateWithLifecycle()
    val groupNicknames by chatViewModel.groupNicknames.collectAsStateWithLifecycle()
    val channelMessages by chatViewModel.channelMessages.collectAsStateWithLifecycle()
    val unreadChannelMessages by chatViewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val unreadMeshCount by chatViewModel.unreadMeshCount.collectAsStateWithLifecycle()
    val nickname by chatViewModel.nickname.collectAsStateWithLifecycle()
    val myPeerID = chatViewModel.myPeerID
    val contacts by chatViewModel.contacts.collectAsStateWithLifecycle()
    val privateChats by chatViewModel.privateChats.collectAsStateWithLifecycle()
    val unreadPrivateMessages by chatViewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val peerNicknames by chatViewModel.peerNicknames.collectAsStateWithLifecycle()
    val p2pTopicStates by chatViewModel.p2pTopicStates.collectAsStateWithLifecycle()
    val geohashParticipantCounts by chatViewModel.geohashParticipantCounts.collectAsStateWithLifecycle()
    val connectedPeers by chatViewModel.connectedPeers.collectAsStateWithLifecycle()
    val isConnected by chatViewModel.isConnected.collectAsStateWithLifecycle()

    // Transport state for computing per-group connection status
    val transportConfig = remember { P2PConfig(context) }
    val transportToggles by remember(transportConfig) {
        P2PConfig.transportTogglesFlow
    }.collectAsStateWithLifecycle()
    val nostrRelayManager = remember { NostrRelayManager.getInstance(context) }
    val nostrConnected by nostrRelayManager.isConnected.collectAsStateWithLifecycle()
    val nostrRelays by nostrRelayManager.relays.collectAsStateWithLifecycle()
    val nostrIsUp = transportToggles.nostrEnabled &&
        (nostrConnected || nostrRelays.any { it.isConnected })

    val locationChannelManager = remember {
        try { LocationChannelManager.getInstance(context) } catch (_: Exception) { null }
    }

    // Trigger location resolution so availableChannels populates
    LaunchedEffect(locationChannelManager) {
        locationChannelManager?.enableLocationChannels()
    }

    val locationNames by locationChannelManager?.locationNames?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyMap()) }
    val availableChannels by locationChannelManager?.availableChannels?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList()) }

    // FAB
    var fabExpanded by remember { mutableStateOf(false) }
    BackHandler(enabled = fabExpanded) { fabExpanded = false }

    // Create / Join / Scan dialogs
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    // Long-press action sheet → confirmation flow
    var actionTarget by remember { mutableStateOf<ActionTarget?>(null) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var pendingActionTarget by remember { mutableStateOf<ActionTarget?>(null) }

    // Refresh account confirmation dialog
    var showRefreshDialog by remember { mutableStateOf(false) }

    // Mesh intro dialog (first-time only)
    val meshIntroPrefs = remember { context.getSharedPreferences("mesh_intro", android.content.Context.MODE_PRIVATE) }
    var showMeshIntroDialog by remember { mutableStateOf(false) }

    // GeohashPicker launcher
    val geohashPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(GeohashPickerActivity.EXTRA_RESULT_GEOHASH)
                ?.let { onCityChosen(it) }
        }
    }

    // Geographic group
    val geoChannel = availableChannels.firstOrNull { it.level == GeohashChannelLevel.CITY }
        ?: availableChannels.firstOrNull { it.level == GeohashChannelLevel.NEIGHBORHOOD }
    val geoName = locationNames[GeohashChannelLevel.CITY]
        ?: locationNames[GeohashChannelLevel.NEIGHBORHOOD]

    // Geographic groups sorted by last-message time
    val geoGroupItems = remember(geographicGroups, channelMessages) {
        geographicGroups.map { geohash ->
            val lastTs = channelMessages["geo:$geohash"]?.lastOrNull()?.timestamp?.time ?: 0L
            Triple("group", geohash, lastTs)
        }.sortedByDescending { it.third }
    }

    // "My Groups" = custom groups only, sorted by last-message time
    val myGroupItems = remember(customGroups, channelMessages) {
        customGroups.map { geohash ->
            val lastTs = channelMessages["geo:$geohash"]?.lastOrNull()?.timestamp?.time ?: 0L
            Triple("group", geohash, lastTs)
        }.sortedByDescending { it.third }
    }

    // "Contacts" = persisted peers, sorted by last-message time (contacts without messages shown last)
    val contactItems = remember(contacts, privateChats) {
        contacts.map { peerID ->
            val msgs = privateChats[peerID]
            val lastMsg = msgs?.lastOrNull()
            Triple(peerID, lastMsg, lastMsg?.timestamp?.time ?: 0L)
        }.sortedByDescending { it.third }
    }

    // Helper: compute connection data for a geohash group
    fun geohashConnectionData(geohash: String): ConnectionDisplayData.ChannelConnection {
        val topicState = p2pTopicStates[geohash]
        val p2pPeers = topicState?.peers?.size ?: 0
        val nostrPeers = geohashParticipantCounts[geohash] ?: 0
        val totalPeers = maxOf(p2pPeers, nostrPeers)
        val p2pConnected = transportToggles.p2pEnabled && p2pPeers > 0

        val stage = when {
            p2pConnected || (nostrIsUp && totalPeers > 0) ->
                ConnectionDisplayData.ChannelStage.CONNECTED
            transportToggles.p2pEnabled && (topicState?.providerCount ?: 0) > 0 ->
                ConnectionDisplayData.ChannelStage.CONNECTING
            transportToggles.p2pEnabled ->
                ConnectionDisplayData.ChannelStage.SEARCHING
            nostrIsUp && totalPeers == 0 ->
                ConnectionDisplayData.ChannelStage.NOSTR_IDLE
            transportToggles.nostrEnabled ->
                ConnectionDisplayData.ChannelStage.SEARCHING
            else ->
                ConnectionDisplayData.ChannelStage.ERROR
        }

        return ConnectionDisplayData.ChannelConnection(
            stage = stage,
            peerCount = totalPeers,
            error = topicState?.error
        )
    }

    // ── Scaffold ──

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.background)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: title + nickname subtitle (matching LocationChannelsButton layout)
                        Column(
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Text(
                                "Zemzeme",
                                fontSize = 22.sp,
                                fontFamily = NunitoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                            Text(
                                "@${nickname.ifEmpty { myPeerID.take(8) }}",
                                fontSize = 12.sp,
                                color = colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Right: refresh + settings
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            IconButton(
                                onClick = { showRefreshDialog = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    contentDescription = stringResource(R.string.cd_refresh_account),
                                    tint = colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            IconButton(
                                onClick = onSettingsClick,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = stringResource(R.string.cd_settings),
                                    tint = colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    color = colorScheme.outline.copy(alpha = 0.3f)
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(fabExpanded, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FabMenuItem(stringResource(R.string.fab_scan_qr), Icons.Outlined.QrCodeScanner, colorScheme.tertiaryContainer) {
                            fabExpanded = false; showQrScanner = true
                        }
                        val canAdd = customGroups.size + geographicGroups.size < ChatViewModel.MAX_P2P_GROUPS
                        FabMenuItem(stringResource(R.string.fab_choose_city), Icons.Outlined.LocationCity,
                            if (canAdd) colorScheme.tertiaryContainer else colorScheme.surfaceVariant
                        ) {
                            if (canAdd) {
                                fabExpanded = false
                                geohashPickerLauncher.launch(Intent(context, GeohashPickerActivity::class.java))
                            }
                        }
                        FabMenuItem(stringResource(R.string.fab_join_group), Icons.Outlined.GroupAdd,
                            if (canAdd) colorScheme.secondaryContainer else colorScheme.surfaceVariant
                        ) {
                            if (canAdd) { fabExpanded = false; showJoinDialog = true }
                        }
                        FabMenuItem(stringResource(R.string.fab_create_group), Icons.Outlined.Group,
                            if (canAdd) colorScheme.primaryContainer else colorScheme.surfaceVariant
                        ) {
                            if (canAdd) { fabExpanded = false; showCreateDialog = true }
                        }
                    }
                }
                FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                    Icon(
                        if (fabExpanded) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = null
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // ── NEARBY ──
            item(key = "nearby_header") { SectionHeader(stringResource(R.string.section_nearby)) }

            item(key = "mesh") {
                val meshPeerCount = connectedPeers.size
                val meshConnectionData = ConnectionDisplayData.ChannelConnection(
                    stage = when {
                        meshPeerCount > 0 -> ConnectionDisplayData.ChannelStage.CONNECTED
                        isConnected -> ConnectionDisplayData.ChannelStage.SEARCHING
                        else -> ConnectionDisplayData.ChannelStage.SEARCHING
                    },
                    peerCount = meshPeerCount
                )
                GroupRow(
                    name = "Bluetooth",
                    lastMessage = messages.lastOrNull(),
                    avatarColor = extended.electricCyan,
                    icon = Icons.Outlined.Hub,
                    myPeerID = myPeerID,
                    myNickname = nickname,
                    unreadCount = unreadMeshCount,
                    connectionData = meshConnectionData,
                    onClick = {
                        if (!meshIntroPrefs.getBoolean("dismissed", false)) {
                            showMeshIntroDialog = true
                        } else {
                            chatViewModel.navigateToMesh(); onGroupSelected()
                        }
                    },
                    onLongPress = { actionTarget = ActionTarget("mesh", "mesh", "Bluetooth") }
                )
            }

            item(key = "geo") {
                val ready = geoChannel != null
                val pulse = rememberInfiniteTransition(label = "geo_pulse")
                val alpha by pulse.animateFloat(
                    1f, 0.3f,
                    infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "geo_alpha"
                )
                val geoDisplayName = if (ready) (geoName ?: geoChannel!!.displayName) else "Locating..."
                val geoLastMsg = if (ready) channelMessages["geo:${geoChannel!!.geohash}"]?.lastOrNull() else null
                GroupRow(
                    name = geoDisplayName,
                    lastMessage = geoLastMsg,
                    avatarColor = extended.electricCyan,
                    icon = Icons.Outlined.LocationOn,
                    myPeerID = myPeerID,
                    myNickname = nickname,
                    unreadCount = if (ready) (unreadChannelMessages["geo:${geoChannel!!.geohash}"] ?: 0) else 0,
                    connectionData = if (ready) geohashConnectionData(geoChannel!!.geohash) else null,
                    rowAlpha = if (ready) 1f else alpha,
                    onClick = {
                        if (ready) {
                            chatViewModel.navigateToLocationChannel(ChannelID.Location(geoChannel!!))
                            onGroupSelected()
                        }
                    },
                    onLongPress = if (ready) {{ actionTarget = ActionTarget("location", geoChannel!!.geohash, geoDisplayName) }} else null
                )
            }

            // ── GEOGRAPHIC GROUPS ──
            if (geoGroupItems.isNotEmpty()) {
                item(key = "geogroups_header") { SectionHeader(stringResource(R.string.section_geographic_groups)) }

                items(geoGroupItems, key = { "geo_${it.second}" }) { (_, key, _) ->
                    val nick = groupNicknames[key]
                    val display = nick ?: key
                    val groupLastMsg = channelMessages["geo:$key"]?.lastOrNull()
                    GroupRow(
                        name = display,
                        lastMessage = groupLastMsg,
                        avatarColor = extended.solarOrange,
                        icon = Icons.Outlined.Map,
                        myPeerID = myPeerID,
                        myNickname = nickname,
                        unreadCount = unreadChannelMessages["geo:$key"] ?: 0,
                        connectionData = geohashConnectionData(key),
                        onClick = { chatViewModel.navigateToGeohashGroup(key); onGroupSelected() },
                        onLongPress = { actionTarget = ActionTarget("geographic", key, display) }
                    )
                }
            }

            // ── MY GROUPS ──
            if (myGroupItems.isNotEmpty()) {
                item(key = "mygroups_header") { SectionHeader(stringResource(R.string.section_my_groups)) }

                items(myGroupItems, key = { "group_${it.second}" }) { (_, key, _) ->
                    val nick = groupNicknames[key]
                    val display = nick ?: key
                    val groupLastMsg = channelMessages["geo:$key"]?.lastOrNull()
                    GroupRow(
                        name = display,
                        lastMessage = groupLastMsg,
                        avatarColor = extended.neonPurple,
                        myPeerID = myPeerID,
                        myNickname = nickname,
                        unreadCount = unreadChannelMessages["geo:$key"] ?: 0,
                        connectionData = geohashConnectionData(key),
                        onClick = { chatViewModel.navigateToGeohashGroup(key); onGroupSelected() },
                        onLongPress = { actionTarget = ActionTarget("group", key, display) }
                    )
                }
            }

            // ── CONTACTS ──
            if (contactItems.isNotEmpty()) {
                item(key = "contacts_header") { SectionHeader(stringResource(R.string.section_contacts)) }

                items(contactItems, key = { "dm_${it.first}" }) { (peerID, lastMsg, _) ->
                    val displayName = peerNicknames[peerID]
                        ?: com.roman.zemzeme.p2p.P2PAliasRegistry.getDisplayName(peerID)
                        ?: "User"
                    val unread = if (peerID in unreadPrivateMessages) 1 else 0
                    GroupRow(
                        name = displayName,
                        lastMessage = lastMsg,
                        avatarColor = extended.contactGreen,
                        myPeerID = myPeerID,
                        myNickname = nickname,
                        unreadCount = unread,
                        onClick = {
                            chatViewModel.navigateToContact(peerID)
                            onGroupSelected()
                        },
                        onLongPress = { actionTarget = ActionTarget("dm", peerID, displayName) }
                    )
                }
            }

        }
    }

    // ── Action sheet (centered dialog) ──

    actionTarget?.let { target ->
        GroupActionSheet(
            groupName = target.displayName,
            showDelete = target.type != "mesh" && target.type != "location",
            deleteLabel = if (target.type == "dm") stringResource(R.string.dialog_delete_chat) else stringResource(R.string.dialog_delete_group),
            showRename = target.type == "group",
            onRename = {
                pendingActionTarget = target
                actionTarget = null
                pendingAction = PendingAction.RENAME
            },
            onDelete = {
                pendingActionTarget = target
                actionTarget = null
                pendingAction = PendingAction.DELETE
            },
            onClear = {
                pendingActionTarget = target
                actionTarget = null
                pendingAction = PendingAction.CLEAR
            },
            onDismiss = { actionTarget = null }
        )
    }

    // ── Confirmation dialogs ──

    if (pendingAction == PendingAction.DELETE && pendingActionTarget != null) {
        val target = pendingActionTarget!!
        ConfirmationDialog(
            title = if (target.type == "dm") stringResource(R.string.dialog_delete_chat) else stringResource(R.string.dialog_delete_group),
            body = stringResource(R.string.dialog_delete_confirm_body, target.displayName),
            confirmLabel = stringResource(R.string.action_delete),
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                when (target.type) {
                    "group" -> chatViewModel.removeGroup(target.key)
                    "geographic" -> chatViewModel.removeGeographicGroup(target.key)
                    "dm" -> chatViewModel.removeContact(target.key)
                }
                pendingAction = null
                pendingActionTarget = null
            },
            onDismiss = { pendingAction = null; pendingActionTarget = null }
        )
    }

    if (pendingAction == PendingAction.CLEAR && pendingActionTarget != null) {
        val target = pendingActionTarget!!
        ConfirmationDialog(
            title = stringResource(R.string.dialog_clear_chat),
            body = stringResource(R.string.dialog_clear_confirm_body, target.displayName),
            confirmLabel = stringResource(R.string.action_clear),
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                when (target.type) {
                    "mesh" -> chatViewModel.clearMeshHistory()
                    "location" -> chatViewModel.clearGeohashHistory(target.key)
                    "group" -> chatViewModel.clearGeohashHistory(target.key)
                    "geographic" -> chatViewModel.clearGeohashHistory(target.key)
                    "dm" -> chatViewModel.clearPrivateChatHistory(target.key)
                }
                pendingAction = null
                pendingActionTarget = null
            },
            onDismiss = { pendingAction = null; pendingActionTarget = null }
        )
    }

    if (pendingAction == PendingAction.RENAME && pendingActionTarget != null) {
        val target = pendingActionTarget!!
        RenameGroupDialog(
            currentName = target.displayName,
            onConfirm = { newName ->
                chatViewModel.renameGroup(target.key, newName)
                pendingAction = null
                pendingActionTarget = null
            },
            onDismiss = { pendingAction = null; pendingActionTarget = null }
        )
    }

    // ── Create / Join dialogs ──

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { chatViewModel.createGroup(it); showCreateDialog = false }
        )
    }
    if (showJoinDialog) {
        JoinGroupDialog(
            onDismiss = { showJoinDialog = false },
            onConfirm = { g, n -> chatViewModel.joinGroup(g, n); showJoinDialog = false }
        )
    }

    if (showQrScanner) {
        QrScannerSheet(
            isPresented = showQrScanner,
            onDismiss = { showQrScanner = false },
            viewModel = chatViewModel
        )
    }

    if (showRefreshDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshDialog = false },
            title = { Text(stringResource(R.string.refresh_account_title)) },
            text = { Text(stringResource(R.string.refresh_account_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshDialog = false
                    onRefreshAccount()
                }) {
                    Text(stringResource(R.string.refresh_account_confirm), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Mesh intro dialog
    if (showMeshIntroDialog) {
        var dontShowAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                showMeshIntroDialog = false
                if (dontShowAgain) meshIntroPrefs.edit().putBoolean("dismissed", true).apply()
                chatViewModel.navigateToMesh(); onGroupSelected()
            },
            title = {
                Text(
                    stringResource(R.string.mesh_intro_title),
                    fontFamily = NunitoFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.mesh_intro_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontShowAgain = !dontShowAgain }
                    ) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.mesh_intro_dont_show),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMeshIntroDialog = false
                    if (dontShowAgain) meshIntroPrefs.edit().putBoolean("dismissed", true).apply()
                    chatViewModel.navigateToMesh(); onGroupSelected()
                }) {
                    Text(stringResource(R.string.mesh_intro_close))
                }
            }
        )
    }

    // About / Settings sheet
    val showAppInfo by chatViewModel.showAppInfo.collectAsStateWithLifecycle()
    var showDebugSheet by remember { mutableStateOf(false) }
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = { chatViewModel.hideAppInfo() },
        onShowDebug = if (BuildConfig.DEBUG) ({ showDebugSheet = true }) else null,
        nickname = nickname,
        onNicknameChange = { chatViewModel.setNickname(it) }
    )
    if (BuildConfig.DEBUG && showDebugSheet) {
        com.roman.zemzeme.ui.debug.DebugSettingsSheet(
            isPresented = showDebugSheet,
            onDismiss = { showDebugSheet = false },
            meshService = chatViewModel.meshService
        )
    }
}

// ══════════════════════════════════════════════
// Composables
// ══════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = NunitoFontFamily,
            color = MaterialTheme.extendedColors.textTertiary,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp)
        ),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun FabMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 2.dp
        ) {
            Text(
                label,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = NunitoFontFamily)
            )
        }
        SmallFloatingActionButton(onClick = onClick, containerColor = containerColor) {
            Icon(icon, contentDescription = label)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    name: String,
    lastMessage: ZemzemeMessage?,
    avatarColor: Color,
    icon: ImageVector? = null,
    myPeerID: String = "",
    myNickname: String = "",
    unreadCount: Int = 0,
    connectionData: ConnectionDisplayData.ChannelConnection? = null,
    rowAlpha: Float = 1f,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val extended = MaterialTheme.extendedColors
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongPress?.invoke() }
            ),
        color = Color.Transparent
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Avatar with unread badge on corner
            Box(modifier = Modifier.size(48.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(avatarColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = avatarColor,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text(
                            name.firstOrNull()?.uppercase() ?: "#",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = NunitoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = avatarColor
                            )
                        )
                    }
                }
                if (unreadCount > 0) {
                    val displayCount = if (unreadCount > 99) "99" else unreadCount.toString()
                    val badgeSize = 20.dp
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-2).dp)
                            .defaultMinSize(minWidth = badgeSize, minHeight = badgeSize)
                            .background(Color(0xFFFFD700), CircleShape)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayCount,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = NunitoFontFamily,
                                lineHeight = 10.sp
                            ),
                            color = Color.Black
                        )
                    }
                }
            }

            // Name + connection status + last message
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = NunitoFontFamily, fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (connectionData != null) {
                    ConnectionIndicatorFactory.ChannelIndicator(data = connectionData)
                }
                if (lastMessage != null) {
                    val isMe = (myPeerID.isNotEmpty() && lastMessage.senderPeerID == myPeerID) ||
                        (myNickname.isNotEmpty() && lastMessage.sender == myNickname)
                    val prefix = if (isMe) "You" else {
                        val sender = lastMessage.sender
                        if (sender.startsWith("p2p:")) {
                            lastMessage.senderPeerID?.let { pid ->
                                com.roman.zemzeme.p2p.P2PAliasRegistry.getDisplayName(pid)
                            } ?: "User"
                        } else sender
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$prefix: ${lastMessage.content}",
                            style = MaterialTheme.typography.bodySmall.copy(color = extended.textSecondary),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            timeFormat.format(lastMessage.timestamp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = NunitoFontFamily, color = extended.textTertiary
                            )
                        )
                    }
                }
            }
        }
    }
}

// ── Action sheet dialog (centered, full-width card) ──

@Composable
private fun GroupActionSheet(
    groupName: String,
    showDelete: Boolean = true,
    deleteLabel: String = "Delete group",
    showRename: Boolean = false,
    onRename: () -> Unit = {},
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val extended = MaterialTheme.extendedColors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    groupName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = NunitoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                HorizontalDivider(color = extended.borderSubtle)

                // Rename group button
                if (showRename) {
                    Surface(
                        onClick = onRename,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = colorScheme.surfaceVariant
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                stringResource(R.string.dialog_title_rename_group),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = NunitoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                // Clear chat button
                Surface(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = colorScheme.surfaceVariant
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.LayersClear,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            stringResource(R.string.dialog_clear_chat),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = NunitoFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                // Delete group button
                if (showDelete) {
                    Surface(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = colorScheme.errorContainer.copy(alpha = 0.4f)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                deleteLabel,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = NunitoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.error
                                )
                            )
                        }
                    }
                }

                // Cancel
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = NunitoFontFamily,
                            color = extended.textSecondary
                        )
                    )
                }
            }
        }
    }
}

// ── Confirmation dialog ──

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = NunitoFontFamily))
        },
        text = {
            Text(body, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = confirmColor, fontFamily = NunitoFontFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), fontFamily = NunitoFontFamily)
            }
        }
    )
}

// ── Create / Join dialogs ──

@Composable
private fun CreateGroupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var nickname by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_create_group), style = MaterialTheme.typography.titleMedium.copy(fontFamily = NunitoFontFamily)) },
        text = {
            OutlinedTextField(
                value = nickname, onValueChange = { if (it.length <= 15) nickname = it },
                label = { Text(stringResource(R.string.label_group_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (nickname.isNotBlank()) onConfirm(nickname.trim()) }, enabled = nickname.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun JoinGroupDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var geohash by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_join_group), style = MaterialTheme.typography.titleMedium.copy(fontFamily = NunitoFontFamily)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = geohash,
                    onValueChange = { input ->
                        val filtered = input.lowercase().filter { it in "0123456789bcdefghjkmnpqrstuvwxyz" }
                        geohash = filtered.take(6)
                    },
                    label = { Text(stringResource(R.string.label_group_id)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nickname, onValueChange = { if (it.length <= 15) nickname = it },
                    label = { Text(stringResource(R.string.label_nickname_optional)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (geohash.isNotBlank()) onConfirm(geohash.trim(), nickname.trim().ifEmpty { geohash.trim() }) },
                enabled = geohash.isNotBlank()
            ) { Text(stringResource(R.string.join)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun RenameGroupDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var newName by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_rename_group), style = MaterialTheme.typography.titleMedium.copy(fontFamily = NunitoFontFamily)) },
        text = {
            OutlinedTextField(
                value = newName, onValueChange = { if (it.length <= 15) newName = it },
                label = { Text(stringResource(R.string.label_group_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (newName.isNotBlank()) onConfirm(newName.trim()) }, enabled = newName.isNotBlank()) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
