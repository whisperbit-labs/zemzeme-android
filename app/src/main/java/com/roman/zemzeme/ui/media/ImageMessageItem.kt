package com.roman.zemzeme.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import com.roman.zemzeme.mesh.BluetoothMeshService
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.model.ZemzemeMessageType
import androidx.compose.material3.ColorScheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ImageMessageItem(
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
    val path = message.content.trim()
    Column(modifier = modifier.fillMaxWidth()) {
        val headerText = com.roman.zemzeme.ui.formatMessageHeaderAnnotatedString(
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
                })
            },
            onTextLayout = { headerLayout = it }
        )

        val context = LocalContext.current
        val bmp = remember(path) { try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Exception) { null } }

        // Collect all image paths from messages for swipe navigation
        val imagePaths = remember(messages) {
            messages.filter { it.type == ZemzemeMessageType.Image }
                .map { it.content.trim() }
        }

        if (bmp != null) {
            val img = bmp.asImageBitmap()
            val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).takeIf { it.isFinite() && it > 0 } ?: 1f
            val progressFraction: Float? = when (val st = message.deliveryStatus) {
                is com.roman.zemzeme.model.DeliveryStatus.PartiallyDelivered -> if (st.total > 0) st.reached.toFloat() / st.total.toFloat() else 0f
                else -> null
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (progressFraction != null && progressFraction < 1f && message.sender == currentUserNickname) {
                        // Cyberpunk block-reveal while sending
                        BlockRevealImage(
                            bitmap = img,
                            progress = progressFraction,
                            blocksX = 24,
                            blocksY = 16,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .aspectRatio(aspect)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable {
                                    val currentIndex = imagePaths.indexOf(path)
                                    onImageClick?.invoke(path, imagePaths, currentIndex)
                                }
                        )
                    } else {
                        // Fully revealed image
                        Image(
                            bitmap = img,
                            contentDescription = stringResource(com.roman.zemzeme.R.string.cd_image),
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .aspectRatio(aspect)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable {
                                    val currentIndex = imagePaths.indexOf(path)
                                    onImageClick?.invoke(path, imagePaths, currentIndex)
                                },
                            contentScale = ContentScale.Fit
                        )
                    }
                    // Cancel button overlay during sending
                    val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is com.roman.zemzeme.model.DeliveryStatus.PartiallyDelivered)
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
                            Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(com.roman.zemzeme.R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        } else {
            Text(text = stringResource(com.roman.zemzeme.R.string.image_unavailable), fontFamily = NunitoFontFamily, color = Color.Gray)
        }
    }
}
