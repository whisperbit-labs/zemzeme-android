package com.roman.zemzeme.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roman.zemzeme.core.ui.component.sheet.ZemzemeBottomSheet
import com.roman.zemzeme.core.ui.component.sheet.ZemzemeSheetTopBar
import com.roman.zemzeme.core.ui.component.sheet.ZemzemeSheetTitle
import com.roman.zemzeme.geohash.GeohashChannelLevel
import com.roman.zemzeme.geohash.LocationChannelManager
import com.roman.zemzeme.R

/**
 * Presenter component for LocationNotesSheet
 * Handles sheet presentation logic with proper error states
 * Extracts this logic from ChatScreen for better separation of concerns
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationNotesSheetPresenter(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val availableChannels by locationManager.availableChannels.collectAsStateWithLifecycle()
    val permissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val isLoadingLocation by locationManager.isLoadingLocation.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    
    // iOS pattern: notesGeohash ?? LocationChannelManager.shared.availableChannels.first(where: { $0.level == .building })?.geohash
    val buildingGeohash = availableChannels.firstOrNull { it.level == GeohashChannelLevel.BUILDING }?.geohash
    
    if (buildingGeohash != null) {
        // Get location name from locationManager
        val locationNames by locationManager.locationNames.collectAsStateWithLifecycle()
        val locationName = locationNames[GeohashChannelLevel.BUILDING]
            ?: locationNames[GeohashChannelLevel.BLOCK]
        
        LocationNotesSheet(
            geohash = buildingGeohash,
            locationName = locationName,
            nickname = nickname,
            onDismiss = onDismiss
        )
    } else if (permissionState == LocationChannelManager.PermissionState.AUTHORIZED && isLoadingLocation) {
        LocationNotesAcquiringSheet(onDismiss = onDismiss)
    } else {
        // No building geohash available - show error state (matches iOS)
        LocationNotesErrorSheet(
            onDismiss = onDismiss,
            locationManager = locationManager
        )
    }
}

/**
 * Loading sheet when location is being acquired
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationNotesAcquiringSheet(
    onDismiss: () -> Unit
) {
    ZemzemeBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.location_acquiring_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.location_acquiring_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Error sheet when location is unavailable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationNotesErrorSheet(
    onDismiss: () -> Unit,
    locationManager: LocationChannelManager
) {
    ZemzemeBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 80.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.location_unavailable_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.location_permission_required_notes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    // UNIFIED FIX: Enable location services first (user toggle)
                    locationManager.enableLocationServices()
                    // Then request location channels (which will also request permission if needed)
                    locationManager.enableLocationChannels()
                    locationManager.refreshChannels()
                }) {
                    Text(stringResource(R.string.action_enable_location))
                }
            }

            ZemzemeSheetTopBar(
                onClose = onDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
                title = {
                    ZemzemeSheetTitle(
                        text = stringResource(R.string.cd_location_notes).uppercase()
                    )
                }
            )
        }
    }
}
