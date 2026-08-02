package com.feldman.ha.ui.standby

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.api.HomeAssistantWebSocket
import com.feldman.ha.api.getStoredApi
import com.feldman.ha.api.provideHAApi
import com.feldman.ha.data.HAEntity
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.Color
import com.feldman.ha.ui.cards.CardSurface
import com.feldman.ha.ui.cards.LocalCardSurfaceOverride
import com.feldman.ha.ui.cards.LocalPopupsAllowed
import com.feldman.ha.ui.pages.LocalCardsPreferencesName
import com.feldman.ha.ui.pages.newClockCardConfig
import com.feldman.ha.ui.navigation.AppDest
import com.feldman.ha.ui.navigation.AppNavHost
import com.feldman.ha.ui.navigation.AppState
import com.feldman.ha.ui.navigation.LocalAppState
import com.feldman.ha.ui.pages.SetupPage
import com.feldman.ha.widgets.WidgetNetworkGate
import com.feldman.ha.widgets.WidgetRegistry
import com.feldman.motion.MotionLevel
import com.feldman.motion.ThemeRepository
import com.feldman.motion.navigation.DestBackStack
import com.feldman.motion.navigation.rememberMotionNavigationState
import com.feldman.motion.rememberSymbolPainter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import kotlin.math.min

// How often the dashboard pulls a fresh REST snapshot on top of the live WebSocket.
// Mirrors Home's MainActivity.
private const val AUTO_REFRESH_SECONDS = 30

private val emptyApi = object : HomeAssistantApi {
    override suspend fun getEntities() = emptyList<HAEntity>()
    override suspend fun getStates() = emptyList<HAEntity>()
    override suspend fun getState(entityId: String) = HAEntity(entityId, "unknown", emptyMap())
    override suspend fun getServices() = emptyList<Map<String, Any>>()
    override suspend fun callService(domain: String, service: String, body: Map<String, Any>) =
        retrofit2.Response.success(Unit)
    override suspend fun renderTemplate(body: Map<String, Any>) = "".toResponseBody(null)
}

/**
 * The standby screensaver dashboard, backed by Feldman Home's native card stack.
 *
 * This host replicates Home's MainActivity wiring — entity snapshot + WebSocket + optimistic
 * service calls + layout/config persistence — with standby-specific chrome: no top bar unless
 * editing, and a translucent edit/close overlay instead of an app bar.
 */
@Composable
fun HomeStandbyHost(
    onDismiss: () -> Unit,
    isInteractive: Boolean = true,
    // Editing is only offered by preview/standby activities; the real dream is view-only.
    allowEdit: Boolean = true
) {
    val context = LocalContext.current

    // Keep setup available when Home Assistant credentials have not been configured yet.
    // from the Home app's signature-protected bridge, then falls back to the same setup
    // page Home uses.
    var credsVersion by remember { mutableIntStateOf(0) }
    var hasCreds by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(credsVersion) {
        hasCreds = withContext(Dispatchers.IO) {
            HomeAssistantAuth.hasCredentials(context)
        }
    }

    val standbyScheme = dynamicDarkColorScheme(context).copy(
        background = Color.Black,
        surface = Color.Black
    )
    val orientation = remember(context) { HomeStandbyPreferences.orientation(context) }
    val configuration = LocalConfiguration.current
    val rotation = remember(configuration.orientation, orientation) {
        val display = context.resources.displayMetrics
        when {
            orientation == HomeStandbyOrientation.PORTRAIT &&
                display.widthPixels > display.heightPixels -> -90f
            orientation == HomeStandbyOrientation.LANDSCAPE &&
                display.widthPixels < display.heightPixels -> 90f
            else -> 0f
        }
    }
    val cardStyle = remember(context) { HomeStandbyPreferences.cardStyle(context) }
    val cardSurface = remember(cardStyle) {
        when (cardStyle) {
            HomeStandbyCardStyle.FILLED -> CardSurface(
                background = Color.White.copy(alpha = 0.07f),
                buttonBackground = Color.White.copy(alpha = 0.10f)
            )
            HomeStandbyCardStyle.OUTLINED -> CardSurface(
                background = Color.Transparent,
                buttonBackground = Color.White.copy(alpha = 0.07f),
                border = Color.White.copy(alpha = 0.35f)
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (rotation != 0f) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
    ) {
        RotatedStandbyPage(rotation) {
            val baseConfiguration = LocalConfiguration.current
            val effectiveConfiguration = remember(baseConfiguration, rotation) {
                if (rotation == 0f) baseConfiguration else Configuration(baseConfiguration).apply {
                    this.orientation = if (baseConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        Configuration.ORIENTATION_PORTRAIT
                    } else {
                        Configuration.ORIENTATION_LANDSCAPE
                    }
                    screenWidthDp = baseConfiguration.screenHeightDp
                    screenHeightDp = baseConfiguration.screenWidthDp
                }
            }

            MaterialTheme(colorScheme = standbyScheme) {
                CompositionLocalProvider(
                    LocalConfiguration provides effectiveConfiguration,
                    LocalPopupsAllowed provides (rotation == 0f),
                    LocalCardSurfaceOverride provides cardSurface,
                    LocalCardsPreferencesName provides "standby_cards_prefs"
                ) {
                    when (hasCreds) {
                        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        false -> SetupPage(
                            initialBaseUrl = "",
                            initialToken = "",
                            initialFrigateUrl = "",
                            canDismiss = false,
                            onDismiss = {},
                            onSave = { newBaseUrl, newToken, newFrigateUrl, oauthSession ->
                                if (oauthSession != null) {
                                    HomeAssistantAuth.saveOAuthSession(
                                        context,
                                        newBaseUrl,
                                        newFrigateUrl,
                                        oauthSession
                                    )
                                } else {
                                    HomeAssistantAuth.saveManualToken(
                                        context,
                                        newBaseUrl,
                                        newToken,
                                        newFrigateUrl
                                    )
                                }
                                credsVersion++
                            }
                        )
                        true -> StandbyHomeDashboard(
                            onDismiss = onDismiss,
                            isInteractive = isInteractive,
                            allowEdit = allowEdit,
                            credsVersion = credsVersion
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandbyHomeDashboard(
    onDismiss: () -> Unit,
    isInteractive: Boolean,
    allowEdit: Boolean,
    credsVersion: Int
) {
    val context = LocalContext.current
    remember {
        WidgetRegistry.init(context)
        0
    }

    val prefs = remember { context.getSharedPreferences(HomeAssistantAuth.PREFS_NAME, Context.MODE_PRIVATE) }
    val token = remember(credsVersion) { prefs.getString("token", "") ?: "" }
    val baseUrl = remember(credsVersion) { prefs.getString("url", "") ?: "" }
    val frigateUrl = remember(credsVersion) { prefs.getString("frigate_url", "") ?: "" }
    val api = remember(credsVersion) {
        runCatching { getStoredApi(context) ?: provideHAApi(token, baseUrl) }.getOrDefault(emptyApi)
    }

    val cardsPrefs = remember { context.getSharedPreferences("standby_cards_prefs", Context.MODE_PRIVATE) }

    // First run: the standby dashboard starts with a single clock card. Everything else is
    // user-added through the same picker Home uses.
    remember {
        if (!cardsPrefs.getBoolean("standby_clock_seeded_v1", false)) {
            val id = "clock.card_default"
            if (cardsPrefs.getString("clock_card_ids_v1", "").isNullOrBlank()) {
                val type = Types.newParameterizedType(
                    Map::class.java, String::class.java,
                    Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                )
                val adapter = Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(type)
                val existing = cardsPrefs.getString("card_cfg_v2", null)
                    ?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: emptyMap()
                val seeded = existing + (id to (newClockCardConfig() + mapOf(
                    "card_span_x" to 4, "card_span_y" to 8,
                    "card_span_x_land" to 4, "card_span_y_land" to 6,
                    "card_height_default_land" to false,
                )))
                cardsPrefs.edit {
                    putString("clock_card_ids_v1", id)
                    putString("card_cfg_v2", adapter.toJson(seeded))
                    // Fresh store: skip the legacy 2->4 column span migration.
                    putBoolean("span_migrated_4col", true)
                }
            }
            cardsPrefs.edit { putBoolean("standby_clock_seeded_v1", true) }
        }
        0
    }

    val cfgAdapter = remember {
        val type = Types.newParameterizedType(
            Map::class.java, String::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(type)
    }
    val nameAdapter = remember {
        Moshi.Builder().build().adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        )
    }

    val configs = remember {
        mutableStateMapOf<String, Map<String, Any>>().apply {
            cardsPrefs.getString("card_cfg_v2", null)
                ?.let { runCatching { cfgAdapter.fromJson(it) }.getOrNull() }
                ?.forEach { (id, map) -> put(id, map) }
        }
    }
    fun saveConfigs() {
        cardsPrefs.edit { putString("card_cfg_v2", cfgAdapter.toJson(configs)) }
    }
    val names = remember {
        mutableStateMapOf<String, String>().apply {
            cardsPrefs.getString("entity_names", null)
                ?.let { runCatching { nameAdapter.fromJson(it) }.getOrNull() }
                ?.forEach { (id, name) -> put(id, name) }
        }
    }
    fun saveNames() {
        cardsPrefs.edit { putString("entity_names", nameAdapter.toJson(names)) }
    }

    val savedIds = cardsPrefs.getString("selected_entities", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val savedIdsLand = cardsPrefs.getString("selected_entities_land", null)?.split(",")?.filter { it.isNotBlank() } ?: savedIds

    var all by remember { mutableStateOf<List<HAEntity>>(emptyList()) }
    val selected = remember { mutableStateListOf<HAEntity>() }
    val selectedLandscape = remember { mutableStateListOf<HAEntity>() }
    var edit by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    val mainScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pendingOptimisticEntities = remember { mutableStateMapOf<String, OptimisticEntityPatch>() }
    var optimisticSequence by remember { mutableLongStateOf(0L) }

    var activeCardKey by remember { mutableStateOf<String?>(null) }
    var configEntityId by remember { mutableStateOf<String?>(null) }

    fun mergeOptimisticEntity(entity: HAEntity): HAEntity {
        val patch = pendingOptimisticEntities[entity.entity_id] ?: return entity
        return if (patch.isConfirmedBy(entity)) {
            pendingOptimisticEntities.remove(entity.entity_id)
            entity
        } else {
            patch.applyTo(entity)
        }
    }

    fun updateEntityEverywhere(entityId: String, update: (HAEntity) -> HAEntity) {
        val selectedIdx = selected.indexOfFirst { it.entity_id == entityId }
        if (selectedIdx != -1) selected[selectedIdx] = update(selected[selectedIdx])

        val selectedLandscapeIdx = selectedLandscape.indexOfFirst { it.entity_id == entityId }
        if (selectedLandscapeIdx != -1) {
            selectedLandscape[selectedLandscapeIdx] = update(selectedLandscape[selectedLandscapeIdx])
        }

        val allIdx = all.indexOfFirst { it.entity_id == entityId }
        if (allIdx != -1) {
            all = all.toMutableList().also { it[allIdx] = update(it[allIdx]) }
        }
    }

    suspend fun refreshDashboardSnapshot(entityOrder: List<String>) {
        val states = withContext(Dispatchers.IO) { api.getStates() }
            .map(::mergeOptimisticEntity)
        val portraitIds = entityOrder.ifEmpty { selected.map { it.entity_id } }
        val landscapeIds = if (selectedLandscape.isEmpty()) portraitIds
            else selectedLandscape.map { it.entity_id }
        all = states
        if (portraitIds.isNotEmpty()) {
            selected.clear()
            selected.addAll(states.filter { it.entity_id in portraitIds }.sortedBy { portraitIds.indexOf(it.entity_id) })
        }
        if (landscapeIds.isNotEmpty()) {
            selectedLandscape.clear()
            selectedLandscape.addAll(states.filter { it.entity_id in landscapeIds }.sortedBy { landscapeIds.indexOf(it.entity_id) })
        }
    }

    fun saveLayout() {
        cardsPrefs.edit {
            putString("selected_entities", selected.joinToString(",") { it.entity_id })
            putString("selected_entities_land", selectedLandscape.joinToString(",") { it.entity_id })
        }
    }

    val onAddEntity: (HAEntity) -> Unit = { entity ->
        if (selected.none { it.entity_id == entity.entity_id }) selected.add(entity)
        if (selectedLandscape.none { it.entity_id == entity.entity_id }) selectedLandscape.add(entity)
        saveLayout()
    }

    val onRemoveEntity: (HAEntity) -> Unit = { entity ->
        selected.removeAll { it.entity_id == entity.entity_id }
        selectedLandscape.removeAll { it.entity_id == entity.entity_id }
        saveLayout()
    }

    fun retryLoad() {
        mainScope.launch {
            loading = true
            loadFailed = false
            var attempts = 0
            while (isActive) {
                attempts++
                try {
                    refreshDashboardSnapshot(savedIds)
                    break
                } catch (e: Exception) {
                    if (attempts == 1) continue
                    if (!WidgetNetworkGate.isNetworkFailure(e) || attempts >= 5) {
                        loadFailed = true
                        break
                    }
                    delay(2_000L)
                }
            }
            loading = false
        }
    }

    val callServiceOptimistically: (String, String, String, Map<String, Any>, (HAEntity) -> HAEntity) -> Unit = { entityId, domain, service, body, updateFn ->
        val baseEntity = all.firstOrNull { it.entity_id == entityId }
            ?: selected.firstOrNull { it.entity_id == entityId }
            ?: selectedLandscape.firstOrNull { it.entity_id == entityId }
        val patchBaseEntity = pendingOptimisticEntities[entityId]?.originalEntity() ?: baseEntity
        val optimisticEntity = baseEntity?.let(updateFn)
        val patch = if (patchBaseEntity != null && optimisticEntity != null) {
            OptimisticEntityPatch.from(++optimisticSequence, patchBaseEntity, optimisticEntity)
        } else {
            null
        }

        if (patch != null && patch.hasChanges) {
            pendingOptimisticEntities[entityId] = patch
            updateEntityEverywhere(entityId) { patch.applyTo(it) }
        }

        mainScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.callService(domain, service, body)
                }
                if (!response.isSuccessful) {
                    throw HttpException(response)
                }
            } catch (e: Exception) {
                if (patch != null && pendingOptimisticEntities[entityId]?.id == patch.id) {
                    pendingOptimisticEntities.remove(entityId)
                    updateEntityEverywhere(entityId) { patch.revertIn(it) }
                }
                val entityName = names[entityId] ?: baseEntity?.attributes?.get("friendly_name")?.toString() ?: entityId
                snackbarHostState.showSnackbar(
                    message = "Failed to control $entityName: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (selectedLandscape.isEmpty() && savedIdsLand.isNotEmpty()) {
            selectedLandscape.addAll(savedIdsLand.map { id -> HAEntity(id, "unknown", emptyMap()) })
        }
        var attempts = 0
        while (isActive) {
            attempts++
            try {
                loadFailed = false
                refreshDashboardSnapshot(savedIds)
                break
            } catch (e: Exception) {
                if (attempts == 1) continue
                if (!WidgetNetworkGate.isNetworkFailure(e) || attempts >= 8) {
                    loadFailed = true
                    break
                }
                delay(2_000L)
            }
        }
        loading = false
    }

    // ── Live updates over the Home Assistant WebSocket, bound to the visible lifecycle ──
    val wsRef = remember { mutableStateOf<HomeAssistantWebSocket?>(null) }
    val subscribedEntityIds by remember {
        derivedStateOf {
            val base = selected.map { it.entity_id }
            val buttonCardIds = configs.keys.filter { it.startsWith("button.") }
            // Duplicate entity-card instances ("entity_id#<uuid>") subscribe their target.
            val instanceTargets = configs.keys.filter { it.contains("#") }.map { it.substringBefore("#") }
            val extras = getExtraEntityIds(base + buttonCardIds, configs)
            (base + instanceTargets + extras).distinct().filterNot { it.startsWith("clock.card_") }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        onDispose {
            wsRef.value?.disconnect()
            wsRef.value = null
        }
    }

    LaunchedEffect(baseUrl, token, loading, lifecycleOwner) {
        val hasAuth = token.isNotBlank() || HomeAssistantAuth.hasCredentials(context)
        if (!hasAuth || baseUrl.isBlank() || loading) {
            wsRef.value?.disconnect()
            wsRef.value = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var backoffMs = 2_000L
            try {
                while (isActive) {
                    var activeWs: HomeAssistantWebSocket? = null
                    var collectJob: Job? = null
                    try {
                        wsRef.value?.disconnect()
                        val wsToken = withContext(Dispatchers.IO) {
                            HomeAssistantAuth.currentAccessToken(context)
                        }
                        val ws = HomeAssistantWebSocket(
                            url = baseUrl,
                            token = wsToken,
                            initialEntityIds = subscribedEntityIds,
                            onFailure = { },
                            label = "Standby"
                        )
                        activeWs = ws
                        wsRef.value = ws
                        collectJob = launch {
                            launch {
                                ws.connectedEvents.collect {
                                    try {
                                        refreshDashboardSnapshot(selected.map { it.entity_id })
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            launch {
                                ws.stateChanges.collect { updated ->
                                    val merged = mergeOptimisticEntity(updated)
                                    updateEntityEverywhere(updated.entity_id) { merged }
                                }
                            }
                        }
                        ws.connect()
                        var connectCheckTime = 0
                        while (isActive && !ws.isConnected && connectCheckTime < 8_000 && wsRef.value === ws) {
                            delay(500)
                            connectCheckTime += 500
                        }
                        if (ws.isConnected) {
                            backoffMs = 2_000L
                            while (isActive && ws.isConnected) delay(3_000)
                        }
                    } finally {
                        collectJob?.cancel()
                        activeWs?.disconnect()
                        if (wsRef.value === activeWs) wsRef.value = null
                    }
                    if (isActive) {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                    }
                }
            } finally {
                wsRef.value?.disconnect()
                wsRef.value = null
            }
        }
    }

    LaunchedEffect(subscribedEntityIds) {
        wsRef.value?.updateEntities(subscribedEntityIds)
    }

    var refreshCountdown by remember { mutableIntStateOf(AUTO_REFRESH_SECONDS) }
    LaunchedEffect(lifecycleOwner, loadFailed) {
        if (!loadFailed) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                refreshCountdown = AUTO_REFRESH_SECONDS
                while (refreshCountdown > 0) {
                    delay(1_000L)
                    refreshCountdown--
                }
                try {
                    refreshDashboardSnapshot(selected.map { it.entity_id }.ifEmpty { savedIds })
                    loadFailed = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    // ── Navigation: the same destinations Home registers, without its bottom bar ──
    val backStack = remember { DestBackStack(AppDest.Dashboard()) }
    val destinations = remember(frigateUrl, token, baseUrl) {
        listOf(
            AppDest.Dashboard(),
            AppDest.Grid(frigateUrl, token),
            AppDest.Detail("", frigateUrl, token),
            AppDest.HACamera("", baseUrl, token),
            AppDest.Settings,
            AppDest.ThemeSettings,
            AppDest.ConnectionSettings,
            AppDest.DashboardSettings,
            AppDest.ScreensaverSettings,
            AppDest.CameraSettings,
            AppDest.PhoneSettings,
            AppDest.CustomFeatureDetail("", null)
        )
    }
    val currentScreen = backStack.backStack.lastOrNull() ?: AppDest.Dashboard()
    val motionLevel by remember(context) { ThemeRepository(context).motionLevel }
        .collectAsState(initial = MotionLevel.MEDIUM)
    val navigationState = rememberMotionNavigationState(
        backStack = backStack.backStack,
        topLevelDestinations = emptyList(),
        motionLevel = motionLevel,
        showsBottomBar = { false }
    )

    val appState = AppState(
        api = api,
        selected = selected,
        selectedLandscape = selectedLandscape,
        all = all,
        loading = loading,
        edit = edit,
        onEditToggle = {
            edit = !edit
            if (!edit) activeCardKey = null
        },
        names = names,
        configs = configs as MutableMap<String, Map<String, Any>>,
        saveLayout = ::saveLayout,
        saveConfigs = ::saveConfigs,
        saveNames = ::saveNames,
        scaffoldPadding = PaddingValues(0.dp),
        hostManagesDashboardBackground = true,
        showExpressiveDashboardSurface = false,
        activeCardKey = activeCardKey,
        onActiveCardKeyChange = { activeCardKey = it },
        configEntityId = configEntityId,
        onConfigEntityIdChange = { configEntityId = it },
        onShowSettings = { backStack.navigate(AppDest.Settings) },
        onFullScreenChange = { },
        baseUrl = baseUrl,
        token = token,
        tokenProvider = { HomeAssistantAuth.currentAccessToken(context) },
        frigateUrl = frigateUrl,
        onSaveSettings = { newBaseUrl, newToken, newFrigateUrl, oauthSession ->
            if (oauthSession != null) {
                HomeAssistantAuth.saveOAuthSession(context, newBaseUrl, newFrigateUrl, oauthSession)
            } else {
                HomeAssistantAuth.saveManualToken(context, newBaseUrl, newToken, newFrigateUrl)
            }
            backStack.pop()
            retryLoad()
        },
        callServiceOptimistically = callServiceOptimistically,
        loadFailed = loadFailed,
        refreshCountdown = refreshCountdown,
        onRetryLoad = ::retryLoad,
        onAddEntity = onAddEntity,
        onRemoveEntity = onRemoveEntity,
        // Tapping empty dashboard space exits the screensaver (cards keep their own taps).
        onDashboardBackgroundTap = { onDismiss() },
        // Standby chrome: never the Home top bar or page tabs; while editing, Dashboard's
        // floating toolbar carries the done/remove/configure/settings actions. Card settings
        // render inline so they follow the screensaver's visual rotation.
        showDashboardTopBar = false,
        showDashboardPageTabs = false,
        inlineCardSettings = true,
    )

    // NavDisplay requires a NavigationEventDispatcherOwner. Activities normally provide it,
    // but the DreamService (and hosts on older androidx.activity) don't — supply a local one.
    val navEventOwner = LocalNavigationEventDispatcherOwner.current
        ?: remember {
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalAppState provides appState,
            LocalNavigationEventDispatcherOwner provides navEventOwner
        ) {
            // adaptive=false: the DreamService is not a UI context, so the list-detail
            // strategy's WindowInfoTracker call would crash the dream on every composition.
            AppNavHost(
                backStack = backStack,
                destinations = destinations,
                navigationState = navigationState,
                dashboardBackgroundColor = Color.Black,
                modifier = Modifier.fillMaxSize(),
                adaptive = false
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        )

        // Standby chrome: while not editing there is no app bar at all; a translucent
        // overlay in the corner enters edit mode (which brings up the regular Home top
        // bar with remove/configure/settings) or dismisses the screensaver.
        val onDashboard = currentScreen is AppDest.Dashboard
        // The exit (X) control stays visible even while an entity sheet is open, so standby
        // can always be left in one tap; only the edit pencil hides under the sheet.
        if (isInteractive && !edit && onDashboard && configEntityId == null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                    .padding(12.dp)
            ) {
                val overlayColors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (allowEdit) {
                    FilledTonalIconButton(
                        onClick = { edit = true },
                        colors = overlayColors,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(rememberSymbolPainter("edit"), contentDescription = "Edit cards", modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                FilledTonalIconButton(
                    onClick = onDismiss,
                    colors = overlayColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(rememberSymbolPainter("close"), contentDescription = "Exit standby", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ── Copies of Home MainActivity's private helpers (kept in sync manually — small and stable) ──

@Composable
private fun RotatedStandbyPage(rotation: Float, content: @Composable () -> Unit) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val rotating = rotation != 0f
        val childConstraints = if (!rotating) constraints else Constraints(
            minWidth = 0,
            minHeight = 0,
            maxWidth = height,
            maxHeight = width
        )
        val placeables = subcompose("standby", content).map { it.measure(childConstraints) }
        val childWidth = placeables.maxOfOrNull { it.width } ?: 0
        val childHeight = placeables.maxOfOrNull { it.height } ?: 0
        val scale = if (!rotating || childWidth == 0 || childHeight == 0) 1f else min(
            width / childHeight.toFloat(),
            height / childWidth.toFloat()
        )

        layout(width, height) {
            val centerX = width / 2
            val centerY = height / 2
            placeables.forEach { placeable ->
                placeable.placeWithLayer(
                    centerX - placeable.width / 2,
                    centerY - placeable.height / 2
                ) {
                    rotationZ = rotation
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center
                }
            }
        }
    }
}

private fun getExtraEntityIds(selectedIds: List<String>, configs: Map<String, Map<String, Any>>): Set<String> {
    val extras = mutableSetOf<String>()
    val regex = Regex("\\{([^}.]+(?:\\.[^}.]+)+)\\}")
    val blacklist = setOf("state", "name", "attributes", "current", "remaining")

    selectedIds.forEach { parentId ->
        val cfg = configs[parentId] ?: return@forEach

        val stateTemplate = cfg["state_template"] as? String
        if (!stateTemplate.isNullOrBlank()) {
            regex.findAll(stateTemplate).forEach { match ->
                val path = match.groupValues[1].trim()
                val parts = path.split(".")
                if (parts.size >= 2 && parts[0] !in blacklist) {
                    extras.add("${parts[0]}.${parts[1]}")
                }
            }
        }

        val customFeatures = (cfg["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
        customFeatures.forEach { feature ->
            val targetEntity = feature["targetEntity"] as? String
            if (!targetEntity.isNullOrBlank()) {
                extras.add(targetEntity)
            }
            val targets = (feature["targets"] as? List<*>)?.filterIsInstance<Map<String, String>>().orEmpty()
            targets.forEach { tgt ->
                if (tgt["type"] == "entity") {
                    val valStr = tgt["value"]
                    if (!valStr.isNullOrBlank()) {
                        extras.add(valStr)
                    }
                }
            }
            val valueTemplate = feature["valueTemplate"] as? String
            if (!valueTemplate.isNullOrBlank()) {
                regex.findAll(valueTemplate).forEach { match ->
                    val path = match.groupValues[1].trim()
                    val parts = path.split(".")
                    if (parts.size >= 2 && parts[0] !in blacklist) {
                        extras.add("${parts[0]}.${parts[1]}")
                    }
                }
            }
            val visibility = (feature["visibility"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            visibility.forEach { cond ->
                collectEntityIdsFromCondition(cond, extras)
            }
        }

        val buttonTarget = cfg["entityId"] as? String
        if (!buttonTarget.isNullOrBlank()) {
            extras.add(buttonTarget)
        }
        val labelBlocks = (cfg["label_blocks"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
        labelBlocks.forEach { block ->
            val rules = (block["rules"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            rules.forEach { rule ->
                val conds = (rule["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                conds.forEach { collectEntityIdsFromCondition(it, extras) }
            }
        }
    }

    return extras
}

private fun collectEntityIdsFromCondition(condition: Map<String, Any>, out: MutableSet<String>) {
    val condType = condition["condition"] as? String ?: return
    when (condType) {
        "state", "numeric_state" -> {
            val entity = condition["entity"] as? String
            if (!entity.isNullOrBlank()) {
                out.add(entity)
            }
        }
        "and", "or", "not" -> {
            val subConds = (condition["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            subConds.forEach { collectEntityIdsFromCondition(it, out) }
        }
    }
}

private data class OptimisticEntityPatch(
    val id: Long,
    private val original: HAEntity,
    private val optimistic: HAEntity,
    private val changedState: Boolean,
    private val changedAttributeKeys: Set<String>
) {
    val hasChanges: Boolean = changedState || changedAttributeKeys.isNotEmpty()

    fun originalEntity(): HAEntity = original

    fun applyTo(entity: HAEntity): HAEntity {
        if (!hasChanges) return entity
        val attrs = entity.attributes.toMutableMap()
        changedAttributeKeys.forEach { key ->
            if (optimistic.attributes.containsKey(key)) {
                optimistic.attributes[key]?.let { attrs[key] = it } ?: attrs.remove(key)
            } else {
                attrs.remove(key)
            }
        }
        return entity.copy(
            state = if (changedState) optimistic.state else entity.state,
            attributes = attrs
        )
    }

    fun revertIn(entity: HAEntity): HAEntity {
        if (!hasChanges) return entity
        val attrs = entity.attributes.toMutableMap()
        changedAttributeKeys.forEach { key ->
            if (original.attributes.containsKey(key)) {
                original.attributes[key]?.let { attrs[key] = it } ?: attrs.remove(key)
            } else {
                attrs.remove(key)
            }
        }
        return entity.copy(
            state = if (changedState) original.state else entity.state,
            attributes = attrs
        )
    }

    fun isConfirmedBy(entity: HAEntity): Boolean {
        if (changedState && entity.state != optimistic.state) return false
        return changedAttributeKeys.all { key ->
            if (optimistic.attributes.containsKey(key)) {
                entity.attributes[key] == optimistic.attributes[key]
            } else {
                !entity.attributes.containsKey(key)
            }
        }
    }

    companion object {
        fun from(id: Long, original: HAEntity, optimistic: HAEntity): OptimisticEntityPatch {
            val attrKeys = original.attributes.keys + optimistic.attributes.keys
            val changedAttrs = attrKeys.filterTo(mutableSetOf()) { key ->
                original.attributes.containsKey(key) != optimistic.attributes.containsKey(key) ||
                    original.attributes[key] != optimistic.attributes[key]
            }
            return OptimisticEntityPatch(
                id = id,
                original = original,
                optimistic = optimistic,
                changedState = original.state != optimistic.state,
                changedAttributeKeys = changedAttrs
            )
        }
    }
}
