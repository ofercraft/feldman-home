package com.feldman.ha.widgets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.feldman.motion.AppTheme
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.rememberSymbolPainter

class SyncLogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                LogViewer()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogViewer() {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(WidgetSyncLogger.getLogs(context)) }
    
    SettingsScaffold(
        title = "Sync Logs",
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Sync Logs", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { logs = WidgetSyncLogger.getLogs(context) }) {
                        Icon(rememberSymbolPainter("refresh"), "Refresh")
                    }
                    IconButton(onClick = { 
                        WidgetSyncLogger.clearLogs(context)
                        logs = WidgetSyncLogger.getLogs(context)
                    }) {
                        Icon(rememberSymbolPainter("delete"), "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = Color.Unspecified,
                    navigationIconContentColor = Color.Unspecified,
                    titleContentColor = Color.Unspecified,
                    actionIconContentColor = Color.Unspecified
                )
            )
        }
    ) {
        val logLines = remember(logs) { 
            logs.split("\n")
                .filter { it.isNotBlank() }
                .reversed()
                .take(300) 
        }
        
        title("Recent History")
        section {
            if (logLines.isEmpty()) {
                item {
                    Text("No logs found.", modifier = Modifier.padding(16.dp))
                }
            } else {
                logLines.forEach { line ->
                    item {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
