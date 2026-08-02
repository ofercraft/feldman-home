package com.feldman.ha.ui.editors

import androidx.activity.compose.BackHandler
import com.feldman.ha.widgets.*
import com.feldman.ha.api.HomeAssistantApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.feldman.ha.data.HAEntity
import com.feldman.ha.ui.navigation.LocalAppState
import com.feldman.ha.ui.pages.SettingsTextField
import com.feldman.ha.ui.pages.SettingsTopBar
import com.feldman.ha.ui.pages.CustomFeatureDetailPage
import com.feldman.motion.navigation.Navigator
import com.feldman.motion.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import com.feldman.ha.ui.camera.CAMERA_REFRESH_MAX_SEC
import com.feldman.ha.ui.camera.CAMERA_REFRESH_MIN_SEC
import com.feldman.ha.ui.camera.CameraSource
import com.feldman.ha.ui.camera.FACTORY_CAMERA_ID_KEY
import com.feldman.ha.ui.camera.FACTORY_CAMERA_NAME_KEY
import com.feldman.ha.ui.camera.FACTORY_CAMERA_REFRESH_INTERVAL_KEY
import com.feldman.ha.ui.camera.FACTORY_CAMERA_SOURCE_KEY
import com.feldman.ha.ui.cards.CardPresets
import com.feldman.ha.ui.cards.FactoryCard
import com.feldman.ha.ui.cards.cardBackgroundColor
import com.feldman.ha.ui.cards.cleanLabelBlocks
import com.feldman.ha.ui.cards.contentColorFor
import com.feldman.ha.ui.cards.ensureLabelBlockIds
import com.feldman.ha.ui.cards.evaluateCondition
import com.feldman.ha.ui.cards.pickerOptionsForEntity
import com.feldman.ha.ui.cards.resolveLabelBlocks
import com.feldman.ha.ui.cards.evaluateSupported

internal const val CARD_LAYOUT_MIN_WIDTH = 2
private const val CARD_LAYOUT_DEFAULT_WIDTH = 2
internal const val CARD_LAYOUT_MIN_HEIGHT = 3
// Buttons have no feature rows, but 1 grid row (16dp) is far too short to tap.
// Give them a comfortable minimum/default that still lets them be shorter than
// a full feature card.
internal const val CARD_BUTTON_MIN_HEIGHT = 3
internal const val CARD_LAYOUT_MAX_HEIGHT = 24
private const val CARD_LAYOUT_DEFAULT_HEIGHT = 3
private const val CARD_SPAN_X_KEY = "card_span_x"
private const val CARD_SPAN_Y_KEY = "card_span_y"
private val CARD_LAYOUT_PREVIEW_MIN_HEIGHT = 16.dp
private val CARD_LAYOUT_PREVIEW_HEIGHT_STEP = 13.dp

private data class CameraIntervalOption(val label: String, val seconds: Int)

private val CAMERA_INTERVAL_OPTIONS = listOf(
    CameraIntervalOption("30s", 30),
    CameraIntervalOption("1 min", 60),
    CameraIntervalOption("3 min", 180),
    CameraIntervalOption("5 min", 300),
    CameraIntervalOption("10 min", 600),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactoryCardSettings(
    entity: HAEntity,
    spec: WidgetSpec,
    api: HomeAssistantApi,
    names: MutableMap<String, String>,
    configs: MutableMap<String, Map<String, Any>>,
    saveNames: () -> Unit,
    saveConfigs: () -> Unit,
    onUpdated: () -> Unit,
    onDismiss: () -> Unit,
    allEntities: List<HAEntity> = emptyList(),
    onNavigate: Navigator,
    // Dialogs live in their own window and ignore any visual transform on the host
    // composition (e.g. the Clock standby's rotated screensaver); such hosts render inline.
    useDialog: Boolean = true
) {
    var name by remember {
        mutableStateOf(names[entity.entity_id] ?: entity.attributes["friendly_name"]?.toString() ?: entity.entity_id)
    }

    var selectedPage by remember { mutableStateOf("Config") }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    run {} // use configuration below
    val isCurrentlyLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val portraitWidthDp = if (isCurrentlyLandscape) configuration.screenHeightDp else configuration.screenWidthDp
    val landscapeWidthDp = if (isCurrentlyLandscape) configuration.screenWidthDp else configuration.screenHeightDp
    val settingsPrefs = remember(context) { context.getSharedPreferences("ha_prefs", android.content.Context.MODE_PRIVATE) }
    val portraitColOverride = settingsPrefs.getInt("grid_columns_portrait", 0)
    val landscapeColOverride = settingsPrefs.getInt("grid_columns_landscape", 0)
    fun baseColsFor(w: Int, override: Int) = if (override > 0) override else when { w >= 960 -> 8; w >= 640 -> 6; else -> 4 }
    val portraitColumns = baseColsFor(portraitWidthDp, portraitColOverride)
    val landscapeColumns = if (landscapeColOverride > 0) landscapeColOverride else {
        val colWidthDp = portraitWidthDp.toFloat() / portraitColumns
        ((landscapeWidthDp / colWidthDp) + 0.5f).toInt().coerceIn(4, 16)
    }
    val haptic = LocalHapticFeedback.current
    val themeRepository = remember(context) { ThemeRepository(context) }
    val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)
    val motionSpecs = MotionSpecs.withLevel(motionLevel)

    val config = configs[entity.entity_id] ?: emptyMap()

    val isButton = spec.style == CardStyle.BUTTON
    val isCamera = spec.style == CardStyle.CAMERA
    val isClock = spec.style == CardStyle.CLOCK
    // Button, camera and clock cards may shrink to a single column; entity cards keep the 2-column minimum.
    val minSpanX = if (isButton || isCamera || isClock) 1 else CARD_LAYOUT_MIN_WIDTH

    var btnLabel by remember(config) { mutableStateOf(config["label"] as? String ?: "Action") }
    var btnIcon by remember(config) { mutableStateOf(config["icon"] as? String ?: "bolt") }
    var showIconPicker by remember { mutableStateOf(false) }

    var btnService by remember(config) { mutableStateOf(config["service"] as? String ?: "") }
    var btnTarget by remember(config) { mutableStateOf(config["entityId"] as? String ?: "") }
    var btnServiceData by remember(config) { mutableStateOf(config["data"] as? String ?: "") }
    var showEntityPicker by remember { mutableStateOf(false) }

    var btnMode by remember(config) { mutableStateOf(if ((config["service"] as? String).isNullOrBlank()) "entity" else "service") }

    var btnVertical by remember(config) { mutableStateOf(config["vertical"] as? Boolean ?: false) }
    var btnBgColor by remember(config) { mutableStateOf((config["bgColor"] as? Number)?.toLong()) }

    var cameraSource by remember(entity.entity_id, config) {
        mutableStateOf(config[FACTORY_CAMERA_SOURCE_KEY] as? String ?: CameraSource.HA.name)
    }
    var cameraId by remember(entity.entity_id, config) {
        mutableStateOf(
            (config[FACTORY_CAMERA_ID_KEY] as? String)?.takeIf { it.isNotBlank() }
                ?: entity.entity_id
        )
    }
    var cameraRefreshIntervalSec by remember(entity.entity_id, config) {
        mutableIntStateOf(
            ((config[FACTORY_CAMERA_REFRESH_INTERVAL_KEY] as? Number)?.toInt() ?: 180)
                .coerceIn(CAMERA_REFRESH_MIN_SEC, CAMERA_REFRESH_MAX_SEC)
        )
    }

    // Composed label blocks (see ButtonLabelEditor.kt): ordered text + if/else segments.
    // Each block/rule carries a transient "_id" for stable editor keys (stripped on save).
    var btnLabelBlocks by remember(entity.entity_id, config) {
        mutableStateOf(
            (config["label_blocks"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                .map { ensureLabelBlockIds(it) }
        )
    }
    var showLabelEditor by remember(entity.entity_id) { mutableStateOf(false) }


    val toggleValues = remember(entity.entity_id, config) {
        mutableStateMapOf<String, Boolean>().apply {
            spec.toggles.forEach { toggle ->
                put(toggle.id, config[toggle.id] as? Boolean ?: toggle.defaultValue)
            }
        }
    }

    var stateTemplate by remember(entity.entity_id, config) {
        mutableStateOf(config["state_template"] as? String ?: "")
    }

    val isAlarmEntity = entity.entity_id.startsWith("alarm_control_panel.")
    var alarmCode by remember(entity.entity_id, config) {
        mutableStateOf(config["alarm_code"] as? String ?: "")
    }
    val allRowIds = spec.rows
        .filter { it.visibility != RowVisibility.WIDGET }
        .filter { evaluateSupported(it.supportedIf, entity) }
        .map { it.id }

    val savedRemoved = (config["removed_rows"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val savedOrder   = (config["row_order"]    as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    // Plain immutable lists so ReorderableColumn always gets a consistent snapshot.
    var visibleRows by remember(entity.entity_id, config) {
        val allCustomIds = (config["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            .mapNotNull { (it["id"] as? String)?.takeIf(String::isNotBlank) }
        val visible = allRowIds.filter { it !in savedRemoved } + allCustomIds
        val ordered = if (savedOrder.isNotEmpty()) {
            val validIds = (allRowIds + allCustomIds).toSet()
            (savedOrder.filter { it in validIds }.distinct() + visible.filter { it !in savedOrder }).distinct()
        } else visible
        mutableStateOf(ordered)
    }

    var removedRows by remember(entity.entity_id, config) {
        mutableStateOf(savedRemoved.filter { it in allRowIds })
    }

    // Chosen presentation per row, for rows whose spec offers more than one. Kept beside
    // pickerHidden because it is saved and reloaded on exactly the same path.
    val rowStyles = remember(entity.entity_id, config) {
        mutableStateMapOf<String, String>().apply {
            spec.rows.filterIsInstance<PickerRowSpec>().forEach { row ->
                val styleSpec = row.style ?: return@forEach
                put(row.id, config[styleSpec.configKey] as? String ?: styleSpec.default)
            }
        }
    }

    val pickerHidden = remember(entity.entity_id, config) {
        mutableStateMapOf<String, Set<String>>().apply {
            config.entries.filter { it.key.startsWith("picker_hidden:") }.forEach { (k, v) ->
                val rowId = k.removePrefix("picker_hidden:")
                put(rowId, (v as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet())
            }
        }
    }

    val minSpanY = cardLayoutMinSpanY(spec, entity, visibleRows, config, allEntities, context)

    var isDefaultHeight by remember(entity.entity_id, config, isCamera) {
        mutableStateOf(if (isCamera) false else config["card_height_default"] as? Boolean != false)
    }

    var spanX by remember(entity.entity_id, config, portraitColumns) {
        mutableIntStateOf(cardLayoutSpanX(config, portraitColumns, minSpanX))
    }
    var spanY by remember(entity.entity_id, config) {
        mutableIntStateOf(if (isDefaultHeight) minSpanY else cardLayoutSpanY(config, minSpanY))
    }

    // Landscape-specific layout state (falls back to portrait values if not set)
    var isDefaultHeightLand by remember(entity.entity_id, config, isCamera) {
        mutableStateOf(
            if (isCamera) false
            else config["card_height_default_land"] as? Boolean ?: config["card_height_default"] as? Boolean ?: true
        )
    }
    var spanXLand by remember(entity.entity_id, config, landscapeColumns) {
        mutableIntStateOf(
            ((config["card_span_x_land"] as? Number)?.toInt()
                ?: (config["card_span_x"] as? Number)?.toInt()
                ?: CARD_LAYOUT_DEFAULT_WIDTH).coerceIn(minSpanX, landscapeColumns)
        )
    }
    var spanYLand by remember(entity.entity_id, config) {
        mutableIntStateOf(
            if (isDefaultHeightLand) minSpanY
            else ((config["card_span_y_land"] as? Number)?.toInt()
                ?: (config["card_span_y"] as? Number)?.toInt()
                ?: minSpanY).coerceIn(minSpanY.coerceIn(1, CARD_LAYOUT_MAX_HEIGHT), CARD_LAYOUT_MAX_HEIGHT)
        )
    }

    LaunchedEffect(config, minSpanY, isDefaultHeight, portraitColumns) {
        spanX = cardLayoutSpanX(config, portraitColumns, minSpanX)
        spanY = if (isDefaultHeight) minSpanY else cardLayoutSpanY(config, minSpanY)
    }

    fun saveCardLayout(
        nextSpanX: Int = spanX,
        nextSpanY: Int = spanY,
        nextDefaultHeight: Boolean = isDefaultHeight
    ) {
        val clampedSpanX = nextSpanX.coerceIn(minSpanX, portraitColumns)
        val clampedSpanY = nextSpanY.coerceIn(minSpanY, CARD_LAYOUT_MAX_HEIGHT)
        val actualSpanY = if (nextDefaultHeight) minSpanY else clampedSpanY

        isDefaultHeight = nextDefaultHeight
        spanX = clampedSpanX
        spanY = actualSpanY
    }

    fun saveCardLayoutLand(
        nextSpanX: Int = spanXLand,
        nextSpanY: Int = spanYLand,
        nextDefaultHeight: Boolean = isDefaultHeightLand
    ) {
        val clampedSpanX = nextSpanX.coerceIn(minSpanX, landscapeColumns)
        val clampedSpanY = nextSpanY.coerceIn(minSpanY, CARD_LAYOUT_MAX_HEIGHT)
        isDefaultHeightLand = nextDefaultHeight
        spanXLand = clampedSpanX
        spanYLand = if (nextDefaultHeight) minSpanY else clampedSpanY
    }

    LaunchedEffect(minSpanY, isDefaultHeight) {
        if (!isDefaultHeight && spanY < minSpanY) saveCardLayout(nextSpanY = minSpanY)
    }

    var customFeatures by remember(entity.entity_id, config) {
        mutableStateOf((config["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty())
    }

    fun getCustomFeatureLabel(rowId: String): String {
        return customFeatures.find { it["id"] == rowId }?.get("label") as? String ?: "Custom Feature"
    }

    fun getCustomFeatureIcon(rowId: String): String {
        return customFeatures.find { it["id"] == rowId }?.get("icon") as? String ?: "bolt"
    }

    val effectiveSpanX = spanX.coerceIn(minSpanX, portraitColumns)
    val effectiveSpanY = if (isDefaultHeight) minSpanY else spanY.coerceIn(minSpanY, CARD_LAYOUT_MAX_HEIGHT)
    val effectiveSpanXLand = spanXLand.coerceIn(minSpanX, landscapeColumns)
    val effectiveSpanYLand = if (isDefaultHeightLand) minSpanY else spanYLand.coerceIn(minSpanY, CARD_LAYOUT_MAX_HEIGHT)

    // Live preview config tracks pending edits so the preview stays in sync.
    // Keyed on `config` too: editing picker options happens on a separate page that writes
    // config, which changes its identity and re-creates pickerHidden/visibleRows as new state
    // objects. Without `config` in the key, this derivedStateOf keeps a stale closure over the
    // old objects, so the preview wouldn't reflect picker option hide/show (rows still did,
    // because those are edited in place without a config write).
    val previewConfig by remember(entity.entity_id, config, portraitColumns, landscapeColumns) {
        derivedStateOf {
            config.toMutableMap().apply {
                val previewMinSpanY = cardLayoutMinSpanY(spec, entity, visibleRows, config, allEntities, context)

                if (isButton) {
                    put("label", btnLabel)
                    put("icon", btnIcon)
                    put("service", if (btnMode == "service") btnService else "")
                    put("entityId", btnTarget)
                    put("data", btnServiceData)
                    put("vertical", btnVertical)
                    if (btnBgColor != null) put("bgColor", btnBgColor!!) else remove("bgColor")
                    if (btnLabelBlocks.isNotEmpty()) put("label_blocks", btnLabelBlocks) else remove("label_blocks")
                }
                if (isCamera) {
                    put(FACTORY_CAMERA_SOURCE_KEY, cameraSource)
                    put(FACTORY_CAMERA_ID_KEY, cameraId)
                    put(FACTORY_CAMERA_NAME_KEY, name.trim().ifBlank { cameraId })
                    put(FACTORY_CAMERA_REFRESH_INTERVAL_KEY, cameraRefreshIntervalSec)
                }
                put(CARD_SPAN_X_KEY, spanX.coerceIn(minSpanX, portraitColumns))
                put(CARD_SPAN_Y_KEY, if (isDefaultHeight) previewMinSpanY else spanY.coerceIn(previewMinSpanY, CARD_LAYOUT_MAX_HEIGHT))
                put("card_height_default", isDefaultHeight)
                put("card_span_x_land", spanXLand.coerceIn(minSpanX, landscapeColumns))
                put("card_span_y_land", if (isDefaultHeightLand) previewMinSpanY else spanYLand.coerceIn(previewMinSpanY, CARD_LAYOUT_MAX_HEIGHT))
                put("card_height_default_land", isDefaultHeightLand)
                put("row_order", visibleRows)
                put("removed_rows", allRowIds.filter { it !in visibleRows })
                put("custom_features", customFeatures)
                put("state_template", stateTemplate.trim())
                if (isAlarmEntity) put("alarm_code", alarmCode.trim())
                pickerHidden.forEach { (rowId, hidden) ->
                    put("picker_hidden:$rowId", hidden.toList())
                }
                rowStyles.forEach { (rowId, style) ->
                    spec.rows.filterIsInstance<PickerRowSpec>()
                        .find { it.id == rowId }?.style?.let { put(it.configKey, style) }
                }
                toggleValues.forEach { (k, v) -> put(k, v) }
            } as Map<String, Any>
        }
    }
    // previewConfig is re-created whenever `config` changes (it's keyed on it above). A keyless
    // `remember { derivedStateOf { ... } }` here would capture the FIRST previewConfig State and
    // keep reading it after a config write (e.g. editing a picker row, which goes through
    // saveCurrentSettings()), freezing the preview while in-place edits like reorder kept working.
    // Keying on the current value re-reads previewConfig each time it changes.
    val previewConfigs = remember(previewConfig) { mapOf(entity.entity_id to previewConfig) }
    val previewNames   = remember(name) { mapOf(entity.entity_id to name) }

    val scope = rememberCoroutineScope()

    var featuresExpanded by remember { mutableStateOf(true) }
    var showAddFeature   by remember { mutableStateOf(false) }
    var editingRowId     by remember { mutableStateOf<String?>(null) }
    var showCustomFeatureEditor by remember(entity.entity_id) { mutableStateOf(false) }
    var customFeatureEditorId by remember(entity.entity_id) { mutableStateOf<String?>(null) }
    var customFeatureSaveText by remember(entity.entity_id) { mutableStateOf("Save changes") }
    var customFeatureSaveEnabled by remember(entity.entity_id) { mutableStateOf(false) }
    var customFeatureSaveAction by remember(entity.entity_id) { mutableStateOf<() -> Unit>({}) }
    val appState = LocalAppState.current

    fun saveCurrentSettings() {
        names[entity.entity_id] = name.trim()
        saveNames()
        val newConfig = config.toMutableMap()
        if (isButton) {
            newConfig["label"] = btnLabel.trim()
            newConfig["icon"] = btnIcon.trim().ifBlank { "bolt" }
            // Blank service means "entity mode": the tap action is derived from the
            // target entity's domain at fire time (toggle/press/trigger/…).
            newConfig["service"] = if (btnMode == "service") btnService.trim() else ""
            newConfig["entityId"] = btnTarget.trim()
            newConfig["data"] = btnServiceData.trim()
            newConfig["vertical"] = btnVertical
            if (btnBgColor != null) newConfig["bgColor"] = btnBgColor!! else newConfig.remove("bgColor")
            if (btnLabelBlocks.isEmpty()) {
                newConfig.remove("label_blocks")
            } else {
                newConfig["label_blocks"] = cleanLabelBlocks(btnLabelBlocks)
            }
        }
        if (isCamera) {
            newConfig[FACTORY_CAMERA_SOURCE_KEY] = cameraSource
            newConfig[FACTORY_CAMERA_ID_KEY] = cameraId.trim().ifBlank { entity.entity_id }
            newConfig[FACTORY_CAMERA_NAME_KEY] = name.trim().ifBlank { cameraId.trim().ifBlank { entity.entity_id } }
            newConfig[FACTORY_CAMERA_REFRESH_INTERVAL_KEY] =
                cameraRefreshIntervalSec.coerceIn(CAMERA_REFRESH_MIN_SEC, CAMERA_REFRESH_MAX_SEC)
        }
        newConfig[CARD_SPAN_X_KEY] = effectiveSpanX
        newConfig[CARD_SPAN_Y_KEY] = effectiveSpanY
        newConfig["card_height_default"] = isDefaultHeight
        newConfig["card_span_x_land"] = effectiveSpanXLand
        newConfig["card_span_y_land"] = effectiveSpanYLand
        newConfig["card_height_default_land"] = isDefaultHeightLand
        newConfig["row_order"] = visibleRows
        newConfig["removed_rows"] = removedRows
        newConfig["custom_features"] = customFeatures
        newConfig["state_template"] = stateTemplate.trim()
        if (isAlarmEntity) {
            val trimmedCode = alarmCode.trim()
            if (trimmedCode.isNotBlank()) newConfig["alarm_code"] = trimmedCode
            else newConfig.remove("alarm_code")
        }
        pickerHidden.forEach { (rowId, hidden) ->
            newConfig["picker_hidden:$rowId"] = hidden.toList()
        }
        rowStyles.forEach { (rowId, style) ->
            spec.rows.filterIsInstance<PickerRowSpec>()
                .find { it.id == rowId }?.style?.let { newConfig[it.configKey] = style }
        }
        toggleValues.forEach { (k, v) -> newConfig[k] = v }
        configs[entity.entity_id] = newConfig
        saveConfigs()
        onUpdated()
    }

    fun syncCustomFeaturesFromConfig() {
        val latestConfig = configs[entity.entity_id] ?: emptyMap()
        val latestCustomFeatures = (latestConfig["custom_features"] as? List<*>)
            ?.filterIsInstance<Map<String, Any>>()
            .orEmpty()
        val latestCustomIds = latestCustomFeatures.mapNotNull { (it["id"] as? String)?.takeIf(String::isNotBlank) }
        val latestSavedRemoved = (latestConfig["removed_rows"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val latestSavedOrder = (latestConfig["row_order"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val visible = allRowIds.filter { it !in latestSavedRemoved } + latestCustomIds

        customFeatures = latestCustomFeatures
        removedRows = latestSavedRemoved.filter { it in allRowIds }
        visibleRows = if (latestSavedOrder.isNotEmpty()) {
            val validIds = (allRowIds + latestCustomIds).toSet()
            (latestSavedOrder.filter { it in validIds }.distinct() + visible.filter { it !in latestSavedOrder }).distinct()
        } else {
            visible.distinct()
        }
        stateTemplate = latestConfig["state_template"] as? String ?: ""
        // Picker option visibility is edited on the separate options page, which writes
        // picker_hidden:* back to config. Reload it here too (rows were already synced above)
        // so the live preview reflects hidden/shown options — not just row add/remove/reorder.
        // Everything else the editor may have written. This list is the bug: any key held in
        // memory here but missing from this reload stays stale, and the next saveCurrentSettings()
        // writes that stale value straight back over what the editor just saved.
        spec.toggles.forEach { toggle ->
            toggleValues[toggle.id] = latestConfig[toggle.id] as? Boolean ?: toggle.defaultValue
        }
        spec.rows.filterIsInstance<PickerRowSpec>().forEach { row ->
            val styleSpec = row.style ?: return@forEach
            rowStyles[row.id] = latestConfig[styleSpec.configKey] as? String ?: styleSpec.default
        }
        (latestConfig["card_span_x"] as? Number)?.let { spanX = it.toInt() }
        (latestConfig["card_span_y"] as? Number)?.let { spanY = it.toInt() }
        (latestConfig["card_span_x_land"] as? Number)?.let { spanXLand = it.toInt() }
        (latestConfig["card_span_y_land"] as? Number)?.let { spanYLand = it.toInt() }

        pickerHidden.clear()
        latestConfig.entries.filter { it.key.startsWith("picker_hidden:") }.forEach { (k, v) ->
            pickerHidden[k.removePrefix("picker_hidden:")] =
                (v as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        }
        onUpdated()
    }

    fun closeCustomFeatureEditor() {
        showCustomFeatureEditor = false
        customFeatureEditorId = null
        customFeatureSaveText = "Save changes"
        customFeatureSaveEnabled = false
        customFeatureSaveAction = {}
        syncCustomFeaturesFromConfig()
    }

    fun openCustomFeatureEditor(featureId: String?) {
        saveCurrentSettings()
        customFeatureEditorId = featureId
        customFeatureSaveText = if (featureId != null) "Save changes" else "Add feature"
        customFeatureSaveEnabled = featureId != null
        customFeatureSaveAction = {}
        showCustomFeatureEditor = true
    }

    BackHandler {
        if (showLabelEditor) {
            showLabelEditor = false
        } else if (showCustomFeatureEditor) {
            closeCustomFeatureEditor()
        } else if (editingRowId != null) {
            editingRowId = null
        } else {
            onDismiss()
        }
    }

    // ── Picker-options edit dialog ────────────────────────────────────────────
    editingRowId?.let { rowId ->
        val picker = spec.rows.filterIsInstance<PickerRowSpec>().find { it.id == rowId }
        if (picker != null) {
            AlertDialog(
                onDismissRequest = { editingRowId = null },
                title = { Text(rowDisplayName(spec, rowId)) },
                text = {
                    Column {
                        picker.style?.let { styleSpec ->
                            Text(
                                "Style",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                styleSpec.options.forEach { option ->
                                    val selected = (rowStyles[rowId] ?: styleSpec.default) == option.id
                                    FilterChip(
                                        selected = selected,
                                        onClick = { rowStyles[rowId] = option.id },
                                        label = { Text(option.label) }
                                    )
                                }
                            }
                            // Only the multi-button style has options worth hiding; a single
                            // toggle has none, so the list below would be meaningless.
                            if ((rowStyles[rowId] ?: styleSpec.default) == ROW_STYLE_TOGGLE) {
                                return@Column
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        // List the options actually rendered for THIS entity (built-ins filtered by
                        // supportedIf + the entity's dynamic modes), so the ids we hide match what
                        // the card shows. Iterating the static spec options would store ids that
                        // never line up with dynamic modes, and the hide would silently do nothing.
                        val entityOptionIds = pickerOptionsForEntity(picker, entity).map { it.id }
                        pickerOptionsForEntity(picker, entity).forEach { opt ->
                            // Mirror the card's own hidden logic (isPickerOptionHidden): match by
                            // normalized key so real aliases line up, but ignore phantom leftovers —
                            // a hidden built-in "medium" must not read as hiding an entity whose real
                            // mode is "mid". Otherwise the checkbox and the card disagree.
                            val optKey = pickerOptionKey(rowId, opt.id)
                            val isHidden = isPickerOptionHidden(rowId, opt.id, pickerHidden[rowId].orEmpty(), entityOptionIds)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val cur = pickerHidden[rowId] ?: emptySet()
                                        pickerHidden[rowId] = if (isHidden) {
                                            cur.filterNot { pickerOptionKey(rowId, it) == optKey }.toSet()
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
                                    Text(
                                        opt.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { editingRowId = null }) { Text("Done") }
                }
            )
        }
    }



    // ── Main dialog (or inline fullscreen overlay for rotated/embedded hosts) ──
    val settingsDismissRequest = {
        if (showLabelEditor) {
            showLabelEditor = false
        } else if (showCustomFeatureEditor) {
            closeCustomFeatureEditor()
        } else {
            onDismiss()
        }
    }
    FactoryCardSettingsContainer(useDialog = useDialog, onDismissRequest = settingsDismissRequest) {
        // A Dialog window starts without any theme, so re-apply AppTheme there; inline
        // hosts already provide a theme (e.g. standby's forced black scheme) — keep it.
        OptionalAppTheme(useAppTheme = useDialog) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val scrollState = rememberScrollState()
                val scope = rememberCoroutineScope()
                Box(Modifier.fillMaxSize()) {
                    SettingsScaffold(
                        scrollState = scrollState,
                        // The same bar every settings page uses, rather than a local copy that
                        // drifted: bold title, matching back button, matching container.
                        topBar = {
                            SettingsTopBar(
                                title = (entity.attributes["friendly_name"] as? String)
                                    ?: entity.entity_id,
                                onBack = onDismiss
                            )
                        },
                        contentWindowInsets = WindowInsets(0.dp)
                    ) {



                        // ── Page Picker ───────────────────────────────────────────
                        item {
                            val pages = listOf("Config", "Layout")
                            val scheme = MaterialTheme.colorScheme
                            val pickerSpringSpec = remember {
                                spring<Float>(
                                    dampingRatio = 0.45f,
                                    stiffness = 200f
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                pages.forEachIndexed { index, page ->
                                    val isSelected = selectedPage == page
                                    val isLeftEdge = (index == 0)
                                    val isRightEdge = (index == pages.size - 1)

                                    val animatedRadius by animateFloatAsState(
                                        targetValue = if (isSelected) 26f else 13f,
                                        animationSpec = pickerSpringSpec,
                                        label = "PagePickerCorner"
                                    )

                                    val tl: androidx.compose.ui.unit.Dp
                                    val tr: androidx.compose.ui.unit.Dp
                                    val bl: androidx.compose.ui.unit.Dp
                                    val br: androidx.compose.ui.unit.Dp
                                    if (!isSelected) {
                                        val r = animatedRadius.dp
                                        tl = r; tr = r; bl = r; br = r
                                    } else if (isLeftEdge) {
                                        tl = 26.dp; tr = animatedRadius.dp; bl = 26.dp; br = 26.dp
                                    } else if (isRightEdge) {
                                        tl = animatedRadius.dp; tr = 26.dp; bl = 26.dp; br = 26.dp
                                    } else {
                                        val r = animatedRadius.dp
                                        tl = r; tr = r; bl = r; br = r
                                    }

                                    val bgColor = if (isSelected) scheme.primary else scheme.surfaceVariant
                                    val contentColor = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(tl, tr, br, bl))
                                            .background(bgColor)
                                            .clickable { selectedPage = page },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = page,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                color = contentColor,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (selectedPage == "Config") {
                            item {
                                val configPreviewSpanX = if (isCurrentlyLandscape) effectiveSpanXLand else effectiveSpanX
                                val configPreviewSpanY = if (isCurrentlyLandscape) effectiveSpanYLand else effectiveSpanY
                                val configPreviewDisplaySpanY = cardLayoutDisplaySpanY(configPreviewSpanY, minSpanY)
                                val configPreviewColumns = if (isCurrentlyLandscape) landscapeColumns else portraitColumns
                                val previewHeight = cardLayoutPreviewHeight(configPreviewSpanY)

                                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Preview",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.weight(1f)
                                        )
                                        SuggestionChip(
                                            onClick = {},
                                            label = {
                                                Text(
                                                    "${configPreviewSpanX}×${configPreviewDisplaySpanY}",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            icon = {
                                                Icon(
                                                    rememberSymbolPainter("grid_view"),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(configPreviewSpanX.toFloat())
                                                .height(previewHeight)
                                                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                                        ) {
                                            FactoryCard(
                                                entity = entity,
                                                spec = spec,
                                                api = api,
                                                scope = scope,
                                                names = previewNames,
                                                configs = previewConfigs,
                                                modifier = Modifier.fillMaxSize(),
                                                allEntities = allEntities,
                                                onUpdated = {}
                                            )
                                        }
                                        if (configPreviewSpanX < configPreviewColumns) {
                                            Spacer(Modifier.weight((configPreviewColumns - configPreviewSpanX).toFloat()))
                                        }
                                    }
                                }
                            }

                            if (isButton) {
                                // ── Label ─────────────────────────────────────────────
                                section {
                                    item {
                                        // The label opens its own editor page: plain text, or
                                        // composed from text + if/else blocks. Shows the
                                        // currently resolved value.
                                        val resolvedLabel = if (btnLabelBlocks.isEmpty()) btnLabel
                                            else resolveLabelBlocks(btnLabelBlocks, allEntities, context)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable { showLabelEditor = true }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "Label",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                                Text(
                                                    resolvedLabel.ifBlank { "Tap to set label" },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (resolvedLabel.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                                        else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                )
                                                if (btnLabelBlocks.isNotEmpty()) {
                                                    Text(
                                                        "Composed from ${btnLabelBlocks.size} block${if (btnLabelBlocks.size == 1) "" else "s"}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            Icon(rememberSymbolPainter("chevron_right"), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    item {
                                        OutlinedTextField(
                                            value = btnIcon,
                                            onValueChange = { btnIcon = it },
                                            label = { Text("Icon (Material Symbol)") },
                                            singleLine = true,
                                            trailingIcon = {
                                                IconButton(onClick = { showIconPicker = true }) {
                                                    Icon(rememberSymbolPainter(btnIcon.ifBlank { "bolt" }), "Pick icon")
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                    }
                                }

                                // ── Action ────────────────────────────────────────────
                                title("Action")
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf("entity" to "Entity", "service" to "Custom service").forEachIndexed { idx, (id, lbl) ->
                                            val sel = btnMode == id
                                            val shape = RoundedCornerShape(
                                                topStart = if (idx == 0) 24.dp else 8.dp,
                                                bottomStart = if (idx == 0) 24.dp else 8.dp,
                                                topEnd = if (idx == 0) 8.dp else 24.dp,
                                                bottomEnd = if (idx == 0) 8.dp else 24.dp
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f).height(46.dp)
                                                    .clip(shape)
                                                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable { btnMode = id },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(lbl, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                if (btnMode == "entity") {
                                    section {
                                        item {
                                            val ent = allEntities.find { it.entity_id == btnTarget }
                                            val eName = ent?.attributes?.get("friendly_name")?.toString() ?: btnTarget
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .clickable { showEntityPicker = true }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        if (btnTarget.isBlank()) "Choose entity" else eName,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (btnTarget.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (btnTarget.isNotBlank()) {
                                                        Text(
                                                            "${actionVerb(btnTarget)} · $btnTarget",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                Icon(rememberSymbolPainter("chevron_right"), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                } else {
                                    section {
                                        item {
                                            OutlinedTextField(
                                                value = btnService, onValueChange = { btnService = it },
                                                label = { Text("Service") },
                                                placeholder = { Text("light.turn_on") },
                                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(20.dp)
                                            )
                                        }
                                        item {
                                            OutlinedTextField(
                                                value = btnTarget, onValueChange = { btnTarget = it },
                                                label = { Text("Target entity (optional)") },
                                                placeholder = { Text("light.kitchen") },
                                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(20.dp)
                                            )
                                        }
                                        item {
                                            OutlinedTextField(
                                                value = btnServiceData, onValueChange = { btnServiceData = it },
                                                label = { Text("Service data (optional JSON)") },
                                                placeholder = { Text("{\"brightness_pct\": 50}") },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(20.dp)
                                            )
                                        }
                                    }
                                }

                                // ── Appearance ────────────────────────────────────────
                                title("Appearance")
                                section {
                                    item(padding = 16.dp) {
                                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            // Orientation
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("Vertical layout", style = MaterialTheme.typography.bodyLarge)
                                                    Text("Icon above label", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Switch(checked = btnVertical, onCheckedChange = { btnVertical = it })
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            // Background color
                                            Column {
                                                Text("Background", style = MaterialTheme.typography.bodyLarge)
                                                Spacer(Modifier.height(10.dp))
                                                androidx.compose.foundation.layout.FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    BUTTON_CARD_PRESET_COLORS.forEach { preset ->
                                                        val swatch = preset?.let { androidx.compose.ui.graphics.Color(it) } ?: cardBackgroundColor()
                                                        val sel = preset == btnBgColor
                                                        Box(
                                                            modifier = Modifier
                                                                .size(40.dp).clip(CircleShape)
                                                                .background(swatch)
                                                                .clickable { btnBgColor = preset },
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            when {
                                                                sel -> Icon(rememberSymbolPainter("check"), "Selected",
                                                                    tint = if (preset != null) contentColorFor(swatch) else MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.size(18.dp))
                                                                preset == null -> Icon(rememberSymbolPainter("format_color_reset"), "Default",
                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (isCamera) {
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

                                title("Snapshot refresh")
                                section {
                                    item(padding = 16.dp) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                "How often the dashboard thumbnail updates",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                CAMERA_INTERVAL_OPTIONS.forEach { opt ->
                                                    FilterChip(
                                                        selected = cameraRefreshIntervalSec == opt.seconds,
                                                        onClick = { cameraRefreshIntervalSec = opt.seconds },
                                                        label = { Text(opt.label) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // ── Name ─────────────────────────────────────────────────
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

                                // ── Alarm code (optional) ────────────────────────────────
                                if (isAlarmEntity) {
                                    section {
                                        item {
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
                                                    shape = RoundedCornerShape(20.dp)
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    text = "If set, this PIN is sent automatically when arming/disarming. Leave blank to be prompted for it each time. Stored only on this device.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── State Text Template ──────────────────────────────────
                                title("State text")
                                section {
                                    item(padding = 16.dp) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            SettingsTextField(
                                                value = stateTemplate,
                                                onValueChange = { stateTemplate = it },
                                                label = "State text template"
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                text = "Placeholders like {state}, {name} or {attribute_name}. " +
                                                    "Reference another entity with {sensor.temperature.state}.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    item(padding = 0.dp) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = "Quick placeholders",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                                            )
                                            LazyRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                // Inset the row itself rather than the list, so a chip
                                                // can scroll to the edge instead of being clipped by it.
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 12.dp)
                                            ) {
                                                item {
                                                    SuggestionChip(
                                                        onClick = { stateTemplate += "{state}" },
                                                        label = { Text("{state}") }
                                                    )
                                                }
                                                item {
                                                    SuggestionChip(
                                                        onClick = { stateTemplate += "{name}" },
                                                        label = { Text("{name}") }
                                                    )
                                                }
                                                item {
                                                    SuggestionChip(
                                                        onClick = { stateTemplate += "{attributes}" },
                                                        label = { Text("{attributes}") }
                                                    )
                                                }
                                                // Add attributes of this device
                                                entity.attributes.keys.forEach { attrKey ->
                                                    item {
                                                        SuggestionChip(
                                                            onClick = { stateTemplate += "{$attrKey}" },
                                                            label = { Text("{$attrKey}") }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                text = "Insert from other entity:",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )

                                            var otherEntityQuery by remember { mutableStateOf("") }
                                            var selectedOtherEntity by remember { mutableStateOf<HAEntity?>(null) }

                                            OutlinedTextField(
                                                value = otherEntityQuery,
                                                onValueChange = {
                                                    otherEntityQuery = it
                                                    if (it.isBlank()) selectedOtherEntity = null
                                                },
                                                label = { Text("Search other entity") },
                                                placeholder = { Text("e.g. sensor.temperature") },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(20.dp),
                                                trailingIcon = {
                                                    if (otherEntityQuery.isNotEmpty()) {
                                                        IconButton(onClick = {
                                                            otherEntityQuery = ""
                                                            selectedOtherEntity = null
                                                        }) {
                                                            Icon(rememberSymbolPainter("close"), "Clear")
                                                        }
                                                    }
                                                }
                                            )

                                            if (otherEntityQuery.isNotEmpty() && selectedOtherEntity == null) {
                                                val matches = allEntities.filter {
                                                    it.entity_id.contains(otherEntityQuery, ignoreCase = true) ||
                                                            (it.attributes["friendly_name"]?.toString() ?: "").contains(otherEntityQuery, ignoreCase = true)
                                                }.take(5)

                                                if (matches.isNotEmpty()) {
                                                    Card(
                                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                        shape = RoundedCornerShape(12.dp),
                                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                                                    ) {
                                                        Column {
                                                            matches.forEach { ent ->
                                                                ListItem(
                                                                    headlineContent = { Text(ent.attributes["friendly_name"]?.toString() ?: ent.entity_id) },
                                                                    supportingContent = { Text(ent.entity_id) },
                                                                    modifier = Modifier.clickable {
                                                                        selectedOtherEntity = ent
                                                                        otherEntityQuery = ent.entity_id
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            selectedOtherEntity?.let { ent ->
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    text = "Placeholders for ${ent.attributes["friendly_name"] ?: ent.entity_id}:",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                LazyRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    item {
                                                        SuggestionChip(
                                                            onClick = { stateTemplate += "{${ent.entity_id}}" },
                                                            label = { Text("{${ent.entity_id}}") }
                                                        )
                                                    }
                                                    item {
                                                        SuggestionChip(
                                                            onClick = { stateTemplate += "{${ent.entity_id}.name}" },
                                                            label = { Text("{${ent.entity_id}.name}") }
                                                        )
                                                    }
                                                    item {
                                                        SuggestionChip(
                                                            onClick = { stateTemplate += "{${ent.entity_id}.attributes}" },
                                                            label = { Text("{${ent.entity_id}.attributes}") }
                                                        )
                                                    }
                                                    ent.attributes.keys.forEach { attrKey ->
                                                        item {
                                                            SuggestionChip(
                                                                onClick = { stateTemplate += "{${ent.entity_id}.$attrKey}" },
                                                                label = { Text("{${ent.entity_id}.$attrKey}") }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // ── Spec toggles ──────────────────────────────────────────
                                if (spec.toggles.isNotEmpty()) {
                                    section {
                                        spec.toggles.forEach { toggle ->
                                            item(padding = 16.dp) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    if (toggle.icon != null) {
                                                        Icon(
                                                            rememberSymbolPainter(toggle.icon),
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(22.dp)
                                                        )
                                                        Spacer(Modifier.width(12.dp))
                                                    }
                                                    Text(
                                                        text = toggle.label,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Switch(
                                                        checked = toggleValues[toggle.id] ?: toggle.defaultValue,
                                                        onCheckedChange = { toggleValues[toggle.id] = it }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // ── Features header (collapsible) ─────────────────────────
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { featuresExpanded = !featuresExpanded }
                                            .padding(horizontal = 4.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            "Features",
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            painter = rememberSymbolPainter(if (featuresExpanded) "keyboard_arrow_up" else "keyboard_arrow_down"),
                                            contentDescription = null
                                        )
                                    }
                                }

                                if (featuresExpanded) {
                                    // ── Reorderable feature rows ──────────────────────────
                                    item {
                                        Box {
                                            Spacer(Modifier.height(5.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                            ) {
                                                ReorderableColumn(
                                                    list = visibleRows,
                                                    onSettle = { from, to ->
                                                        if (from != to) {
                                                            visibleRows = visibleRows.toMutableList().also { l ->
                                                                l.add(to, l.removeAt(from))
                                                            }
                                                        }
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onMove = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    },
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) { _, rowId, isDragging, visualIndex ->
                                                    ReorderableItem {
                                                        val isPicker = spec.rows.filterIsInstance<PickerRowSpec>().any { it.id == rowId }
                                                        val isCustom = rowId.startsWith("custom_")

                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .zIndex(if (isDragging) 100f else 0f)
                                                                .graphicsLayer {
                                                                    if (isDragging) {
                                                                        scaleX = 1.03f
                                                                        scaleY = 1.03f
                                                                        shadowElevation = 16.dp.toPx()
                                                                        shape = RoundedCornerShape(20.dp)
                                                                        clip = false
                                                                    }
                                                                }
                                                                .clip(featureRowShape(visibleRows.size, visualIndex))
                                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(40.dp)
                                                                    .draggableHandle(
                                                                        onDragStarted = {
                                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                        }
                                                                    ),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(
                                                                    painter = rememberSymbolPainter("drag_handle"),
                                                                    contentDescription = "Drag",
                                                                    tint = MaterialTheme.colorScheme.outline
                                                                )
                                                            }
                                                            // The same 48dp rounded icon chip the settings hub uses, so a
                                                            // feature reads as the same kind of thing as a settings entry
                                                            // rather than a bare line of text.
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(48.dp)
                                                                    .clip(RoundedCornerShape(24.dp))
                                                                    .background(
                                                                        if (isCustom) MaterialTheme.colorScheme.tertiaryContainer
                                                                        else MaterialTheme.colorScheme.secondaryContainer
                                                                    ),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(
                                                                    painter = rememberSymbolPainter(
                                                                        if (isCustom) "bolt" else rowSymbol(spec, rowId)
                                                                    ),
                                                                    contentDescription = null,
                                                                    tint = if (isCustom) MaterialTheme.colorScheme.onTertiaryContainer
                                                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                                                                    modifier = Modifier.size(24.dp)
                                                                )
                                                            }
                                                            Spacer(Modifier.width(14.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    if (isCustom) getCustomFeatureLabel(rowId) else rowDisplayName(spec, rowId),
                                                                    fontSize = 16.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = MaterialTheme.colorScheme.onSurface
                                                                )
                                                                Spacer(Modifier.height(2.dp))
                                                                Text(
                                                                    text = featureKindLabel(spec, rowId, isCustom),
                                                                    fontSize = 14.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                            IconButton(onClick = {
                                                                openCustomFeatureEditor(rowId)
                                                            }) {
                                                                Icon(
                                                                    painter = rememberSymbolPainter("tune"),
                                                                    contentDescription = "Edit options",
                                                                    tint = MaterialTheme.colorScheme.primary
                                                                )
                                                            }
                                                            IconButton(onClick = {
                                                                visibleRows = visibleRows - rowId
                                                                if (isCustom) {
                                                                    customFeatures = customFeatures.filter { it["id"] != rowId }
                                                                } else {
                                                                    removedRows = listOf(rowId) + removedRows
                                                                }
                                                            }) {
                                                                Icon(
                                                                    painter = rememberSymbolPainter("delete"),
                                                                    contentDescription = "Remove",
                                                                    tint = MaterialTheme.colorScheme.error
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // ── Add feature ───────────────────────────────────────
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { showAddFeature = true },
                                                enabled = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(14.dp)
                                            ) {
                                                Icon(rememberSymbolPainter("add"), contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Add feature")
                                            }
                                            DropdownMenu(
                                                expanded = showAddFeature,
                                                onDismissRequest = { showAddFeature = false }
                                            ) {
                                                removedRows.toList().forEach { rowId ->
                                                    DropdownMenuItem(
                                                        text = { Text(rowDisplayName(spec, rowId)) },
                                                        leadingIcon = {
                                                            Icon(
                                                                rememberSymbolPainter(rowSymbol(spec, rowId)),
                                                                contentDescription = null
                                                            )
                                                        },
                                                        onClick = {
                                                            visibleRows = visibleRows + rowId
                                                            removedRows = removedRows - rowId
                                                            showAddFeature = false
                                                        }
                                                    )
                                                }
                                                if (removedRows.isNotEmpty()) {
                                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("Add Custom Feature") },
                                                    leadingIcon = {
                                                        Icon(
                                                            rememberSymbolPainter("add_circle"),
                                                            contentDescription = null
                                                        )
                                                    },
                                                    onClick = {
                                                        showAddFeature = false
                                                        openCustomFeatureEditor(null)
                                                    }
                                                )
                                            }
                                        }
                                    }

                                }
                            }
                        }
                        if (selectedPage == "Layout") {
                                title("Layout")

                                    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                    val activeSpanX = if (isLandscape) effectiveSpanXLand else effectiveSpanX
                                    val activeSpanY = if (isLandscape) effectiveSpanYLand else effectiveSpanY
                                    val activeDisplaySpanY = cardLayoutDisplaySpanY(activeSpanY, minSpanY)
                                    val activeColumns = if (isLandscape) landscapeColumns else portraitColumns
                                    item {
                                        val previewHeight = cardLayoutPreviewHeight(activeSpanY)

                                        Column(modifier = Modifier.padding(bottom = 4.dp)) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "Preview",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = {
                                                        Text(
                                                            "${activeSpanX}×${activeDisplaySpanY}",
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    },
                                                    icon = {
                                                        Icon(
                                                            rememberSymbolPainter("grid_view"),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(activeSpanX.toFloat())
                                                        .height(previewHeight)
                                                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                                                ) {
                                                    FactoryCard(
                                                        entity = entity,
                                                        spec = spec,
                                                        api = api,
                                                        scope = scope,
                                                        names = previewNames,
                                                        configs = previewConfigs,
                                                        modifier = Modifier.fillMaxSize(),
                                                        allEntities = allEntities,
                                                        onUpdated = {}
                                                    )
                                                }
                                                if (activeSpanX < activeColumns) {
                                                    Spacer(Modifier.weight((activeColumns - activeSpanX).toFloat()))
                                                }
                                            }
                                        }
                                    }

                                    section {
                                        item(padding = 16.dp) {
                                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                if (!isCamera) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("Default height", style = MaterialTheme.typography.bodyLarge)
                                                            Text("Auto-fit height to row count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                        Switch(
                                                            checked = if (isLandscape) isDefaultHeightLand else isDefaultHeight,
                                                            onCheckedChange = { if (isLandscape) saveCardLayoutLand(nextDefaultHeight = it) else saveCardLayout(nextDefaultHeight = it) }
                                                        )
                                                    }
                                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                }
                                                val density = LocalDensity.current
                                                if (isLandscape) {
                                                    CardLayoutEditor(
                                                        spanX = effectiveSpanXLand,
                                                        spanY = effectiveSpanYLand,
                                                        columns = landscapeColumns,
                                                        minSpanY = minSpanY,
                                                        minSpanX = minSpanX,
                                                        isDefaultHeight = if (isCamera) false else isDefaultHeightLand,
                                                        onSpanXChange = { saveCardLayoutLand(nextSpanX = it) },
                                                        onSpanYChange = {
                                                            val diff = it - effectiveSpanYLand
                                                            if (diff != 0) {
                                                                saveCardLayoutLand(nextSpanY = it, nextDefaultHeight = false)
                                                                scope.launch {
                                                                    scrollState.scrollTo(scrollState.value + with(density) { ((diff * 29).dp).toPx().roundToInt() })
                                                                }
                                                            }
                                                        }
                                                    )
                                                } else {
                                                    CardLayoutEditor(
                                                        spanX = effectiveSpanX,
                                                        spanY = effectiveSpanY,
                                                        columns = portraitColumns,
                                                        minSpanY = minSpanY,
                                                        minSpanX = minSpanX,
                                                        isDefaultHeight = if (isCamera) false else isDefaultHeight,
                                                        onSpanXChange = { saveCardLayout(nextSpanX = it) },
                                                        onSpanYChange = {
                                                            val diff = it - effectiveSpanY
                                                            if (diff != 0) {
                                                                saveCardLayout(nextSpanY = it, nextDefaultHeight = false)
                                                                scope.launch {
                                                                    scrollState.scrollTo(scrollState.value + with(density) { ((diff * 29).dp).toPx().roundToInt() })
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                            val canPinWidget = remember(entity.entity_id, isCamera) {
                                !isCamera && com.feldman.ha.widgets.CardWidgetPinner.canPin(context, entity.entity_id)
                            }
                            if (canPinWidget) {
                                item {
                                    Text(
                                        "Home screen",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                    )
                                }
                                section {
                                    item(padding = 16.dp) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                "Add this card to your home screen as a widget. Its name, features, order and options are carried over.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Button(
                                                onClick = {
                                                    saveCurrentSettings()
                                                    val ok = com.feldman.ha.widgets.CardWidgetPinner.requestPin(context, entity.entity_id)
                                                    Toast.makeText(
                                                        context,
                                                        if (ok) "Choose where to place the widget" else "Widgets aren't supported here",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(14.dp)
                                            ) {
                                                Icon(rememberSymbolPainter("widgets"), null, modifier = Modifier.size(20.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Add to home screen")
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Text(
                                    "Saved setups",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }
                            section {
                                item(padding = 16.dp) {
                                    var presetSaved by remember(entity.entity_id) { mutableStateOf(false) }
                                    var presetName by remember(entity.entity_id) { mutableStateOf(name) }
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Save this card's setup to reuse it later — on another page, or from the \"Saved\" tab of the add-card picker in the Clock screensaver.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedTextField(
                                            value = presetName,
                                            onValueChange = {
                                                presetName = it
                                                presetSaved = false
                                            },
                                            label = { Text("Preset name") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        Button(
                                            onClick = {
                                                CardPresets.save(
                                                    context,
                                                    CardPresets.CardPreset(
                                                        id = java.util.UUID.randomUUID().toString(),
                                                        name = presetName.trim().ifBlank { name.trim().ifBlank { "Card" } },
                                                        sourceId = entity.entity_id,
                                                        config = previewConfig
                                                    )
                                                )
                                                presetSaved = true
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Icon(rememberSymbolPainter(if (presetSaved) "bookmark_added" else "bookmark_add"), null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (presetSaved) "Saved" else "Save card setup")
                                        }
                                    }
                                }
                            }

                            item { Spacer(Modifier.height(128.dp)) }
                        }

                        // Fullscreen editor overlays and the floating save dock are Box children,
                        // NOT scaffold content: the scaffold body is a verticalScroll Column, and a
                        // nested scrollable page measured with its infinite height crashes Compose.
                        if (showLabelEditor) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                ButtonLabelEditorPage(
                                    staticLabel = btnLabel,
                                    blocks = btnLabelBlocks,
                                    onBlocksChange = { btnLabelBlocks = it },
                                    allEntities = allEntities,
                                    defaultEntityId = btnTarget,
                                    onBack = { showLabelEditor = false }
                                )
                            }
                        }

                        if (showCustomFeatureEditor) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                key(customFeatureEditorId) {
                                    CustomFeatureDetailPage(
                                        entityId = entity.entity_id,
                                        featureId = customFeatureEditorId,
                                        onBack = { closeCustomFeatureEditor() },
                                        appState = appState,
                                        showSaveDock = false,
                                        onSaveDockStateChange = { text, enabled, onSave ->
                                            customFeatureSaveText = text
                                            customFeatureSaveEnabled = enabled
                                            customFeatureSaveAction = onSave
                                        }
                                    )
                                }
                            }
                        }

                        ConfigureCardSaveDock(
                            pageScrollState = scrollState,
                            text = when {
                                showLabelEditor -> "Done"
                                showCustomFeatureEditor -> customFeatureSaveText
                                else -> "Save changes"
                            },
                            onSave = {
                                when {
                                    showLabelEditor -> showLabelEditor = false
                                    showCustomFeatureEditor -> customFeatureSaveAction()
                                    else -> {
                                        saveCurrentSettings()
                                        onDismiss()
                                    }
                                }
                            },
                            enabled = showLabelEditor || !showCustomFeatureEditor || customFeatureSaveEnabled,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                }
            }
        }

        if (showIconPicker) {
            IconPickerDialog(
                initial = btnIcon.ifBlank { "bolt" },
                onDismiss = { showIconPicker = false },
                onPick = {
                    btnIcon = it
                    showIconPicker = false
                }
            )
        }

        if (showEntityPicker) {
            EntityPickerDialog(
                allEntities = allEntities,
                onDismiss = { showEntityPicker = false },
                onPick = { entId ->
                    btnTarget = entId
                    showEntityPicker = false
                    val ent = allEntities.find { it.entity_id == entId }
                    if (ent != null) {
                        if (btnLabel.isBlank() || btnLabel == "Action") {
                            btnLabel = ent.attributes["friendly_name"]?.toString() ?: ent.entity_id
                        }
                        if (btnIcon.isBlank() || btnIcon == "bolt") {
                            btnIcon = "bolt"
                        }
                    }
                }
            )
        }
    }
}

private fun featureRowShape(count: Int, visualIndex: Int): RoundedCornerShape =
    when {
        count <= 1 -> RoundedCornerShape(20.dp)
        visualIndex == 0 -> RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )
        visualIndex == count - 1 -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 20.dp,
            bottomEnd = 20.dp
        )
        else -> RoundedCornerShape(4.dp)
    }

@Composable
fun ConfigureCardSaveDock(
    text: String = "Save changes",
    onSave: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    // The dock floats OVER the settings scroll container, so drags starting on it (easy to
    // do in landscape, where it covers a big slice of the page) would otherwise go nowhere.
    // Passing the page's scroll state forwards those drags into the page.
    pageScrollState: androidx.compose.foundation.ScrollState? = null
) {
    val scheme = MaterialTheme.colorScheme
    val dockGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to scheme.surface.copy(alpha = 0f),
            0.42f to scheme.surface.copy(alpha = 0.58f),
            1f to scheme.surface.copy(alpha = 0.96f)
        )
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (pageScrollState != null) {
                    // Matches verticalScroll's direction mapping (finger up = content down).
                    Modifier.scrollable(pageScrollState, androidx.compose.foundation.gestures.Orientation.Vertical, reverseDirection = true)
                } else {
                    Modifier
                }
            )
    ) {
        val buttonWidth = maxWidth - 32.dp

        Spacer(
            modifier = Modifier
                .matchParentSize()
                .background(dockGradient)
                
        )
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .background(dockGradient)
        )

        MotionButton(
            text = text,
            icon = "check",
            onClick = {
                if (enabled) onSave()
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 12.dp)
                .navigationBarsPadding(),
            width = buttonWidth,
            height = 64.dp,
            fontSize = 20.sp,
            iconSize = 22.dp,
            defaultState = MotionButtonState(
                backgroundColor = if (enabled) scheme.primary else scheme.surfaceVariant,
                contentColor = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                outlineWidth = 0.dp,
                outlineColor = scheme.outline
            )
        )
    }
}

@Composable
internal fun CardLayoutEditor(
    spanX: Int,
    spanY: Int,
    columns: Int,
    minSpanY: Int,
    isDefaultHeight: Boolean,
    onSpanXChange: (Int) -> Unit,
    onSpanYChange: (Int) -> Unit,
    minSpanX: Int = CARD_LAYOUT_MIN_WIDTH,
) {
    val scheme = MaterialTheme.colorScheme
    val railWidth = 88.dp
    val controlGap = 8.dp
    val previewHeight = 252.dp
    val heightMin = minSpanY.coerceIn(1, CARD_LAYOUT_MAX_HEIGHT)
    // slider never reads 0: min-1 cards (buttons) map N → 2N-1 grid rows, others map N → 2N+1.
    val uiSpanOffset = if (heightMin <= 1) -1 else 1
    val uiSpanY = cardLayoutDisplaySpanY(spanY, minSpanY)
    val uiMaxSpanY = (CARD_LAYOUT_MAX_HEIGHT - uiSpanOffset) / 2
    val uiSliderMinSpanY = 0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = rememberSymbolPainter("dashboard_customize"),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Card size",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${spanX}x$uiSpanY (of $columns cols)",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Spacer(Modifier.width(railWidth + controlGap))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LayoutValueBubble(spanX)
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = spanX.toFloat(),
                    onValueChange = { value ->
                        onSpanXChange(value.roundToInt().coerceIn(minSpanX, columns))
                    },
                    valueRange = 0f..columns.toFloat(),
                    steps = (columns - 1).coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
            ) {
                LayoutValueBubble(
                    value = uiSpanY,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(48.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Slider(
                        value = uiSpanY.toFloat(),
                        onValueChange = { value ->
                            val newSpanY = (value.roundToInt() * 2) + uiSpanOffset
                            onSpanYChange(newSpanY.coerceIn(heightMin, CARD_LAYOUT_MAX_HEIGHT))
                        },
                        enabled = !isDefaultHeight,
                        valueRange = uiSliderMinSpanY.toFloat()..uiMaxSpanY.toFloat(),
                        steps = (uiMaxSpanY - uiSliderMinSpanY - 1).coerceAtLeast(0),
                        modifier = Modifier
                            .requiredWidth(previewHeight)
                            .height(48.dp)
                            .graphicsLayer { rotationZ = 90f }
                    )
                }
            }

            Spacer(Modifier.width(controlGap))

            CardLayoutGridPreview(
                spanX = spanX,
                spanY = uiSpanY,
                maxSpanY = uiMaxSpanY,
                columns = columns,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun LayoutValueBubble(
    value: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
        modifier = modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CardLayoutGridPreview(
    spanX: Int,
    spanY: Int,
    maxSpanY: Int = (CARD_LAYOUT_MAX_HEIGHT - 1) / 2,
    columns: Int,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val background = scheme.surfaceContainerLow
    val selected = scheme.surfaceContainerHighest
    val grid = scheme.outline.copy(alpha = 0.58f)
    val border = scheme.outline.copy(alpha = 0.28f)

    Canvas(
        modifier = modifier.clip(RoundedCornerShape(18.dp))
    ) {
        val radius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
        val gridWidth = size.width
        val gridHeight = size.height
        val cellWidth = gridWidth / columns
        val cellHeight = gridHeight / maxSpanY
        val left = 0f
        val top = 0f
        val dot = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()), 0f)

        drawRoundRect(
            color = background,
            size = Size(gridWidth, gridHeight),
            cornerRadius = radius
        )

        drawRoundRect(
            color = selected,
            topLeft = Offset(left, top),
            size = Size(cellWidth * spanX, cellHeight * spanY),
            cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        )

        for (column in 0..columns) {
            val x = left + column * cellWidth
            drawLine(
                color = grid,
                start = Offset(x, top),
                end = Offset(x, top + gridHeight),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dot
            )
        }

        for (row in 0..maxSpanY) {
            val y = top + row * cellHeight
            drawLine(
                color = grid,
                start = Offset(left, y),
                end = Offset(left + gridWidth, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dot
            )
        }

        drawRoundRect(
            color = border,
            topLeft = Offset(left, top),
            size = Size(gridWidth, gridHeight),
            cornerRadius = radius,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

private fun cardLayoutSpanX(
    config: Map<String, Any>?,
    columns: Int = 4,
    minSpanX: Int = CARD_LAYOUT_MIN_WIDTH
): Int =
    ((config?.get(CARD_SPAN_X_KEY) as? Number)?.toInt() ?: CARD_LAYOUT_DEFAULT_WIDTH)
        .coerceIn(minSpanX, columns)

private fun cardLayoutSpanY(
    config: Map<String, Any>?,
    minSpanY: Int = CARD_LAYOUT_MIN_HEIGHT
): Int =
    ((config?.get(CARD_SPAN_Y_KEY) as? Number)?.toInt() ?: CARD_LAYOUT_DEFAULT_HEIGHT)
        .coerceIn(minSpanY.coerceIn(1, CARD_LAYOUT_MAX_HEIGHT), CARD_LAYOUT_MAX_HEIGHT)

internal fun cardLayoutPreviewHeight(spanY: Int): androidx.compose.ui.unit.Dp {
    val s = spanY.coerceIn(1, CARD_LAYOUT_MAX_HEIGHT)
    return 16.dp * s.toFloat() + 13.dp * (s - 1).toFloat()
}

private fun cardLayoutDisplaySpanY(spanY: Int, minSpanY: Int): Int {
    val heightMin = minSpanY.coerceIn(1, CARD_LAYOUT_MAX_HEIGHT)
    val uiSpanOffset = if (heightMin <= 1) -1 else 1
    return ((spanY.coerceIn(heightMin, CARD_LAYOUT_MAX_HEIGHT) - uiSpanOffset) / 2)
        .coerceAtLeast(0)
}

private fun cardLayoutMinSpanY(
    spec: WidgetSpec,
    entity: HAEntity,
    visibleRows: List<String>,
    config: Map<String, Any>,
    allEntities: List<HAEntity>,
    context: android.content.Context
): Int {
    // Camera cards have no feature rows and may shrink to a single grid row.
    if (spec.style == CardStyle.CAMERA) return 1
    // Clock cards scale their text, so a single grid row is enough.
    if (spec.style == CardStyle.CLOCK) return 1
    // Buttons also have no rows, but need a tappable minimum height.
    if (spec.style == CardStyle.BUTTON) return CARD_BUTTON_MIN_HEIGHT
    val rowsById = spec.rows.associateBy { it.id }
    val customFeatures = (config["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
    val customFeaturesById = customFeatures.associateBy { it["id"] as? String ?: "" }

    val visibleCardRows = visibleRows.count { rowId ->
        if (rowId.startsWith("custom_")) {
            val customFeature = customFeaturesById[rowId] ?: return@count false
            val visibilityConds = (customFeature["visibility"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
            if (!visibilityConds.isNullOrEmpty()) {
                val matches = visibilityConds.all { evaluateCondition(it, allEntities, context) }
                if (!matches) return@count false
            }
            return@count true
        }
        val row = rowsById[rowId] ?: return@count false
        val hiddenIfToggle = row.hiddenIfToggle
        if (hiddenIfToggle == null) return@count true
        val toggleVal = config[hiddenIfToggle] as? Boolean
            ?: spec.toggles.find { it.id == hiddenIfToggle }?.defaultValue
            ?: false
        !toggleVal
    }
    return (3 + visibleCardRows * 2).coerceIn(CARD_LAYOUT_MIN_HEIGHT, CARD_LAYOUT_MAX_HEIGHT)
}

/** Secondary line on a feature row, so each one says what kind of control it is. */
private fun featureKindLabel(spec: WidgetSpec, rowId: String, isCustom: Boolean): String {
    if (isCustom) return "Custom feature"
    return when (spec.rows.find { it.id == rowId }) {
        is PickerRowSpec -> "Options"
        is SliderRowSpec -> "Slider"
        is ButtonsRowSpec -> "Buttons"
        is CounterRowSpec -> "Counter"
        is DataRowSpec -> "Reading"
        else -> "Feature"
    }
}

private fun rowDisplayName(spec: WidgetSpec, rowId: String): String {
    val row = spec.rows.find { it.id == rowId }
    return when (row) {
        is DataRowSpec   -> row.label ?: rowId.toLabel()
        is SliderRowSpec -> row.label ?: rowId.toLabel()
        else             -> rowId.toLabel()
    }
}

private fun rowSymbol(spec: WidgetSpec, rowId: String): String =
    when (spec.rows.find { it.id == rowId }) {
        is PickerRowSpec  -> "toggle_on"
        is SliderRowSpec  -> "sliders"
        is CounterRowSpec -> "exposure"
        is ButtonsRowSpec -> "smart_button"
        is DataRowSpec    -> "data_usage"
        is SpacerRowSpec  -> "space_bar"
        else              -> "add_box"
    }

private fun String.toLabel() = replace("_", " ").replaceFirstChar { it.uppercase() }

val POPULAR_SERVICES = listOf(
    "homeassistant.turn_on",
    "homeassistant.turn_off",
    "homeassistant.toggle",
    "light.turn_on",
    "light.turn_off",
    "light.toggle",
    "switch.turn_on",
    "switch.turn_off",
    "switch.toggle",
    "fan.turn_on",
    "fan.turn_off",
    "fan.toggle",
    "fan.set_percentage",
    "climate.set_temperature",
    "climate.set_hvac_mode",
    "climate.set_preset_mode",
    "climate.turn_on",
    "climate.turn_off",
    "media_player.turn_on",
    "media_player.turn_off",
    "media_player.toggle",
    "media_player.media_play_pause",
    "media_player.media_play",
    "media_player.media_pause",
    "media_player.volume_up",
    "media_player.volume_down",
    "media_player.volume_mute",
    "cover.open_cover",
    "cover.close_cover",
    "cover.stop_cover",
    "cover.toggle",
    "lock.lock",
    "lock.unlock",
    "input_boolean.turn_on",
    "input_boolean.turn_off",
    "input_boolean.toggle",
    "script.turn_on",
    "automation.trigger",
    "vacuum.start",
    "vacuum.pause",
    "vacuum.return_to_base",
    "scene.turn_on"
)

data class ServiceParamSpec(
    val key: String,
    val label: String,
    val type: String, // "text", "number", "slider", "dropdown", "boolean"
    val min: Float? = null,
    val max: Float? = null,
    val options: List<String>? = null,
    val defaultValue: String? = null
)

val SERVICE_SCHEMAS = mapOf(
    "light.turn_on" to listOf(
        ServiceParamSpec("brightness_pct", "Brightness (%)", "slider", 0f, 100f),
        ServiceParamSpec("color_temp", "Color Temperature", "slider", 153f, 500f),
        ServiceParamSpec("transition", "Transition Duration (s)", "number"),
        ServiceParamSpec("effect", "Effect Name", "text")
    ),
    "light.turn_off" to listOf(
        ServiceParamSpec("transition", "Transition Duration (s)", "number")
    ),
    "climate.set_temperature" to listOf(
        ServiceParamSpec("temperature", "Target Temperature", "number"),
        ServiceParamSpec("target_temp_low", "Low Target Temperature", "number"),
        ServiceParamSpec("target_temp_high", "High Target Temperature", "number"),
        ServiceParamSpec("hvac_mode", "HVAC Mode", "dropdown", options = listOf("off", "heat", "cool", "auto", "dry", "fan_only"))
    ),
    "climate.set_hvac_mode" to listOf(
        ServiceParamSpec("hvac_mode", "HVAC Mode", "dropdown", options = listOf("off", "heat", "cool", "auto", "dry", "fan_only"))
    ),
    "climate.set_fan_mode" to listOf(
        ServiceParamSpec("fan_mode", "Fan Mode", "dropdown", options = listOf("auto", "low", "medium", "high"))
    ),
    "fan.set_percentage" to listOf(
        ServiceParamSpec("percentage", "Fan Speed (%)", "slider", 0f, 100f)
    ),
    "fan.turn_on" to listOf(
        ServiceParamSpec("percentage", "Fan Speed (%)", "slider", 0f, 100f)
    ),
    "media_player.volume_set" to listOf(
        ServiceParamSpec("volume_level", "Volume Level (0-1)", "slider", 0f, 1f)
    ),
    "media_player.volume_mute" to listOf(
        ServiceParamSpec("is_volume_muted", "Muted", "boolean")
    ),
    "cover.set_cover_position" to listOf(
        ServiceParamSpec("position", "Cover Position (%)", "slider", 0f, 100f)
    ),
    "input_select.select_option" to listOf(
        ServiceParamSpec("option", "Option", "text")
    )
)

fun parseValueType(str: String): Any {
    if (str.equals("true", ignoreCase = true)) return true
    if (str.equals("false", ignoreCase = true)) return false
    val intVal = str.toIntOrNull()
    if (intVal != null) return intVal
    val floatVal = str.toFloatOrNull()
    if (floatVal != null) return floatVal
    return str
}

/**
 * Presentation shell for FactoryCardSettings: a fullscreen Dialog window normally, or an
 * inline fullscreen overlay when the host applies a visual transform a Dialog would escape.
 */
@Composable
private fun FactoryCardSettingsContainer(
    useDialog: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    if (useDialog) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false
            )
        ) { content() }
    } else {
        androidx.activity.compose.BackHandler(onBack = onDismissRequest)
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun OptionalAppTheme(useAppTheme: Boolean, content: @Composable () -> Unit) {
    if (useAppTheme) AppTheme { content() } else content()
}
