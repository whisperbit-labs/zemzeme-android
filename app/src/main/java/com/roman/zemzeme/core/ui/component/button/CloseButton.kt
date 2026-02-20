package com.roman.zemzeme.core.ui.component.button

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roman.zemzeme.R

@Composable
fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.Companion
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(32.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
        )
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.cd_close),
            modifier = Modifier.Companion.size(18.dp)
        )
    }
}