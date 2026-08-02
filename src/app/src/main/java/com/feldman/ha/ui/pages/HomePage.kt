package com.feldman.ha.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.feldman.ha.data.HAEntity
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.R
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Brush
import com.feldman.ha.ui.components.HomeAssistantTopBar
import com.feldman.ha.widgets.BUTTON_ACTIONABLE_DOMAINS
import com.feldman.ha.widgets.CardStyle
import com.feldman.ha.widgets.ConfigureQuery
import com.feldman.ha.widgets.RowVisibility
import com.feldman.ha.widgets.ServiceMap
import com.feldman.ha.widgets.WidgetSpec
import com.feldman.ha.widgets.WidgetRegistry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import com.feldman.ha.ui.navigation.AppDest
import com.feldman.ha.ui.navigation.DashboardEditAction
import com.feldman.ha.ui.navigation.DashboardEditActionRequest
import com.feldman.ha.ui.navigation.LocalAppState
import com.feldman.ha.ui.camera.CameraCardConfig
import com.feldman.ha.ui.camera.CameraSource
import com.feldman.ha.ui.camera.FACTORY_CAMERA_ID_KEY
import com.feldman.ha.ui.camera.FACTORY_CAMERA_IDS_KEY
import com.feldman.ha.ui.camera.FACTORY_CAMERA_NAME_KEY
import com.feldman.ha.ui.camera.FACTORY_CAMERA_SOURCE_KEY
import com.feldman.ha.ui.camera.factoryConfigForCameraCard
import com.feldman.ha.ui.camera.loadCameraCards
import com.feldman.ha.widgets.CameraSnapshotWorker
import com.feldman.motion.navigation.Navigator
import androidx.compose.ui.unit.sp
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import com.feldman.motion.rememberSymbolPainter
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import com.feldman.ha.ui.cards.CARD_CORNER_RADIUS
import com.feldman.ha.ui.cards.CLOCK_SHOW_DATE_KEY
import com.feldman.ha.ui.cards.CLOCK_SHOW_SECONDS_KEY
import com.feldman.ha.ui.cards.GenericCard
import com.feldman.ha.ui.cards.CardPresets
import com.feldman.ha.ui.cards.ExpressiveCanvasSetting
import com.feldman.ha.ui.cards.FactoryCard
import com.feldman.ha.ui.cards.FactoryEntityStateSheet
import com.feldman.ha.ui.cards.LocalCardSurfaceOverride
import com.feldman.ha.ui.cards.evaluateCondition
import com.feldman.ha.ui.cards.evaluateSupported
import com.feldman.ha.ui.cards.formatEntityState
import com.feldman.ha.ui.cards.resolveEntityIcon
import com.feldman.ha.ui.editors.CARD_BUTTON_MIN_HEIGHT
import com.feldman.ha.ui.editors.FactoryCardSettings
import com.feldman.ha.ui.dashboard.FullScreenAddPage
import com.feldman.ha.ui.dashboard.GridPos
import com.feldman.ha.ui.dashboard.ReorderableDashboardGrid


val LocalDashboardColumns = compositionLocalOf { 4 }
val LocalDashboardRowHeight = compositionLocalOf { 60.dp }
val LocalIsLandscape = compositionLocalOf { false }
val LocalCardsPreferencesName = compositionLocalOf { "cards_prefs" }

/** Max blur radius (dp) applied to the dashboard behind an open entity state sheet. */
internal const val SHEET_BACKGROUND_BLUR = 18f
/** Extra breathing room under the last row of a scrolling page, on top of the navbar inset. */
internal val PAGE_BOTTOM_SPACING = 32.dp
/** Sheet panel slide — fast and snappy, independent of the background reveal. */
private val sheetSlideSpec = spring<Float>(dampingRatio = 0.85f, stiffness = 800f)
/** Background blur + darkening ripple — slower and gradual so it doesn't snap to dark. */
private val backgroundRevealSpec = tween<Float>(durationMillis = 800, easing = FastOutSlowInEasing)
/** Closing both layers — fast. */
private val sheetCloseSpec = tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing)
private const val EDIT_REVEAL_SOLID_FRACTION = 0.7f

@Stable
class DashboardEntitySheetState internal constructor(private val scope: CoroutineScope) {
    var entityId by mutableStateOf<String?>(null)
        private set
    var originCoords by mutableStateOf<LayoutCoordinates?>(null)
        private set
    var originLocal by mutableStateOf(Offset.Zero)
        private set
    var onUpdated: () -> Unit = {}
        private set

    val slide = Animatable(0f)
    val backgroundReveal = Animatable(0f)

    fun open(
        entityId: String,
        originCoords: LayoutCoordinates?,
        originLocal: Offset,
        onUpdated: () -> Unit
    ) {
        this.entityId = entityId
        this.originCoords = originCoords
        this.originLocal = originLocal
        this.onUpdated = onUpdated
        scope.launch {
            slide.snapTo(0f)
            backgroundReveal.snapTo(0f)
            launch { slide.animateTo(1f, sheetSlideSpec) }
            launch { backgroundReveal.animateTo(1f, backgroundRevealSpec) }
        }
    }

    fun dismiss() {
        scope.launch {
            launch { backgroundReveal.animateTo(0f, sheetCloseSpec) }
            slide.animateTo(0f, sheetCloseSpec)
            entityId = null
        }
    }
}

@Composable
fun rememberDashboardEntitySheetState(): DashboardEntitySheetState {
    val scope = rememberCoroutineScope()
    return remember(scope) { DashboardEntitySheetState(scope) }
}

/**
 * A card on the unified dashboard grid. [instanceId] is the per-card-instance identity used
 * to key config/name/layout — it equals the entity_id for a normal single card, but a
 * duplicate card of the same entity carries a distinct id ("entity_id#<uuid>"), so the same
 * entity can appear on multiple cards. [key] is the persisted grid-position id.
 */
private sealed interface DashCard {
    val key: String
    val instanceId: String

    data class Entity(val entity: HAEntity, override val instanceId: String = entity.entity_id) : DashCard {
        override val key = "ent:$instanceId"
    }
}

/** The real entity id behind a card instance id (strips any "#<uuid>" duplicate suffix). */
fun entityIdOfInstance(instanceId: String): String = instanceId.substringBefore("#")

// ── Pages (dashboard tabs) ──────────────────────────────────────────────────────
// The dashboard is split into named pages shown as tabs at the top. Each card
// instance is assigned to exactly one page (cardPage map); cards with no assignment
// fall back to the first page, so existing dashboards migrate transparently into
// "Home". All other card storage (selected entities, button/camera ids, layout) is
// unchanged — pages are a filtering layer on top.
data class DashPage(val id: String, val title: String)

private const val PAGES_KEY = "pages_v1"
private const val CARD_PAGE_KEY = "card_page_v1"
private const val ACTIVE_PAGE_KEY = "active_page_v1"

private fun loadPages(prefs: SharedPreferences): List<DashPage> {
    val json = prefs.getString(PAGES_KEY, null) ?: return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DashPage(id, o.optString("title").ifBlank { "Page" })
        }
    }.getOrDefault(emptyList())
}

private fun savePages(prefs: SharedPreferences, pages: List<DashPage>) {
    val arr = org.json.JSONArray()
    pages.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("title", it.title)) }
    prefs.edit().putString(PAGES_KEY, arr.toString()).apply()
}

private fun loadCardPages(prefs: SharedPreferences): Map<String, String> {
    val json = prefs.getString(CARD_PAGE_KEY, null) ?: return emptyMap()
    return runCatching {
        val o = org.json.JSONObject(json)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
}

private fun saveCardPages(prefs: SharedPreferences, map: Map<String, String>) {
    val o = org.json.JSONObject()
    map.forEach { (k, v) -> o.put(k, v) }
    prefs.edit().putString(CARD_PAGE_KEY, o.toString()).apply()
}

private val CAMERA_FACTORY_SPEC = WidgetSpec(
    key = "app_camera",
    title = "Camera",
    iconRes = null,
    domain = "camera",
    style = CardStyle.CAMERA,
    configureQuery = ConfigureQuery("camera."),
    rows = emptyList(),
    haBindings = emptyList(),
    actions = ServiceMap(emptyMap())
)

private fun computeAdaptiveGridColumns(screenWidthDp: Int, override: Int): Int {
    if (override > 0) return override
    return when {
        screenWidthDp >= 960 -> 8
        screenWidthDp >= 640 -> 6
        else -> 4
    }
}

/**
 * Auto-landscape: scale columns so each column is the same width as a portrait column,
 * keeping card widths visually consistent across orientations.
 */
private fun computeAdaptiveLandscapeColumns(
    landscapeWidthDp: Int,
    portraitWidthDp: Int,
    portraitColOverride: Int,
    landscapeColOverride: Int
): Int {
    if (landscapeColOverride > 0) return landscapeColOverride
    val portraitCols = computeAdaptiveGridColumns(portraitWidthDp, portraitColOverride)
    val portraitColWidthDp = portraitWidthDp.toFloat() / portraitCols
    return ((landscapeWidthDp / portraitColWidthDp) + 0.5f).toInt().coerceIn(4, 16)
}

private fun computeAdaptiveGridRows(screenHeightDp: Int, override: Int): Int {
    if (override > 0) return override
    return when {
        screenHeightDp >= 800 -> 5
        else -> 4
    }
}

private const val CARD_MIN_SPAN_X = 2
private const val CARD_SPAN_X_KEY = "card_span_x"
private const val CARD_SPAN_Y_KEY = "card_span_y"
private const val CARD_SPAN_X_LAND_KEY = "card_span_x_land"
private const val CARD_SPAN_Y_LAND_KEY = "card_span_y_land"
private const val CARD_HEIGHT_DEFAULT_LAND_KEY = "card_height_default_land"
private const val CARD_MAX_SPAN_Y = 24
private val CARD_MIN_HEIGHT = 16.dp

@Composable
fun HomePage(
    api: HomeAssistantApi,
    selected: MutableList<HAEntity>,
    all: List<HAEntity>,
    loading: Boolean,
    edit: Boolean,
    onEditToggle: () -> Unit,
    names: MutableMap<String, String>,
    configs: MutableMap<String, Map<String, Any>>,
    saveLayout: () -> Unit,
    saveConfigs: () -> Unit,
    saveNames: () -> Unit,
    scaffoldPadding: PaddingValues,
    activeCardKey: String?,
    onActiveCardKeyChange: (String?) -> Unit,
    configEntityId: String?,
    onConfigEntityIdChange: (String?) -> Unit,
    onShowSettings: () -> Unit,
    isCameraOpen: Boolean,
    loadFailed: Boolean,
    refreshCountdown: Int = 30,
    onRetryLoad: () -> Unit,
    onNavigate: Navigator,
    selectedLandscape: MutableList<HAEntity>,
    onAddEntity: (HAEntity) -> Unit,
    onRemoveEntity: (HAEntity) -> Unit,
    entityStateSheetState: DashboardEntitySheetState? = null,
    hostManagesBackground: Boolean = false,
    showExpressiveSurface: Boolean = true,
    // Hosts that provide their own chrome (e.g. the Clock app's standby screensaver) can
    // hide the top bar. While editing, a floating toolbar over the grid stands in for the
    // top bar's done/remove/configure/settings actions.
    showTopBar: Boolean = true,
    // Hide the dashboard pages tab bar entirely (standby has a single page).
    showPageTabs: Boolean = true,
    // Render card settings inline instead of in a Dialog window. Dialogs escape any visual
    // transform the host applies (e.g. standby's rotated screensaver), so rotated hosts
    // need the inline presentation.
    inlineCardSettings: Boolean = false,
    // Fired when empty dashboard space is tapped outside edit mode. The Clock screensaver
    // uses it to exit standby (cards keep their own tap handling).
    onBackgroundTap: (() -> Unit)? = null,
    externalSheetEntityId: String? = null,
    externalSheetRequestId: Int = 0,
    dashboardEditActionRequest: DashboardEditActionRequest? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // The activity supplies this state so the sheet can render above both its navigation
    // rail and dashboard. Other hosts retain the local presentation.
    val localEntityStateSheetState = rememberDashboardEntitySheetState()
    val sheetState = entityStateSheetState ?: localEntityStateSheetState
    val externalSheetEntityAvailable = externalSheetEntityId?.let { entityId ->
        all.any { it.entity_id == entityId }
    } == true
    LaunchedEffect(externalSheetRequestId, externalSheetEntityAvailable) {
        if (externalSheetEntityAvailable) {
            sheetState.open(externalSheetEntityId, null, Offset.Zero) { refreshTrigger++ }
        }
    }

    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val prefs = remember(context) { context.getSharedPreferences("ha_prefs", android.content.Context.MODE_PRIVATE) }
    val cardsPreferencesName = LocalCardsPreferencesName.current
    val cardsPref = remember(context, cardsPreferencesName) {
        context.getSharedPreferences(cardsPreferencesName, android.content.Context.MODE_PRIVATE)
    }
    WidgetRegistry.init(context)
    // Keep the background snapshot refresh chain in sync with the configured cameras.
    LaunchedEffect(Unit) {
        if (!isPreview) CameraSnapshotWorker.sync(context)
    }
    var showCameraPicker by remember { mutableStateOf(false) }
    // Standalone button cards are factory cards too: each one is a synthetic
    // "button.<uuid>" entity whose config (label/icon/service/…) lives in the shared
    // configs map and is rendered by FactoryCard's BUTTON style. Only the id list is
    // kept here; legacy button_cards_v1 storage is migrated on first load.
    val buttonCardIds = remember {
        mutableStateListOf<String>().apply {
            if (migrateLegacyButtonCards(cardsPref, configs)) saveConfigs()
            addAll(loadButtonCardIds(cardsPref))
        }
    }
    val cameraCardIds = remember {
        mutableStateListOf<String>().apply {
            if (migrateLegacyCameraCards(cardsPref, configs, names)) {
                saveConfigs()
                saveNames()
            }
            addAll(loadCameraCardIds(cardsPref))
        }
    }
    // Standalone clock cards: each is a synthetic "clock.card_<uuid>" entity rendered by
    // ClockCard; only the id list is kept here, options live in the shared configs map.
    val clockCardIds = remember {
        mutableStateListOf<String>().apply { addAll(loadClockCardIds(cardsPref)) }
    }
    // Duplicate entity cards: extra instances of an entity that is already on the dashboard.
    // Each is an "entity_id#<uuid>" instance id whose config/name/page are independent of the
    // original card; the rendered entity state always mirrors the live target.
    val entityInstanceIds = remember {
        mutableStateListOf<String>().apply { addAll(loadEntityInstanceIds(cardsPref)) }
    }
    // ── Page state ──────────────────────────────────────────────────────────
    val pages = remember {
        mutableStateListOf<DashPage>().apply {
            val loaded = loadPages(cardsPref)
            if (loaded.isEmpty()) add(DashPage(id = "home", title = "Home")) else addAll(loaded)
        }
    }
    val cardPageMap = remember {
        mutableStateMapOf<String, String>().apply { putAll(loadCardPages(cardsPref)) }
    }
    var activePageId by remember {
        mutableStateOf(
            cardsPref.getString(ACTIVE_PAGE_KEY, null)?.takeIf { id -> pages.any { it.id == id } }
                ?: pages.first().id
        )
    }
    var pageEditTarget by remember { mutableStateOf<DashPage?>(null) }
    fun persistPages() = savePages(cardsPref, pages)
    fun persistCardPages() = saveCardPages(cardsPref, cardPageMap)
    fun setActivePage(id: String) {
        activePageId = id
        cardsPref.edit { putString(ACTIVE_PAGE_KEY, id) }
    }
    // Page for a card instance, defaulting to the first page (legacy cards migrate to it).
    fun pageOf(instanceId: String): String = cardPageMap[instanceId] ?: pages.first().id
    fun addPage() {
        val p = DashPage(
            id = "page_${java.util.UUID.randomUUID().toString().take(8)}",
            title = "Page ${pages.size + 1}"
        )
        pages.add(p)
        persistPages()
        setActivePage(p.id)
    }

    fun addButtonCard(config: Map<String, Any>): String {
        val id = "button.${java.util.UUID.randomUUID()}"
        configs[id] = config
        saveConfigs()
        buttonCardIds.add(id)
        saveButtonCardIds(cardsPref, buttonCardIds)
        cardPageMap[id] = activePageId
        persistCardPages()
        return id
    }

    fun addCameraCard(config: CameraCardConfig): String {
        val id = "camera.card_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        configs[id] = factoryConfigForCameraCard(config)
        names[id] = config.name
        saveConfigs()
        saveNames()
        cameraCardIds.add(id)
        saveCameraCardIds(cardsPref, cameraCardIds)
        cardPageMap[id] = activePageId
        persistCardPages()
        CameraSnapshotWorker.sync(context)
        return id
    }

    fun addClockCard(config: Map<String, Any>): String {
        val id = "clock.card_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        configs[id] = config
        saveConfigs()
        clockCardIds.add(id)
        saveClockCardIds(cardsPref, clockCardIds)
        cardPageMap[id] = activePageId
        persistCardPages()
        return id
    }

    // A duplicate card for an entity that is already on the dashboard. Starts from the
    // original card's config (also guarantees a configs entry, which keeps the target
    // entity in the WebSocket subscription).
    fun addEntityInstance(entity: HAEntity, config: Map<String, Any>? = null, name: String? = null): String {
        val id = "${entity.entity_id}#${java.util.UUID.randomUUID().toString().take(8)}"
        configs[id] = config ?: configs[entity.entity_id] ?: emptyMap()
        if (name != null) names[id] = name
        saveConfigs()
        saveNames()
        entityInstanceIds.add(id)
        saveEntityInstanceIds(cardsPref, entityInstanceIds)
        cardPageMap[id] = activePageId
        persistCardPages()
        return id
    }

    // Instantiates a saved card setup: synthetic cards get a fresh id, entity cards re-bind
    // to their original entity (which must exist on this Home Assistant instance).
    fun addPresetCard(preset: CardPresets.CardPreset): Boolean {
        val srcId = preset.sourceId
        when {
            srcId.startsWith("button.") -> {
                val id = addButtonCard(preset.config)
                names[id] = preset.name
                saveNames()
            }
            srcId.startsWith("clock.") -> {
                val id = addClockCard(preset.config)
                names[id] = preset.name
                saveNames()
            }
            srcId.startsWith("camera.card_") -> {
                val id = "camera.card_${java.util.UUID.randomUUID().toString().replace("-", "")}"
                configs[id] = preset.config
                names[id] = preset.name
                saveConfigs()
                saveNames()
                cameraCardIds.add(id)
                saveCameraCardIds(cardsPref, cameraCardIds)
                cardPageMap[id] = activePageId
                persistCardPages()
                CameraSnapshotWorker.sync(context)
            }
            else -> {
                // Entity presets (the saved id may itself be a "#<uuid>" instance) always
                // become a fresh instance card, so they can be applied any number of times.
                val target = all.find { it.entity_id == entityIdOfInstance(srcId) } ?: return false
                if (selected.none { it.entity_id == target.entity_id } &&
                    selectedLandscape.none { it.entity_id == target.entity_id }
                ) {
                    // First card of this entity: a regular dashboard card.
                    configs[target.entity_id] = preset.config
                    if (preset.name.isNotBlank()) names[target.entity_id] = preset.name
                    saveConfigs()
                    saveNames()
                    onAddEntity(target)
                    cardPageMap[target.entity_id] = activePageId
                    persistCardPages()
                } else {
                    addEntityInstance(target, config = preset.config, name = preset.name.takeIf { it.isNotBlank() })
                }
            }
        }
        return true
    }
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val activeSelected: MutableList<HAEntity> = if (isLandscape) selectedLandscape else selected
    val portraitColOverride = prefs.getInt("grid_columns_portrait", 0)
    val landscapeColOverride = prefs.getInt("grid_columns_landscape", 0)
    val portraitWidthDp = if (isLandscape) configuration.screenHeightDp else configuration.screenWidthDp
    val gridColumns = if (isLandscape) {
        computeAdaptiveLandscapeColumns(
            landscapeWidthDp = configuration.screenWidthDp,
            portraitWidthDp = portraitWidthDp,
            portraitColOverride = portraitColOverride,
            landscapeColOverride = landscapeColOverride
        )
    } else {
        computeAdaptiveGridColumns(configuration.screenWidthDp, portraitColOverride)
    }
    val gridRowsKey = if (isLandscape) "grid_rows_landscape" else "grid_rows_portrait"
    val gridRowsOverride = prefs.getInt(gridRowsKey, 0)
    val cardRowHeight: androidx.compose.ui.unit.Dp = if (gridRowsOverride > 0) {
        (configuration.screenHeightDp.toFloat() / gridRowsOverride).coerceAtLeast(40f).dp
    } else {
        CARD_MIN_HEIGHT
    }

    // ── Unified card layout ───────────────────────────────────────────────────
    // All card types live in ONE launcher-style grid: every card has an explicit
    // (column, row) cell, so any card can be dropped exactly where you point — even
    // left of a wider card with free space in the row above. Cards without a saved
    // cell (new ones, or on first run) are auto-placed in entity→button→camera order.
    val layoutPrefKey = if (isLandscape) "dashboard_grid_land_v1" else "dashboard_grid_v1"
    val cardPositions = remember(isLandscape) {
        mutableStateMapOf<String, GridPos>().apply {
            cardsPref.getString(layoutPrefKey, null)?.let { json ->
                runCatching {
                    val obj = org.json.JSONObject(json)
                    obj.keys().forEach { k ->
                        val parts = obj.getString(k).split(',')
                        put(k, GridPos(parts[0].toInt(), parts[1].toInt()))
                    }
                }
            }
        }
    }
    val saveCardPositions: (Map<Any, GridPos>) -> Unit = { layout ->
        cardPositions.clear()
        layout.forEach { (k, p) -> cardPositions[k.toString()] = p }
        val obj = org.json.JSONObject()
        layout.forEach { (k, p) -> obj.put(k.toString(), "${p.x},${p.y}") }
        cardsPref.edit().putString(layoutPrefKey, obj.toString()).apply()
    }
    val dashCards: List<DashCard> = remember(
        activeSelected.toList(), buttonCardIds.toList(), cameraCardIds.toList(), clockCardIds.toList(),
        entityInstanceIds.toList(), all, configs.toMap(), names.toMap()
    ) {
        buildList {
            activeSelected.forEach { add(DashCard.Entity(it)) }
            entityInstanceIds.forEach { id ->
                val target = all.find { it.entity_id == entityIdOfInstance(id) }
                    ?: HAEntity(entityIdOfInstance(id), "unknown", emptyMap())
                add(DashCard.Entity(target, instanceId = id))
            }
            buttonCardIds.forEach { add(DashCard.Entity(syntheticButtonEntity(it, configs[it]))) }
            cameraCardIds.forEach { add(DashCard.Entity(syntheticCameraEntity(it, configs[it], names[it]))) }
            clockCardIds.forEach { add(DashCard.Entity(syntheticClockEntity(it, names[it]))) }
        }
    }

    CompositionLocalProvider(
        LocalDashboardColumns provides gridColumns,
        LocalDashboardRowHeight provides cardRowHeight,
        LocalIsLandscape provides isLandscape
    ) {
    // Shared by the top bar and the floating edit toolbar (shown when the top bar is hidden).
    fun removeCardByKey(key: String) {
        when {
            key.startsWith("ent:") -> {
                val entityId = key.removePrefix("ent:")
                if (entityId in entityInstanceIds) {
                    entityInstanceIds.remove(entityId)
                    saveEntityInstanceIds(cardsPref, entityInstanceIds)
                    configs.remove(entityId)
                    names.remove(entityId)
                    saveConfigs()
                    saveNames()
                } else if (entityId in cameraCardIds) {
                    cameraCardIds.remove(entityId)
                    saveCameraCardIds(cardsPref, cameraCardIds)
                    configs.remove(entityId)
                    names.remove(entityId)
                    saveConfigs()
                    saveNames()
                    CameraSnapshotWorker.sync(context)
                } else if (entityId in buttonCardIds) {
                    buttonCardIds.remove(entityId)
                    saveButtonCardIds(cardsPref, buttonCardIds)
                    configs.remove(entityId)
                    saveConfigs()
                } else if (entityId in clockCardIds) {
                    clockCardIds.remove(entityId)
                    saveClockCardIds(cardsPref, clockCardIds)
                    configs.remove(entityId)
                    names.remove(entityId)
                    saveConfigs()
                    saveNames()
                } else {
                    val entity = all.find { it.entity_id == entityId }
                    if (entity != null) {
                        onRemoveEntity(entity)
                    }
                }
            }
        }
        cardPageMap.remove(key.removePrefix("ent:"))
        persistCardPages()
        onActiveCardKeyChange(null)
    }

    LaunchedEffect(dashboardEditActionRequest) {
        val request = dashboardEditActionRequest ?: return@LaunchedEffect
        when (request.action) {
            DashboardEditAction.ADD -> showPicker = true
            DashboardEditAction.EDIT -> activeCardKey?.let { key ->
                if (key.startsWith("ent:")) {
                    onConfigEntityIdChange(key.removePrefix("ent:"))
                }
                onActiveCardKeyChange(null)
            }
            DashboardEditAction.DELETE -> activeCardKey?.let { removeCardByKey(it) }
        }
    }

    val isExpressiveCanvas = ExpressiveCanvasSetting.isEnabled(LocalContext.current)
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
    val tertiaryContainerColor = MaterialTheme.colorScheme.tertiaryContainer
    val primaryWash = primaryContainerColor.copy(alpha = 0.4f)
    val tertiaryWash = tertiaryContainerColor.copy(alpha = 0.4f)
    val editReveal = remember { Animatable(if (edit) 1f else 0f) }
    LaunchedEffect(edit) {
        if (edit) {
            editReveal.snapTo(0f)
            editReveal.animateTo(1f, backgroundRevealSpec)
        } else {
            editReveal.animateTo(0f, sheetCloseSpec)
        }
    }
    val editRevealProgress = editReveal.value.coerceIn(0f, 1f)
    val fabCenterYInRoot = with(density) {
        configuration.screenHeightDp.dp.toPx() - if (isLandscape) 64.dp.toPx() else 72.dp.toPx()
    }
    val editRevealOriginXFromEnd = 72.dp
    val dashboardBackgroundModifier = if (hostManagesBackground) {
        Modifier
    } else {
        Modifier.dashboardEditRevealBackground(
            baseColor = if (isExpressiveCanvas) primaryWash else MaterialTheme.colorScheme.background,
            revealColor = tertiaryWash,
            revealProgress = editRevealProgress,
            originXFromEnd = editRevealOriginXFromEnd
        )
    }
    val topBarBackgroundModifier = if (hostManagesBackground || isExpressiveCanvas) {
        Modifier
    } else {
        Modifier.dashboardEditRevealBackground(
            baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            revealColor = tertiaryWash,
            revealProgress = editRevealProgress,
            originXFromEnd = editRevealOriginXFromEnd,
            originY = fabCenterYInRoot
        )
    }

    Scaffold(
        modifier = dashboardBackgroundModifier,
        containerColor = Color.Transparent,
        contentWindowInsets = if (showTopBar) ScaffoldDefaults.contentWindowInsets
            else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            if (!showTopBar) return@Scaffold
            Column(
                modifier = topBarBackgroundModifier
            ) {
                HomeAssistantTopBar(
                    onShowCameras = {}, // Handled by bottom bar in MainActivity
                    onShowSettings = onShowSettings,
                    isCameraOpen = isCameraOpen,
                    edit = edit,
                    editProgress = editRevealProgress,
                    containerColor = Color.Transparent
                )
                if (showPageTabs && (pages.size > 1 || edit)) {
                    PageTabBar(
                        pages = pages,
                        activePageId = activePageId,
                        edit = edit,
                        editProgress = editRevealProgress,
                        onSelect = { setActivePage(it) },
                        onAddPage = { addPage() },
                        onEditPage = { pageEditTarget = it }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .then(
                    if (isExpressiveCanvas && showExpressiveSurface) {
                        Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 0.dp)
                            .clip(RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp))
                    } else Modifier
                ),
            color = if (isExpressiveCanvas && showExpressiveSurface) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
        ) {
            Box(Modifier.fillMaxSize()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (loadFailed) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dead_home),
                        contentDescription = "Failed to connect",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(180.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Failed to connect to Home Assistant",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Please check your network connection or Home Assistant settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Refreshing in ${refreshCountdown}s…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    MotionButton(
                        text = "Refresh",
                        icon = "refresh",
                        onClick = onRetryLoad,
                        width = 180.dp,
                        height = 64.dp,
                        fontSize = 18.sp,
                        iconSize = 24.dp,
                        defaultState = MotionButtonState(
                            backgroundColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            outlineWidth = 0.dp,
                            outlineColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            } else {
                // Non-lazy layout: a Column of Rows. LazyVerticalGrid crashes here because
                // NavDisplay (Navigation 3) runs content inside a LookaheadScope, and the grid's
                // beyond-bounds placement double-places items under lookahead ("Place was called
                // on a node which was placed already"). Packing cards by width span into Rows with
                // weights gives the same multi-width grid without any lazy-layout placement.
                val scrollState = rememberScrollState()
                val backgroundBlur = if (entityStateSheetState == null) {
                    (sheetState.backgroundReveal.value.coerceIn(0f, 1f) * SHEET_BACKGROUND_BLUR).dp
                } else {
                    0.dp
                }
                // Only this page's cards are shown; legacy cards default to the first page.
                val visibleCards = dashCards.filter { pageOf(it.instanceId) == activePageId }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(backgroundBlur)
                        .then(
                            if (onBackgroundTap != null && !edit) {
                                // Parent-level tap detection: taps consumed by cards never get
                                // here, so this only fires for empty grid space.
                                Modifier.pointerInput(onBackgroundTap) {
                                    detectTapGestures { onBackgroundTap() }
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                  Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(
                            start = 12.dp,
                            top = 12.dp,
                            end = 12.dp,
                            bottom = 12.dp + PAGE_BOTTOM_SPACING + scaffoldPadding.calculateBottomPadding()
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // One grid for ALL card types, so any card can be dragged anywhere.
                    ReorderableDashboardGrid(
                        items = visibleCards,
                        itemKey = { it.key },
                        itemSpanX = { card ->
                            when (card) {
                                is DashCard.Entity -> {
                                    val spec = factorySpecForEntity(card.entity, configs[card.instanceId])
                                    cardSpanX(configs[card.instanceId], gridColumns, isLandscape, cardMinimumSpanX(spec))
                                }
                            }
                        },
                        itemSpanY = { card ->
                            when (card) {
                                is DashCard.Entity -> {
                                    val entity = card.entity
                                    val spec = factorySpecForEntity(entity, configs[card.instanceId])
                                    val minSpanY = cardMinimumSpanY(spec, entity, configs[card.instanceId], all, context)
                                    cardSpanY(configs[card.instanceId], minSpanY, isLandscape)
                                }
                            }
                        },
                        savedPosition = { card -> cardPositions[card.key] },
                        columns = gridColumns,
                        rowHeight = cardRowHeight,
                        gap = 13.dp,
                        dragEnabled = edit,
                        scrollState = scrollState,
                        onCommitLayout = { layout -> saveCardPositions(layout) },
                        onDragStarted = { onActiveCardKeyChange(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) { card, isDragging ->
                        when (card) {
                            is DashCard.Entity -> DashboardCard(
                                entity = card.entity,
                                instanceId = card.instanceId,
                                api = api,
                                edit = edit,
                                activeCardKey = activeCardKey,
                                names = names,
                                configs = configs,
                                allEntities = all,
                                onActiveCardKeyChange = onActiveCardKeyChange,
                                onRefresh = { refreshTrigger++ },
                                onNavigate = onNavigate,
                                onOpenSheet = { coords, local ->
                                    sheetState.open(card.entity.entity_id, coords, local) {
                                        refreshTrigger++
                                    }
                                }
                            )
                        }
                    }

                  }
                }
            }
        }
    }

        // State sheet overlay — full-screen, drawn above the scrolling grid. Resolve the
        // live entity by id so optimistic updates (e.g. climate temperature) reflect here.
        if (entityStateSheetState == null) sheetState.entityId?.let { id ->
            val sheetEntity = all.firstOrNull { it.entity_id == id }
            val sheetSpec = sheetEntity?.let { factorySpecForEntity(it, configs[it.entity_id]) }
            if (sheetEntity != null && sheetSpec != null) {
                FactoryEntityStateSheet(
                    entity = sheetEntity,
                    spec = sheetSpec,
                    api = api,
                    names = names,
                    configs = configs,
                    allEntities = all,
                    originCoords = sheetState.originCoords,
                    originLocal = sheetState.originLocal,
                    slideProgress = sheetState.slide.value,
                    scrimProgress = sheetState.backgroundReveal.value,
                    onUpdated = sheetState.onUpdated,
                    onDismiss = sheetState::dismiss
                )
            } else {
                // Entity was removed/became unavailable while open — close the sheet.
                LaunchedEffect(id) { sheetState.dismiss() }
            }
        }

        // Rename/delete a page (long-press a tab in edit mode).
        pageEditTarget?.let { target ->
            var titleText by remember(target.id) { mutableStateOf(target.title) }
            AlertDialog(
                onDismissRequest = { pageEditTarget = null },
                title = { Text("Edit page") },
                text = {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it },
                        singleLine = true,
                        label = { Text("Title") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val idx = pages.indexOfFirst { it.id == target.id }
                        if (idx != -1) {
                            pages[idx] = target.copy(title = titleText.trim().ifBlank { target.title })
                            persistPages()
                        }
                        pageEditTarget = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        if (pages.size > 1) {
                            TextButton(onClick = {
                                // Move this page's cards onto the first remaining page, then drop it.
                                val fallback = pages.first { it.id != target.id }.id
                                cardPageMap.keys.toList().forEach { k ->
                                    if (cardPageMap[k] == target.id) cardPageMap[k] = fallback
                                }
                                persistCardPages()
                                pages.removeAll { it.id == target.id }
                                persistPages()
                                if (activePageId == target.id) setActivePage(fallback)
                                pageEditTarget = null
                            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                        TextButton(onClick = { pageEditTarget = null }) { Text("Cancel") }
                    }
                }
            )
        }

        // With the top bar hidden, editing gets a floating stand-in for its actions:
        // add, remove/configure for the selected card, and done. Composed before the
        // picker/settings overlays so those cover it.
        if (!showTopBar && edit) {
            val overlayColors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            ) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalIconButton(
                    onClick = { showPicker = true },
                    colors = overlayColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        rememberSymbolPainter("add"),
                        contentDescription = "Add card",
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (activeCardKey != null) {
                    FilledTonalIconButton(
                        onClick = { removeCardByKey(activeCardKey) },
                        colors = overlayColors,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(rememberSymbolPainter("delete"), contentDescription = "Remove card", modifier = Modifier.size(20.dp))
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (activeCardKey.startsWith("ent:")) {
                                onConfigEntityIdChange(activeCardKey.removePrefix("ent:"))
                            }
                            onActiveCardKeyChange(null)
                        },
                        colors = overlayColors,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(rememberSymbolPainter("edit"), contentDescription = "Card settings", modifier = Modifier.size(20.dp))
                    }
                }
                FilledTonalIconButton(
                    onClick = onEditToggle,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(rememberSymbolPainter("check"), contentDescription = "Done", modifier = Modifier.size(20.dp))
                }
            }
            }
        }

        if (showPicker) {
            val scheme = MaterialTheme.colorScheme
            var addMode by remember { mutableStateOf("entity") }
            var searchQuery by remember { mutableStateOf("") }
            var entityFilter by remember { mutableStateOf<String?>(null) }

            FullScreenAddPage(title = "Add Card", onClose = { showPicker = false }, useDialog = !inlineCardSettings) {
                AddModeSegmented(addMode) { addMode = it }
                Spacer(Modifier.height(10.dp))

                if (addMode == "entity") {
                    // Entities already on the dashboard stay listed: adding one again creates
                    // an independent duplicate card (own config/name/page).
                    val remaining = all.filter { ent ->
                        val d = ent.entity_id.split(".")[0]
                        entityFilter == null || d == entityFilter
                    }
                    val finalFiltered = if (searchQuery.isBlank()) remaining else remaining.filter { ent ->
                        val friendly = ent.attributes["friendly_name"]?.toString() ?: ""
                        friendly.contains(searchQuery, ignoreCase = true) ||
                            ent.entity_id.contains(searchQuery, ignoreCase = true)
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search entities...") },
                        leadingIcon = { Icon(rememberSymbolPainter("search"), null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) { Icon(rememberSymbolPainter("close"), null) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = scheme.outline.copy(alpha = 0.3f),
                            focusedBorderColor = scheme.primary
                        )
                    )

                    if (entityFilter != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Filtered:", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(scheme.primary.copy(alpha = 0.15f))
                                    .clickable { entityFilter = null }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cardTypeLabel(entityFilter!!), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = scheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Icon(rememberSymbolPainter("close"), "Clear filter", tint = scheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(finalFiltered) { entity ->
                            val domain = entity.entity_id.split(".")[0]
                            val friendlyName = entity.attributes["friendly_name"]?.toString() ?: entity.entity_id
                            val typeColor = Color(cardTypeColor(domain))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        val alreadyOnDashboard =
                                            selected.any { it.entity_id == entity.entity_id } ||
                                                selectedLandscape.any { it.entity_id == entity.entity_id }
                                        if (alreadyOnDashboard) {
                                            addEntityInstance(entity)
                                        } else {
                                            onAddEntity(entity)
                                            cardPageMap[entity.entity_id] = activePageId
                                            persistCardPages()
                                        }
                                        showPicker = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(typeColor.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(rememberSymbolPainter(cardTypeIcon(domain)), domain, tint = typeColor, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(friendlyName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(entity.entity_id, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(typeColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(cardTypeLabel(domain), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = typeColor)
                                }
                                Spacer(Modifier.width(8.dp))
                                Icon(rememberSymbolPainter("add_circle_outline"), "Add", tint = scheme.primary.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
                            }
                        }
                        if (finalFiltered.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(rememberSymbolPainter("search_off"), null, tint = scheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("No matching entities found", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                } else if (addMode == "card") {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(ADD_CARD_TYPES) { card ->
                            val color = Color(card.color)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(scheme.surfaceContainerHigh)
                                    .clickable {
                                        when (card.id) {
                                            "camera" -> { showPicker = false; showCameraPicker = true }
                                            "button" -> {
                                                showPicker = false
                                                // Create with defaults, then open the regular factory
                                                // configure page to customize it.
                                                val id = addButtonCard(newButtonCardConfig())
                                                onConfigEntityIdChange(id)
                                            }
                                            "clock" -> {
                                                showPicker = false
                                                val id = addClockCard(newClockCardConfig())
                                                onConfigEntityIdChange(id)
                                            }
                                            else -> { entityFilter = card.domain; searchQuery = ""; addMode = "entity" }
                                        }
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(rememberSymbolPainter(card.icon), card.label, tint = color, modifier = Modifier.size(26.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(card.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                                    Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                                Icon(rememberSymbolPainter("chevron_right"), null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                } else {
                    // Saved card setups: local presets plus (when a bridge is wired up, e.g. the
                    // Clock standby reading Feldman Home) presets from the sibling app.
                    var presetsRefresh by remember { mutableIntStateOf(0) }
                    val localPresets = remember(presetsRefresh) { CardPresets.load(context) }
                    var remotePresets by remember { mutableStateOf<List<CardPresets.CardPreset>>(emptyList()) }
                    LaunchedEffect(Unit) {
                        remotePresets = withContext(Dispatchers.IO) { CardPresets.loadRemote(context) }
                    }
                    val allPresets = localPresets + remotePresets
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allPresets, key = { it.remoteSource.orEmpty() + it.id }) { preset ->
                            val domain = preset.sourceId.substringBefore(".").ifBlank { "card" }
                            val typeColor = Color(cardTypeColor(domain))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(scheme.surfaceContainerHigh)
                                    .clickable {
                                        val ok = addPresetCard(preset)
                                        if (ok) {
                                            showPicker = false
                                        } else {
                                            android.widget.Toast.makeText(
                                                context,
                                                preset.sourceId + " is not available on this Home Assistant",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(typeColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(rememberSymbolPainter("bookmark"), preset.name, tint = typeColor, modifier = Modifier.size(26.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(
                                        if (preset.remoteSource != null) cardTypeLabel(domain) + " · from " + preset.remoteSource else cardTypeLabel(domain),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                                if (preset.remoteSource == null) {
                                    IconButton(onClick = {
                                        CardPresets.delete(context, preset.id)
                                        presetsRefresh++
                                    }) {
                                        Icon(rememberSymbolPainter("delete"), "Delete preset", tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                    }
                                }
                                Icon(rememberSymbolPainter("add_circle_outline"), "Add", tint = scheme.primary.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
                            }
                        }
                        if (allPresets.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(rememberSymbolPainter("bookmark"), null, tint = scheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("No saved setups yet", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                                    Text("Save one from any card's settings page", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showCameraPicker) {
            var cameraTab by remember { mutableIntStateOf(0) }
            var frigateId by remember { mutableStateOf("") }
            var frigateDisplayName by remember { mutableStateOf("") }
            val haCameras = all.filter { it.entity_id.startsWith("camera.") }
            val scheme = MaterialTheme.colorScheme
            FullScreenAddPage(title = "Add Camera", onClose = { showCameraPicker = false }, useDialog = !inlineCardSettings) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SecondaryTabRow(selectedTabIndex = cameraTab, containerColor = scheme.surface) {
                            Tab(selected = cameraTab == 0, onClick = { cameraTab = 0 }) {
                                Text("Home Assistant", modifier = Modifier.padding(vertical = 10.dp))
                            }
                            Tab(selected = cameraTab == 1, onClick = { cameraTab = 1 }) {
                                Text("Frigate", modifier = Modifier.padding(vertical = 10.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (cameraTab) {
                            0 -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(haCameras) { entity ->
                                        val friendlyName = entity.attributes["friendly_name"]?.toString() ?: entity.entity_id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    val config = CameraCardConfig(
                                                        id = entity.entity_id,
                                                        source = CameraSource.HA,
                                                        name = friendlyName
                                                    )
                                                    addCameraCard(config)
                                                    showCameraPicker = false
                                                }
                                                .padding(vertical = 8.dp, horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(scheme.primary.copy(alpha = 0.1f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = rememberSymbolPainter("videocam"),
                                                    contentDescription = null,
                                                    tint = scheme.primary,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(friendlyName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                                                Text(entity.entity_id, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                            }
                                            Icon(
                                                painter = rememberSymbolPainter("add_circle_outline"),
                                                contentDescription = "Add",
                                                tint = scheme.primary.copy(alpha = 0.8f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    if (haCameras.isEmpty()) {
                                        item {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    painter = rememberSymbolPainter("videocam_off"),
                                                    contentDescription = null,
                                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(40.dp)
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text("No camera entities found", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = frigateId,
                                        onValueChange = { frigateId = it },
                                        label = { Text("Camera ID (e.g. front_door)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = scheme.outline.copy(alpha = 0.3f),
                                            focusedBorderColor = scheme.primary
                                        )
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = frigateDisplayName,
                                        onValueChange = { frigateDisplayName = it },
                                        label = { Text("Display Name (optional)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = scheme.outline.copy(alpha = 0.3f),
                                            focusedBorderColor = scheme.primary
                                        )
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            val trimmed = frigateId.trim()
                                            if (trimmed.isNotBlank()) {
                                                val config = CameraCardConfig(
                                                    id = trimmed,
                                                    source = CameraSource.FRIGATE,
                                                    name = frigateDisplayName.trim().ifBlank { trimmed }
                                                )
                                                addCameraCard(config)
                                                showCameraPicker = false
                                            }
                                        },
                                        enabled = frigateId.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(rememberSymbolPainter("videocam"), null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Add Frigate Camera")
                                    }
                                }
                            }
                        }
                    }
                }
        }

        if (configEntityId != null) {
            val entity = configEntityId.takeIf { it.contains("#") }?.let { id ->
                    // Duplicate instance: the settings page keys config/name by entity_id, so hand
                    // it a live copy of the target entity carrying the instance id.
                    val target = all.find { it.entity_id == entityIdOfInstance(id) }
                    HAEntity(id, target?.state ?: "unknown", target?.attributes ?: emptyMap())
                }
                ?: all.find { it.entity_id == configEntityId }
                ?: selected.find { it.entity_id == configEntityId }
                ?: configEntityId.takeIf { it in buttonCardIds }?.let { syntheticButtonEntity(it, configs[it]) }
                ?: configEntityId.takeIf { it in cameraCardIds }?.let { syntheticCameraEntity(it, configs[it], names[it]) }
                ?: configEntityId.takeIf { it in clockCardIds }?.let { syntheticClockEntity(it, names[it]) }
            if (entity != null) {
                val spec = factorySpecForEntity(entity, configs[entity.entity_id])
                if (spec != null) {
                    FactoryCardSettings(
                        entity = entity,
                        spec = spec,
                        api = api,
                        names = names,
                        configs = configs,
                        saveNames = saveNames,
                        saveConfigs = saveConfigs,
                        onUpdated = { refreshTrigger++ },
                        onDismiss = { onConfigEntityIdChange(null) },
                        allEntities = all,
                        onNavigate = onNavigate,
                        useDialog = !inlineCardSettings
                    )
                }
            }
        }
    }
    } // CompositionLocalProvider
}

// ── Add-card page helpers ──────────────────────────────────────────────────────
// Domains that get a rich, entity-backed card (their own widget spec).
private val SUPPORTED_CARD_DOMAINS = listOf("light", "climate", "fan", "alarm_control_panel", "lock", "cover")

// Everything offered in the "By entity" picker: rich-card domains plus every entity a button card
// can act on. Non-rich domains are added as button cards targeting that entity.
private val ADDABLE_ENTITY_DOMAINS: Set<String> =
    (SUPPORTED_CARD_DOMAINS + BUTTON_ACTIONABLE_DOMAINS).toSet()

private fun cardTypeLabel(domain: String): String = when (domain) {
    "light" -> "Light"; "climate" -> "Climate"; "fan" -> "Fan"
    "alarm_control_panel" -> "Alarm"; "lock" -> "Lock"
    "input_boolean" -> "Input boolean"; "input_button" -> "Input button"
    "media_player" -> "Media player"; "water_heater" -> "Water heater"
    else -> domain.replace("_", " ").replaceFirstChar { it.uppercase() }
}

private fun cardTypeIcon(domain: String): String = when (domain) {
    "light" -> "lightbulb"; "climate" -> "thermostat"; "fan" -> "mode_fan"
    "alarm_control_panel" -> "shield"; "lock" -> "lock"
    "switch" -> "toggle_on"; "input_boolean" -> "toggle_on"
    "automation" -> "smart_toy"; "script" -> "description"
    "button" -> "smart_button"; "input_button" -> "smart_button"
    "scene" -> "palette"; "media_player" -> "play_circle"
    "cover" -> "blinds"; "siren" -> "notifications_active"
    "humidifier" -> "humidity_percentage"; "remote" -> "settings_remote"
    "group" -> "group_work"; "vacuum" -> "cleaning_services"
    "valve" -> "water_drop"; "water_heater" -> "water_heater"
    else -> "device_hub"
}

private fun cardTypeColor(domain: String): Long = when (domain) {
    "light" -> 0xFFE9C400L; "climate" -> 0xFF00ACC1L; "fan" -> 0xFF4CAF50L
    "alarm_control_panel" -> 0xFFFF5722L; "lock" -> 0xFF9C27B0L
    "cover" -> 0xFF8D6E63L
    "switch", "input_boolean" -> 0xFF26A69AL
    "automation", "script" -> 0xFF7E57C2L
    "button", "input_button" -> 0xFFEC407AL
    "scene", "media_player" -> 0xFF5C6BC0L
    else -> 0xFF42A5F5L
}

private data class AddCardType(
    val id: String,
    val label: String,
    val subtitle: String,
    val icon: String,
    val color: Long,
    val domain: String?, // entity domain to filter to; null for camera/button
)

private val ADD_CARD_TYPES = listOf(
    AddCardType("camera", "Camera", "HA or Frigate live view", "videocam", 0xFF26A69AL, null),
    AddCardType("button", "Button", "Run a service on tap", "smart_button", 0xFF7E57C2L, null),
    AddCardType("clock", "Clock", "Time & date", "schedule", 0xFF5C6BC0L, null),
    AddCardType("climate", "Climate", "Thermostat / AC", "thermostat", 0xFF00ACC1L, "climate"),
    AddCardType("light", "Light", "Lights & brightness", "lightbulb", 0xFFE9C400L, "light"),
    AddCardType("switch", "Switch", "Switches & plugs", "power_settings_new", 0xFF26A69AL, "switch"),
    AddCardType("vacuum", "Vacuum", "Robot vacuums", "cleaning_services", 0xFF7E57C2L, "vacuum"),
    AddCardType("fan", "Fan", "Fans", "mode_fan", 0xFF4CAF50L, "fan"),
    AddCardType("lock", "Lock", "Locks", "lock", 0xFF9C27B0L, "lock"),
    AddCardType("cover", "Cover", "Blinds, curtains & shades", "blinds", 0xFF8D6E63L, "cover"),
    AddCardType("alarm_control_panel", "Alarm", "Alarm panel", "shield", 0xFFFF5722L, "alarm_control_panel"),
    AddCardType("sensor", "Sensor", "Sensors", "sensors", 0xFF42A5F5L, "sensor"),
    AddCardType("binary_sensor", "Binary Sensor", "Binary Sensors", "sensor_window", 0xFF42A5F5L, "binary_sensor"),
)

private fun Modifier.dashboardEditRevealBackground(
    baseColor: Color,
    revealColor: Color,
    revealProgress: Float,
    originXFromEnd: Dp = 72.dp,
    originYFromBottom: Dp = 72.dp,
    originY: Float? = null
): Modifier = drawBehind {
    drawRect(baseColor)
    val progress = revealProgress.coerceIn(0f, 1f)
    if (progress <= 0f) return@drawBehind

    val center = Offset(
        x = size.width - originXFromEnd.toPx(),
        y = originY ?: (size.height - originYFromBottom.toPx())
    )
    val radius = maxDistanceToCorner(center, size) / EDIT_REVEAL_SOLID_FRACTION * progress
    if (radius <= 0f) return@drawBehind

    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to revealColor,
                EDIT_REVEAL_SOLID_FRACTION to revealColor,
                1f to Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun maxDistanceToCorner(center: Offset, size: Size): Float {
    val dx = max(center.x, size.width - center.x)
    val dy = max(center.y, size.height - center.y)
    return sqrt(dx * dx + dy * dy)
}

/** Tab bar of dashboard pages. In edit mode: long-press a tab to rename/delete, "+" adds a page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTabBar(
    pages: List<DashPage>,
    activePageId: String,
    edit: Boolean,
    editProgress: Float,
    onSelect: (String) -> Unit,
    onAddPage: () -> Unit,
    onEditPage: (DashPage) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isExpressive = ExpressiveCanvasSetting.isEnabled(context)
    val modeProgress = editProgress.coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pages.forEach { page ->
            val selected = page.id == activePageId
            val containerColor = if (selected) {
                lerp(
                    if (isExpressive) scheme.primaryContainer else scheme.primary,
                    if (isExpressive) scheme.tertiaryContainer else scheme.tertiary,
                    modeProgress
                )
            } else {
                if (isExpressive) scheme.background else scheme.surfaceVariant
            }
            val contentColor = if (selected) {
                lerp(
                    if (isExpressive) scheme.onPrimaryContainer else scheme.onPrimary,
                    if (isExpressive) scheme.onTertiaryContainer else scheme.onTertiary,
                    modeProgress
                )
            } else {
                if (isExpressive) scheme.onSurface else scheme.onSurfaceVariant
            }

            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(containerColor)
                    .combinedClickable(
                        onClick = { onSelect(page.id) },
                        onLongClick = { if (edit) onEditPage(page) }
                    )
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 20.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                    color = contentColor
                )
            }
        }
        if (edit) {
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isExpressive) scheme.background else scheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onAddPage() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(rememberSymbolPainter("add"), "Add page", tint = scheme.onSurface, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Segmented "By entity" / "By card" picker in the app's pill style. */
@Composable
private fun AddModeSegmented(mode: String, onMode: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Springy corner bounce on selection, matching the card pickers. Widths stay fixed 50:50.
    val cornerSpring = remember { spring<Float>(dampingRatio = 0.45f, stiffness = 200f) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("entity" to "By entity", "card" to "By card", "saved" to "Saved").forEachIndexed { i, (id, label) ->
            val selected = mode == id
            val left = i == 0
            // The inner corner springs out to a full pill when selected, and shrinks when not.
            val animatedRadius by animateFloatAsState(
                targetValue = if (selected) 24f else 12f,
                animationSpec = cornerSpring,
                label = "AddPickerCorner"
            )
            val r = animatedRadius.dp
            val shape = when {
                !selected -> RoundedCornerShape(r)
                left -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = r, bottomEnd = r)
                else -> RoundedCornerShape(topStart = r, bottomStart = r, topEnd = 24.dp, bottomEnd = 24.dp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(shape)
                    .background(if (selected) scheme.primary else scheme.surfaceVariant)
                    .clickable { onMode(id) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardCard(
    entity: HAEntity,
    instanceId: String,
    api: HomeAssistantApi,
    edit: Boolean,
    activeCardKey: String?,
    names: MutableMap<String, String>,
    configs: MutableMap<String, Map<String, Any>>,
    allEntities: List<HAEntity>,
    onActiveCardKeyChange: (String?) -> Unit,
    onRefresh: () -> Unit,
    onNavigate: Navigator,
    onOpenSheet: (androidx.compose.ui.layout.LayoutCoordinates, Offset) -> Unit
) {
    val scope = rememberCoroutineScope()
    val domain = entity.entity_id.split(".")[0]
    val context = LocalContext.current
    WidgetRegistry.init(context)
    val gridColumns = LocalDashboardColumns.current
    val cardRowHeight = LocalDashboardRowHeight.current
    val isLandscape = LocalIsLandscape.current
    val cardConfig = configs[instanceId]
    val appState = LocalAppState.current
    val spec = factorySpecForEntity(entity, cardConfig)
    val minSpanY = cardMinimumSpanY(spec, entity, cardConfig, allEntities, context)
    val cardSpanY = cardSpanY(cardConfig, minSpanY, isLandscape)
    val cardSpanX = cardSpanX(cardConfig, gridColumns, isLandscape, cardMinimumSpanX(spec))
    val cardKey = "ent:$instanceId"
    val isActiveCard = edit && activeCardKey == cardKey
    val density = LocalDensity.current

    var measuredHeightPx by remember(cardSpanX, cardSpanY) { mutableIntStateOf(0) }

    val cardMinHeight = run {
        val baseMin = cardHeightForSpan(cardSpanY, cardRowHeight)
        if (measuredHeightPx > 0) {
            val measuredDp = with(density) { measuredHeightPx.toDp().value }
            val widthScale = cardSpanX.toFloat() / gridColumns
            val scaledDp = measuredDp * widthScale
            val snappedSpanY = cardSpanYForHeight(scaledDp, cardRowHeight.value).coerceAtMost(CARD_MAX_SPAN_Y)
            val snappedMin = cardHeightForSpan(snappedSpanY, cardRowHeight)
            maxOf(baseMin, snappedMin)
        } else {
            baseMin
        }
    }

    val cardContentModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = cardMinHeight)

    // A non-edit tap on a normal (DEFAULT-style) entity card opens its state sheet, with
    // the reveal rippling out from the tap point — captured in root coordinates via the
    // card's layout. Button/camera cards keep handling their own inner taps, so the outer
    // gesture stays off for them when not editing.
    val opensSheet = !edit && spec?.style == CardStyle.DEFAULT
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val cardModifier = Modifier
        .clip(RoundedCornerShape(CARD_CORNER_RADIUS))
        .onGloballyPositioned { cardCoords = it }
        .then(
            when {
                opensSheet -> Modifier.pointerInput(cardKey) {
                    detectTapGestures { local ->
                        cardCoords?.let { coords -> onOpenSheet(coords, local) }
                    }
                }
                edit -> Modifier.combinedClickable(
                    onClick = {
                        onActiveCardKeyChange(
                            if (activeCardKey == cardKey) null else cardKey
                        )
                    },
                    onLongClick = {}
                )
                else -> Modifier
            }
        )

    val surfaceOverride = LocalCardSurfaceOverride.current
    val activeBorderModifier = if (isActiveCard) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(CARD_CORNER_RADIUS))
    } else if (surfaceOverride?.border != null) {
        Modifier.border(1.dp, surfaceOverride.border, RoundedCornerShape(CARD_CORNER_RADIUS))
    } else {
        Modifier
    }

    Box(
        modifier = cardModifier
            .fillMaxWidth()
            .heightIn(min = cardMinHeight)
            .onGloballyPositioned { measuredHeightPx = it.size.height }
            .then(activeBorderModifier)
    ) {
        if (spec != null) {
            FactoryCard(
                entity = entity,
                spec = spec,
                api = api,
                scope = scope,
                names = names,
                configs = configs,
                modifier = cardContentModifier,
                allEntities = allEntities,
                onUpdated = onRefresh,
                isEdit = edit,
                configKey = instanceId,
                onCameraNavigate = { camera ->
                    when (camera.source) {
                        CameraSource.FRIGATE -> onNavigate(AppDest.Detail(camera.id, appState.frigateUrl, appState.token))
                        CameraSource.HA -> onNavigate(AppDest.HACamera(camera.id, appState.baseUrl, appState.token))
                    }
                }
            )
        } else {
            val customIconStr = resolveEntityIcon(entity) as? String
            val defaultIconStr = cardTypeIcon(domain)
            
            GenericCard(
                title = names[instanceId] ?: entity.attributes["friendly_name"]?.toString() ?: entity.entity_id,
                state = formatEntityState(entity),
                icon = rememberSymbolPainter(customIconStr ?: defaultIconStr),
                modifier = cardContentModifier
            ) {
                addCustomRow("id") { Text("Entity ID: ${entity.entity_id}") }
                addCustomRow("toggle") {
                    val appState = LocalAppState.current
                    Button(onClick = {
                        appState.callServiceOptimistically(
                            entity.entity_id,
                            domain,
                            "toggle",
                            mapOf("entity_id" to entity.entity_id)
                        ) { ent ->
                            ent.copy(state = if (ent.state == "on") "off" else "on")
                        }
                    }) { Text("Toggle") }
                }
            }
        }


        if (isActiveCard) {
            FilledIconButton(
                onClick = {
                    appState.onConfigEntityIdChange(instanceId)
                    onActiveCardKeyChange(null)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    rememberSymbolPainter("edit"),
                    contentDescription = "Card settings",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Resizing is no longer allowed directly on the dashboard page,
        // it is managed through the Layout section in the card settings / configuration dialog.
    }
}

private fun cardSpanX(
    config: Map<String, Any>?,
    gridColumns: Int = 4,
    isLandscape: Boolean = false,
    minSpanX: Int = CARD_MIN_SPAN_X
): Int {
    val key = if (isLandscape) CARD_SPAN_X_LAND_KEY else CARD_SPAN_X_KEY
    val saved = (config?.get(key) as? Number)?.toInt()
        ?: if (isLandscape) (config?.get(CARD_SPAN_X_KEY) as? Number)?.toInt() else null
    return (saved ?: CARD_MIN_SPAN_X).coerceIn(minSpanX, gridColumns)
}

/** Button-style cards may shrink to a single column; everything else keeps the 2-column minimum. */
private fun cardMinimumSpanX(spec: WidgetSpec?): Int =
    if (spec?.style == CardStyle.BUTTON || spec?.style == CardStyle.CAMERA || spec?.style == CardStyle.CLOCK) 1 else CARD_MIN_SPAN_X

private fun cardSpanY(config: Map<String, Any>?, minSpanY: Int = 1, isLandscape: Boolean = false): Int {
    val defaultKey = if (isLandscape) CARD_HEIGHT_DEFAULT_LAND_KEY else "card_height_default"
    val spanKey = if (isLandscape) CARD_SPAN_Y_LAND_KEY else CARD_SPAN_Y_KEY
    val isDefaultHeight = config?.get(defaultKey) as? Boolean
        ?: config?.get("card_height_default") as? Boolean
        ?: true
    if (isDefaultHeight != false) return minSpanY.coerceIn(1, CARD_MAX_SPAN_Y)
    val saved = (config?.get(spanKey) as? Number)?.toInt()
        ?: if (isLandscape) (config?.get(CARD_SPAN_Y_KEY) as? Number)?.toInt() else null
    return (saved ?: minSpanY).coerceIn(minSpanY.coerceIn(1, CARD_MAX_SPAN_Y), CARD_MAX_SPAN_Y)
}

private fun cardHeightForSpan(
    spanY: Int,
    rowHeight: androidx.compose.ui.unit.Dp = CARD_MIN_HEIGHT,
    gap: androidx.compose.ui.unit.Dp = 13.dp
): androidx.compose.ui.unit.Dp {
    val s = spanY.coerceIn(1, CARD_MAX_SPAN_Y)
    return rowHeight * s.toFloat() + gap * (s - 1).toFloat()
}

private fun cardMinimumSpanY(
    spec: WidgetSpec?,
    entity: HAEntity,
    config: Map<String, Any>?,
    allEntities: List<HAEntity> = emptyList(),
    context: android.content.Context? = null
): Int {
    if (spec == null) return 2
    // Camera cards have no feature rows and may be a single grid row tall.
    if (spec.style == CardStyle.CAMERA) return 1
    // Clock cards scale their text, so a single grid row is enough.
    if (spec.style == CardStyle.CLOCK) return 1
    // Buttons also have no rows, but need a tappable minimum height.
    if (spec.style == CardStyle.BUTTON) return CARD_BUTTON_MIN_HEIGHT
    val removedRows = (config?.get("removed_rows") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
    val visibleCardRows = spec.rows.count { row ->
        if (row.visibility == RowVisibility.WIDGET || row.id in removedRows) return@count false

        // Match the renderer and settings preview when filtering unsupported features.
        if (!evaluateSupported(row.supportedIf, entity)) return@count false

        // Evaluate visibility conditions for built-in features
        if (config != null && allEntities.isNotEmpty() && context != null) {
            val visibilityConds = (config["visibility:${row.id}"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
            if (!visibilityConds.isNullOrEmpty()) {
                val matches = visibilityConds.all { evaluateCondition(it, allEntities, context) }
                if (!matches) return@count false
            }
        }

        val hiddenIfToggle = row.hiddenIfToggle
        if (hiddenIfToggle == null) return@count true
        val toggleVal = config?.get(hiddenIfToggle) as? Boolean
            ?: spec.toggles.find { it.id == hiddenIfToggle }?.defaultValue
            ?: false
        !toggleVal
    }

    val customFeaturesConfig = (config?.get("custom_features") as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
    val visibleCustomRows = customFeaturesConfig.count { cfg ->
        val id = cfg["id"] as? String ?: return@count false
        if (id.isBlank() || id in removedRows) return@count false

        val visibilityConds = (cfg["visibility"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
        if (!visibilityConds.isNullOrEmpty() && allEntities.isNotEmpty() && context != null) {
            val matches = visibilityConds.all { evaluateCondition(it, allEntities, context) }
            if (!matches) return@count false
        }

        val hiddenIfToggle = cfg["hiddenIfToggle"] as? String
        if (hiddenIfToggle != null) {
            val toggleVal = config?.get(hiddenIfToggle) as? Boolean
                ?: spec.toggles.find { it.id == hiddenIfToggle }?.defaultValue
                ?: false
            if (toggleVal) return@count false
        }
        true
    }

    return (3 + (visibleCardRows + visibleCustomRows) * 2).coerceIn(1, CARD_MAX_SPAN_Y)
}

private fun cardSpanYForHeight(
    heightDp: Float,
    rowHeight: Float = CARD_MIN_HEIGHT.value,
    gap: Float = 13f
): Int {
    val s = (heightDp + gap) / (rowHeight + gap)
    return ceil(s).toInt().coerceIn(1, CARD_MAX_SPAN_Y)
}

// ── Factory button-card helpers ───────────────────────────────────────────────
// A standalone button card is a synthetic "button.<uuid>" entity: its whole config
// (label/icon/service/entityId/data/vertical/bgColor + spans) lives in the shared
// card-config map, so FactoryCard/FactoryCardSettings treat it like any other card.

private const val BUTTON_IDS_KEY = "button_card_ids_v2"

private fun loadButtonCardIds(prefs: SharedPreferences): List<String> =
    prefs.getString(BUTTON_IDS_KEY, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

private fun saveButtonCardIds(prefs: SharedPreferences, ids: List<String>) {
    prefs.edit().putString(BUTTON_IDS_KEY, ids.joinToString(",")).apply()
}

private fun syntheticButtonEntity(id: String, config: Map<String, Any>?): HAEntity = HAEntity(
    entity_id = id,
    state = "idle",
    attributes = mapOf(
        "friendly_name" to ((config?.get("label") as? String)?.takeIf { it.isNotBlank() } ?: "Button")
    )
)

// Standalone clock-card helpers. A clock card is a synthetic "clock.card_<uuid>" entity whose
// options (seconds/date/layout) live in the shared card-config map.

private const val CLOCK_IDS_KEY = "clock_card_ids_v1"

private fun loadClockCardIds(prefs: SharedPreferences): List<String> =
    prefs.getString(CLOCK_IDS_KEY, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

private fun saveClockCardIds(prefs: SharedPreferences, ids: List<String>) {
    prefs.edit().putString(CLOCK_IDS_KEY, ids.joinToString(",")).apply()
}

private fun syntheticClockEntity(id: String, name: String?): HAEntity = HAEntity(
    entity_id = id,
    state = "idle",
    attributes = mapOf("friendly_name" to (name?.takeIf { it.isNotBlank() } ?: "Clock"))
)

// Duplicate entity-card instances ("entity_id#<uuid>").
private const val ENTITY_INSTANCE_IDS_KEY = "entity_instance_ids_v1"

private fun loadEntityInstanceIds(prefs: SharedPreferences): List<String> =
    prefs.getString(ENTITY_INSTANCE_IDS_KEY, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

private fun saveEntityInstanceIds(prefs: SharedPreferences, ids: List<String>) {
    prefs.edit().putString(ENTITY_INSTANCE_IDS_KEY, ids.joinToString(",")).apply()
}

/** Default config for a freshly added clock card. */
fun newClockCardConfig(): Map<String, Any> = mapOf(
    CLOCK_SHOW_SECONDS_KEY to false,
    CLOCK_SHOW_DATE_KEY to false,
    "card_span_x" to 2,
    "card_span_y" to 4,
    "card_height_default" to false,
)

/** Default config for a freshly added button card; blank service = act on the target entity. */
private fun newButtonCardConfig(
    label: String = "Action",
    icon: String = "bolt",
    targetEntityId: String = "",
): Map<String, Any> = mapOf(
    "label" to label,
    "icon" to icon,
    "service" to "",
    "entityId" to targetEntityId,
    "vertical" to true,
    "card_span_x" to 2,
    "card_span_y" to 1,
    "card_height_default" to false,
)

// Factory camera-card helpers. A camera card is a synthetic "camera.card_<uuid>" entity whose
// camera source/id/refresh/layout live in the shared card-config map.

private const val CAMERA_MIGRATED_KEY = "camera_cards_migrated_factory_v1"

private fun isFactoryCameraConfig(config: Map<String, Any>?): Boolean =
    config?.containsKey(FACTORY_CAMERA_SOURCE_KEY) == true

internal fun factorySpecForEntity(entity: HAEntity, config: Map<String, Any>?): WidgetSpec? =
    if (isFactoryCameraConfig(config)) CAMERA_FACTORY_SPEC
    else WidgetRegistry.spec(entity.entity_id.substringBefore("."))

private fun loadCameraCardIds(prefs: SharedPreferences): List<String> =
    prefs.getString(FACTORY_CAMERA_IDS_KEY, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

private fun saveCameraCardIds(prefs: SharedPreferences, ids: List<String>) {
    prefs.edit().putString(FACTORY_CAMERA_IDS_KEY, ids.joinToString(",")).apply()
}

private fun syntheticCameraEntity(
    id: String,
    config: Map<String, Any>?,
    savedName: String?
): HAEntity {
    val cameraId = (config?.get(FACTORY_CAMERA_ID_KEY) as? String)?.takeIf { it.isNotBlank() } ?: id
    val friendlyName = savedName?.takeIf { it.isNotBlank() }
        ?: (config?.get(FACTORY_CAMERA_NAME_KEY) as? String)?.takeIf { it.isNotBlank() }
        ?: cameraId
    return HAEntity(
        entity_id = id,
        state = "idle",
        attributes = mapOf("friendly_name" to friendlyName)
    )
}

private fun migrateLegacyCameraCards(
    prefs: SharedPreferences,
    configs: MutableMap<String, Map<String, Any>>,
    names: MutableMap<String, String>
): Boolean {
    if (prefs.getBoolean(CAMERA_MIGRATED_KEY, false)) return false

    val legacyCards = loadCameraCards(prefs)
    if (legacyCards.isEmpty()) {
        prefs.edit().putBoolean(CAMERA_MIGRATED_KEY, true).apply()
        return false
    }

    val ids = loadCameraCardIds(prefs).toMutableList()
    legacyCards.forEach { camera ->
        val entityId = "camera.card_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        configs[entityId] = configs[entityId].orEmpty() + factoryConfigForCameraCard(camera)
        names[entityId] = camera.name
        ids += entityId
        migrateCameraGridPosition(
            prefs = prefs,
            oldKey = "cam:${camera.source.name}:${camera.id}",
            newKey = "ent:$entityId"
        )
    }

    saveCameraCardIds(prefs, ids.distinct())
    prefs.edit().putBoolean(CAMERA_MIGRATED_KEY, true).apply()
    return true
}

private fun migrateCameraGridPosition(
    prefs: SharedPreferences,
    oldKey: String,
    newKey: String
) {
    listOf("dashboard_grid_v1", "dashboard_grid_land_v1").forEach { prefKey ->
        val json = prefs.getString(prefKey, null) ?: return@forEach
        runCatching {
            val obj = org.json.JSONObject(json)
            if (!obj.has(oldKey) || obj.has(newKey)) return@runCatching
            obj.put(newKey, obj.getString(oldKey))
            obj.remove(oldKey)
            prefs.edit().putString(prefKey, obj.toString()).apply()
        }
    }
}

/**
 * One-time migration of legacy standalone button cards (cards_prefs "button_cards_v1") into
 * factory configs. Grid positions move from "btn:<id>" to "ent:button.<id>" in both
 * orientation layouts. Returns true if configs were modified (caller must persist them).
 */
private fun migrateLegacyButtonCards(
    prefs: SharedPreferences,
    configs: MutableMap<String, Map<String, Any>>
): Boolean {
    val json = prefs.getString("button_cards_v1", null) ?: return false
    val ids = mutableListOf<String>()
    runCatching {
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val legacyId = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val entityId = "button.$legacyId"
            val service = obj.optString("service")
            // Pre-mode button cards were always service-based.
            val mode = obj.optString("mode").takeIf { it.isNotBlank() }
                ?: if (service.isNotBlank()) "service" else "entity"
            val cfg = mutableMapOf<String, Any>(
                "label" to obj.optString("label"),
                "icon" to obj.optString("icon").ifBlank { "bolt" },
                "vertical" to obj.optBoolean("vertical", true),
                "card_span_x" to obj.optInt("spanX", 2),
                "card_span_y" to obj.optInt("spanY", 1),
                "card_height_default" to false,
            )
            if (mode == "service") {
                cfg["service"] = service
                cfg["data"] = obj.optString("serviceData")
                cfg["entityId"] = obj.optString("targetEntity")
            } else {
                cfg["service"] = ""
                cfg["entityId"] = obj.optString("entityId")
            }
            if (obj.has("spanXLand")) cfg["card_span_x_land"] = obj.optInt("spanXLand")
            if (obj.has("spanYLand")) {
                cfg["card_span_y_land"] = obj.optInt("spanYLand")
                cfg["card_height_default_land"] = false
            }
            if (obj.has("bgColor") && !obj.isNull("bgColor")) cfg["bgColor"] = obj.optLong("bgColor")
            configs[entityId] = configs[entityId].orEmpty() + cfg
            ids += entityId
        }
    }
    if (ids.isNotEmpty()) {
        listOf("dashboard_grid_v1", "dashboard_grid_land_v1").forEach { key ->
            prefs.getString(key, null)?.let { layoutJson ->
                runCatching {
                    val obj = org.json.JSONObject(layoutJson)
                    val legacyKeys = obj.keys().asSequence().filter { it.startsWith("btn:") }.toList()
                    if (legacyKeys.isNotEmpty()) {
                        legacyKeys.forEach { old ->
                            obj.put("ent:button.${old.removePrefix("btn:")}", obj.getString(old))
                            obj.remove(old)
                        }
                        prefs.edit().putString(key, obj.toString()).apply()
                    }
                }
            }
        }
    }
    prefs.edit()
        .putString(BUTTON_IDS_KEY, (loadButtonCardIds(prefs) + ids).distinct().joinToString(","))
        .remove("button_cards_v1")
        .apply()
    return ids.isNotEmpty()
}
