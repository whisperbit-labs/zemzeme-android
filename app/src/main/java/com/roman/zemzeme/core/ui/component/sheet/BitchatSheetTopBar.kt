package com.roman.zemzeme.core.ui.component.sheet

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roman.zemzeme.core.ui.component.button.CloseButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZemzemeSheetTopBar(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 0.98f,
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = title,
        navigationIcon = { navigationIcon?.invoke() },
        actions = {
            actions()
            CloseButton(
                onClick = onClose,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = backgroundAlpha),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZemzemeSheetCenterTopBar(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 0.98f,
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = title,
        navigationIcon = { navigationIcon?.invoke() },
        actions = {
            actions()
            CloseButton(
                onClick = onClose,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = backgroundAlpha),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
    )
}

@Composable
fun ZemzemeSheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontFamily = NunitoFontFamily
        )
    )
}
