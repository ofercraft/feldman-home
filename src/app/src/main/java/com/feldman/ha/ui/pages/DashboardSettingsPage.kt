package com.feldman.ha.ui.pages

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.feldman.motion.SettingsScaffold

/**
 * Size of the dashboard grid. Every choice writes straight to prefs and the dashboard picks it up
 * on its next composition, so this page has no save step.
 *
 * "Auto" means the grid derives its own count from the screen size, which is the right answer
 * almost always — the explicit counts exist for people who want a denser or sparser layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
    }

    var columnsPortrait by remember { mutableIntStateOf(prefs.getInt("grid_columns_portrait", 0)) }
    var columnsLandscape by remember { mutableIntStateOf(prefs.getInt("grid_columns_landscape", 0)) }
    var rowsPortrait by remember { mutableIntStateOf(prefs.getInt("grid_rows_portrait", 0)) }
    var rowsLandscape by remember { mutableIntStateOf(prefs.getInt("grid_rows_landscape", 0)) }

    SettingsScaffold(
        title = "Dashboard",
        scaffoldModifier = Modifier.fillMaxSize(),
        topBar = { SettingsTopBar("Dashboard", onBack) }
    ) {
        title("Grid size")
        section {
            item(padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GridSettingRow(
                        label = "Columns (portrait)",
                        options = listOf(0 to "Auto", 4 to "4", 6 to "6", 8 to "8"),
                        selected = columnsPortrait,
                        onSelect = {
                            columnsPortrait = it
                            prefs.edit { putInt("grid_columns_portrait", it) }
                        }
                    )
                    GridSettingRow(
                        label = "Columns (landscape)",
                        options = listOf(0 to "Auto", 4 to "4", 6 to "6", 8 to "8", 10 to "10", 12 to "12"),
                        selected = columnsLandscape,
                        onSelect = {
                            columnsLandscape = it
                            prefs.edit { putInt("grid_columns_landscape", it) }
                        }
                    )
                    GridSettingRow(
                        label = "Rows (portrait)",
                        options = listOf(0 to "Auto", 3 to "3", 4 to "4", 5 to "5", 6 to "6"),
                        selected = rowsPortrait,
                        onSelect = {
                            rowsPortrait = it
                            prefs.edit { putInt("grid_rows_portrait", it) }
                        }
                    )
                    GridSettingRow(
                        label = "Rows (landscape)",
                        options = listOf(0 to "Auto", 2 to "2", 3 to "3", 4 to "4"),
                        selected = rowsLandscape,
                        onSelect = {
                            rowsLandscape = it
                            prefs.edit { putInt("grid_rows_landscape", it) }
                        }
                    )
                }
            }
            item(padding = 16.dp) {
                Text(
                    text = "Auto fits the grid to your screen. Cards keep their own size within it — " +
                        "resize an individual card from its settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
