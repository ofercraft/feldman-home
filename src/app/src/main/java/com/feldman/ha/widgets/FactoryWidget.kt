package com.feldman.ha.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.DrawableRes
import com.feldman.ha.data.HAEntity
import com.feldman.ha.R
import com.feldman.ha.ui.cards.evaluateCondition
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.color.ColorProvider as BackgroundColorProvider
import com.feldman.ha.api.getStoredApi
import com.feldman.ha.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.graphics.toColorInt
import com.feldman.ha.widgets.generated.*
import kotlin.time.Duration.Companion.milliseconds

private const val WIDGET_LOG_TAG = "WidgetRefresh"
private const val VISIBLE_REFRESH_INTERVAL_MS = 5_000L
private const val VISIBLE_REFRESH_TIMEOUT_MS = 4_000L
private val apiRefreshMutex = Mutex()
private val visibleSyncMutex = Mutex()
private val widgetActionMutex = Mutex()

// ---------- Cached Definition (to prevent multiple DataStore instances) ----------
object CachedPreferencesGlanceStateDefinition : GlanceStateDefinition<Preferences> {
    private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

    private fun normalizeKey(key: String): String {
        return when {
            key.contains("appWidgetId=") -> key.substringAfter("appWidgetId=").substringBefore(")")
            key.startsWith("appWidget-") -> key.removePrefix("appWidget-")
            else -> key
        }
    }

    private fun getOldKey(normalized: String): String {
        return "AppWidgetId(appWidgetId=$normalized)"
    }

    override fun getLocation(context: Context, fileKey: String): File {
        val normalized = normalizeKey(fileKey)
        val newFile = context.preferencesDataStoreFile("appWidget-$normalized")
        
        // Aggressive Migration: If new file doesn't exist, search for any candidate old files
        if (!newFile.exists()) {
            val candidates = listOf(
                "appWidget-${getOldKey(normalized)}",
                getOldKey(normalized),
                "appWidget-$normalized",
                normalized,
                "glance-$normalized",
                "glance-${getOldKey(normalized)}"
            )
            
            for (oldName in candidates) {
                val oldFile = context.preferencesDataStoreFile(oldName)
                if (oldFile.exists()) {
                    try {
                        oldFile.copyTo(newFile, overwrite = false)
                        Log.d("WidgetSync", "Successfully migrated widget data from $oldName to appWidget-$normalized")
                        WidgetSyncLogger.log(context, "Restored settings from $oldName")
                        break
                    } catch (e: Exception) {
                        Log.e("WidgetSync", "Failed to migrate from $oldName", e)
                    }
                }
            }
        }
        
        return newFile
    }

    fun dataStore(context: Context, fileKey: String): DataStore<Preferences> {
        val normalized = normalizeKey(fileKey)
        val appCtx = context.applicationContext
        return synchronized(dataStores) {
            dataStores.getOrPut(normalized) {
                PreferenceDataStoreFactory.create(
                    produceFile = { getLocation(appCtx, normalized) }
                )
            }
        }
    }

    fun dataStoreForAppWidget(context: Context, appWidgetId: Int): DataStore<Preferences> =
        dataStore(context, appWidgetId.toString())

    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<Preferences> =
        dataStore(context, fileKey)
}
val FACT_ENTITY_ID      = stringPreferencesKey("fact_entity_id")
val FACT_FRIENDLY_NAME  = stringPreferencesKey("fact_friendly_name")
val FACT_STATE          = stringPreferencesKey("fact_state")       // on/off/…
val FACT_ERROR          = stringPreferencesKey("fact_error")       // error reporting
val FACT_LAST_INTERACTION = androidx.datastore.preferences.core.longPreferencesKey("fact_last_interaction")
val FACT_LAST_SYNC = longPreferencesKey("fact_last_sync")
val FACT_OPTIMISTIC_ROW = stringPreferencesKey("fact_optimistic_row")
val FACT_OPTIMISTIC_INT = intPreferencesKey("fact_optimistic_int")
val FACT_OPTIMISTIC_STRING = stringPreferencesKey("fact_optimistic_string")
val FACT_OPTIMISTIC_ROLLBACK_INT = intPreferencesKey("fact_optimistic_rollback_int")
val FACT_OPTIMISTIC_ROLLBACK_STRING = stringPreferencesKey("fact_optimistic_rollback_string")
val FACT_ROW_ORDER = stringPreferencesKey("fact_row_order")
val FACT_REMOVED_ROWS = stringPreferencesKey("fact_removed_rows")
val FACT_REQUIRE_AUTH = booleanPreferencesKey("fact_require_auth")

/** Which option is armed and waiting for a second tap, as "rowId:optionId", and when. */
val FACT_PENDING_CONFIRM = stringPreferencesKey("fact_pending_confirm")
val FACT_PENDING_CONFIRM_AT = longPreferencesKey("fact_pending_confirm_at")

/** How long an armed confirm option waits for its second tap before disarming. */
const val CONFIRM_TIMEOUT_MS = 4_000L

/** Dead time straight after arming, during which the confirming tap is ignored. */
const val CONFIRM_GUARD_MS = 450L
val FACT_ICON = stringPreferencesKey("fact_icon")
val FACT_THEME = stringPreferencesKey("fact_theme")
val FACT_STATE_TEMPLATE = stringPreferencesKey("fact_state_template")
val FACT_CUSTOM_FEATURES = stringPreferencesKey("fact_custom_features") // JSON list of custom features
val FACT_REF_ENTITIES = stringPreferencesKey("fact_ref_entities") // JSON {entity_id: {state, attributes}} for templates
val FACT_BUTTON_LABEL_BLOCKS = stringPreferencesKey("button_label_blocks") // JSON [{type, …}] composed button label blocks
val FACT_ALARM_CODE = stringPreferencesKey("fact_alarm_code") // optional saved PIN for alarm arm/disarm
// dynamic row values are stored as: fact_row_<rowId>
fun rowKey(rowId: String) = intPreferencesKey("fact_row_$rowId")
fun rowStringKey(rowId: String) = stringPreferencesKey("fact_row_$rowId")
fun pickerVisibleKey(rowId: String) = stringPreferencesKey("fact_picker_visible_$rowId")
fun pickerColorKey(rowId: String, optionId: String) = stringPreferencesKey("fact_picker_color_${rowId}_$optionId")
fun pickerIconKey(rowId: String, optionId: String) = stringPreferencesKey("fact_picker_icon_${rowId}_$optionId")
fun pickerOptionOrderKey(rowId: String) = stringPreferencesKey("fact_picker_order_$rowId")
fun rowMinKey(rowId: String) = intPreferencesKey("fact_row_min_$rowId")
fun rowMaxKey(rowId: String) = intPreferencesKey("fact_row_max_$rowId")

private fun counterMinAttribute(rowId: String): String =
    if (rowId == "target_humidity") "min_humidity" else "min_temp"

private fun counterMaxAttribute(rowId: String): String =
    if (rowId == "target_humidity") "max_humidity" else "max_temp"

private fun Preferences.safeInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>): Int? =
    try { this[key] } catch (_: ClassCastException) { null }

private fun Preferences.safeString(key: androidx.datastore.preferences.core.Preferences.Key<String>): String? =
    try { this[key] } catch (_: ClassCastException) { null }

private fun Preferences.readRowInt(rowId: String): Int? {
    val intKey = rowKey(rowId)
    val strKey = rowStringKey(rowId)
    return safeInt(intKey) ?: safeString(strKey)?.toIntOrNull()
}

private fun Preferences.readRowString(rowId: String): String {
    val strKey = rowStringKey(rowId)
    val intKey = rowKey(rowId)
    return safeString(strKey) ?: safeInt(intKey)?.toString().orEmpty()
}

private fun MutablePreferences.clearOptimisticRow(rowId: String) {
    if (this[FACT_OPTIMISTIC_ROW] == rowId) {
        remove(FACT_OPTIMISTIC_ROW)
        remove(FACT_OPTIMISTIC_INT)
        remove(FACT_OPTIMISTIC_STRING)
        remove(FACT_OPTIMISTIC_ROLLBACK_INT)
        remove(FACT_OPTIMISTIC_ROLLBACK_STRING)
    }
}

private fun MutablePreferences.writeOptimisticAwareInt(rowId: String, remoteValue: Int) {
    val optimisticValue = safeInt(FACT_OPTIMISTIC_INT)
    if (this[FACT_OPTIMISTIC_ROW] == rowId && optimisticValue != null) {
        this[rowKey(rowId)] = optimisticValue
        if (remoteValue == optimisticValue) clearOptimisticRow(rowId)
    } else {
        this[rowKey(rowId)] = remoteValue
    }
}

private fun MutablePreferences.writeOptimisticAwareString(rowId: String, remoteValue: String) {
    val optimisticValue = safeString(FACT_OPTIMISTIC_STRING)
    if (this[FACT_OPTIMISTIC_ROW] == rowId && optimisticValue != null) {
        this[rowStringKey(rowId)] = optimisticValue
        if (remoteValue == optimisticValue) clearOptimisticRow(rowId)
    } else {
        this[rowStringKey(rowId)] = remoteValue
    }
}

private fun Preferences.currentPickerIcon(spec: WidgetSpec): Any? {
    val row = spec.rows.filterIsInstance<PickerRowSpec>().firstOrNull() ?: return null
    val currentVal = readRowString(row.id).ifBlank { this[FACT_STATE].orEmpty() }
    if (currentVal.isBlank()) return null

    val mappedCurrentVal = row.stateAliases[currentVal] ?: currentVal
    val option = row.transitionalStates[currentVal]
        ?: row.transitionalStates[mappedCurrentVal]
        ?: row.options.firstOrNull { it.id == mappedCurrentVal || it.id == currentVal }
        ?: defaultPickerOption(row.id, mappedCurrentVal)

    return safeString(pickerIconKey(row.id, option.id))
        ?: safeString(pickerIconKey(row.id, mappedCurrentVal))
        ?: safeString(pickerIconKey(row.id, currentVal))
        ?: option.icon
}

fun evaluateSupported(logic: SupportedLogic?, prefs: Preferences): Boolean {
    if (logic == null) return true
    val attrKey = stringPreferencesKey("attr_${logic.attribute}")
    val attrVal = prefs[attrKey] ?: return false
    
    if (logic.contains != null) {
        val list = attrVal.split("|").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val result = list.contains(logic.contains.lowercase())
        Log.v(WIDGET_LOG_TAG, "evaluate[${logic.attribute}]: contains=${logic.contains} in=$list -> $result")
        return result
    }

    if (logic.containsAny != null) {
        val list = attrVal.split("|").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val wanted = logic.containsAny.map { it.lowercase() }
        val result = list.any { it in wanted }
        Log.v(WIDGET_LOG_TAG, "evaluate[${logic.attribute}]: containsAny=$wanted in=$list -> $result")
        return result
    }
    
    if (logic.bitmask != null) {
        val intVal = attrVal.toIntOrNull() ?: attrVal.toDoubleOrNull()?.toInt() ?: 0
        val result = (intVal and logic.bitmask) != 0
        Log.v(WIDGET_LOG_TAG, "evaluate[${logic.attribute}]: bitmask=${logic.bitmask} val=$intVal -> $result")
        return result
    }
    
    return true
}

private fun parsePipeList(raw: String?): List<String> =
    raw.orEmpty().split("|").map { it.trim() }.filter { it.isNotBlank() }

private fun parsePickerVisible(raw: String?): List<String>? =
    raw?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() }

private fun parsePipeSet(raw: String?): Set<String> = parsePipeList(raw).toSet()

// ---------- Action keys (generic) ----------
val K_WIDGET_KEY   = ActionParameters.Key<String>("w_key")
val K_ROW_ID       = ActionParameters.Key<String>("row")
val K_BOOL         = ActionParameters.Key<Boolean>("b")
val K_INT          = ActionParameters.Key<Int>("i")
val K_INT_DELTA    = ActionParameters.Key<Int>("di")
val K_STRING       = ActionParameters.Key<String>("s")
val K_ACTION_KEY   = ActionParameters.Key<String>("ak")
val K_TIMESTAMP    = ActionParameters.Key<Long>("ts")
private val K_OPEN_ENTITY_SHEET = ActionParameters.Key<String>(MainActivity.EXTRA_OPEN_ENTITY_SHEET)

object WidgetRegistry {
    private val map = mutableMapOf<String, WidgetSpec>()
    
    fun register(key: String, spec: WidgetSpec) { map[key] = spec }
    // Look up by registry key first (e.g. "alarm" — used by the widget code paths), then fall
    // back to the entity domain (e.g. "alarm_control_panel" — used by the in-app dashboard, which
    // derives the lookup from entity_id). Without this fallback the alarm spec — whose key
    // ("alarm") differs from its domain ("alarm_control_panel") — was invisible to the dashboard,
    // so its card rendered the generic "Entity ID + Toggle" fallback and its config page (gated on
    // spec != null) never opened. Every other domain has key == domain, so they were unaffected.
    fun spec(key: String): WidgetSpec? = map[key] ?: map.values.firstOrNull { it.domain == key }
    fun hasSpecs(): Boolean = map.isNotEmpty()

    /** Registry key (e.g. "alarm") for an entity domain (e.g. "alarm_control_panel"). */
    fun keyForDomain(domain: String): String? =
        map.entries.firstOrNull { it.key == domain || it.value.domain == domain }?.key
    
    fun init(context: Context) {
        if (map.isNotEmpty()) return

        // Primary source-of-truth: Kotlin widget definitions.
        WidgetDefinitions.registerAll()
    }
}

// ---------- Logic model ----------
data class SupportedLogic(
    val attribute: String,             // e.g. "hvac_modes", "supported_features"
    val contains: String? = null,      // e.g. "cool" (check if list contains)
    val bitmask: Int? = null,          // e.g. 1 (check bitmask for supported_features)
    // Satisfied when the list holds *any* of these. Needed where a capability is implied by a
    // set of values rather than one: a light is dimmable if supported_color_modes holds any mode
    // other than "onoff", which no single `contains` check can express.
    val containsAny: List<String>? = null
)

// ---------- Spec model ----------
data class ConfigToggleSpec(
    val id: String,
    val label: String,
    val defaultValue: Boolean,
    val icon: String? = null
)

data class ConfigEntityPickerSpec(
    val id: String,
    val label: String,
    val entityPrefix: String,
    val icon: String? = null
)

data class ConfigureQuery(
    val entityPrefix: String // e.g. "fan." or "light."
)

enum class RowVisibility { BOTH, WIDGET, APP }

sealed interface RowSpec { 
    val id: String
    val hiddenIfToggle: String?
    val hiddenIfStates: List<String>
    val supportedIf: SupportedLogic?
    val visibility: RowVisibility
}

enum class CardStyle { DEFAULT, BUTTON, CAMERA, CLOCK }

data class WidgetSpec(
    val key: String,
    val title: String,
    val iconRes: Int?,
    val domain: String,                  // e.g. "fan", "light"
    val style: CardStyle = CardStyle.DEFAULT,
    val configureQuery: ConfigureQuery,  // how to list entities
    val rows: List<RowSpec>,
    val haBindings: List<Binding>,       // entity.attr -> pref key
    val actions: ServiceMap,             // resolution for toggle/set/etc
    val toggles: List<ConfigToggleSpec> = emptyList(), // generic config options
    val entityPickers: List<ConfigEntityPickerSpec> = emptyList(), // secondary entity selection
    val sheet: SheetSpec? = null         // declarative tap-to-open state sheet (null = generic)
)

/** One of the ways a row can be drawn, offered in that row's feature settings. */
data class RowStyleOption(val id: String, val label: String)

/**
 * Declares that a row has more than one presentation and remembers which was chosen.
 *
 * [configKey] is the card-config entry the choice is stored under, so a style survives the same
 * way hidden picker options do.
 */
data class RowStyleSpec(
    val configKey: String,
    val default: String,
    val options: List<RowStyleOption>
)

/** A picker rendered as a single toggle button rather than one button per option. */
const val ROW_STYLE_TOGGLE = "toggle"

data class PickerRowSpec(
    override val id: String,
    val options: List<PickerOptionSpec>,
    val transitionalStates: Map<String, PickerOptionSpec> = emptyMap(),
    val stateAliases: Map<String, String> = emptyMap(),
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.BOTH,
    /** Non-null when this row can be switched between presentations from its settings. */
    val style: RowStyleSpec? = null
): RowSpec

data class PickerOptionSpec(
    val id: String,
    val label: String,
    val color: Long? = null,
    val nightColor: Long? = null,
    val icon: Any? = null,
    val actionOverrideIfToggle: Pair<String, String>? = null,
    val supportedIf: SupportedLogic? = null,
    val longPressColor: Long? = null,
    val longPressNightColor: Long? = null,
    /**
     * Requires a second tap before the service is called. For anything whose cost of being
     * triggered by accident is high enough to be worth a moment's friction — unlocking a door
     * from a pocket, say.
     */
    val confirm: Boolean = false
) {
    constructor(id: String, label: String, @DrawableRes iconRes: Int, actionOverrideIfToggle: Pair<String, String>? = null) : this(
        id = id,
        label = label,
        icon = iconRes,
        actionOverrideIfToggle = actionOverrideIfToggle
    )

    constructor(id: String, label: String, motionSymbol: String, actionOverrideIfToggle: Pair<String, String>? = null) : this(
        id = id,
        label = label,
        icon = motionSymbol,
        actionOverrideIfToggle = actionOverrideIfToggle
    )
}

data class CounterRowSpec(
    override val id: String,
    val step: Int = 1,
    val min: Int = 0,
    val max: Int = 100,
    val decIcon: Any? = null,
    val incIcon: Any? = null,
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.BOTH
): RowSpec

data class DataRowSpec(
    override val id: String,
    val label: String? = null,
    val unit: String? = null,
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.BOTH
): RowSpec

enum class SliderBackground { NONE, RGB, TEMPERATURE, BRIGHTNESS }

data class SliderRowSpec(
    override val id: String,
    val label: String? = null,
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Float = 1f,
    val icon: String? = null,
    val background: SliderBackground = SliderBackground.NONE,
    /** Service to call (e.g. "set_cover_position"). Defaults to "turn_on". */
    val serviceOverride: String? = null,
    /** Service param name for the slider value (e.g. "position"). Defaults to the row id. */
    val paramName: String? = null,
    /** Entity attribute to update optimistically (e.g. "current_position"). Defaults to paramName ?? row id. */
    val optimisticAttr: String? = null,
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.APP
): RowSpec

data class ButtonsRowSpec(
    override val id: String,
    val buttons: List<WidgetButtonSpec>,
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.BOTH
): RowSpec

data class SpacerRowSpec(
    override val id: String = "spacer",
    val height: Int = 20,
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.WIDGET
): RowSpec

data class CustomFeatureRowSpec(
    override val id: String,
    val type: String,
    val label: String,
    val icon: String? = null,
    val serviceName: String? = null,
    val serviceData: Map<String, Any>? = null,
    val targetEntity: String? = null,
    val targets: List<Map<String, String>>? = null,
    val valueTemplate: String? = null,
    val visibilityConditions: List<Map<String, Any>>? = null,
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.BOTH
): RowSpec

/**
 * A row that opens a media player's remote control panel. App-only (the remote is an
 * interactive dialog), so it never appears in widgets but is counted toward card height.
 */
data class RemoteRowSpec(
    override val id: String = "remote",
    override val hiddenIfToggle: String? = null,
    override val hiddenIfStates: List<String> = emptyList(),
    override val supportedIf: SupportedLogic? = null,
    override val visibility: RowVisibility = RowVisibility.APP,
): RowSpec

data class WidgetButtonSpec(
    val id: String,
    val label: String,
    val icon: String? = null,
    val action: String, // service name (e.g. "open_cover") called on the entity's domain
    val supportedIf: SupportedLogic? = null // per-button feature gate, e.g. cover STOP (bitmask 8)
)

enum class Mapping { IDENTITY, SCALE255 }

data class Binding(                    // binds HA attribute → Preference key
    val attribute: String,             // "percentage", "state", "brightness", …
    val targetPref: String,            // "fact_row_brightness", "fact_state", …
    val transform: Mapping? = null
)

data class ServiceCall(
    val domain: String,
    val service: String,
    val params: Map<String, String>    // { "entity_id":"{entity}", "percentage":"{value}" }
)

data class ServiceMap(
    val calls: Map<String, ServiceCall> = emptyMap()
)

// ---------- Sheet (entity state panel) spec model ----------
//
// Like card rows, the tap-to-open state sheet is declared per widget in WidgetDefinitions:
// a list of blocks rendered by FactorySheetContent (EntityStateDialog.kt). Widgets without
// a sheet spec fall back to the generic sheet. Extend the block vocabulary here as new
// cards need richer sheets.

/** Accent-color families a sheet dropdown can use for its selected value. */
enum class SheetAccent { PRIMARY, CLIMATE_MODE, CLIMATE_FAN, CLIMATE_PRESET, CLIMATE_SWING }

enum class SheetHeroKind { DIAL, SLIDER }

sealed interface SheetBlockSpec { val id: String }

/** The big centerpiece control (thermostat dial, brightness slider, …). */
data class SheetHeroSpec(
    override val id: String,
    val kind: SheetHeroKind,
    val valueAttr: String,                       // target value attribute ("temperature", "brightness")
    val fallbackAttrs: List<String> = emptyList(), // read these when valueAttr is missing
    val currentAttr: String? = null,             // read-only marker value (dial's current temp)
    val minAttr: String? = null,                 // attribute holding the min, else minDefault
    val maxAttr: String? = null,
    val stepAttr: String? = null,
    val minDefault: Double = 0.0,
    val maxDefault: Double = 100.0,
    val stepDefault: Double = 1.0,
    val unitAttr: String? = null,
    val unitDefault: String = "",
    /** Send the committed value as 0-100 percent of the min..max range instead of raw. */
    val sendAsPercentOfRange: Boolean = false,
    /** Show the value as a percent of the range (independent of what gets sent). */
    val displayAsPercentOfRange: Boolean = false,
    val actionKey: String,                       // ServiceMap call fired on commit ({value})
    val supportedIf: SupportedLogic? = null      // hide the hero when the entity lacks the feature
) : SheetBlockSpec

/** A row of large action buttons (cover open/stop/close, lock open, …). */
data class SheetButtonSpec(
    val id: String,
    val label: String,
    val icon: String,
    val actionKey: String,
    val supportedIf: SupportedLogic? = null
)

data class SheetButtonsSpec(
    override val id: String,
    val buttons: List<SheetButtonSpec>
) : SheetBlockSpec

/** Read-only value line, e.g. "Current temperature — 24.6 °C". */
data class SheetDataSpec(
    override val id: String,
    val label: String,
    val attribute: String,
    val unitAttr: String? = null,
    val unitDefault: String = ""
) : SheetBlockSpec

/** Dropdown bound to an options attribute and a value attribute (null = the entity state). */
data class SheetDropdownSpec(
    override val id: String,
    val label: String,
    val optionsAttr: String? = null,             // e.g. "fan_modes"; null = options come from the picker row
    val valueAttr: String? = null,               // e.g. "fan_mode"; null = entity.state
    val actionKey: String,                       // ServiceMap call fired on pick ({value})
    val optionsFromRow: String? = null,          // merge icons/labels/order from this PickerRowSpec
    val accent: SheetAccent = SheetAccent.PRIMARY
) : SheetBlockSpec

/** Embed the widget's regular card rows inside the sheet (what the generic sheet shows). */
data class SheetCardRowsSpec(override val id: String = "rows") : SheetBlockSpec

data class SheetSpec(
    val blocks: List<SheetBlockSpec>
)

fun mapValue(mapping: Mapping?, pct: Int): Int =
    when (mapping) {
        Mapping.SCALE255 -> (pct.coerceIn(0,100) * 255) / 100
        else -> pct.coerceIn(0,100)
    }

// ---------- Factory widget ----------
suspend fun syncWidgetStateFromApi(
    context: Context,
    widgetKey: String,
    glanceId: GlanceId,
    force: Boolean = false,
    staleAfterMillis: Long = 30_000L,
    timeoutMillis: Long = 10_000L,
    respectBackgroundRestriction: Boolean = true
) {
    val appCtx = context.applicationContext
    
    // 1. Resolve entityId
    val prefs = try {
        getAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId)
    } catch (e: Exception) {
        Log.e(WIDGET_LOG_TAG, "sync[$widgetKey]: failed to read prefs", e)
        return
    }
    
    val entityId = prefs[FACT_ENTITY_ID] ?: run {
        Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: skipped, no entity_id in prefs. Available keys: ${prefs.asMap().keys.map { it.name }}")
        return
    }
    
    Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: all keys in prefs: ${prefs.asMap().keys.map { it.name }}")

    val now = System.currentTimeMillis()
    val lastInteraction = prefs[FACT_LAST_INTERACTION] ?: 0L
    val lastSync = prefs[FACT_LAST_SYNC] ?: 0L
    if (!force) {
        if (now - lastInteraction <= staleAfterMillis) {
            Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: skipped, recent interaction")
            return
        }
        if (now - lastSync <= staleAfterMillis) {
            Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: skipped, fresh visible state")
            return
        }
    }
    // If force is true, we proceed regardless of lastInteraction

    Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: starting sync for $entityId (force=$force)")
    val canAttemptNetwork = if (respectBackgroundRestriction) {
        WidgetNetworkGate.canAttemptNetwork(appCtx, "sync[$widgetKey]")
    } else {
        WidgetNetworkGate.canAttemptVisibleWidgetNetwork(appCtx, "visible sync[$widgetKey]")
    }
    if (!canAttemptNetwork) {
        return
    }

    try {
        // 2. Initialize API
        val api = withContext(Dispatchers.IO) { 
            getStoredApi(appCtx) ?: throw IllegalStateException("No API credentials configured")
        }

        // 3. Fetch States. Standalone button widgets are backed by a synthetic
        // "button.<uuid>" entity that doesn't exist in HA — skip the primary fetch and
        // only refresh the entities their label rules reference.
        val state = if (WidgetRegistry.spec(widgetKey)?.style == CardStyle.BUTTON) {
            HAEntity(entityId, "idle", emptyMap())
        } else {
            kotlinx.coroutines.withTimeout(timeoutMillis.milliseconds) {
                api.getState(entityId)
            }
        }
        
        val extraStates = mutableMapOf<String, HAEntity>()
        val spec = WidgetRegistry.spec(widgetKey)
        if (spec != null) {
            spec.entityPickers.forEach { picker ->
                val eid = prefs[stringPreferencesKey("fact_extra_entity_${picker.id}")]
                if (eid != null) {
                    Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: fetching extra entity $eid for picker ${picker.id}")
                    try {
                        val s = api.getState(eid)
                        extraStates[eid] = s
                        Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: fetched extra $eid = ${s.state}")
                    } catch (e: Exception) {
                        WidgetNetworkGate.reportFailure(appCtx, e, "sync[$widgetKey] extra")
                        if (WidgetNetworkGate.isNetworkFailure(e)) {
                            Log.w(WIDGET_LOG_TAG, "sync[$widgetKey]: network failed while fetching extra entity $eid")
                        } else {
                            Log.e(WIDGET_LOG_TAG, "sync[$widgetKey]: failed to fetch extra entity $eid", e)
                        }
                    }
                }
            }
        }

        // 3.5 Fetch entities referenced by custom features / state template so their
        // templates and visibility conditions refresh (and so they get websocket-subscribed).
        val refEntityIds = buildSet {
            addAll(templateEntityIds(prefs[FACT_STATE_TEMPLATE]))
            addAll(customFeatureEntityIds(parseCustomFeatures(prefs[FACT_CUSTOM_FEATURES])))
            addAll(buttonLabelBlockEntityIds(parseButtonLabelBlocks(prefs[FACT_BUTTON_LABEL_BLOCKS])))
            remove(entityId)
            removeAll(extraStates.keys)
        }
        val refStates = mutableListOf<HAEntity>()
        for (rid in refEntityIds) {
            runCatching { withContext(Dispatchers.IO) { api.getState(rid) } }
                .getOrNull()?.let { refStates.add(it) }
        }
        Log.d(WIDGET_LOG_TAG, "sync[$widgetKey]: fetched ${refStates.size} referenced entities: ${refStates.map { it.entity_id }}")

        Log.v(WIDGET_LOG_TAG, "sync[$widgetKey]: raw HA state entity=$entityId state=${state.state} extraCount=${extraStates.size}")

        // 4. Update Preferences
        updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply {
                this[FACT_REF_ENTITIES] = serializeRefEntities(refStates)
                val spec = WidgetRegistry.spec(widgetKey)
                if (spec != null) {
                    mapHAEntityToPreferences(state, spec, this)
                    
                    // Map extra states attributes using their picker IDs
                    spec.entityPickers.forEach { picker ->
                        val eid = this[stringPreferencesKey("fact_extra_entity_${picker.id}")]
                        val extra = extraStates[eid]
                        if (extra != null) {
                            // Primary telemetry: the state itself
                            this[stringPreferencesKey("attr_${picker.id}")] = extra.state
                            
                            // Also map all attributes for advanced logic/display
                            extra.attributes.forEach { (k, v) ->
                                val key = stringPreferencesKey("attr_$k")
                                when (v) {
                                    is Number -> this[key] = v.toString()
                                    is String -> this[key] = v
                                    is Boolean -> this[key] = v.toString()
                                }
                            }
                        }
                    }
                }
            }
        }
        WidgetNetworkGate.reportSuccess()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val errorMsg = when (e) {
            is kotlinx.coroutines.TimeoutCancellationException -> "Sync timeout (${timeoutMillis / 1000}s)"
            else -> e.localizedMessage ?: e.javaClass.simpleName
        }
        WidgetNetworkGate.reportFailure(appCtx, e, "sync[$widgetKey]")
        if (WidgetNetworkGate.isNetworkFailure(e)) {
            Log.w(WIDGET_LOG_TAG, "sync[$widgetKey]: network failed ($errorMsg)")
        } else {
            Log.e(WIDGET_LOG_TAG, "sync[$widgetKey]: failed ($errorMsg)", e)
        }
        
        try {
            updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().apply {
                    this[FACT_ERROR] = "Error: $errorMsg"
                }
            }
        } catch (ex: Exception) {
            Log.e(WIDGET_LOG_TAG, "sync[$widgetKey]: failed to write error to prefs", ex)
        }
    }
}

fun mapHAEntityToPreferences(state: HAEntity, spec: WidgetSpec, prefs: androidx.datastore.preferences.core.MutablePreferences) {
    prefs[FACT_STATE] = state.state
    prefs[FACT_LAST_SYNC] = System.currentTimeMillis()
    val currentCount = prefs[androidx.datastore.preferences.core.intPreferencesKey("fact_update_count")] ?: 0
    prefs[androidx.datastore.preferences.core.intPreferencesKey("fact_update_count")] = currentCount + 1
    prefs.remove(FACT_ERROR) // Clear any previous error
    
    Log.d(WIDGET_LOG_TAG, "sync[${spec.key}]: HA attributes keys=${state.attributes.keys}")
    
    // Store ALL attributes with prefix for logic evaluation
    state.attributes.forEach { (k, v) ->
        val key = stringPreferencesKey("attr_$k")
        when (v) {
            is List<*> -> prefs[key] = v.joinToString("|")
            is Number -> prefs[key] = v.toString()
            is String -> prefs[key] = v
            is Boolean -> prefs[key] = v.toString()
        }
    }

    var mappedCount = 0
    spec.rows.forEach { row ->
        val attrName = row.id
        val attrVal = state.attributes[attrName] ?: state.attributes[attrName.lowercase()]
            ?: if (attrName == "state" || attrName == "hvac_mode") state.state else null

        if (attrVal != null) {
            Log.d(WIDGET_LOG_TAG, "sync[${spec.key}]: mapping row=${row.id} attr=$attrName val=$attrVal (${attrVal::class.simpleName})")
            when (attrVal) {
                is Number -> {
                    if (row is DataRowSpec) prefs[rowStringKey(row.id)] = attrVal.toString()
                    else prefs.writeOptimisticAwareInt(row.id, attrVal.toInt())
                    mappedCount++
                }
                is String -> {
                    val intVal = attrVal.toIntOrNull()
                    if (row is CounterRowSpec && intVal != null) {
                        prefs.writeOptimisticAwareInt(row.id, intVal)
                    } else {
                        prefs.writeOptimisticAwareString(row.id, attrVal)
                    }
                    mappedCount++
                }
                is Boolean -> {
                    prefs.writeOptimisticAwareInt(row.id, if (attrVal) 1 else 0)
                    mappedCount++
                }
                else -> Log.w(WIDGET_LOG_TAG, "sync[${spec.key}]: unknown type for row=${row.id}: ${attrVal::class.simpleName}")
            }
        } else {
            Log.v(WIDGET_LOG_TAG, "sync[${spec.key}]: no value found for row=${row.id} (attr=$attrName)")
        }

        // Handle dynamic min/max for counters
        if (row is CounterRowSpec) {
            val minAttr = state.attributes[counterMinAttribute(row.id)] ?: state.attributes["min"]
            val maxAttr = state.attributes[counterMaxAttribute(row.id)] ?: state.attributes["max"]
            if (minAttr is Number) prefs[rowMinKey(row.id)] = minAttr.toInt()
            else if (minAttr is String) minAttr.toIntOrNull()?.let { prefs[rowMinKey(row.id)] = it }
                ?: minAttr.toDoubleOrNull()?.toInt()?.let { prefs[rowMinKey(row.id)] = it }

            if (maxAttr is Number) prefs[rowMaxKey(row.id)] = maxAttr.toInt()
            else if (maxAttr is String) maxAttr.toIntOrNull()?.let { prefs[rowMaxKey(row.id)] = it }
                ?: maxAttr.toDoubleOrNull()?.toInt()?.let { prefs[rowMaxKey(row.id)] = it }
        }
    }
    
    val iconAttr = state.attributes["icon"] as? String
    if (iconAttr != null) {
        val clean = iconAttr.removePrefix("mdi:").replace("-", "_")
        // Map missing cover-related ligatures back to our local ic_blinds
        val finalIcon = if (clean.contains("shutter") || clean.contains("blind")) "blinds" else clean
        prefs[FACT_ICON] = finalIcon
    }
    Log.d(WIDGET_LOG_TAG, "sync[${spec.key}]: success, state=${state.state} mappedRows=$mappedCount")
}

suspend fun updateFactoryWidgets(
    context: Context,
    widgetKey: String? = null,
    appWidgetId: Int? = null,
    refreshFromApi: Boolean = false
): Boolean {
    val appCtx = context.applicationContext
    WidgetRegistry.init(appCtx)
    val glanceManager = GlanceAppWidgetManager(appCtx)
    val appWidgetManager = AppWidgetManager.getInstance(appCtx)
    var updatedAny = false
    var effectiveRefreshFromApi = refreshFromApi
    Log.d(
        WIDGET_LOG_TAG,
        "updateFactoryWidgets: widgetKey=$widgetKey appWidgetId=$appWidgetId refreshFromApi=$refreshFromApi"
    )

    if (effectiveRefreshFromApi && !WidgetNetworkGate.canAttemptNetwork(appCtx, "updateFactoryWidgets")) {
        effectiveRefreshFromApi = false
    }

    var refreshLockAcquired = false
    if (effectiveRefreshFromApi) {
        refreshLockAcquired = apiRefreshMutex.tryLock()
        if (!refreshLockAcquired) {
            Log.d(WIDGET_LOG_TAG, "updateFactoryWidgets: API refresh already running; rendering cached state")
            effectiveRefreshFromApi = false
        }
    }

    try {
        for (entry in FactoryWidgetProviderCatalog.entries) {
            if (widgetKey != null && entry.widgetKey != widgetKey) continue

            val provider = ComponentName(appCtx.packageName, entry.receiverClassName)
            val ids = appWidgetManager.getAppWidgetIds(provider)
                .filter { appWidgetId == null || it == appWidgetId }
            Log.d(WIDGET_LOG_TAG, "updateFactoryWidgets[${entry.widgetKey}]: found ${ids.size} ids for provider=${entry.receiverClassName}")

            for (id in ids) {
                val glanceId = runCatching { glanceManager.getGlanceIdBy(id) }
                    .onFailure { Log.w(WIDGET_LOG_TAG, "updateFactoryWidgets[${entry.widgetKey}]: no GlanceId for $id", it) }
                    .getOrNull()
                    ?: continue

                if (effectiveRefreshFromApi) {
                    if (!WidgetNetworkGate.canAttemptNetwork(appCtx, "api sync ${entry.widgetKey}")) {
                        effectiveRefreshFromApi = false
                    } else {
                        runCatching {
                            syncWidgetStateFromApi(
                                context = appCtx,
                                widgetKey = entry.widgetKey,
                                glanceId = glanceId,
                                force = true
                            )
                            FactoryGlanceAppWidget(entry.widgetKey).update(appCtx, glanceId)
                        }.onFailure {
                            WidgetNetworkGate.reportFailure(appCtx, it, "updateFactoryWidgets[${entry.widgetKey}]")
                            if (WidgetNetworkGate.isNetworkFailure(it)) {
                                Log.w(WIDGET_LOG_TAG, "updateFactoryWidgets[${entry.widgetKey}]: API sync network failure for $id")
                            } else {
                                Log.e(WIDGET_LOG_TAG, "updateFactoryWidgets[${entry.widgetKey}]: API sync failed for $id", it)
                            }
                        }
                    }
                }
                runCatching {
                    FactoryGlanceAppWidget(entry.widgetKey).update(appCtx, glanceId)
                    Log.d(WIDGET_LOG_TAG, "updateFactoryWidgets[${entry.widgetKey}]: update() CALLED for id=$id (glanceId=$glanceId)")
                }.onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) {
                        Log.e(WIDGET_LOG_TAG, "updateFactoryWidgets[${entry.widgetKey}]: update() FAILED for id=$id", it)
                    }
                }
                updatedAny = true
            }
        }
    } finally {
        if (refreshLockAcquired) {
            apiRefreshMutex.unlock()
        }
    }

    return updatedAny
}

suspend fun getAllConfiguredEntityIds(context: Context): List<String> {
    val appCtx = context.applicationContext
    val glanceManager = GlanceAppWidgetManager(appCtx)
    val appWidgetManager = AppWidgetManager.getInstance(appCtx)
    val entityIds = mutableSetOf<String>()

    for (entry in FactoryWidgetProviderCatalog.entries) {
        val provider = ComponentName(appCtx.packageName, entry.receiverClassName)
        val ids = appWidgetManager.getAppWidgetIds(provider)
        for (id in ids) {
            val glanceIdResult = runCatching { glanceManager.getGlanceIdBy(id) }
            val glanceId = glanceIdResult.getOrNull()
            
            if (glanceId == null) {
                Log.w(WIDGET_LOG_TAG, "getAllConfiguredEntityIds: No glanceId for id=$id (host may be custom or initialization pending). Error: ${glanceIdResult.exceptionOrNull()?.message}")
                continue
            }

            val state = runCatching { androidx.glance.appwidget.state.getAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) }.getOrNull()
            if (state == null) {
                Log.w(WIDGET_LOG_TAG, "getAllConfiguredEntityIds: Found glanceId for $id but failed to read state (DataStore may be empty)")
                continue
            }

            state.asMap().forEach { (key, value) ->
                if (key.name == "fact_entity_id" || key.name.startsWith("fact_extra_entity_")) {
                    entityIds.add(value.toString())
                }
            }
            // Also subscribe to entities referenced by the state template and custom features.
            entityIds.addAll(templateEntityIds(state[FACT_STATE_TEMPLATE]))
            entityIds.addAll(customFeatureEntityIds(parseCustomFeatures(state[FACT_CUSTOM_FEATURES])))
        }
    }
    WidgetSyncLogger.log(appCtx, "Discovery: Found ${entityIds.size} unique entities across all widgets")
    Log.d(WIDGET_LOG_TAG, "getAllConfiguredEntityIds: returning $entityIds")
    return entityIds.toList()
}

class FactoryGlanceAppWidget(private val widgetKey: String) : GlanceAppWidget() {
    override val stateDefinition = CachedPreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    @Composable
    private fun PreviewContent(spec: WidgetSpec, appCtx: Context, realPrefs: Preferences? = null) {
        val prefs = realPrefs ?: emptyPreferences()
        val rawSubtitle = prefs[FACT_STATE] ?: "unknown"
        
        val iconAttr = prefs[FACT_ICON]
        val icon = resolvePickerIcon(appCtx, prefs.currentPickerIcon(spec) ?: iconAttr ?: spec.iconRes ?: resolveDomainIcon(spec.domain))
        
        val stateAliasMap = spec.rows.filterIsInstance<PickerRowSpec>().firstOrNull()?.stateAliases
        val displayState = stateAliasMap?.get(rawSubtitle)?.replaceFirstChar { it.uppercase() }
            ?: rawSubtitle.replace("_", " ").replaceFirstChar { it.uppercase() }
        
        val title = prefs[FACT_FRIENDLY_NAME] ?: spec.title
        var subtitle = if (realPrefs != null) displayState else "Demo: $displayState"
        
        val battery = prefs[stringPreferencesKey("attr_battery_level")]
            ?: prefs[stringPreferencesKey("attr_battery")]
            
        if (battery != null) {
            subtitle = "$subtitle \u2022 $battery%"
        }

        WidgetCard(
            title = title,
            subtitle = subtitle,
            icon = icon
        ) {
            spec.rows.forEach { row ->
                if (row.visibility == RowVisibility.APP) return@forEach
                val isDemo = realPrefs == null
                if (!isDemo) {
                    if (row.id in parsePipeSet(prefs[FACT_REMOVED_ROWS])) return@forEach
                    if (!evaluateSupported(row.supportedIf, prefs)) return@forEach
                }

                when (row) {
                    is RemoteRowSpec -> Unit // App-only; never rendered in widgets.
                    is PickerRowSpec -> {
                        val currentVal = prefs.readRowString(row.id).ifBlank { null }
                        val selectedId = currentVal?.let { row.stateAliases[it] ?: it }
                            ?: if (isDemo) row.options.firstOrNull()?.id else null
                        val previewOptions = if (isDemo) row.options else pickerOptionsForPrefs(row, prefs)
                        val chips = previewOptions.map { opt ->
                            val isSelected = pickerOptionMatches(row.id, opt.id, selectedId)
                            val resolvedIcon = prefs.safeString(pickerIconKey(row.id, opt.id)) ?: opt.icon
                            ChipSpec(
                                id = opt.id,
                                color = if (isSelected) (opt.color ?: 0xFF4CAF50) else null,
                                nightColor = if (isSelected) opt.nightColor else null,
                                label = opt.label,
                                icon = resolvePickerIcon(appCtx, resolvedIcon),
                                isSelected = isSelected
                            )
                        }
                        chipsRow(
                            chips = chips,
                            onClick = { actionRunFactory(widgetKey, row.id, null, null, it.id) }
                        )
                    }
                    is CounterRowSpec -> {
                        val currentVal = prefs.readRowInt(row.id) ?: (row.min + (row.max - row.min) / 2)
                        counterRow(
                            value = currentVal,
                            min = row.min,
                            max = row.max,
                            onDec = actionRunFactory(widgetKey, row.id, intDelta = -row.step),
                            onInc = actionRunFactory(widgetKey, row.id, intDelta = row.step)
                        )
                    }
                    is DataRowSpec -> {
                        val value = prefs.readRowString(row.id).ifBlank { "23.5" }
                        dataRow(value = value, unit = row.unit ?: "")
                    }
                    is ButtonsRowSpec -> {
                        buttonsRow(
                            buttons = row.buttons.map { btn ->
                                WidgetButton(
                                    label = btn.label,
                                    icon = btn.icon,
                                    onClick = actionRunFactory(widgetKey, row.id, null, null, null, btn.action)
                                )
                            }
                        )
                    }
                    is SpacerRowSpec -> {
                        spacerRow(row.height)
                    }
                    is SliderRowSpec -> {
                        // Sliders not supported on widgets
                    }
                    is CustomFeatureRowSpec -> {
                        // Custom features not supported on widgets
                    }
                }
            }
        }
    }

    private suspend fun getFirstConfiguredPrefs(context: Context): Preferences? {
        val appCtx = context.applicationContext
        val glanceManager = GlanceAppWidgetManager(appCtx)
        val appWidgetManager = AppWidgetManager.getInstance(appCtx)
        val catalogEntry = FactoryWidgetProviderCatalog.entries.find { it.widgetKey == widgetKey } ?: return null
        val provider = ComponentName(appCtx.packageName, catalogEntry.receiverClassName)
        val ids = appWidgetManager.getAppWidgetIds(provider)
        for (id in ids) {
            val glanceId = runCatching { glanceManager.getGlanceIdBy(id) }.getOrNull() ?: continue
            val prefs = runCatching { androidx.glance.appwidget.state.getAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) }.getOrNull()
            if (prefs != null && prefs[FACT_ENTITY_ID] != null) return prefs
        }
        return null
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        WidgetRegistry.init(context)
        val spec = WidgetRegistry.spec(widgetKey) ?: return
        val appCtx = context.applicationContext
        val realPrefs = getFirstConfiguredPrefs(appCtx)
        provideContent {
            PreviewContent(spec, appCtx, realPrefs)
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetRegistry.init(context)
        val appCtx = context.applicationContext
        val appWidgetId = runCatching { GlanceAppWidgetManager(appCtx).getAppWidgetId(id) }.getOrNull()
        Log.d(WIDGET_LOG_TAG, "provide[$widgetKey]: start glanceId=$id appWidgetId=$appWidgetId")

        // Guard against stale Glance sessions that no longer map to a real provider info.
        if (appWidgetId == null || appWidgetId <= 0) {
            Log.w(WIDGET_LOG_TAG, "provide[$widgetKey]: missing appWidgetId for $id")
            return
        }
        val providerInfo = runCatching {
            android.appwidget.AppWidgetManager.getInstance(appCtx).getAppWidgetInfo(appWidgetId)
        }.getOrNull()
        if (providerInfo == null) {
            Log.w(WIDGET_LOG_TAG, "provide[$widgetKey]: no provider info appWidgetId=$appWidgetId")
            return
        }
        Log.d(WIDGET_LOG_TAG, "provide[$widgetKey]: appWidgetId=$appWidgetId rendering cached state immediately")

        // Render IMMEDIATELY from cached state. Do NOT block the first frame on a network sync:
        // Android's AppWidgetHost expects initial RemoteViews promptly when a widget is dropped on
        // the home screen, and blocking here (especially behind the global visibleSyncMutex while
        // other widgets sync over the network) made the host time out → "Can't load widget" / the
        // add appeared to fail. The configure activity already wrote fresh state before placement,
        // and the LaunchedEffect below keeps the widget refreshed.
        provideContent {
            androidx.compose.runtime.LaunchedEffect(appWidgetId) {
                // force=false: the staleness check inside syncWidgetStateFromApi pulls whenever the
                // cached state is older than VISIBLE_REFRESH_INTERVAL_MS (and an empty widget has
                // FACT_LAST_SYNC=0, so it always pulls). A freshly placed widget already has fresh
                // data from the configure activity, so this first iteration is a cheap no-op there.
                while (true) {
                    runCatching {
                        visibleSyncMutex.withLock {
                            syncWidgetStateFromApi(
                                context = appCtx,
                                widgetKey = widgetKey,
                                glanceId = id,
                                force = false,
                                staleAfterMillis = VISIBLE_REFRESH_INTERVAL_MS,
                                timeoutMillis = VISIBLE_REFRESH_TIMEOUT_MS,
                                respectBackgroundRestriction = false
                            )
                        }
                    }.onFailure { error ->
                        if (error !is kotlinx.coroutines.CancellationException) {
                            Log.w(WIDGET_LOG_TAG, "visible sync[$widgetKey]: failed: ${error.message}")
                        }
                    }
                    delay(VISIBLE_REFRESH_INTERVAL_MS)
                }
            }
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val spec = WidgetRegistry.spec(widgetKey)
            if (spec == null) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Missing spec: $widgetKey")
                }
                return@provideContent
            }

            val entityId = prefs[FACT_ENTITY_ID] ?: ""
            val widgetEntity = reconstructEntityFromPrefs(prefs)
            val templateEntities = listOf(widgetEntity) + parseRefEntities(prefs.safeString(FACT_REF_ENTITIES))
            val stateTemplateText = prefs[FACT_STATE_TEMPLATE]?.takeIf { it.isNotBlank() }
            val title = prefs[FACT_FRIENDLY_NAME] ?: spec.title
            val rawSubtitle = prefs[FACT_STATE] ?: "unknown"
            val updateCount = prefs[androidx.datastore.preferences.core.intPreferencesKey("fact_update_count")] ?: 0
            val rowOrderRaw = prefs[FACT_ROW_ORDER] ?: ""
            val widgetTheme = prefs[FACT_THEME] ?: "auto"
            val stateAliasMap = spec.rows.filterIsInstance<PickerRowSpec>().firstOrNull()?.stateAliases
            val displayState = stateAliasMap?.get(rawSubtitle)?.replaceFirstChar { it.uppercase() }
                ?: rawSubtitle.replace("_", " ").replaceFirstChar { it.uppercase() }

            // Try to find battery telemetry
            val batteryLevel = prefs[stringPreferencesKey("attr_battery_level")]
                ?: prefs[stringPreferencesKey("attr_battery")]
                ?: prefs[stringPreferencesKey("attr_battery_state")]

            Log.d(WIDGET_LOG_TAG, "provide[$widgetKey]: batteryLevel=$batteryLevel")

            val subtitle = when {
                // A custom state-text template wins and resolves across referenced entities.
                stateTemplateText != null -> evaluateWidgetTemplate(stateTemplateText, templateEntities)
                !batteryLevel.isNullOrBlank() -> {
                    val cleaned = batteryLevel.removeSuffix("%").trim()
                    if (cleaned.all { it.isDigit() || it == '.' }) "$displayState \u2022 $cleaned%"
                    else "$displayState \u2022 $batteryLevel"
                }
                else -> displayState
            }

            val iconAttr = prefs[FACT_ICON]
            val icon = resolvePickerIcon(appCtx, prefs.currentPickerIcon(spec) ?: iconAttr ?: spec.iconRes ?: resolveDomainIcon(spec.domain))
            
            val size = LocalSize.current
            Log.d(WIDGET_LOG_TAG, "provide[$widgetKey]: rendering id=$appWidgetId size=${size.width}x${size.height} count=$updateCount time=${System.currentTimeMillis()}")

            if (spec.style == CardStyle.BUTTON) {
                val isCompactButton = size.height != Dp.Unspecified && size.height < 90.dp
                val customBg = prefs[longPreferencesKey("button_bg_color")]
                val iconPref = prefs[androidx.datastore.preferences.core.stringPreferencesKey("button_icon")] ?: "bolt"
                val labelBlocks = parseButtonLabelBlocks(prefs[FACT_BUTTON_LABEL_BLOCKS])
                val labelPref = if (labelBlocks.isNotEmpty()) {
                    com.feldman.ha.ui.cards.resolveLabelBlocks(labelBlocks, templateEntities, appCtx)
                } else {
                    prefs[androidx.datastore.preferences.core.stringPreferencesKey("button_label")] ?: "Action"
                }
                val isVertical = prefs[booleanPreferencesKey("button_vertical")] ?: false

                val actionParams = actionParametersOf(
                    K_WIDGET_KEY to widgetKey
                )

                WithWidgetColors(widgetTheme) { widgetColors ->
                    // A picked color overrides the theme; otherwise the button shares the same
                    // background/content roles as every other widget.
                    val bgColor = customBg?.let {
                        val c = androidx.compose.ui.graphics.Color(it)
                        BackgroundColorProvider(c, c)
                    } ?: widgetColors.background
                    val contentColor = customBg?.let {
                        val on = if (androidx.compose.ui.graphics.Color(it).luminance() > 0.5f) {
                            androidx.compose.ui.graphics.Color.Black
                        } else {
                            androidx.compose.ui.graphics.Color.White
                        }
                        BackgroundColorProvider(on, on)
                    } ?: widgetColors.onBackground

                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .cornerRadius(24.dp)
                            .background(bgColor)
                            .padding(if (isCompactButton) 8.dp else 12.dp)
                            .clickable(actionRunCallback<ButtonAction>(actionParams)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isVertical && !isCompactButton) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    provider = ImageProvider(resolvePickerIconRes(iconPref)),
                                    contentDescription = labelPref,
                                    modifier = GlanceModifier.size(28.dp),
                                    colorFilter = androidx.glance.ColorFilter.tint(contentColor)
                                )
                                if (labelPref.isNotBlank()) {
                                    Spacer(GlanceModifier.height(6.dp))
                                    Text(
                                        text = labelPref,
                                        style = androidx.glance.text.TextStyle(
                                            color = contentColor,
                                            fontWeight = androidx.glance.text.FontWeight.Medium,
                                            fontSize = 14.sp,
                                            textAlign = androidx.glance.text.TextAlign.Center
                                        ),
                                        maxLines = 2
                                    )
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    provider = ImageProvider(resolvePickerIconRes(iconPref)),
                                    contentDescription = labelPref,
                                    modifier = GlanceModifier.size(if (isCompactButton) 22.dp else 26.dp),
                                    colorFilter = androidx.glance.ColorFilter.tint(contentColor)
                                )
                                if (labelPref.isNotBlank()) {
                                    Spacer(GlanceModifier.width(if (isCompactButton) 8.dp else 10.dp))
                                    Text(
                                        text = labelPref,
                                        style = androidx.glance.text.TextStyle(
                                            color = contentColor,
                                            fontWeight = androidx.glance.text.FontWeight.Medium,
                                            fontSize = if (isCompactButton) 13.sp else 14.sp
                                        ),
                                        maxLines = if (isCompactButton) 1 else 2
                                    )
                                }
                            }
                        }
                    }
                }
                return@provideContent
            }

            WidgetCard(
                title = title,
                subtitle = subtitle,
                icon = icon,
                updateCount = updateCount,
                theme = widgetTheme,
                onClick = actionStartActivity(
                    Intent(appCtx, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    actionParametersOf(K_OPEN_ENTITY_SHEET to entityId)
                ),
                headerAction = {
                    Box(
                        modifier = GlanceModifier
                            .size(36.dp)
                            .background(BackgroundColorProvider(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Transparent))
                            .cornerRadius(18.dp)
                            .clickable(actionRunCallback<FactoryRefreshAction>(
                                actionParametersOf(K_WIDGET_KEY to widgetKey)
                            )),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_refresh),
                            contentDescription = "Refresh $updateCount",
                            modifier = GlanceModifier.size(24.dp)
                        )
                    }
                }
            ) {
                val removedRows = parsePipeSet(prefs.safeString(FACT_REMOVED_ROWS))
                val rowOrder = parsePipeList(prefs.safeString(FACT_ROW_ORDER))
                Log.d(
                    WIDGET_LOG_TAG,
                    "provide[$widgetKey]: config removed=$removedRows rowOrder=$rowOrder"
                )
                val customFeatures = parseCustomFeatures(prefs.safeString(FACT_CUSTOM_FEATURES))
                val allRows: List<RowSpec> = spec.rows + customFeatures
                val rowById = allRows.associateBy { it.id }
                val orderedRows = buildList {
                    // distinct(): a row id duplicated in the saved order would otherwise render the
                    // same row twice. Self-heals widgets saved before the config dedupe fix.
                    rowOrder.distinct().forEach { rowId -> rowById[rowId]?.let { add(it) } }
                    allRows.forEach { row -> if (row.id !in rowOrder) add(row) }
                }.filter { row ->
                    if (row.visibility == RowVisibility.APP) return@filter false
                    if (row.id in removedRows) return@filter false
                    if (row is CustomFeatureRowSpec) {
                        val conds = row.visibilityConditions
                        if (!conds.isNullOrEmpty() && !conds.all { evaluateCondition(it, templateEntities, appCtx) }) {
                            return@filter false
                        }
                    }
                    if (!evaluateSupported(row.supportedIf, prefs)) return@filter false
                    if (row.hiddenIfToggle != null) {
                        val toggleVal = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("fact_toggle_${row.hiddenIfToggle}")] 
                            ?: spec.toggles.find { it.id == row.hiddenIfToggle }?.defaultValue 
                            ?: false
                        if (toggleVal) return@filter false
                    }
                    if (row.hiddenIfStates.isNotEmpty()) {
                        val entityState = prefs[FACT_STATE]
                        if (entityState != null && row.hiddenIfStates.contains(entityState)) {
                            return@filter false
                        }
                    }
                    true
                }

                orderedRows.forEach { row ->
                    when (row) {
                        is RemoteRowSpec -> Unit // App-only; never rendered in widgets.
                        is PickerRowSpec -> {
                            val currentVal = prefs.readRowString(row.id)
                            val visibleRaw = prefs.safeString(pickerVisibleKey(row.id))
                            val visibleOptionIds = parsePickerVisible(visibleRaw)
                            Log.d(
                                WIDGET_LOG_TAG,
                                "provide[$widgetKey]: picker=${row.id} current=$currentVal visibleRaw=$visibleRaw visible=$visibleOptionIds"
                            )
                            val orderedOptions = if (visibleOptionIds == null) {
                                pickerOptionsForPrefs(row, prefs)
                            } else {
                                // pickerOptionsForPrefs already yields the entity's real option ids
                                // (so taps send the value HA expects, e.g. "med" not the spec's
                                // "medium"), with spec visuals merged in and the canonical sort
                                // applied. Just keep the ones the user hasn't hidden — matching on
                                // NORMALIZED keys so aliases like "med"/"medium" line up. Rebuilding
                                // the order from raw ids was what dropped "med" to the end ("1 3 2").
                                val visibleKeySet = normalizePickerOptionIds(
                                    row.id,
                                    visibleOptionIds,
                                    pickerOptionsForPrefs(row, prefs).map { it.id }
                                ).map { pickerOptionKey(row.id, it) }.toSet()
                                pickerOptionsForPrefs(row, prefs)
                                    .filter { pickerOptionKey(row.id, it.id) in visibleKeySet }
                            }.filter { evaluateSupported(it.supportedIf, prefs) }

                            val mappedCurrentVal = row.stateAliases[currentVal] ?: currentVal
                            val transitionalOption = row.transitionalStates[currentVal]
                            
                            // An armed confirm option collapses the row to itself alone, the same
                            // way a transitional state does. The row a moment ago had two small
                            // chips; now it is one wide amber button reading "Confirm" — different
                            // enough in size, position and colour that a reflex second tap cannot
                            // land on it by accident.
                            val armedFor = prefs.safeString(FACT_PENDING_CONFIRM)
                                ?.takeIf {
                                    it.startsWith("${row.id}:") &&
                                        System.currentTimeMillis() - (prefs[FACT_PENDING_CONFIRM_AT] ?: 0L) < CONFIRM_TIMEOUT_MS
                                }
                                ?.substringAfter(':')
                            val armedOption = armedFor?.let { armedId ->
                                orderedOptions.find { it.id == armedId }?.copy(
                                    label = "Confirm",
                                    // No icon on purpose: the chip renders an icon *or* a label,
                                    // never both, and a bare tick says nothing about what it does.
                                    icon = null,
                                    color = 0xFFFFC107,
                                    nightColor = 0xFFFFC107
                                )
                            }

                            val finalOptions = when {
                                armedOption != null -> listOf(armedOption)
                                transitionalOption != null -> listOf(transitionalOption)
                                else -> orderedOptions
                            }

                            val chips = finalOptions.map { opt ->
                                // Armed counts as selected. The chip only takes its colour when
                                // selected, and "unlock" is by definition not the current state of
                                // a locked lock — so without this the confirm chip renders in the
                                // resting surface colour and looks like an ordinary button.
                                val isSelected = armedOption != null ||
                                    pickerOptionMatches(row.id, opt.id, mappedCurrentVal) ||
                                    pickerOptionMatches(row.id, opt.id, currentVal)
                                
                                // Check for overrides
                                val customColorStr = prefs.safeString(pickerColorKey(row.id, opt.id))
                                val customIconStr = prefs.safeString(pickerIconKey(row.id, opt.id))
                                
                                val customColor = customColorStr?.let { 
                                    runCatching { it.toColorInt().toLong() or 0xFF000000L }.getOrNull()
                                }
                                val resolvedIcon = customIconStr ?: opt.icon

                                ChipSpec(
                                    id = opt.id,
                                    color = if (isSelected) (customColor ?: opt.color ?: 0xFF4CAF50) else null,
                                    nightColor = if (isSelected) opt.nightColor else null,
                                    label = opt.label,
                                    icon = resolvePickerIcon(appCtx, resolvedIcon),
                                    isSelected = isSelected
                                )
                            }
                            chipsRow(
                                chips = chips,
                                onClick = { chip -> actionRunFactory(widgetKey, row.id, null, null, chip.id) }
                            )
                        }

                        is CounterRowSpec -> {
                            val currentVal = prefs.readRowInt(row.id) ?: 0
                            val dynamicMin = prefs.safeInt(rowMinKey(row.id)) ?: row.min
                            val dynamicMax = prefs.safeInt(rowMaxKey(row.id)) ?: row.max
                            
                            Log.d(WIDGET_LOG_TAG, "provide[$widgetKey]: rendering counter row=${row.id} value=$currentVal min=$dynamicMin max=$dynamicMax")
                             counterRow(
                                 value = currentVal,
                                 min = dynamicMin,
                                 max = dynamicMax,
                                 decIcon = row.decIcon,
                                 incIcon = row.incIcon,
                                 onDec = actionRunFactory(widgetKey, row.id, intDelta = -row.step),
                                 onInc = actionRunFactory(widgetKey, row.id, intDelta = row.step)
                             )
                        }

                        is DataRowSpec -> {
                            val value = prefs.readRowString(row.id)
                             dataRow(
                                 value = value,
                                 unit = row.unit ?: ""
                             )
                        }

                        is ButtonsRowSpec -> {
                            buttonsRow(
                                buttons = row.buttons.map { btn ->
                                    WidgetButton(
                                        label = btn.label,
                                        icon = btn.icon,
                                        onClick = actionRunFactory(widgetKey, row.id, null, null, null, btn.action)
                                    )
                                }
                            )
                        }
                        is SpacerRowSpec -> {
                            spacerRow(row.height)
                        }
                        is SliderRowSpec -> {
                            // Sliders not supported on widgets
                        }
                        is CustomFeatureRowSpec -> {
                            if (row.type == "value") {
                                val value = row.valueTemplate?.takeIf { it.isNotBlank() }
                                    ?.let { evaluateWidgetTemplate(it, templateEntities) } ?: "—"
                                valueRow(label = row.label, icon = row.icon, value = value)
                            } else if (row.type == "switch") {
                                val targetEntityId = row.targetEntity?.takeIf { it.isNotEmpty() } ?: entityId
                                val targetEntityState = templateEntities.find { it.entity_id == targetEntityId }?.state ?: prefs[FACT_STATE] ?: "off"
                                val isChecked = targetEntityState == "on"
                                switchRow(
                                    checked = isChecked,
                                    action = actionRunFactory(widgetKey, row.id, actionKey = "custom:${row.id}")
                                )
                            } else {
                                customButtonRow(
                                    label = row.label,
                                    icon = row.icon,
                                    action = actionRunFactory(widgetKey, row.id, actionKey = "custom:${row.id}")
                                )
                            }
                        }
                    }
                }

                val errorMsg = prefs[FACT_ERROR]
                if (errorMsg != null) {
                    errorRow(errorMsg)
                }

            }
        }
    }
}

private fun actionRunFactory(
    widgetKey: String,
    rowId: String,
    bool: Boolean? = null,
    intVal: Int? = null,
    stringVal: String? = null,
    actionKey: String? = null,
    intDelta: Int? = null
): Action {
    val paramsList = mutableListOf<androidx.glance.action.ActionParameters.Pair<out Any>>(
        K_WIDGET_KEY to widgetKey,
        K_ROW_ID to rowId,
        K_TIMESTAMP to System.currentTimeMillis()
    )

    if (bool != null)    paramsList.add(K_BOOL to bool)
    if (intVal != null)  paramsList.add(K_INT to intVal)
    if (intDelta != null) paramsList.add(K_INT_DELTA to intDelta)
    if (stringVal != null) paramsList.add(K_STRING to stringVal)
    if (actionKey != null) paramsList.add(K_ACTION_KEY to actionKey)

    return actionRunCallback(FactoryAction::class.java, actionParametersOf(*paramsList.toTypedArray()))
}

private fun String.toDisplayLabel(): String =
    split("_", "-")
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

private fun pickerOptionsForPrefs(row: PickerRowSpec, prefs: Preferences): List<PickerOptionSpec> {
    val builtIns = row.options.filter { evaluateSupported(it.supportedIf, prefs) }
    return mergePickerOptions(row.id, builtIns, pickerOptionIdsFromPrefs(row.id, prefs)) {
        defaultPickerOption(row.id, it)
    }
}

private fun pickerOptionIdsFromPrefs(rowId: String, prefs: Preferences): List<String> {
    val attr = pickerAttributeForRow(rowId) ?: return emptyList()
    return prefs.safeString(stringPreferencesKey("attr_$attr"))
        ?.split("|")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        .orEmpty()
}

private fun pickerAttributeForRow(rowId: String): String? =
    when (rowId) {
        "hvac_mode" -> "hvac_modes"
        "fan_mode" -> "fan_modes"
        "preset_mode" -> "preset_modes"
        "swing_mode" -> "swing_modes"
        "swing_horizontal_mode" -> "swing_horizontal_modes"
        else -> null
    }

private fun defaultPickerOption(rowId: String, optionId: String): PickerOptionSpec =
    when (rowId) {
        "hvac_mode" -> when (optionId) {
            "off" -> PickerOptionSpec(optionId, "Off", icon = R.drawable.ic_power)
            "cool" -> PickerOptionSpec(optionId, "Cool", icon = R.drawable.ic_cool)
            "heat" -> PickerOptionSpec(optionId, "Heat", icon = R.drawable.ic_heat)
            "auto", "heat_cool" -> PickerOptionSpec(optionId, "Auto", icon = R.drawable.ic_auto)
            "fan", "fan_only" -> PickerOptionSpec(optionId, "Fan", icon = R.drawable.ic_fan)
            "dry" -> PickerOptionSpec(optionId, "Dry", icon = R.drawable.ic_dry)
            else -> PickerOptionSpec(optionId, optionId.toDisplayLabel())
        }
        "fan_mode" -> when (optionId) {
            "auto" -> PickerOptionSpec(optionId, "Auto", icon = "hdr_auto")
            "quiet" -> PickerOptionSpec(optionId, "Quiet", icon = "bedtime")
            "low" -> PickerOptionSpec(optionId, "Low", icon = R.drawable.ic_one)
            "medium_low", "mid_low", "midlow" -> PickerOptionSpec(optionId, "Medium low", icon = R.drawable.ic_two)
            "medium", "med", "mid" -> PickerOptionSpec(optionId, "Medium", icon = R.drawable.ic_two)
            "medium_high", "mid_high", "midhigh" -> PickerOptionSpec(optionId, "Medium high", icon = R.drawable.ic_three)
            "high" -> PickerOptionSpec(optionId, "High", icon = R.drawable.ic_three)
            "turbo" -> PickerOptionSpec(optionId, "Turbo", icon = "rocket_launch")
            "super" -> PickerOptionSpec(optionId, "Super", icon = "bolt")
            else -> PickerOptionSpec(optionId, optionId.toDisplayLabel(), icon = R.drawable.ic_fan)
        }
        "preset_mode" -> when (optionId) {
            "none" -> PickerOptionSpec(optionId, "None", icon = "block", color = 0xFF8A8F98, nightColor = 0xFF555A62)
            "eco" -> PickerOptionSpec(optionId, "Eco", icon = "energy_savings_leaf", color = 0xFF00B171, nightColor = 0xFF00B171)
            "away" -> PickerOptionSpec(optionId, "Away", icon = "directions_walk", color = 0xFFFFA000, nightColor = 0xFFFFA000)
            "boost" -> PickerOptionSpec(optionId, "Boost", icon = "rocket_launch", color = 0xFFFF6F22, nightColor = 0xFFFF6F22)
            "comfort" -> PickerOptionSpec(optionId, "Comfort", icon = "weekend", color = 0xFF7E57C2, nightColor = 0xFF7E57C2)
            "home" -> PickerOptionSpec(optionId, "Home", icon = "home", color = 0xFF2196F3, nightColor = 0xFF2196F3)
            "sleep" -> PickerOptionSpec(optionId, "Sleep", icon = "bedtime", color = 0xFF5C6BC0, nightColor = 0xFF5C6BC0)
            "activity" -> PickerOptionSpec(optionId, "Activity", icon = "directions_run", color = 0xFF26A69A, nightColor = 0xFF26A69A)
            else -> PickerOptionSpec(optionId, optionId.toDisplayLabel(), icon = "tune", color = 0xFF7E57C2, nightColor = 0xFF7E57C2)
        }
        "swing_mode", "swing_horizontal_mode" -> when (optionId) {
            "off" -> PickerOptionSpec(optionId, "Off", icon = "airwave", color = 0xFF8A8F98, nightColor = 0xFF555A62)
            "on" -> PickerOptionSpec(optionId, "On", icon = "air", color = 0xFF31B8E5, nightColor = 0xFF31B8E5)
            "vertical" -> PickerOptionSpec(optionId, "Vertical", icon = "swap_vert", color = 0xFF4B8DFF, nightColor = 0xFF4B8DFF)
            "horizontal" -> PickerOptionSpec(optionId, "Horizontal", icon = "swap_horiz", color = 0xFF4B8DFF, nightColor = 0xFF4B8DFF)
            "both" -> PickerOptionSpec(optionId, "Both", icon = "open_with", color = 0xFF7E57C2, nightColor = 0xFF7E57C2)
            else -> PickerOptionSpec(optionId, optionId.toDisplayLabel(), icon = "air", color = 0xFF31B8E5, nightColor = 0xFF31B8E5)
        }
        "state" -> when (optionId) {
            "lock", "locked" -> PickerOptionSpec(optionId, "Lock", icon = "lock")
            "unlock", "unlocked" -> PickerOptionSpec(optionId, "Unlock", icon = "lock_open")
            "locking" -> PickerOptionSpec(optionId, "Locking", icon = "lock")
            "unlocking" -> PickerOptionSpec(optionId, "Unlocking", icon = "lock_open")
            "open" -> PickerOptionSpec(optionId, "Open", icon = "door_open")
            else -> PickerOptionSpec(optionId, optionId.toDisplayLabel())
        }
        else -> PickerOptionSpec(optionId, optionId.toDisplayLabel())
    }

// ---------- Generic ActionCallback ----------
// ---------- Custom features (ported from app cards) ----------

/** Parses the JSON list stored in FACT_CUSTOM_FEATURES into CustomFeatureRowSpec rows. */
fun parseCustomFeatures(json: String?): List<CustomFeatureRowSpec> {
    if (json.isNullOrBlank()) return emptyList()
    val type = Types.newParameterizedType(
        List::class.java,
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    val list = runCatching {
        Moshi.Builder().build().adapter<List<Map<String, Any>>>(type).fromJson(json)
    }.getOrNull() ?: return emptyList()
    return list.mapNotNull { m ->
        val id = m["id"] as? String ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        CustomFeatureRowSpec(
            id = id,
            type = m["type"] as? String ?: "value",
            label = m["label"] as? String ?: id,
            icon = m["icon"] as? String,
            serviceName = m["serviceName"] as? String,
            serviceData = m["serviceData"] as? Map<String, Any>,
            targetEntity = m["targetEntity"] as? String,
            targets = (m["targets"] as? List<*>)?.mapNotNull { it as? Map<String, String> },
            valueTemplate = m["valueTemplate"] as? String,
            visibilityConditions = (m["visibility"] as? List<*>)?.mapNotNull { it as? Map<String, Any> }
        )
    }
}

/** Rebuilds an HAEntity from the widget's stored prefs (state + attr_* keys) for template eval. */
fun reconstructEntityFromPrefs(prefs: Preferences): HAEntity {
    val id = prefs[FACT_ENTITY_ID].orEmpty()
    val state = prefs[FACT_STATE] ?: "unknown"
    val attrs = mutableMapOf<String, Any>()
    prefs.asMap().forEach { (k, v) ->
        if (k.name.startsWith("attr_")) attrs[k.name.removePrefix("attr_")] = v
    }
    return HAEntity(id, state, attrs)
}

private val TEMPLATE_PLACEHOLDER = Regex("\\{([^}]+)\\}")

/**
 * Same placeholder syntax as the app cards: {state}, {name}, {attributes.x}, {x} resolve against
 * the primary (first) entity; {sensor.x.state} / {sensor.x.attributes.y} resolve cross-entity.
 */
fun evaluateWidgetTemplate(template: String, entities: List<HAEntity>): String {
    val primary = entities.firstOrNull()
    return TEMPLATE_PLACEHOLDER.replace(template) { m ->
        resolveWidgetPlaceholder(m.groupValues[1].trim(), primary, entities)
    }
}

private fun resolveWidgetPlaceholder(path: String, primary: HAEntity?, all: List<HAEntity>): String {
    if (primary != null) {
        if (primary.entity_id.startsWith("timer.") && (
                path == "state" || path == "current.state" ||
                path == "remaining" || path == "attributes.remaining" ||
                path == "current.attributes.remaining")) {
            return widgetFormatTimerState(primary)
        }
        when {
            path == "state" || path == "current.state" -> return primary.state
            path == "name" || path == "current.name" ->
                return primary.attributes["friendly_name"]?.toString() ?: primary.entity_id
            path.startsWith("attributes.") || path.startsWith("current.attributes.") -> {
                val k = path.removePrefix("current.").removePrefix("attributes.")
                return primary.attributes[k]?.toString() ?: ""
            }
            primary.attributes.containsKey(path) -> return primary.attributes[path]?.toString() ?: ""
        }
    }
    val match = all.find { path == it.entity_id || path.startsWith("${it.entity_id}.") }
    if (match != null) {
        val rem = path.removePrefix(match.entity_id).removePrefix(".")
        // Timers need the same treatment as the in-app card: an idle timer resolves to "Idle",
        // and an active timer's remaining is computed live from finishes_at (HA's `remaining`
        // attribute is a frozen snapshot, which is why the widget was stuck on the start time).
        if (match.entity_id.startsWith("timer.") && (
                rem.isEmpty() || rem == "state" || rem == "remaining" || rem == "attributes.remaining")) {
            return widgetFormatTimerState(match)
        }
        return when {
            rem.isEmpty() || rem == "state" -> match.state
            rem == "name" -> match.attributes["friendly_name"]?.toString() ?: match.entity_id
            rem.startsWith("attributes.") -> match.attributes[rem.removePrefix("attributes.")]?.toString() ?: ""
            match.attributes.containsKey(rem) -> match.attributes[rem]?.toString() ?: ""
            else -> ""
        }
    }
    return ""
}

/** Timer-aware formatting matching the in-app card (formatEntityState). */
private fun widgetFormatTimerState(entity: HAEntity): String = when (entity.state) {
    "active" -> {
        val finishesAt = entity.attributes["finishes_at"]?.toString()
        if (finishesAt != null) widgetFormatRemaining(widgetTimerRemainingSeconds(finishesAt))
        else entity.attributes["remaining"]?.toString() ?: entity.state
    }
    "paused" -> entity.attributes["remaining"]?.toString() ?: "Paused"
    else -> entity.state.replace("_", " ").replaceFirstChar { it.uppercase() }
}

private fun widgetTimerRemainingSeconds(finishesAtStr: String): Long = try {
    val finishesAt = if (finishesAtStr.endsWith("Z")) java.time.Instant.parse(finishesAtStr)
        else java.time.OffsetDateTime.parse(finishesAtStr).toInstant()
    maxOf(0L, java.time.temporal.ChronoUnit.SECONDS.between(java.time.Instant.now(), finishesAt))
} catch (e: Exception) { 0L }

private fun widgetFormatRemaining(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
}

/** Entity ids referenced inside a template string's {placeholders}. */
fun templateEntityIds(template: String?): Set<String> {
    if (template.isNullOrBlank()) return emptySet()
    return TEMPLATE_PLACEHOLDER.findAll(template).mapNotNull { m ->
        val path = m.groupValues[1].trim()
        // An entity ref looks like "domain.object_id" (optionally ".state"/".attributes.x").
        val firstTwo = path.split(".").take(2)
        if (firstTwo.size == 2 && firstTwo[0].isNotBlank() && firstTwo[1].isNotBlank()
            && firstTwo[0] != "current" && firstTwo[0] != "attributes") {
            "${firstTwo[0]}.${firstTwo[1]}"
        } else null
    }.toSet()
}

/** All entity ids a set of custom features depend on (targets, targetEntity, templates, conditions). */
fun customFeatureEntityIds(features: List<CustomFeatureRowSpec>): Set<String> {
    val ids = mutableSetOf<String>()
    features.forEach { f ->
        f.targetEntity?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        f.targets?.forEach { if (it["type"] == "entity") it["value"]?.let { v -> ids.add(v) } }
        ids.addAll(templateEntityIds(f.valueTemplate))
        f.visibilityConditions?.forEach { ids.addAll(conditionEntityIds(it)) }
    }
    return ids
}

/** Parses serialized composed button label blocks ([{type, text | rules, else}]). */
fun parseButtonLabelBlocks(json: String?): List<Map<String, Any>> {
    if (json.isNullOrBlank()) return emptyList()
    val type = Types.newParameterizedType(
        List::class.java,
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    return runCatching { Moshi.Builder().build().adapter<List<Map<String, Any>>>(type).fromJson(json) }
        .getOrNull().orEmpty()
}

/** Entity ids referenced by the conditions inside composed button label blocks. */
fun buttonLabelBlockEntityIds(blocks: List<Map<String, Any>>): Set<String> {
    val ids = mutableSetOf<String>()
    blocks.forEach { block ->
        (block["rules"] as? List<*>)?.forEach { rule ->
            ((rule as? Map<*, *>)?.get("conditions") as? List<*>)?.forEach { sub ->
                @Suppress("UNCHECKED_CAST")
                (sub as? Map<String, Any>)?.let { ids.addAll(conditionEntityIds(it)) }
            }
        }
    }
    return ids
}

/** Entity ids referenced by a (possibly nested) visibility condition. */
private fun conditionEntityIds(condition: Map<String, Any>): Set<String> {
    val ids = mutableSetOf<String>()
    (condition["entity"] as? String)?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
    (condition["conditions"] as? List<*>)?.forEach { sub ->
        (sub as? Map<String, Any>)?.let { ids.addAll(conditionEntityIds(it)) }
    }
    return ids
}

/** Serializes referenced entity states for the render to resolve templates against. */
fun serializeRefEntities(entities: List<HAEntity>): String {
    val map = entities.associate { it.entity_id to mapOf("state" to it.state, "attributes" to it.attributes) }
    val type = Types.newParameterizedType(
        Map::class.java, String::class.java,
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    return runCatching { Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(type).toJson(map) }.getOrNull().orEmpty()
}

fun parseRefEntities(json: String?): List<HAEntity> {
    if (json.isNullOrBlank()) return emptyList()
    val type = Types.newParameterizedType(
        Map::class.java, String::class.java,
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    val map = runCatching { Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(type).fromJson(json) }
        .getOrNull() ?: return emptyList()
    return map.map { (eid, data) ->
        @Suppress("UNCHECKED_CAST")
        HAEntity(eid, data["state"] as? String ?: "unknown", (data["attributes"] as? Map<String, Any>).orEmpty())
    }
}

/** Builds the service-call body for a custom toggle/service feature (targets + serviceData). */
fun buildCustomFeatureBody(feature: CustomFeatureRowSpec, fallbackEntityId: String): MutableMap<String, Any> {
    fun put(body: MutableMap<String, Any>, key: String, values: List<String>) {
        val cleaned = values.filter { it.isNotBlank() }.distinct()
        if (cleaned.isNotEmpty()) body[key] = cleaned.singleOrNull() ?: cleaned
    }
    val body = mutableMapOf<String, Any>()
    val targets = feature.targets ?: emptyList()
    if (targets.isNotEmpty()) {
        put(body, "entity_id", targets.filter { it["type"] == "entity" }.mapNotNull { it["value"] })
        put(body, "device_id", targets.filter { it["type"] == "device" }.mapNotNull { it["value"] })
        put(body, "area_id",   targets.filter { it["type"] == "area" }.mapNotNull { it["value"] })
        put(body, "label_id",  targets.filter { it["type"] == "label" }.mapNotNull { it["value"] })
    } else {
        body["entity_id"] = feature.targetEntity?.takeIf { it.isNotEmpty() } ?: fallbackEntityId
    }
    if (feature.type == "service") feature.serviceData?.let { body.putAll(it) }
    return body
}

/** Resolves a custom feature's (domain, service) pair. */
fun customFeatureService(feature: CustomFeatureRowSpec): Pair<String, String> = when (feature.type) {
    "toggle", "switch" -> "homeassistant" to "toggle"
    else -> {
        val parts = (feature.serviceName ?: "homeassistant.toggle").split(".")
        (parts.getOrNull(0) ?: "homeassistant") to (parts.getOrNull(1) ?: "toggle")
    }
}

class FactoryAction : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        widgetActionMutex.withLock {
        val widgetKey = parameters[K_WIDGET_KEY] ?: return
        val rowId = parameters[K_ROW_ID] ?: return
        var i = parameters[K_INT]
        val intDelta = parameters[K_INT_DELTA]
        val s = parameters[K_STRING]
        val ak = parameters[K_ACTION_KEY]

        WidgetRegistry.init(context)
        val spec = WidgetRegistry.spec(widgetKey) ?: return
        val appCtx = context.applicationContext
        var prefs = getAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId)
        val entityId = prefs[FACT_ENTITY_ID] ?: return
        val appWidgetId = runCatching { GlanceAppWidgetManager(appCtx).getAppWidgetId(glanceId) }.getOrNull()
        Log.d(WIDGET_LOG_TAG, "action[$widgetKey]: row=$rowId i=$i di=$intDelta s=$s action=$ak entity=$entityId")

        // 1.5) Custom feature button → call its arbitrary service.
        if (ak != null && ak.startsWith("custom:")) {
            val featureId = ak.removePrefix("custom:")
            val feature = parseCustomFeatures(prefs.safeString(FACT_CUSTOM_FEATURES)).find { it.id == featureId }
            if (feature == null || feature.type == "value") return
            val (cDomain, cService) = customFeatureService(feature)
            val cBody = buildCustomFeatureBody(feature, entityId)

            if (prefs[FACT_REQUIRE_AUTH] == true) {
                val intent = android.content.Intent(appCtx, AuthenticationActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("entity_id", cBody["entity_id"]?.toString() ?: entityId)
                    putExtra("domain", cDomain)
                    putExtra("service", cService)
                    putExtra("widget_key", widgetKey)
                    cBody.forEach { (k, v) -> putExtra("param_$k", v.toString()) }
                }
                appCtx.startActivity(intent)
                return
            }

            updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().apply {
                    this[FACT_LAST_INTERACTION] = System.currentTimeMillis()
                    this.remove(FACT_ERROR)
                }
            }
            FactoryGlanceAppWidget(widgetKey).update(appCtx, glanceId)
            WidgetActionRetryWorker.enqueue(appCtx, widgetKey, appWidgetId, rowId, cDomain, cService, cBody)
            WidgetRefreshScheduler.requestImmediate(appCtx, widgetKey, refreshFromApi = false)
            Log.d(WIDGET_LOG_TAG, "action[$widgetKey]: custom queued $cDomain.$cService body=$cBody")
            return
        }

        // 2) Resolve service call
        val row = spec.rows.find { it.id == rowId }
        var rollbackInt: Int? = null
        var rollbackString: String? = null

        if (row is CounterRowSpec && intDelta != null) {
            prefs = getAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId)
            val dynamicMin = prefs.safeInt(rowMinKey(row.id)) ?: row.min
            val dynamicMax = prefs.safeInt(rowMaxKey(row.id)) ?: row.max
            val currentValue = prefs.readRowInt(row.id) ?: i ?: row.min
            rollbackInt = if (prefs[FACT_OPTIMISTIC_ROW] == row.id) {
                prefs.safeInt(FACT_OPTIMISTIC_ROLLBACK_INT) ?: currentValue
            } else {
                currentValue
            }
            i = (currentValue + intDelta).coerceIn(dynamicMin, dynamicMax)
        }
        
        var effectiveAk = ak
        if (effectiveAk == null && row is PickerRowSpec) {
            val valueStr = (i ?: s ?: "").toString()
            val option = row.options.find { it.id == valueStr }
            val override = option?.actionOverrideIfToggle
            if (override != null) {
                val toggleVal = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("fact_toggle_${override.first}")] 
                    ?: spec.toggles.find { it.id == override.first }?.defaultValue 
                    ?: false
                if (toggleVal) {
                    effectiveAk = override.second
                }
            }
        }

        // Resolve service call. For ButtonsRowSpec the action key IS the service name (e.g.
        // "open_cover"), so fall back to a direct call on the entity's domain when there is no
        // named entry in the spec — this keeps button specs generic without needing every
        // service pre-declared in actions.
        val domain = entityId.split(".")[0]
        val serviceCall = when {
            effectiveAk != null -> spec.actions.calls[effectiveAk]
                ?: if (row is ButtonsRowSpec) ServiceCall(domain, effectiveAk, mapOf("entity_id" to "{entity}")) else null
            row is PickerRowSpec -> spec.actions.calls["pick:$rowId"] ?: spec.actions.calls["pick"]
            row is CounterRowSpec -> spec.actions.calls["setCounter:$rowId"] ?: spec.actions.calls["setCounter"]
            else -> null
        } ?: return

        val value = (i ?: s ?: "").toString()
        if (i != null && rollbackInt == null) {
            rollbackInt = if (prefs[FACT_OPTIMISTIC_ROW] == rowId) {
                prefs.safeInt(FACT_OPTIMISTIC_ROLLBACK_INT)
            } else {
                prefs.readRowInt(rowId)
            }
        }
        if (s != null) {
            rollbackString = if (prefs[FACT_OPTIMISTIC_ROW] == rowId) {
                prefs.safeString(FACT_OPTIMISTIC_ROLLBACK_STRING)
            } else {
                prefs.readRowString(rowId).takeIf { it.isNotBlank() }
            }
        }
        val actualService = serviceCall.service.replace("{value}", value)
        val body = mutableMapOf<String, Any>("entity_id" to entityId)

        serviceCall.params.forEach { (k, v) ->
            body[k] = when (v) {
                "{entity}" -> entityId
                "{value}"  -> value.toIntOrNull() ?: value
                else       -> v
            }
        }

        // 2.25) Alarm code. Alarm panels can require a PIN to arm/disarm; without it HA returns
        // 500. If a code is saved in the widget config we attach it silently; otherwise we launch
        // a small prompt activity (Glance widgets can't show a dialog inline) that asks for the
        // code and performs the call.
        if (serviceCall.domain == "alarm_control_panel") {
            val codeFormat = prefs.safeString(stringPreferencesKey("attr_code_format"))?.takeIf { it.isNotBlank() }
            val codeArmRequired = prefs.safeString(stringPreferencesKey("attr_code_arm_required"))?.equals("true", ignoreCase = true) == true
            val needsCode = codeFormat != null && (value == "disarm" || codeArmRequired)
            if (needsCode) {
                val savedCode = prefs.safeString(FACT_ALARM_CODE)?.takeIf { it.isNotBlank() }
                if (savedCode != null) {
                    body["code"] = savedCode
                } else {
                    val intent = android.content.Intent(appCtx, AlarmCodeActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("entity_id", entityId)
                        putExtra("domain", serviceCall.domain)
                        putExtra("service", actualService)
                        putExtra("widget_key", widgetKey)
                        putExtra("code_format", codeFormat)
                        body.forEach { (k, v) -> putExtra("param_$k", v.toString()) }
                    }
                    appCtx.startActivity(intent)
                    return
                }
            }
        }

        // 2.4) Confirmation. Some options are too costly to fire by accident — unlocking a door
        // from a home screen widget in a pocket. The first tap arms the option and re-renders so
        // the widget shows it waiting; the second within the window goes through. A widget has no
        // ephemeral state, so "armed" lives in its prefs with a timestamp rather than in memory.
        run {
            val tappedOption = (spec.rows.find { it.id == rowId } as? PickerRowSpec)
                ?.options?.find { it.id == value }
            if (tappedOption?.confirm == true) {
                val armed = prefs.safeString(FACT_PENDING_CONFIRM)
                val armedAt = prefs[FACT_PENDING_CONFIRM_AT] ?: 0L
                val elapsed = System.currentTimeMillis() - armedAt
                // Dead time straight after arming, matching the card. A widget tap is a broadcast
                // rather than a press on a live view, so without this a quick double-tap would
                // sail through both steps before the row had even redrawn.
                if (armed == "$rowId:$value" && elapsed < CONFIRM_GUARD_MS) {
                    Log.d(WIDGET_LOG_TAG, "action[$widgetKey]: ignoring tap inside confirm guard")
                    return
                }
                val stillArmed = armed == "$rowId:$value" && elapsed < CONFIRM_TIMEOUT_MS
                if (!stillArmed) {
                    updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { p ->
                        p.toMutablePreferences().apply {
                            this[FACT_PENDING_CONFIRM] = "$rowId:$value"
                            this[FACT_PENDING_CONFIRM_AT] = System.currentTimeMillis()
                        }
                    }
                    FactoryGlanceAppWidget(widgetKey).update(appCtx, glanceId)
                    Log.d(WIDGET_LOG_TAG, "action[$widgetKey]: armed confirm for $rowId:$value")
                    return
                }
            }
            // Either confirmed or not a confirm option — nothing should stay armed past here.
            updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().apply {
                    this.remove(FACT_PENDING_CONFIRM)
                    this.remove(FACT_PENDING_CONFIRM_AT)
                }
            }
        }

        // 2.5) Authentication check
        if (prefs[FACT_REQUIRE_AUTH] == true) {
            val intent = android.content.Intent(appCtx, AuthenticationActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("entity_id", entityId)
                putExtra("domain", serviceCall.domain)
                putExtra("service", actualService)
                putExtra("value", value)
                putExtra("widget_key", widgetKey)
                // Pass enough info to recreate the body params
                serviceCall.params.forEach { (k, v) ->
                    val resolved = when (v) {
                        "{entity}" -> entityId
                        "{value}"  -> value
                        else       -> v
                    }
                    putExtra("param_$k", resolved)
                }
            }
            appCtx.startActivity(intent)
            return
        }

        // 3) Local state first (instant UI) - Only if no auth or after auth
        updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply {
                this[FACT_LAST_INTERACTION] = System.currentTimeMillis()
                this.remove(FACT_ERROR)
                if (i != null) {
                    this[rowKey(rowId)] = i
                    this[FACT_OPTIMISTIC_ROW] = rowId
                    this[FACT_OPTIMISTIC_INT] = i
                    rollbackInt?.let { this[FACT_OPTIMISTIC_ROLLBACK_INT] = it }
                    this.remove(FACT_OPTIMISTIC_STRING)
                    this.remove(FACT_OPTIMISTIC_ROLLBACK_STRING)
                }
                if (s != null) {
                    this[stringPreferencesKey("fact_row_$rowId")] = s
                    this[FACT_OPTIMISTIC_ROW] = rowId
                    this[FACT_OPTIMISTIC_STRING] = s
                    rollbackString?.let { this[FACT_OPTIMISTIC_ROLLBACK_STRING] = it }
                    this.remove(FACT_OPTIMISTIC_INT)
                    this.remove(FACT_OPTIMISTIC_ROLLBACK_INT)
                }
            }
        }
        FactoryGlanceAppWidget(widgetKey).update(appCtx, glanceId)
        WidgetRefreshScheduler.requestProviderUpdate(appCtx, widgetKey)
        WidgetRefreshScheduler.requestImmediate(appCtx, widgetKey, refreshFromApi = false)

        WidgetActionRetryWorker.enqueue(
            appCtx,
            widgetKey,
            appWidgetId,
            rowId,
            serviceCall.domain,
            actualService,
            body,
            rollbackInt = rollbackInt,
            rollbackString = rollbackString
        )
        Log.d(WIDGET_LOG_TAG, "action[$widgetKey]: queued ${serviceCall.domain}.$actualService")
        }
    }
}

// ---------- Manual Refresh Action ----------
class FactoryRefreshAction : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val widgetKey = parameters[K_WIDGET_KEY] ?: return
        android.util.Log.d("WidgetRefresh", "action[$widgetKey]: MANUAL REFRESH started")
        try {
            syncWidgetStateFromApi(context, widgetKey, glanceId, force = true)
            FactoryGlanceAppWidget(widgetKey).update(context, glanceId)
        } catch (e: Exception) {
            WidgetNetworkGate.reportFailure(context.applicationContext, e, "manual refresh[$widgetKey]")
            android.util.Log.e("WidgetRefresh", "Manual refresh failed", e)
        }
    }
}

class ButtonAction : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val widgetKey = parameters[K_WIDGET_KEY] ?: return
        android.util.Log.d(WIDGET_LOG_TAG, "ButtonAction triggered for widgetKey=$widgetKey")

        val stateDef = CachedPreferencesGlanceStateDefinition
        val prefs = try {
            stateDef.getLocation(context, widgetKey)
            withContext(Dispatchers.IO) { stateDef.getDataStore(context, widgetKey).data.first() }
        } catch (e: Exception) {
            android.util.Log.e(WIDGET_LOG_TAG, "Failed to read prefs for ButtonAction", e)
            return
        }

        val serviceRaw = prefs[stringPreferencesKey("button_service")] ?: ""
        if (serviceRaw.isBlank()) return

        val parts = serviceRaw.split(".")
        if (parts.size != 2) return

        val dataStr = prefs[stringPreferencesKey("button_data")] ?: ""
        val targetEntity = prefs[stringPreferencesKey("button_entityId")]

        try {
            val dataMap = if (dataStr.isNotBlank()) {
                val moshiAdapter: com.squareup.moshi.JsonAdapter<Map<String, Any>> = Moshi.Builder().build().adapter(
                    Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                )
                moshiAdapter.fromJson(dataStr)
            } else emptyMap()

            val params = dataMap?.toMutableMap() ?: mutableMapOf()
            if (!targetEntity.isNullOrBlank()) {
                params["entity_id"] = targetEntity
            }

            val api = getStoredApi(context) ?: return
            api.callService(parts[0], parts[1], params)
        } catch (e: Exception) {
            android.util.Log.e(WIDGET_LOG_TAG, "ButtonAction API call failed", e)
        }
    }
}

fun resolvePickerIconRes(iconName: String): Int {
    // Instead of parsing MaterialIcons (which requires compose), we return a default icon
    // because Glance widgets only support Android resource drawable IDs, and we don't have
    // 3000 drawables downloaded. We'll fallback to a generic bolt for now, unless it's a known domain.
    return com.feldman.ha.R.drawable.ic_bolt
}

fun resolveDomainIcon(domain: String?): Int? = when (domain) {
    "climate" -> R.drawable.ic_ac
    "lock" -> R.drawable.ic_lock
    "light" -> R.drawable.ic_lightbulb
    "switch" -> R.drawable.ic_power
    "fan" -> R.drawable.ic_fan
    "alarm_control_panel" -> R.drawable.ic_shield
    "cover" -> R.drawable.ic_blinds
    else -> null
}
