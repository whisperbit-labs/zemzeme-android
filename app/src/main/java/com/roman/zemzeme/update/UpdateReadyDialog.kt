package com.roman.zemzeme.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun UpdateReadyDialog(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updateManager = UpdateManager.getInstance(context)
    val updateState by updateManager.updateState.collectAsState()
    var dismissedVersionCode by remember { mutableStateOf(-1) }

    val readyState = updateState as? UpdateState.ReadyToInstall
    if (readyState != null && readyState.info.versionCode != dismissedVersionCode) {
        Dialog(
            onDismissRequest = { dismissedVersionCode = readyState.info.versionCode },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Card(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Update Ready", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Version ${readyState.info.versionName} has been downloaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (readyState.info.releaseNotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(readyState.info.releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { dismissedVersionCode = readyState.info.versionCode }) {
                            Text("Later")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { updateManager.installUpdate() }) {
                            Text("Install Now")
                        }
                    }
                }
            }
        }
    }
}
