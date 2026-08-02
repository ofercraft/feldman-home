package com.feldman.ha.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feldman.motion.AppTheme
import com.feldman.motion.rememberSymbolPainter

/**
 * Full-screen "add" page used by the dashboard's Add Card / Add Camera / Add Button flows.
 * Presented as a full-screen Dialog window by default; hosts that apply a visual transform
 * or their own theme (the Clock standby) render it inline instead — Dialog windows escape
 * both the transform and the composition's MaterialTheme, and cannot be shown at all from
 * a DreamService.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenAddPage(
    title: String,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    useDialog: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (useDialog) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false
            )
        ) {
            AppTheme {
                AddPageBody(title = title, onClose = onClose, actions = actions, content = content)
            }
        }
    } else {
        androidx.activity.compose.BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize()) {
            AddPageBody(title = title, onClose = onClose, actions = actions, content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPageBody(
    title: String,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(title, fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = {
                        FilledIconButton(
                            onClick = onClose,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(rememberSymbolPainter("arrow_back"), "Back")
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = content
            )
        }
    }
}
