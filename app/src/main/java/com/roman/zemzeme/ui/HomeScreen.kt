package com.roman.zemzeme.ui

import android.app.Activity
import android.content.Intent
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
import androidx.compose.material.icons.filled.Refresh
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
import com.roman.zemzeme.R
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
    val privateChats by chatViewModel.privateChats.collectAsStateWithLifecycle()
    val peerNicknames by chatViewModel.peerNicknames.collectAsStateWithLifecycle()
    val customGroups by chatViewModel.customGroups.collectAsStateWithLifecycle()
    val geographicGroups by chatViewModel.geographicGroups.collectAsStateWithLifecycle()
    val groupNicknames by chatViewModel.groupNicknames.collectAsStateWithLifecycle()
    val channelMessages by chatViewModel.channelMessages.collectAsStateWithLifecycle()
    val nickname by chatViewModel.nickname.collectAsStateWithLifecycle()
    val myPeerID = chatViewModel.myPeerID

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

    // "Contacts" = DMs, sorted by last-message time
    val contactItems = remember(privateChats) {
        privateChats.map { (pid, msgs) ->
            Triple("dm", pid, msgs.lastOrNull()?.timestamp?.time ?: 0L)
        }.sortedByDescending { it.third }
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
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "@${nickname.ifEmpty { myPeerID.take(8) }}",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f),
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
                                    Icons.Filled.Refresh,
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
                        FabMenuItem("Scan QR", Icons.Outlined.QrCodeScanner, colorScheme.tertiaryContainer) {
                            fabExpanded = false; showQrScanner = true
                        }
                        FabMenuItem("Choose city", Icons.Outlined.LocationCity, colorScheme.tertiaryContainer) {
                            fabExpanded = false
                            geohashPickerLauncher.launch(Intent(context, GeohashPickerActivity::class.java))
                        }
                        FabMenuItem("Join group", Icons.Outlined.GroupAdd, colorScheme.secondaryContainer) {
                            fabExpanded = false; showJoinDialog = true
                        }
                        FabMenuItem("Create group", Icons.Outlined.Group, colorScheme.primaryContainer) {
                            fabExpanded = false; showCreateDialog = true
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
            item(key = "nearby_header") { SectionHeader("Nearby") }

            item(key = "mesh") {
                GroupRow(
                    name = "Mesh (ALL)",
                    lastMessage = messages.lastOrNull(),
                    avatarColor = extended.electricCyan,
                    icon = Icons.Outlined.Hub,
                    myPeerID = myPeerID,
                    myNickname = nickname,
                    onClick = { chatViewModel.navigateToMesh(); onGroupSelected() },
                    onLongPress = { actionTarget = ActionTarget("mesh", "mesh", "Mesh (ALL)") }
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
                item(key = "geogroups_header") { SectionHeader("Geographic Groups") }

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
                        onClick = { chatViewModel.navigateToGeohashGroup(key); onGroupSelected() },
                        onLongPress = { actionTarget = ActionTarget("geographic", key, display) }
                    )
                }
            }

            // ── MY GROUPS ──
            if (myGroupItems.isNotEmpty()) {
                item(key = "mygroups_header") { SectionHeader("My Groups") }

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
                        onClick = { chatViewModel.navigateToGeohashGroup(key); onGroupSelected() },
                        onLongPress = { actionTarget = ActionTarget("group", key, display) }
                    )
                }
            }

            // ── CONTACTS ──
            if (contactItems.isNotEmpty()) {
                item(key = "contacts_header") { SectionHeader("Contacts") }

                items(contactItems, key = { "dm_${it.second}" }) { (_, key, _) ->
                    val lastMsg = (privateChats[key] ?: emptyList()).lastOrNull()
                    val peerName = peerNicknames[key]
                        ?: com.roman.zemzeme.p2p.P2PAliasRegistry.getDisplayName(key)
                        ?: key.take(12) + "..."
                    GroupRow(
                        name = peerName,
                        lastMessage = lastMsg,
                        avatarColor = Color(0xFF32D74B),
                        myPeerID = myPeerID,
                        myNickname = nickname,
                        onClick = {
                            chatViewModel.navigateToMesh()
                            chatViewModel.navigateToPrivateChat(key)
                            onGroupSelected()
                        },
                        onLongPress = { actionTarget = ActionTarget("dm", key, peerName) }
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
            deleteLabel = if (target.type == "dm") "Delete chat" else "Delete group",
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
            title = "Delete group",
            body = "Are you sure you want to delete \"${target.displayName}\"? This cannot be undone.",
            confirmLabel = "Delete",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                when (target.type) {
                    "group" -> chatViewModel.removeGroup(target.key)
                    "geographic" -> chatViewModel.removeGeographicGroup(target.key)
                    "dm" -> chatViewModel.deletePrivateChat(target.key)
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
            title = "Clear chat",
            body = "Are you sure you want to clear all messages in \"${target.displayName}\"?",
            confirmLabel = "Clear",
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
}

// ══════════════════════════════════════════════
// Composables
// ══════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 2.dp) {
            Text(
                label,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
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
            // Avatar
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
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = avatarColor
                        )
                    )
                }
            }

            // Name + last message
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (lastMessage != null) {
                    val isMe = (myPeerID.isNotEmpty() && lastMessage.senderPeerID == myPeerID) ||
                        (myNickname.isNotEmpty() && lastMessage.sender == myNickname)
                    val prefix = if (isMe) "You" else lastMessage.sender
                    Text(
                        "$prefix: ${lastMessage.content}",
                        style = MaterialTheme.typography.bodySmall.copy(color = extended.textSecondary),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Timestamp
            if (lastMessage != null) {
                Text(
                    timeFormat.format(lastMessage.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, color = extended.textTertiary
                    )
                )
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
                        fontFamily = FontFamily.Monospace,
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
                                "Rename group",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
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
                            "Clear chat",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
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
                                    fontFamily = FontFamily.Monospace,
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
                        "Cancel",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
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
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
        },
        text = {
            Text(body, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = confirmColor, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = FontFamily.Monospace)
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
        title = { Text("Create New Group", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)) },
        text = {
            OutlinedTextField(
                value = nickname, onValueChange = { nickname = it },
                label = { Text("Group name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (nickname.isNotBlank()) onConfirm(nickname.trim()) }, enabled = nickname.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun JoinGroupDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var geohash by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Group", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = geohash, onValueChange = { geohash = it.lowercase() },
                    label = { Text("Group ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it },
                    label = { Text("Nickname (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (geohash.isNotBlank()) onConfirm(geohash.trim(), nickname.trim().ifEmpty { geohash.trim() }) },
                enabled = geohash.isNotBlank()
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RenameGroupDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var newName by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Group", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)) },
        text = {
            OutlinedTextField(
                value = newName, onValueChange = { newName = it },
                label = { Text("Group name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (newName.isNotBlank()) onConfirm(newName.trim()) }, enabled = newName.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
