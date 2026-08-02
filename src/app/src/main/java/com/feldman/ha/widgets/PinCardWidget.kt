package com.feldman.ha.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.feldman.ha.api.getStoredApi
import com.feldman.ha.widgets.generated.FactoryWidgetProviderCatalog
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val ACTION_PIN_CALLBACK = "com.feldman.ha.action.PIN_CARD_WIDGET"
private const val EXTRA_ENTITY_ID = "pin_entity_id"
private const val EXTRA_WIDGET_KEY = "pin_widget_key"

/**
 * Pins an in-app card to the home screen as a widget, carrying over all of the card's saved
 * settings (name, row order, hidden rows, picker visibility, toggles, state template).
 */
object CardWidgetPinner {

    /** True if this entity's domain has a matching widget type and the launcher supports pinning. */
    fun canPin(context: Context, entityId: String): Boolean {
        WidgetRegistry.init(context)
        val domain = entityId.substringBefore(".")
        val key = WidgetRegistry.keyForDomain(domain) ?: return false
        if (FactoryWidgetProviderCatalog.entries.none { it.widgetKey == key }) return false
        return AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    }

    /**
     * Asks the launcher to pin the widget. Returns false if unsupported or no matching widget type.
     * The card's settings are applied once the user confirms, via [PinCardWidgetReceiver].
     */
    fun requestPin(context: Context, entityId: String): Boolean {
        WidgetRegistry.init(context)
        val domain = entityId.substringBefore(".")
        val key = WidgetRegistry.keyForDomain(domain) ?: return false
        val receiverClass = FactoryWidgetProviderCatalog.entries
            .firstOrNull { it.widgetKey == key }?.receiverClassName ?: return false

        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) return false

        val provider = ComponentName(context.packageName, receiverClass)
        val callback = Intent(context, PinCardWidgetReceiver::class.java).apply {
            action = ACTION_PIN_CALLBACK
            putExtra(EXTRA_ENTITY_ID, entityId)
            putExtra(EXTRA_WIDGET_KEY, key)
        }
        // FLAG_MUTABLE so the system can add EXTRA_APPWIDGET_ID when the widget is placed.
        val pending = PendingIntent.getBroadcast(
            context,
            entityId.hashCode(),
            callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return runCatching { mgr.requestPinAppWidget(provider, null, pending) }
            .onFailure { Log.e("PinCardWidget", "requestPinAppWidget failed", it) }
            .getOrDefault(false)
    }
}

class PinCardWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PIN_CALLBACK) return
        val entityId = intent.getStringExtra(EXTRA_ENTITY_ID) ?: return
        val widgetKey = intent.getStringExtra(EXTRA_WIDGET_KEY) ?: return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                applyCardSettingsToWidget(appCtx, widgetKey, appWidgetId, entityId)
            } catch (e: Exception) {
                Log.e("PinCardWidget", "applyCardSettingsToWidget failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Reads the app card config (cards_prefs) for [entityId] and writes it to the widget's state. */
private suspend fun applyCardSettingsToWidget(
    context: Context,
    widgetKey: String,
    appWidgetId: Int,
    entityId: String
) {
    WidgetRegistry.init(context)
    val spec = WidgetRegistry.spec(widgetKey) ?: return

    val cardsPrefs = context.getSharedPreferences("cards_prefs", Context.MODE_PRIVATE)
    val moshi = Moshi.Builder().build()

    val cfgType = Types.newParameterizedType(
        Map::class.java, String::class.java,
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    val allConfigs = cardsPrefs.getString("card_cfg_v2", null)
        ?.let { runCatching { moshi.adapter<Map<String, Map<String, Any>>>(cfgType).fromJson(it) }.getOrNull() }
        .orEmpty()
    val config = allConfigs[entityId].orEmpty()

    val nameType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    val names = cardsPrefs.getString("entity_names", null)
        ?.let { runCatching { moshi.adapter<Map<String, String>>(nameType).fromJson(it) }.getOrNull() }
        .orEmpty()

    val friendlyName = names[entityId]
        ?: (config["friendly_name"] as? String)
        ?: entityId

    val rowOrder = (config["row_order"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val removedRows = (config["removed_rows"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val stateTemplate = config["state_template"] as? String ?: ""

    // Serialize custom features (value / toggle / service) for the widget to render.
    val customFeaturesJson = (config["custom_features"] as? List<*>)
        ?.filterIsInstance<Map<String, Any>>()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            val cfType = Types.newParameterizedType(
                List::class.java,
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            )
            runCatching { moshi.adapter<List<Map<String, Any>>>(cfType).toJson(it) }.getOrNull()
        }

    val api = getStoredApi(context)
    val state = api?.let { runCatching { it.getState(entityId) }.getOrNull() }

    val glanceId = runCatching {
        GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    }.getOrNull() ?: return

    updateAppWidgetState(context, CachedPreferencesGlanceStateDefinition, glanceId) { prefs ->
        prefs.toMutablePreferences().apply {
            this[FACT_ENTITY_ID] = entityId
            this[FACT_FRIENDLY_NAME] = friendlyName
            this[FACT_STATE_TEMPLATE] = stateTemplate
            if (state != null) this[FACT_STATE] = state.state
            this[FACT_LAST_INTERACTION] = System.currentTimeMillis()
            this[FACT_ROW_ORDER] = rowOrder.joinToString("|")
            this[FACT_REMOVED_ROWS] = removedRows.joinToString("|")
            this[FACT_REQUIRE_AUTH] = false
            this[FACT_THEME] = "auto"
            if (customFeaturesJson != null) this[FACT_CUSTOM_FEATURES] = customFeaturesJson

            val count = this[intPreferencesKey("fact_update_count")] ?: 0
            this[intPreferencesKey("fact_update_count")] = count + 1

            // Toggles
            spec.toggles.forEach { toggle ->
                val v = config[toggle.id] as? Boolean ?: toggle.defaultValue
                this[booleanPreferencesKey("fact_toggle_${toggle.id}")] = v
            }

            // Picker visibility: cards store hidden ids per row as "picker_hidden:<rowId>".
            spec.rows.filterIsInstance<PickerRowSpec>().forEach { picker ->
                val hidden = (config["picker_hidden:${picker.id}"] as? List<*>)
                    ?.filterIsInstance<String>()?.toSet() ?: emptySet()
                val visible = picker.options.map { it.id }.filter { it !in hidden }
                this[pickerVisibleKey(picker.id)] = visible.joinToString("|")
            }

            // Button-style cards: carry the button's appearance and tap action over to the
            // widget's button_* prefs (read by FactoryGlanceAppWidget/ButtonAction).
            if (spec.style == CardStyle.BUTTON) {
                val target = config["entityId"] as? String ?: ""
                val service = (config["service"] as? String).orEmpty().ifBlank {
                    // Entity mode: resolve the per-domain action now, since the widget's
                    // ButtonAction only fires explicit "domain.service" strings.
                    if (target.isBlank()) ""
                    else buttonEntityAction(target.substringBefore(".")).let { "${it.first}.${it.second}" }
                }
                this[stringPreferencesKey("button_label")] = config["label"] as? String ?: ""
                val labelBlocksJson = (config["label_blocks"] as? List<*>)
                    ?.filterIsInstance<Map<String, Any>>()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { blocks ->
                        val blocksType = Types.newParameterizedType(
                            List::class.java,
                            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                        )
                        runCatching { moshi.adapter<List<Map<String, Any>>>(blocksType).toJson(blocks) }.getOrNull()
                    }
                this[FACT_BUTTON_LABEL_BLOCKS] = labelBlocksJson ?: ""
                this[stringPreferencesKey("button_icon")] = config["icon"] as? String ?: "bolt"
                this[booleanPreferencesKey("button_vertical")] = config["vertical"] as? Boolean ?: false
                (config["bgColor"] as? Number)?.toLong()?.let { this[longPreferencesKey("button_bg_color")] = it }
                this[stringPreferencesKey("button_service")] = service
                this[stringPreferencesKey("button_entityId")] = target
                this[stringPreferencesKey("button_data")] = config["data"] as? String ?: ""
            }

            if (state != null) mapHAEntityToPreferences(state, spec, this)
        }
    }

    FactoryGlanceAppWidget(widgetKey).update(context, glanceId)
    WidgetRefreshScheduler.requestProviderUpdate(context, widgetKey, appWidgetId)
    WidgetRefreshScheduler.requestImmediate(context, widgetKey, appWidgetId, refreshFromApi = true)
}
