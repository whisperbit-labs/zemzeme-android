package com.roman.zemzeme.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.Intent
import android.net.Uri
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.model.DeliveryStatus
import com.roman.zemzeme.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*
import com.roman.zemzeme.ui.media.VoiceNotePlayer
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import com.roman.zemzeme.ui.media.FileMessageItem
import com.roman.zemzeme.model.ZemzemeMessageType
import com.roman.zemzeme.R
import androidx.compose.ui.res.stringResource
import com.roman.zemzeme.ui.theme.ZemzemeShapes
import com.roman.zemzeme.ui.theme.ZemzemeElevation
import com.roman.zemzeme.ui.theme.extendedColors


// VoiceNotePlayer moved to com.roman.zemzeme.ui.media.VoiceNotePlayer

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<ZemzemeMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((ZemzemeMessage) -> Unit)? = null,
    onCancelTransfer: ((ZemzemeMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val listState = rememberLazyListState()

    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    var followIncomingMessages by remember { mutableStateOf(true) }

    // Smart scroll: auto-scroll to bottom for initial load, then follow unless user scrolls away
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val isFirstLoad = !hasScrolledToInitialPosition
            if (isFirstLoad || followIncomingMessages) {
                listState.scrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }

    // Track whether user has scrolled away from the latest messages
    val isAtLatest by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        followIncomingMessages = isAtLatest
        onScrolledUpChanged?.invoke(!isAtLatest)
    }

    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            // With reverseLayout=true and reversed data, latest is at index 0
            followIncomingMessages = true
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize(),
        reverseLayout = true
    ) {
        items(
            items = messages.asReversed(),
            key = { it.id }
        ) { message ->
                MessageItem(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: ZemzemeMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    messages: List<ZemzemeMessage> = emptyList(),
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((ZemzemeMessage) -> Unit)? = null,
    onCancelTransfer: ((ZemzemeMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val haptic = LocalHapticFeedback.current

    // Determine if this message was sent by self
    val isSelf = message.senderPeerID == meshService.myPeerID ||
                 message.sender == currentUserNickname ||
                 message.sender.startsWith("$currentUserNickname#")

    // System messages remain centered without bubbles
    if (message.sender == "system") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "* ${message.content} *",
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                color = Color.Gray.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        return
    }

    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val baseColor = if (isSelf) Color(0xFFFF9500) else getPeerColor(message, isDark)
    val resolvedSender = if (message.sender.startsWith("p2p:")) {
        message.senderPeerID?.let { pid ->
            com.roman.zemzeme.p2p.P2PAliasRegistry.getDisplayName(pid)
        } ?: "User"
    } else message.sender
    val (baseName, suffix) = splitSuffix(resolvedSender)

    // Modern message layout with avatar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .pointerInput(message.id) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    ) {
        // Message content column with sender name and bubble
        Row(
            horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            // For received messages, show avatar on the left, aligned with bubble
            if (!isSelf) {
                AvatarCircle(
                    letter = baseName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    color = baseColor,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Message content column - wraps content width
            Column(
                horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start
            ) {
                // Sender name (only for received messages)
                if (!isSelf) {
                    Text(
                        text = truncateNickname(baseName) + suffix,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = baseColor,
                        modifier = Modifier
                            .padding(bottom = 4.dp, start = 4.dp)
                            .clickable(enabled = onNicknameClick != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNicknameClick?.invoke(message.originalSender ?: message.sender)
                            }
                    )
                }

                // Message bubble - wraps content
                MessageBubble(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    colorScheme = colorScheme,
                    timeFormatter = timeFormatter,
                    isSelf = isSelf,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick
                )
            }
        }
    }
}

@Composable
private fun AvatarCircle(
    letter: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(color.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ZemzemeMessage,
    messages: List<ZemzemeMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    isSelf: Boolean,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((ZemzemeMessage) -> Unit)?,
    onCancelTransfer: ((ZemzemeMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?
) {
    val extendedColors = MaterialTheme.extendedColors

    // Bubble background color from theme
    val bubbleColor = if (isSelf) {
        extendedColors.sentBubble
    } else {
        extendedColors.receivedBubble
    }

    // Cyberpunk bubble shape with "tail" effect
    // Sent messages: tail on bottom-right (20dp, 20dp, 6dp, 20dp)
    // Received messages: tail on bottom-left (20dp, 20dp, 20dp, 6dp)
    val bubbleShape = if (isSelf) {
        RoundedCornerShape(
            topStart = ZemzemeShapes.MessageBubbleRadius,
            topEnd = ZemzemeShapes.MessageBubbleRadius,
            bottomEnd = ZemzemeShapes.MessageBubbleRadiusSmall,
            bottomStart = ZemzemeShapes.MessageBubbleRadius
        )
    } else {
        RoundedCornerShape(
            topStart = ZemzemeShapes.MessageBubbleRadius,
            topEnd = ZemzemeShapes.MessageBubbleRadius,
            bottomEnd = ZemzemeShapes.MessageBubbleRadius,
            bottomStart = ZemzemeShapes.MessageBubbleRadiusSmall
        )
    }

    Surface(
        modifier = Modifier.wrapContentWidth(),
        shape = bubbleShape,
        color = bubbleColor,
        tonalElevation = ZemzemeElevation.Low,
        shadowElevation = ZemzemeElevation.Low
    ) {
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Message content
            MessageBubbleContent(
                message = message,
                messages = messages,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter,
                isSelf = isSelf,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                onImageClick = onImageClick
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamp row at the bottom
            Row(
                modifier = Modifier.wrapContentWidth(),
                horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormatter.format(message.timestamp),
                    fontSize = 11.sp,
                    color = Color.Gray.copy(alpha = 0.7f)
                )

                // PoW badge
                message.powDifficulty?.let { bits ->
                    if (bits > 0) {
                        Text(
                            text = " ⛨${bits}b",
                            fontSize = 10.sp,
                            color = Color.Gray.copy(alpha = 0.6f)
                        )
                    }
                }

                // Delivery status for private messages
                if (message.isPrivate && isSelf) {
                    message.deliveryStatus?.let { status ->
                        Spacer(modifier = Modifier.width(4.dp))
                        DeliveryStatusIcon(status = status)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleContent(
    message: ZemzemeMessage,
    messages: List<ZemzemeMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    isSelf: Boolean,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((ZemzemeMessage) -> Unit)?,
    onCancelTransfer: ((ZemzemeMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?
) {
    // Handle special message types
    when (message.type) {
        ZemzemeMessageType.Image -> {
            com.roman.zemzeme.ui.media.ImageMessageItem(
                message = message,
                messages = messages,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                onImageClick = onImageClick,
                modifier = Modifier.fillMaxWidth()
            )
            return
        }
        ZemzemeMessageType.Audio -> {
            com.roman.zemzeme.ui.media.AudioMessageItem(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                modifier = Modifier.fillMaxWidth()
            )
            return
        }
        ZemzemeMessageType.File -> {
            RenderFileMessage(
                message = message,
                currentUserNickname = currentUserNickname,
                onCancelTransfer = onCancelTransfer
            )
            return
        }
        else -> {
            // Text message - continue below
        }
    }

    // Check if this message should be animated during PoW mining
    val shouldAnimate = shouldAnimateMessage(message.id)

    if (shouldAnimate) {
        MessageWithMatrixAnimation(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onImageClick = onImageClick,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        // Normal text message
        val annotatedText = formatMessageContentOnly(
            message = message,
            currentUserNickname = currentUserNickname,
            colorScheme = colorScheme,
            isSelf = isSelf
        )

        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

        Text(
            text = annotatedText,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .wrapContentWidth()
                .pointerInput(message) {
                    detectTapGestures(
                        onTap = { position ->
                            val layout = textLayoutResult ?: return@detectTapGestures
                            val offset = layout.getOffsetForPosition(position)

                            // Geohash teleport
                            val geohashAnnotations = annotatedText.getStringAnnotations(
                                tag = "geohash_click",
                                start = offset,
                                end = offset
                            )
                            if (geohashAnnotations.isNotEmpty()) {
                                val geohash = geohashAnnotations.first().item
                                try {
                                    val locationManager =
                                        com.roman.zemzeme.geohash.LocationChannelManager.getInstance(
                                            context
                                        )
                                    val level = when (geohash.length) {
                                        in 0..2 -> com.roman.zemzeme.geohash.GeohashChannelLevel.REGION
                                        in 3..4 -> com.roman.zemzeme.geohash.GeohashChannelLevel.PROVINCE
                                        5 -> com.roman.zemzeme.geohash.GeohashChannelLevel.CITY
                                        6 -> com.roman.zemzeme.geohash.GeohashChannelLevel.NEIGHBORHOOD
                                        else -> com.roman.zemzeme.geohash.GeohashChannelLevel.BLOCK
                                    }
                                    val channel = com.roman.zemzeme.geohash.GeohashChannel(
                                        level,
                                        geohash.lowercase()
                                    )
                                    locationManager.setTeleported(true)
                                    locationManager.select(
                                        com.roman.zemzeme.geohash.ChannelID.Location(
                                            channel
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                return@detectTapGestures
                            }

                            // URL open
                            val urlAnnotations = annotatedText.getStringAnnotations(
                                tag = "url_click",
                                start = offset,
                                end = offset
                            )
                            if (urlAnnotations.isNotEmpty()) {
                                val raw = urlAnnotations.first().item
                                val resolved =
                                    if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith(
                                            "https://",
                                            ignoreCase = true
                                        )
                                    ) raw else "https://$raw"
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                return@detectTapGestures
                            }
                        }
                    )
                },
            fontSize = 15.sp,
            lineHeight = 20.sp,
            color = colorScheme.onSurface,
            onTextLayout = { result -> textLayoutResult = result }
        )
    }
}

@Composable
private fun RenderFileMessage(
    message: ZemzemeMessage,
    currentUserNickname: String,
    onCancelTransfer: ((ZemzemeMessage) -> Unit)?
) {
    val path = message.content.trim()
    val (overrideProgress, _) = when (val st = message.deliveryStatus) {
        is com.roman.zemzeme.model.DeliveryStatus.PartiallyDelivered -> {
            if (st.total > 0 && st.reached < st.total) {
                (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5)
            } else null to null
        }
        else -> null to null
    }

    val packet = try {
        val file = java.io.File(path)
        if (file.exists()) {
            com.roman.zemzeme.model.ZemzemeFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = com.roman.zemzeme.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                content = file.readBytes()
            )
        } else null
    } catch (e: Exception) {
        null
    }

    Box {
        if (packet != null) {
            if (overrideProgress != null) {
                com.roman.zemzeme.ui.media.FileSendingAnimation(
                    fileName = packet.fileName,
                    progress = overrideProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                FileMessageItem(
                    packet = packet,
                    onFileClick = {}
                )
            }

            val showCancel = message.sender == currentUserNickname &&
                    (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
            if (showCancel) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                        .clickable { onCancelTransfer?.invoke(message) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_cancel),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.file_unavailable),
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
    private fun MessageTextWithClickableNicknames(
        message: ZemzemeMessage,
        messages: List<ZemzemeMessage>,
        currentUserNickname: String,
        meshService: BluetoothMeshService,
        colorScheme: ColorScheme,
        timeFormatter: SimpleDateFormat,
        onNicknameClick: ((String) -> Unit)?,
        onMessageLongPress: ((ZemzemeMessage) -> Unit)?,
        onCancelTransfer: ((ZemzemeMessage) -> Unit)?,
        onImageClick: ((String, List<String>, Int) -> Unit)?,
        modifier: Modifier = Modifier
    ) {
    // Image special rendering
    if (message.type == ZemzemeMessageType.Image) {
        com.roman.zemzeme.ui.media.ImageMessageItem(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            onImageClick = onImageClick,
            modifier = modifier
        )
        return
    }

    // Voice note special rendering
    if (message.type == ZemzemeMessageType.Audio) {
        com.roman.zemzeme.ui.media.AudioMessageItem(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            modifier = modifier
        )
        return
    }

    // File special rendering
    if (message.type == ZemzemeMessageType.File) {
        val path = message.content.trim()
        // Derive sending progress if applicable
        val (overrideProgress, _) = when (val st = message.deliveryStatus) {
            is com.roman.zemzeme.model.DeliveryStatus.PartiallyDelivered -> {
                if (st.total > 0 && st.reached < st.total) {
                    (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5) // blue while sending
                } else null to null
            }
            else -> null to null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // Header: nickname + timestamp line above the file, identical styling to text messages
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter
            )
            val haptic = LocalHapticFeedback.current
            var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = headerText,
                fontFamily = NunitoFontFamily,
                color = colorScheme.onSurface,
                modifier = Modifier.pointerInput(message.id) {
                    detectTapGestures(onTap = { pos ->
                        val layout = headerLayout ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                        if (ann.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick.invoke(ann.first().item)
                        }
                    }, onLongPress = { onMessageLongPress?.invoke(message) })
                },
                onTextLayout = { headerLayout = it }
            )

            // Try to load the file packet from the path
            val packet = try {
                val file = java.io.File(path)
                if (file.exists()) {
                    // Create a temporary ZemzemeFilePacket for display
                    // In a real implementation, this would be stored with the packet metadata
                    com.roman.zemzeme.model.ZemzemeFilePacket(
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = com.roman.zemzeme.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                        content = file.readBytes()
                    )
                } else null
            } catch (e: Exception) {
                null
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (packet != null) {
                        if (overrideProgress != null) {
                            // Show sending animation while in-flight
                            com.roman.zemzeme.ui.media.FileSendingAnimation(
                                fileName = packet.fileName,
                                progress = overrideProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // Static file display with open/save dialog
                            FileMessageItem(
                                packet = packet,
                                onFileClick = {
                                    // handled inside FileMessageItem via dialog
                                }
                            )
                        }

                        // Cancel button overlay during sending
                        val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
                        if (showCancel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                                    .clickable { onCancelTransfer?.invoke(message) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else {
                        Text(text = stringResource(R.string.file_unavailable), fontFamily = NunitoFontFamily, color = Color.Gray)
                    }
                }
            }
        }
        return
    }

    // Check if this message should be animated during PoW mining
    val shouldAnimate = shouldAnimateMessage(message.id)
    
    // If animation is needed, use the matrix animation component for content only
    if (shouldAnimate) {
        // Display message with matrix animation for content
        MessageWithMatrixAnimation(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onImageClick = onImageClick,
            modifier = modifier
        )
    } else {
        // Normal message display
        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        
        // Check if this message was sent by self to avoid click interactions on own nickname
        val isSelf = message.senderPeerID == meshService.myPeerID || 
                     message.sender == currentUserNickname ||
                     message.sender.startsWith("$currentUserNickname#")
        
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        // Nickname click only when not self
                        if (!isSelf && onNicknameClick != null) {
                            val nicknameAnnotations = annotatedText.getStringAnnotations(
                                tag = "nickname_click",
                                start = offset,
                                end = offset
                            )
                            if (nicknameAnnotations.isNotEmpty()) {
                                val nickname = nicknameAnnotations.first().item
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNicknameClick.invoke(nickname)
                                return@detectTapGestures
                            }
                        }
                        // Geohash teleport (all messages)
                        val geohashAnnotations = annotatedText.getStringAnnotations(
                            tag = "geohash_click",
                            start = offset,
                            end = offset
                        )
                        if (geohashAnnotations.isNotEmpty()) {
                            val geohash = geohashAnnotations.first().item
                            try {
                                val locationManager = com.roman.zemzeme.geohash.LocationChannelManager.getInstance(
                                    context
                                )
                                val level = when (geohash.length) {
                                    in 0..2 -> com.roman.zemzeme.geohash.GeohashChannelLevel.REGION
                                    in 3..4 -> com.roman.zemzeme.geohash.GeohashChannelLevel.PROVINCE
                                    5 -> com.roman.zemzeme.geohash.GeohashChannelLevel.CITY
                                    6 -> com.roman.zemzeme.geohash.GeohashChannelLevel.NEIGHBORHOOD
                                    else -> com.roman.zemzeme.geohash.GeohashChannelLevel.BLOCK
                                }
                                val channel = com.roman.zemzeme.geohash.GeohashChannel(level, geohash.lowercase())
                                locationManager.setTeleported(true)
                                locationManager.select(com.roman.zemzeme.geohash.ChannelID.Location(channel))
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                        // URL open (all messages)
                        val urlAnnotations = annotatedText.getStringAnnotations(
                            tag = "url_click",
                            start = offset,
                            end = offset
                        )
                        if (urlAnnotations.isNotEmpty()) {
                            val raw = urlAnnotations.first().item
                            val resolved = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = NunitoFontFamily,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(
                color = colorScheme.onSurface
            ),
            onTextLayout = { result -> textLayoutResult = result }
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = stringResource(R.string.status_sending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            // Use a subtle hollow marker for Sent; single check is reserved for Delivered (iOS parity)
            Text(
                text = stringResource(R.string.status_pending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            // Single check for Delivered (matches iOS expectations)
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = stringResource(R.string.status_delivered),
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = stringResource(R.string.status_failed),
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            // Show a single subdued check without numeric label
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}
