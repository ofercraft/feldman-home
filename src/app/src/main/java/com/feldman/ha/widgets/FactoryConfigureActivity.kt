package com.feldman.ha.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.api.getStoredApi
import com.feldman.ha.api.provideHAApi
import com.feldman.ha.data.HAEntity
import com.feldman.ha.ui.navigation.AppState
import com.feldman.ha.ui.editors.ConfigureCardSaveDock
import com.feldman.ha.ui.pages.CustomFeatureDetailPage
import com.feldman.motion.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class FactoryConfigureActivity : ComponentActivity() {
    abstract val widgetKey: String
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        WidgetRegistry.init(this)
        val spec = WidgetRegistry.spec(widgetKey) ?: run { finish(); return }

        val mainPrefs = getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
        val token = mainPrefs.getString("token", null).orEmpty()
        val baseUrl = mainPrefs.getString("url", null).orEmpty().ifBlank { "http://homeassistant.local:8123/api/" }
        val api = getStoredApi(this) ?: provideHAApi(token = token, baseUrl = baseUrl)

        setContent {
            AppTheme {
                ConfigureScreen(
                    api = api,
                    spec = spec,
                    appWidgetId = appWidgetId,
                    onSave = { entityId, friendly, rowOrder, removedRows, pickerVisible, optionOrder,
                               colors, icons, toggles, requireAuth, theme, extraEntities, stateTemplate,
                               customFeaturesJson, alarmCode ->
                        lifecycleScope.launch {
                            val stateDeferred = async { runCatching { api.getState(entityId) }.getOrNull() }
                            val extraStatesDeferred = extraEntities.values.map { eid ->
                                async { eid to runCatching { api.getState(eid) }.getOrNull() }
                            }
                            val state = stateDeferred.await()
                            val extraStates = extraStatesDeferred.awaitAll()
                                .mapNotNull { (eid, e) -> e?.let { eid to it } }.toMap()

                            val glanceId = runCatching {
                                GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
                            }.getOrNull()

                            if (glanceId != null) {
                                withContext(NonCancellable) {
                                    updateAppWidgetState(applicationContext, CachedPreferencesGlanceStateDefinition, glanceId) { prefs ->
                                        prefs.toMutablePreferences().apply {
                                            this[FACT_ENTITY_ID]       = entityId
                                            this[FACT_FRIENDLY_NAME]   = friendly
                                            this[FACT_STATE_TEMPLATE]  = stateTemplate
                                            if (state != null) this[FACT_STATE] = state.state
                                            this[FACT_LAST_INTERACTION] = System.currentTimeMillis()
                                            this[FACT_ROW_ORDER]       = rowOrder.joinToString("|")
                                            this[FACT_REMOVED_ROWS]    = removedRows.joinToString("|")
                                            this[FACT_REQUIRE_AUTH]    = requireAuth
                                            this[FACT_THEME]           = theme
                                            if (customFeaturesJson.isNotBlank()) this[FACT_CUSTOM_FEATURES] = customFeaturesJson
                                            else this.remove(FACT_CUSTOM_FEATURES)
                                            if (alarmCode.isNotBlank()) this[FACT_ALARM_CODE] = alarmCode
                                            else this.remove(FACT_ALARM_CODE)
                                            val cur = this[androidx.datastore.preferences.core.intPreferencesKey("fact_update_count")] ?: 0
                                            this[androidx.datastore.preferences.core.intPreferencesKey("fact_update_count")] = cur + 1
                                            extraEntities.forEach { (id, eid) ->
                                                this[stringPreferencesKey("fact_extra_entity_$id")] = eid
                                            }
                                            pickerVisible.forEach { (rowId, visibleIds) ->
                                                this[pickerVisibleKey(rowId)] = visibleIds.joinToString("|")
                                            }
                                            optionOrder.forEach { (rowId, order) ->
                                                this[pickerOptionOrderKey(rowId)] = order.joinToString("|")
                                            }
                                            colors.forEach { (pair, color) ->
                                                this[pickerColorKey(pair.first, pair.second)] = color
                                            }
                                            icons.forEach { (pair, icon) ->
                                                this[pickerIconKey(pair.first, pair.second)] = icon
                                            }
                                            toggles.forEach { (id, value) ->
                                                this[androidx.datastore.preferences.core.booleanPreferencesKey("fact_toggle_$id")] = value
                                            }
                                            if (state != null) mapHAEntityToPreferences(state, spec, this)
                                            extraStates.values.forEach { es ->
                                                es.attributes.forEach { (k, v) ->
                                                    val key = stringPreferencesKey("attr_$k")
                                                    when (v) {
                                                        is Number  -> this[key] = v.toString()
                                                        is String  -> this[key] = v
                                                        is Boolean -> this[key] = v.toString()
                                                    }
                                                }
                                                spec.entityPickers.forEach { picker ->
                                                    val savedEid = this[stringPreferencesKey("fact_extra_entity_${picker.id}")]
                                                    if (savedEid == es.entity_id) {
                                                        this[stringPreferencesKey("attr_${picker.id}")] = es.state
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    FactoryGlanceAppWidget(widgetKey).update(applicationContext, glanceId)
                                    WidgetRefreshScheduler.requestProviderUpdate(applicationContext, widgetKey, appWidgetId)
                                    WidgetRefreshScheduler.requestImmediate(applicationContext, widgetKey, refreshFromApi = true)
                                    WidgetRefreshScheduler.reloadWebSocket(applicationContext)
                                    delay(500)
                                }
                            }
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfigureScreen(
        api: HomeAssistantApi,
        spec: WidgetSpec,
        appWidgetId: Int,
        onSave: (
            entityId: String,
            friendly: String,
            rowOrder: List<String>,
            removedRows: Set<String>,
            pickerVisible: Map<String, List<String>>,
            optionOrder: Map<String, List<String>>,
            colors: Map<Pair<String, String>, String>,
            icons: Map<Pair<String, String>, String>,
            toggles: Map<String, Boolean>,
            requireAuth: Boolean,
            theme: String,
            extraEntities: Map<String, String>,
            stateTemplate: String,
            customFeaturesJson: String,
            alarmCode: String
        ) -> Unit
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val haptic = LocalHapticFeedback.current
        val themeRepository = remember(context) { ThemeRepository(context) }
        val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)

        var allEntities by remember { mutableStateOf<List<HAEntity>>(emptyList()) }
        var entities by remember { mutableStateOf<List<HAEntity>>(emptyList()) }
        var selected by remember { mutableStateOf<HAEntity?>(null) }
        var name by remember { mutableStateOf("") }
        var stateTemplate by remember { mutableStateOf("") }
        var alarmCode by remember { mutableStateOf("") }
        var requireAuth by remember { mutableStateOf(false) }
        var widgetTheme by remember { mutableStateOf("auto") }
        var isLoading by remember { mutableStateOf(true) }
        val haPrefs = remember { context.getSharedPreferences("ha_prefs", android.content.Context.MODE_PRIVATE) }
        var noEnlarge by remember { mutableStateOf(haPrefs.getBoolean("widget_no_enlarge", false)) }

        val rowOrder = remember(spec.key) { mutableStateListOf<String>() }
        val removedRows = remember(spec.key) { mutableStateListOf<String>() }
        val pickerOptionOrder = remember(spec.key) { mutableStateMapOf<String, List<String>>() }
        val pickerHidden = remember(spec.key) { mutableStateMapOf<String, Set<String>>() }
        val pickerColors = remember(spec.key) { mutableStateMapOf<Pair<String, String>, String>() }
        val pickerIcons = remember(spec.key) { mutableStateMapOf<Pair<String, String>, String>() }
        val toggles = remember(spec.key) { mutableStateMapOf<String, Boolean>() }
        val extraEntities = remember(spec.key) { mutableStateMapOf<String, String>() }

        var editingRowId by remember { mutableStateOf<String?>(null) }

        // ── Custom features (reuse the app card editor, backed by cards_prefs) ──
        val cardsPrefs = remember { context.getSharedPreferences("cards_prefs", android.content.Context.MODE_PRIVATE) }
        val cfgType = remember {
            Types.newParameterizedType(
                Map::class.java, String::class.java,
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            )
        }
        val cfListType = remember {
            Types.newParameterizedType(
                List::class.java,
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            )
        }
        val cardConfigs = remember {
            mutableStateMapOf<String, Map<String, Any>>().apply {
                cardsPrefs.getString("card_cfg_v2", null)
                    ?.let { runCatching { Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(cfgType).fromJson(it) }.getOrNull() }
                    ?.forEach { (k, v) -> put(k, v) }
            }
        }
        fun persistCardConfigs() {
            cardsPrefs.edit().putString(
                "card_cfg_v2",
                Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(cfgType).toJson(cardConfigs)
            ).apply()
        }
        val customFeatures = remember(spec.key) { mutableStateListOf<Map<String, Any>>() }
        var showCustomFeatureEditor by remember { mutableStateOf(false) }
        var customFeatureEditorId by remember { mutableStateOf<String?>(null) }
        var cfSaveText by remember { mutableStateOf("Add feature") }
        var cfSaveEnabled by remember { mutableStateOf(false) }
        var cfSaveAction by remember { mutableStateOf<() -> Unit>({}) }

        fun customIds(): List<String> = customFeatures.mapNotNull { it["id"] as? String }
        fun isCustom(rowId: String): Boolean = customFeatures.any { it["id"] == rowId }
        fun customFeatureLabel(rowId: String): String =
            customFeatures.find { it["id"] == rowId }?.get("label") as? String ?: "Custom feature"
        fun customFeatureIcon(rowId: String): String =
            customFeatures.find { it["id"] == rowId }?.get("icon") as? String ?: "bolt"

        fun customFeaturesJson(): String =
            if (customFeatures.isEmpty()) ""
            else runCatching {
                Moshi.Builder().build().adapter<List<Map<String, Any>>>(cfListType).toJson(customFeatures.toList())
            }.getOrNull().orEmpty()

        // A minimal AppState so the existing CustomFeatureDetailPage editor works here. It only
        // uses configs / all / api / saveConfigs; everything else is an inert stub.
        val editorAppState = remember(allEntities) {
            AppState(
                api = api,
                selected = mutableListOf(),
                selectedLandscape = mutableListOf(),
                all = allEntities,
                loading = false,
                edit = false,
                onEditToggle = {},
                names = mutableMapOf(),
                configs = cardConfigs,
                saveLayout = {},
                saveConfigs = { persistCardConfigs() },
                saveNames = {},
                scaffoldPadding = PaddingValues(0.dp),
                activeCardKey = null,
                onActiveCardKeyChange = {},
                configEntityId = null,
                onConfigEntityIdChange = {},
                onShowSettings = {},
                onFullScreenChange = {},
                baseUrl = "", token = "", tokenProvider = { HomeAssistantAuth.currentAccessToken(context) }, frigateUrl = "",
                onSaveSettings = { _, _, _, _ -> },
                callServiceOptimistically = { _, _, _, _, _ -> },
                loadFailed = false,
                refreshCountdown = 30,
                onRetryLoad = {},
                onAddEntity = {},
                onRemoveEntity = {},
            )
        }

        // Pulls custom features for the selected entity out of the card config, seeding from the
        // widget's own stored features the first time (so an imported widget stays editable).
        //
        // forceFromSeed = true  → existing widget being re-edited: ALWAYS initialize cards_prefs
        //   from this widget's own DataStore state (even if empty, even if cards_prefs already has
        //   features from a different widget or card for the same entity).
        // forceFromSeed = false → new widget or post-editor refresh: use whatever is in cards_prefs
        //   (allows seeding from the card's custom features for a brand-new widget).
        fun reloadCustomFeatures(seedFrom: String? = null, forceFromSeed: Boolean = false) {
            val eid = selected?.entity_id ?: return
            var cfg = cardConfigs[eid].orEmpty()
            var list = (cfg["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            val shouldSeed = forceFromSeed || (list.isEmpty() && !seedFrom.isNullOrBlank())
            if (shouldSeed) {
                val seeded = parseCustomFeatures(seedFrom.orEmpty())
                val asMaps = seeded.map { f ->
                    buildMap<String, Any> {
                        put("id", f.id); put("type", f.type); put("label", f.label)
                        f.icon?.let { put("icon", it) }
                        f.serviceName?.let { put("serviceName", it) }
                        f.serviceData?.let { put("serviceData", it) }
                        f.targetEntity?.let { put("targetEntity", it) }
                        f.targets?.let { put("targets", it) }
                        f.valueTemplate?.let { put("valueTemplate", it) }
                        f.visibilityConditions?.let { put("visibility", it) }
                    }
                }
                cardConfigs[eid] = cfg.toMutableMap().apply { put("custom_features", asMaps) }
                persistCardConfigs()
                list = asMaps
            }
            customFeatures.clear()
            customFeatures.addAll(list)
            customIds().forEach { id -> if (id !in rowOrder && id !in removedRows) rowOrder.add(id) }
        }

        fun openCustomFeatureEditor(featureId: String?) {
            customFeatureEditorId = featureId
            cfSaveText = if (featureId != null) "Save changes" else "Add feature"
            showCustomFeatureEditor = true
        }

        // ── Helpers ────────────────────────────────────────────────────────────

        fun evaluateEntitySupport(logic: SupportedLogic?, entity: HAEntity?): Boolean {
            if (logic == null) return true
            val attrs = entity?.attributes ?: return true
            val attrVal = attrs[logic.attribute]
            if (attrVal == null) {
                if (logic.attribute == "supported_features") return true
                return false
            }
            if (logic.contains != null) {
                val list = when (attrVal) {
                    is List<*> -> attrVal.filterIsInstance<String>().map { it.lowercase() }
                    is String  -> attrVal.split("|").map { it.trim().lowercase() }
                    else -> emptyList()
                }
                return list.contains(logic.contains.lowercase())
            }
            if (logic.bitmask != null) {
                val intVal = when (attrVal) {
                    is Number -> attrVal.toInt()
                    is String -> attrVal.toIntOrNull() ?: attrVal.toDoubleOrNull()?.toInt() ?: 0
                    else -> 0
                }
                return (intVal and logic.bitmask) != 0
            }
            return true
        }

        fun supportedRows(entity: HAEntity?) = spec.rows.filter { evaluateEntitySupport(it.supportedIf, entity) }

        fun pickerAttribute(rowId: String): String? =
            when (rowId) {
                "hvac_mode" -> "hvac_modes"
                "fan_mode" -> "fan_modes"
                "preset_mode" -> "preset_modes"
                "swing_mode" -> "swing_modes"
                "swing_horizontal_mode" -> "swing_horizontal_modes"
                else -> null
            }

        fun supportedPickerOptions(picker: PickerRowSpec, entity: HAEntity?): List<PickerOptionSpec> {
            val builtIns = picker.options.filter { evaluateEntitySupport(it.supportedIf, entity) }
            val dynamicIds = pickerAttribute(picker.id)
                ?.let { attr -> entity?.attributes?.get(attr) as? Iterable<*> }
                ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                .orEmpty()

            return mergePickerOptions(picker.id, builtIns, dynamicIds) { id ->
                picker.options.firstOrNull { it.id == id }
                    // Reuse a built-in's icon/label for aliases (e.g. "mid" -> "medium") while keeping
                    // the entity's own id so the service call still sends the value HA expects.
                    ?: picker.options.firstOrNull { pickerOptionKey(picker.id, it.id) == pickerOptionKey(picker.id, id) }
                        ?.copy(id = id, supportedIf = null)
                    ?: PickerOptionSpec(id, pickerOptionFallbackLabel(picker.id, id))
            }
        }

        fun pickerIdsForEntity(entity: HAEntity?, picker: PickerRowSpec) =
            supportedPickerOptions(picker, entity).map { it.id }

        fun rowDisplayName(rowId: String): String {
            if (isCustom(rowId)) return customFeatureLabel(rowId)
            val row = spec.rows.find { it.id == rowId }
            return when (row) {
                is DataRowSpec   -> row.label ?: rowId.replace("_", " ").replaceFirstChar { it.uppercase() }
                is SliderRowSpec -> row.label ?: rowId.replace("_", " ").replaceFirstChar { it.uppercase() }
                else             -> rowId.replace("_", " ").replaceFirstChar { it.uppercase() }
            }
        }

        fun rowSymbol(rowId: String): String {
            if (isCustom(rowId)) return customFeatureIcon(rowId)
            return when (spec.rows.find { it.id == rowId }) {
                is PickerRowSpec  -> "toggle_on"
                is SliderRowSpec  -> "sliders"
                is CounterRowSpec -> "exposure"
                is ButtonsRowSpec -> "smart_button"
                is DataRowSpec    -> "data_usage"
                is SpacerRowSpec  -> "space_bar"
                else              -> "add_box"
            }
        }

        fun visiblePickerMap(): Map<String, List<String>> =
            spec.rows.filterIsInstance<PickerRowSpec>().associate { picker ->
                val hidden  = pickerHidden[picker.id].orEmpty()
                val allIds = pickerIdsForEntity(selected, picker)
                val ordered = normalizePickerOptionIds(
                    picker.id,
                    pickerOptionOrder[picker.id].orEmpty() + allIds,
                    allIds
                )
                val hiddenKeys = hidden.map { pickerOptionKey(picker.id, it) }.toSet()
                picker.id to ordered.filter { pickerOptionKey(picker.id, it) !in hiddenKeys }
            }

        // ── Load existing settings ─────────────────────────────────────────────

        LaunchedEffect(Unit) {
            isLoading = true
            try {
                val all = api.getStates()
                allEntities = all
                entities = all.filter { it.entity_id.startsWith(spec.configureQuery.entityPrefix) }
                    .sortedBy { it.entity_id }

                val existingPrefs = runCatching {
                    val gid = GlanceAppWidgetManager(this@FactoryConfigureActivity).getGlanceIdBy(appWidgetId)
                    getAppWidgetState(applicationContext, CachedPreferencesGlanceStateDefinition, gid)
                }.getOrNull()

                val existingEntityId = existingPrefs?.get(FACT_ENTITY_ID)
                selected = entities.firstOrNull { it.entity_id == existingEntityId } ?: entities.firstOrNull()
                name = existingPrefs?.get(FACT_FRIENDLY_NAME)
                    ?: selected?.attributes?.get("friendly_name")?.toString()
                    ?: selected?.entity_id ?: ""
                stateTemplate = existingPrefs?.get(FACT_STATE_TEMPLATE).orEmpty()
                alarmCode = existingPrefs?.get(FACT_ALARM_CODE).orEmpty()
                requireAuth = existingPrefs?.get(FACT_REQUIRE_AUTH) ?: false
                widgetTheme = existingPrefs?.get(FACT_THEME) ?: "auto"

                val savedRowOrder  = existingPrefs?.get(FACT_ROW_ORDER).orEmpty().split("|").filter { it.isNotBlank() }
                val savedRemoved   = existingPrefs?.get(FACT_REMOVED_ROWS).orEmpty().split("|").filter { it.isNotBlank() }

                spec.entityPickers.forEach { picker ->
                    val saved = existingPrefs?.get(stringPreferencesKey("fact_extra_entity_${picker.id}"))
                    if (saved != null) extraEntities[picker.id] = saved
                }

                // Seed/load custom features for this entity.
                // forceFromSeed = true for existing widgets so each widget instance always starts
                // from its own DataStore state, never from another widget's leftover in cards_prefs.
                reloadCustomFeatures(
                    seedFrom = existingPrefs?.get(FACT_CUSTOM_FEATURES),
                    forceFromSeed = existingPrefs != null
                )
                val custom = customIds()

                val supported = supportedRows(selected).map { it.id }
                val valid = supported + custom
                rowOrder.clear()
                if (savedRowOrder.isNotEmpty()) {
                    // A removed row stays in savedRemoved; it must NOT be re-added to the reorderable
                    // list, otherwise it both reappears as "added" and lives in rowOrder + removedRows
                    // at once. Re-adding it later from the "Add feature" menu then duplicates it,
                    // and the widget renders the same row twice. Keep rowOrder disjoint from removed.
                    rowOrder.addAll(savedRowOrder.filter { it in valid && it !in savedRemoved }.distinct())
                    supported.forEach { if (it !in rowOrder && it !in savedRemoved) rowOrder.add(it) }
                    custom.forEach { if (it !in rowOrder) rowOrder.add(it) }
                } else {
                    rowOrder.addAll(supported)
                    rowOrder.addAll(custom)
                }
                removedRows.clear()
                removedRows.addAll(savedRemoved.filter { it in supported })

                spec.rows.filterIsInstance<PickerRowSpec>().forEach { picker ->
                    val allIds = pickerIdsForEntity(selected, picker)
                    val savedVisibleRaw = existingPrefs?.get(pickerVisibleKey(picker.id))
                    val savedVisible = savedVisibleRaw
                        ?.split("|")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.let { normalizePickerOptionIds(picker.id, it, allIds) }
                    val ordered = if (savedVisibleRaw == null) allIds else buildList {
                        addAll(savedVisible.orEmpty())
                        allIds.forEach { if (it !in savedVisible.orEmpty()) add(it) }
                    }
                    pickerOptionOrder[picker.id] = ordered
                    pickerHidden[picker.id] = if (savedVisibleRaw == null) emptySet()
                        else allIds.toSet() - savedVisible.orEmpty().toSet()
                    allIds.forEach { optionId ->
                        existingPrefs?.get(pickerColorKey(picker.id, optionId))?.let { pickerColors[picker.id to optionId] = it }
                        existingPrefs?.get(pickerIconKey(picker.id, optionId))?.let { pickerIcons[picker.id to optionId] = it }
                    }
                }

                spec.toggles.forEach { toggle ->
                    val saved = existingPrefs?.get(
                        androidx.datastore.preferences.core.booleanPreferencesKey("fact_toggle_${toggle.id}")
                    )
                    toggles[toggle.id] = saved ?: toggle.defaultValue
                }
            } catch (e: Exception) {
                Log.e("HA_CONFIG", "Failed to load states", e)
            } finally {
                isLoading = false
            }
        }

        LaunchedEffect(selected?.entity_id) {
            reloadCustomFeatures()
            val custom = customIds()
            val supported = supportedRows(selected).map { it.id }
            val valid = supported + custom
            val currentOrder = rowOrder.filter { it in valid }.distinct()
            rowOrder.clear()
            rowOrder.addAll(currentOrder)
            supported.forEach { if (it !in rowOrder && it !in removedRows) rowOrder.add(it) }
            custom.forEach { if (it !in rowOrder) rowOrder.add(it) }
            removedRows.removeAll { it !in supported }

            spec.rows.filterIsInstance<PickerRowSpec>()
                .filter { evaluateEntitySupport(it.supportedIf, selected) }
                .forEach { picker ->
                    val allIds = pickerIdsForEntity(selected, picker)
                    val currentOpt = normalizePickerOptionIds(picker.id, pickerOptionOrder[picker.id].orEmpty(), allIds)
                    pickerOptionOrder[picker.id] = buildList {
                        addAll(currentOpt)
                        allIds.forEach { if (it !in currentOpt) add(it) }
                    }
                    pickerHidden[picker.id] = pickerHidden[picker.id].orEmpty().filter { it in allIds }.toSet()
                }

            if (selected != null) {
                spec.entityPickers.forEach { picker ->
                    if (extraEntities[picker.id] == null) {
                        val baseId = selected?.entity_id?.split(".")?.last()?.lowercase() ?: ""
                        val friendlyBase = selected?.attributes?.get("friendly_name")?.toString()?.lowercase() ?: ""
                        val match = allEntities.find { e ->
                            e.entity_id.startsWith(picker.entityPrefix) && (
                                e.entity_id.contains(baseId) ||
                                (friendlyBase.isNotEmpty() && e.attributes["friendly_name"]?.toString()?.lowercase()?.contains(friendlyBase) == true)
                            ) && (
                                e.entity_id.contains("battery") ||
                                e.attributes["device_class"] == "battery" ||
                                e.attributes["friendly_name"]?.toString()?.lowercase()?.contains("battery") == true
                            )
                        }
                        if (match != null) extraEntities[picker.id] = match.entity_id
                    }
                }
            }
        }

        // ── Loading ────────────────────────────────────────────────────────────

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        // ── Picker options dialog (same as FactoryCardSettings) ───────────────

        editingRowId?.let { rowId ->
            val picker = spec.rows.filterIsInstance<PickerRowSpec>().find { it.id == rowId }
            if (picker != null) {
                AlertDialog(
                    onDismissRequest = { editingRowId = null },
                    title = { Text(rowDisplayName(rowId)) },
                    text = {
                        Column {
                            supportedPickerOptions(picker, selected).forEach { opt ->
                                val hiddenKeys = pickerHidden[rowId].orEmpty().map { pickerOptionKey(rowId, it) }.toSet()
                                val isHidden = pickerOptionKey(rowId, opt.id) in hiddenKeys
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val cur = pickerHidden[rowId] ?: emptySet()
                                            pickerHidden[rowId] = if (isHidden) {
                                                cur.filter { pickerOptionKey(rowId, it) != pickerOptionKey(rowId, opt.id) }.toSet()
                                            } else {
                                                cur + opt.id
                                            }
                                        }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Checkbox(checked = !isHidden, onCheckedChange = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(opt.label, modifier = Modifier.weight(1f))
                                    if (opt.id != opt.label) {
                                        Text(opt.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { editingRowId = null }) { Text("Done") } }
                )
            }
        }

        // ── Main settings UI ──────────────────────────────────────────────────

        Box(Modifier.fillMaxSize()) {
            SettingsScaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Configure ${spec.title}") },
                        navigationIcon = {
                            FilledIconButton(
                                onClick = { finish() },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) { Icon(rememberSymbolPainter("arrow_back"), "Back") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    )
                },
                contentWindowInsets = WindowInsets(0.dp)
            ) {

                // ── Name ──────────────────────────────────────────────────────
                section {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Display name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }

                // ── State template ────────────────────────────────────────────
                section {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = stateTemplate,
                                onValueChange = { stateTemplate = it },
                                label = { Text("State text template") },
                                placeholder = { Text("Default (state)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Use {state}, {name}, {attribute_name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item { SuggestionChip(onClick = { stateTemplate += "{state}" }, label = { Text("{state}") }) }
                            item { SuggestionChip(onClick = { stateTemplate += "{name}" }, label = { Text("{name}") }) }
                            selected?.attributes?.keys?.forEach { attrKey ->
                                item { SuggestionChip(onClick = { stateTemplate += "{$attrKey}" }, label = { Text("{$attrKey}") }) }
                            }
                        }
                    }
                }

                // ── Toggles (spec settings) ───────────────────────────────────
                if (spec.toggles.isNotEmpty()) {
                    section {
                        spec.toggles.forEach { toggle ->
                            item(padding = 16.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    if (toggle.icon != null) {
                                        Icon(rememberSymbolPainter(toggle.icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Text(toggle.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = toggles[toggle.id] ?: toggle.defaultValue,
                                        onCheckedChange = { toggles[toggle.id] = it }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Features (row order + visibility) ─────────────────────────
                item {
                    Text(
                        "Features",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                    )
                }
                item {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))) {
                        ReorderableColumn(
                            list = rowOrder.toList(),
                            onSettle = { from, to ->
                                if (from != to) {
                                    val moved = rowOrder.removeAt(from)
                                    rowOrder.add(to, moved)
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onMove = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { idx, rowId, isDragging, _ ->
                            ReorderableItem {
                                val isPickerRow = spec.rows.filterIsInstance<PickerRowSpec>().any { it.id == rowId }
                                val rowCount = rowOrder.size
                                val shape = when {
                                    rowCount <= 1 -> RoundedCornerShape(20.dp)
                                    idx == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                                    idx == rowCount - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                                    else -> RoundedCornerShape(4.dp)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .zIndex(if (isDragging) 100f else 0f)
                                        .graphicsLayer { if (isDragging) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 16.dp.toPx() } }
                                        .clip(shape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                            .draggableHandle(onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(rememberSymbolPainter("drag_handle"), "Drag", tint = MaterialTheme.colorScheme.outline) }
                                    Icon(rememberSymbolPainter(rowSymbol(rowId)), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).padding(end = 4.dp))
                                    Text(rowDisplayName(rowId), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    val rowIsCustom = isCustom(rowId)
                                    if (isPickerRow || rowIsCustom) {
                                        IconButton(onClick = { if (rowIsCustom) openCustomFeatureEditor(rowId) else editingRowId = rowId }) {
                                            Icon(rememberSymbolPainter(if (rowIsCustom) "edit" else "tune"), "Edit", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    IconButton(onClick = {
                                        rowOrder.remove(rowId)
                                        if (rowIsCustom) {
                                            customFeatures.removeAll { it["id"] == rowId }
                                            selected?.entity_id?.let { eid ->
                                                cardConfigs[eid] = cardConfigs[eid].orEmpty().toMutableMap().apply {
                                                    put("custom_features", customFeatures.toList())
                                                }
                                                persistCardConfigs()
                                            }
                                        } else {
                                            removedRows.add(rowId)
                                        }
                                    }) { Icon(rememberSymbolPainter("delete"), "Remove", tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }

                // Add feature (restore hidden rows + add custom features)
                item {
                    var showAdd by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Icon(rememberSymbolPainter("add"), null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add feature")
                        }
                        DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                            removedRows.toList().forEach { rowId ->
                                DropdownMenuItem(
                                    text = { Text(rowDisplayName(rowId)) },
                                    leadingIcon = { Icon(rememberSymbolPainter(rowSymbol(rowId)), null) },
                                    onClick = {
                                        rowOrder.add(rowId)
                                        removedRows.remove(rowId)
                                        showAdd = false
                                    }
                                )
                            }
                            if (removedRows.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Text("Add Custom Feature") },
                                leadingIcon = { Icon(rememberSymbolPainter("add_circle"), null) },
                                onClick = {
                                    showAdd = false
                                    openCustomFeatureEditor(null)
                                }
                            )
                        }
                    }
                }

                // ── Widget settings ───────────────────────────────────────────
                title("Widget")
                section {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            listOf("auto", "light", "dark").forEach { t ->
                                FilterChip(
                                    selected = widgetTheme == t,
                                    onClick = { widgetTheme = t },
                                    label = { Text(t.replaceFirstChar { it.uppercase() }) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                    item(padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Require authentication", style = MaterialTheme.typography.bodyLarge)
                                Text("Ask for biometric confirmation before actions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = requireAuth, onCheckedChange = { requireAuth = it })
                        }
                    }
                    if (spec.domain == "alarm_control_panel") {
                        item(padding = 16.dp) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = alarmCode,
                                    onValueChange = { alarmCode = it },
                                    label = { Text("Alarm code (optional)") },
                                    placeholder = { Text("Ask each time") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "If set, this PIN is sent automatically when arming/disarming. Leave blank to be prompted each time. Stored on this device only.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item(padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Keep original size", style = MaterialTheme.typography.bodyLarge)
                                Text("Never enlarge widgets — only shrink to fit. A bigger cell adds space instead of scaling up.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = noEnlarge,
                                onCheckedChange = {
                                    noEnlarge = it
                                    haPrefs.edit().putBoolean("widget_no_enlarge", it).apply()
                                    WidgetRefreshScheduler.requestProviderUpdate(context)
                                }
                            )
                        }
                    }
                }

                // ── Secondary entities ────────────────────────────────────────
                if (spec.entityPickers.isNotEmpty()) {
                    title("Secondary Entities")
                    section {
                        spec.entityPickers.forEach { picker ->
                            item {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(picker.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(8.dp))
                                    val selectedExtraId = extraEntities[picker.id]
                                    val entitiesMatching = allEntities.filter { it.entity_id.startsWith(picker.entityPrefix) }
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedTextField(
                                            value = selectedExtraId ?: "Not selected",
                                            onValueChange = {},
                                            readOnly = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = false,
                                            shape = RoundedCornerShape(12.dp),
                                            trailingIcon = { Icon(rememberSymbolPainter("expand_more"), null) }
                                        )
                                        Box(Modifier.matchParentSize().clickable { expanded = true })
                                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.9f)) {
                                            DropdownMenuItem(text = { Text("None") }, onClick = { extraEntities.remove(picker.id); expanded = false })
                                            entitiesMatching.forEach { e ->
                                                DropdownMenuItem(
                                                    text = { Text(e.attributes["friendly_name"]?.toString() ?: e.entity_id) },
                                                    onClick = { extraEntities[picker.id] = e.entity_id; expanded = false }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Entity selection ──────────────────────────────────────────
                title("Entity")
                section {
                    item {
                        OutlinedTextField(
                            value = entities.firstOrNull { it.entity_id == selected?.entity_id }?.let {
                                it.attributes["friendly_name"]?.toString() ?: it.entity_id
                            } ?: "Select entity",
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            label = { Text("Entity") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    items(entities) { e ->
                        val isSel = selected?.entity_id == e.entity_id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = e
                                    name = e.attributes["friendly_name"]?.toString() ?: e.entity_id
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = isSel, onClick = {
                                selected = e
                                name = e.attributes["friendly_name"]?.toString() ?: e.entity_id
                            })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(e.attributes["friendly_name"]?.toString() ?: e.entity_id, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
                                Text(e.entity_id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(128.dp)) }
            }

            // ── Save dock ─────────────────────────────────────────────────────
            if (!showCustomFeatureEditor) {
                ConfigureCardSaveDock(
                    text = "Save widget",
                    enabled = selected != null,
                    onSave = {
                        selected?.let { sel ->
                            onSave(
                                sel.entity_id,
                                name.trim(),
                                rowOrder.toList(),
                                removedRows.toSet(),
                                visiblePickerMap(),
                                pickerOptionOrder.toMap(),
                                pickerColors.toMap(),
                                pickerIcons.toMap(),
                                toggles.toMap(),
                                requireAuth,
                                widgetTheme,
                                extraEntities.toMap(),
                                stateTemplate.trim(),
                                customFeaturesJson(),
                                alarmCode.trim()
                            )
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // ── Custom feature editor overlay (reuses the app card editor) ─────
            if (showCustomFeatureEditor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val eid = selected?.entity_id ?: ""
                    key(customFeatureEditorId, eid) {
                        CustomFeatureDetailPage(
                            entityId = eid,
                            featureId = customFeatureEditorId,
                            onBack = {
                                showCustomFeatureEditor = false
                                reloadCustomFeatures()
                            },
                            appState = editorAppState,
                            showSaveDock = false,
                            onSaveDockStateChange = { text, enabled, action ->
                                cfSaveText = text
                                cfSaveEnabled = enabled
                                cfSaveAction = action
                            }
                        )
                    }
                }
                ConfigureCardSaveDock(
                    text = cfSaveText,
                    enabled = cfSaveEnabled,
                    onSave = { cfSaveAction() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}
