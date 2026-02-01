package com.roman.zemzeme.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updateManager = UpdateManager.getInstance(context)
    val updateState by updateManager.updateState.collectAsState()

    val shouldShow = when (updateState) {
        is UpdateState.Downloading, is UpdateState.ReadyToInstall,
        is UpdateState.Installing, is UpdateState.PendingUserAction,
        is UpdateState.Error -> true
        else -> false
    }

    AnimatedVisibility(visible = shouldShow, enter = expandVertically(), exit = shrinkVertically(), modifier = modifier) {
        val backgroundColor = when (updateState) {
            is UpdateState.Error -> MaterialTheme.colorScheme.errorContainer
            is UpdateState.ReadyToInstall -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val contentColor = when (updateState) {
            is UpdateState.Error -> MaterialTheme.colorScheme.onErrorContainer
            is UpdateState.ReadyToInstall -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Box(modifier = Modifier.fillMaxWidth().background(backgroundColor)) {
            when (val state = updateState) {
                is UpdateState.Downloading -> DownloadingBanner(state.progress, state.info.versionName, contentColor) { updateManager.cancelDownload() }
                is UpdateState.ReadyToInstall -> ReadyBanner(state.info.versionName, contentColor, { updateManager.installUpdate() }, { updateManager.dismissUpdate() })
                is UpdateState.Installing, is UpdateState.PendingUserAction -> InstallingBanner(contentColor)
                is UpdateState.Error -> ErrorBanner(state.message, contentColor, { updateManager.checkForUpdate() }, { updateManager.dismissUpdate() })
                else -> {}
            }
        }
    }
}

@Composable
private fun DownloadingBanner(progress: Float, versionName: String, contentColor: Color, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Download, null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Downloading $versionName", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = contentColor)
            Spacer(Modifier.weight(1f))
            if (progress >= 0) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.7f))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, "Cancel", tint = contentColor.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (progress >= 0) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp), strokeCap = StrokeCap.Round, trackColor = contentColor.copy(alpha = 0.2f), color = contentColor)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), strokeCap = StrokeCap.Round, trackColor = contentColor.copy(alpha = 0.2f), color = contentColor)
        }
    }
}

@Composable
private fun ReadyBanner(versionName: String, contentColor: Color, onInstall: () -> Unit, onDismiss: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SystemUpdate, null, tint = contentColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Update ready", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = contentColor)
            Text("Version $versionName downloaded", style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.7f))
        }
        TextButton(onClick = onDismiss) { Text("Later", color = contentColor.copy(alpha = 0.7f)) }
        Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = contentColor, contentColor = MaterialTheme.colorScheme.primaryContainer)) { Text("Install") }
    }
}

@Composable
private fun InstallingBanner(contentColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = contentColor)
        Spacer(Modifier.width(12.dp))
        Text("Installing update...", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = contentColor)
    }
}

@Composable
private fun ErrorBanner(message: String, contentColor: Color, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Update failed", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = contentColor)
            Text(message, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.7f), maxLines = 1)
        }
        TextButton(onClick = onDismiss) { Text("Dismiss", color = contentColor.copy(alpha = 0.7f)) }
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = contentColor, contentColor = MaterialTheme.colorScheme.errorContainer)) { Text("Retry") }
    }
}
