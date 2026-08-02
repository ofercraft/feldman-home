package com.feldman.ha

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import androidx.collection.intSetOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.feldman.ha.mobile.MobileAppRegistration
import com.feldman.ha.mobile.MobileAppSensorService
import com.feldman.ha.mobile.MobileAppSensorUpdateWorker
import com.feldman.ha.widgets.WidgetRegistry
import com.feldman.ha.widgets.WidgetRefreshScheduler
import com.feldman.ha.widgets.WidgetSyncLogger
import com.feldman.ha.widgets.WidgetSyncWatchdog
import com.feldman.ha.widgets.generated.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import androidx.core.content.edit

class HAApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // One-time rename of the dashboard "tile" storage to "card" (file + keys). Runs before any
        // component reads the new prefs so existing dashboards/cards/layouts are preserved.
        CardsPrefsMigration.migrate(this)

        val currentProcessName =
            getProcessName()

        WidgetSyncLogger.log(this, "App: Application onCreate - process: $currentProcessName")

        if (currentProcessName == packageName) {
            WidgetRefreshScheduler.cancel(this)
            WidgetRefreshScheduler.stopWebSocket(this)
            WidgetSyncWatchdog.cancel(this)
            MobileAppSensorUpdateWorker.schedule(this)
            // schedule() uses ExistingPeriodicWorkPolicy.UPDATE, which keeps the *existing* next
            // run time — so on its own, an app update that adds new sensors would not tell Home
            // Assistant about them until the current 15-minute period elapses, and longer if the
            // device is dozing. This reconciles on every launch instead.
            WidgetSyncLogger.log(
                this,
                "App: mobile app registered=${MobileAppRegistration.isRegistered(this)}"
            )
            MobileAppSensorUpdateWorker.enqueueNow(this, fullSync = true)
            MobileAppSensorService.start(this)
            publishWidgetPreviews()
        }
    }

    private fun publishWidgetPreviews() {
        if (Build.VERSION.SDK_INT < 35) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetRegistry.init(this@HAApplication)
                val manager = GlanceAppWidgetManager(this@HAApplication)
                val receiverClasses = listOf<KClass<out GlanceAppWidgetReceiver>>(
                    FanWidgetReceiver::class,
                    ClimateWidgetReceiver::class,
                    LightWidgetReceiver::class,
                    LockWidgetReceiver::class,
                    AlarmWidgetReceiver::class,
                )
                for (cls in receiverClasses) {
                    runCatching {
                        manager.setWidgetPreviews(
                            cls,
                            intSetOf(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                        )
                    }
                }
                WidgetSyncLogger.log(this@HAApplication, "App: Widget previews published")
            } catch (e: Exception) {
                WidgetSyncLogger.log(this@HAApplication, "App: Widget preview publish failed: ${e.message}")
            }
        }
    }
}

/**
 * Migrates the old "tiles_prefs" SharedPreferences (and its "tile_*" keys, incl. the per-entity
 * config JSON's nested span/height keys) into the renamed "cards_prefs" used after the
 * tile -> card rename. Idempotent: guarded by a flag in the new file.
 */
object CardsPrefsMigration {
    fun migrate(context: Context) {
        val newPrefs = context.getSharedPreferences("cards_prefs", Context.MODE_PRIVATE)
        if (newPrefs.getBoolean("card_migration_done", false)) return

        val oldAll = context.getSharedPreferences("tiles_prefs", Context.MODE_PRIVATE).all
        newPrefs.edit {
            oldAll.forEach { (key, value) ->
                // Top-level key rename: tile_cfg_v2 -> card_cfg_v2, button_tiles_v1 -> button_cards_v1.
                val newKey = key.replace("tile", "card")
                when (value) {
                    is String -> {
                        // Inside the config JSON, the per-entity maps use keys like "tile_span_x",
                        // "tile_height_default", etc. Rename those nested keys too.
                        val newValue =
                            if (key == "tile_cfg_v2") value.replace("\"tile_", "\"card_") else value
                        putString(newKey, newValue)
                    }

                    is Boolean -> putBoolean(newKey, value)
                    is Int -> putInt(newKey, value)
                    is Long -> putLong(newKey, value)
                    is Float -> putFloat(newKey, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(
                        newKey,
                        value as Set<String>
                    )
                }
            }
            putBoolean("card_migration_done", true)
        }
    }
}
