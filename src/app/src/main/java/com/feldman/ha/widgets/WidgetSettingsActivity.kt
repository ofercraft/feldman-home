package com.feldman.ha.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.feldman.ha.widgets.generated.AlarmConfigureActivity
import com.feldman.ha.widgets.generated.AlarmWidgetReceiver
import com.feldman.ha.widgets.generated.ClimateConfigureActivity
import com.feldman.ha.widgets.generated.ClimateWidgetReceiver
import com.feldman.ha.widgets.generated.FanConfigureActivity
import com.feldman.ha.widgets.generated.FanWidgetReceiver
import com.feldman.ha.widgets.generated.LightConfigureActivity
import com.feldman.ha.widgets.generated.LightWidgetReceiver
import com.feldman.ha.widgets.generated.LockConfigureActivity
import com.feldman.ha.widgets.generated.LockWidgetReceiver
import com.feldman.motion.AppTheme
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.launch

private data class WidgetEntry(
    val appWidgetId: Int,
    val label: String,
    val icon: String,
    val configureActivityClass: Class<*>
)

class WidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                SettingsScaffold(title = "Widget Settings") {
                    val entries = loadEntries()
                    val prefs = remember { getSharedPreferences("ha_prefs", Context.MODE_PRIVATE) }
                    var noEnlarge by remember { mutableStateOf(prefs.getBoolean("widget_no_enlarge", false)) }

                    title("Display")
                    section {
                        switchItem(
                            title = "Keep original size",
                            description = "Never enlarge widgets — only shrink to fit. A bigger cell adds space instead of scaling up.",
                            checked = noEnlarge,
                            onCheckedChange = { checked ->
                                noEnlarge = checked
                                prefs.edit { putBoolean("widget_no_enlarge", checked) }
                                lifecycleScope.launch {
                                    WidgetRefreshScheduler.updateAllProviders(this@WidgetSettingsActivity)
                                }
                            }
                        )
                    }

                    title("Active Widgets")
                    section {
                        if (entries.isEmpty()) {
                            item {
                                androidx.compose.material3.Text(
                                    "No widgets found on home screen.",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            entries.forEach { entry ->
                                pageItem(
                                    title = entry.label,
                                    description = "Widget #${entry.appWidgetId} · Tap to configure",
                                    icon = rememberSymbolPainter(entry.icon),
                                    onClick = {
                                        startActivity(
                                            Intent(this@WidgetSettingsActivity, entry.configureActivityClass).apply {
                                                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, entry.appWidgetId)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    title("Diagnostics")
                    section {
                        pageItem(
                            title = "Sync Logs",
                            description = "Background sync and update history",
                            icon = rememberSymbolPainter("history"),
                            onClick = {
                                startActivity(Intent(this@WidgetSettingsActivity, SyncLogsActivity::class.java))
                            }
                        )
                    }
                }
            }
        }
    }

    private fun loadEntries(): List<WidgetEntry> {
        val mgr = AppWidgetManager.getInstance(this)
        val known = listOf(
            Triple(ComponentName(this, FanWidgetReceiver::class.java),     "Fan",     FanConfigureActivity::class.java     to "mode_fan"),
            Triple(ComponentName(this, ClimateWidgetReceiver::class.java), "Climate", ClimateConfigureActivity::class.java to "thermostat"),
            Triple(ComponentName(this, LightWidgetReceiver::class.java),   "Light",   LightConfigureActivity::class.java   to "lightbulb"),
            Triple(ComponentName(this, LockWidgetReceiver::class.java),    "Lock",    LockConfigureActivity::class.java    to "lock"),
            Triple(ComponentName(this, AlarmWidgetReceiver::class.java),   "Alarm",   AlarmConfigureActivity::class.java   to "shield"),
        )
        return known.flatMap { (provider, label, classPair) ->
            val (configClass, icon) = classPair
            mgr.getAppWidgetIds(provider).map { id ->
                WidgetEntry(appWidgetId = id, label = "$label Widget", icon = icon, configureActivityClass = configClass)
            }
        }.sortedBy { it.label }
    }
}
