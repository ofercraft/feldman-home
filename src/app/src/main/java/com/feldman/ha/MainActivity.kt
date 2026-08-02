package com.feldman.ha

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.api.HomeAssistantWebSocket
import com.feldman.ha.api.getStoredApi
import com.feldman.ha.api.provideHAApi
import com.feldman.ha.data.HAEntity
import com.feldman.ha.widgets.WidgetNetworkGate
import com.feldman.ha.widgets.WidgetRegistry
import com.feldman.ha.mobile.MobileAppRegistration
import com.feldman.ha.mobile.MobileAppSensorUpdateWorker
import com.feldman.ha.mobile.MobileAppSensors
import com.feldman.ha.ui.navigation.AppDest
import com.feldman.ha.ui.navigation.AppNavHost
import com.feldman.ha.ui.navigation.AppState
import com.feldman.ha.ui.navigation.DashboardEditAction
import com.feldman.ha.ui.navigation.DashboardEditActionRequest
import com.feldman.ha.ui.navigation.LocalAppState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.feldman.motion.MotionLevel
import com.feldman.motion.ThemeRepository
import com.feldman.motion.appbars.FloatingBottomAppBar
import com.feldman.motion.appbars.MotionBottomBarHost
import com.feldman.motion.appbars.FloatingToolbarDefaults
import com.feldman.motion.appbars.VerticalFloatingToolbar
import com.feldman.motion.navigation.DestBackStack
import com.feldman.motion.navigation.rememberMotionNavigationState
import com.feldman.ha.ui.pages.SetupPage
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import retrofit2.HttpException
import androidx.core.content.edit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.feldman.motion.rememberSymbolPainter
import com.feldman.ha.ui.cards.ExpressiveCanvasSetting
import com.feldman.ha.ui.cards.FactoryEntityStateSheet
import com.feldman.ha.ui.pages.SHEET_BACKGROUND_BLUR
import com.feldman.ha.ui.pages.factorySpecForEntity
import com.feldman.ha.ui.pages.rememberDashboardEntitySheetState
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.math.max
import kotlin.math.sqrt

// How often the dashboard pulls a fresh REST snapshot on top of the live WebSocket.
private const val AUTO_REFRESH_SECONDS = 30
private const val EDIT_REVEAL_SOLID_FRACTION = 0.7f

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_ENTITY_SHEET = "open_entity_sheet"
    }

    private var api: HomeAssistantApi = object : HomeAssistantApi {
        override suspend fun getEntities() = emptyList<HAEntity>()
        override suspend fun getStates() = emptyList<HAEntity>()
        override suspend fun getState(entityId: String) = HAEntity(entityId, "unknown", emptyMap())
        override suspend fun getServices() = emptyList<Map<String, Any>>()
        override suspend fun callService(domain: String, service: String, body: Map<String, Any>) = retrofit2.Response.success(Unit)
        override suspend fun renderTemplate(body: Map<String, Any>) =
            "".toResponseBody(null)
    }
    private var showSetupDialog by mutableStateOf(false)
    private var widgetSheetEntityId by mutableStateOf<String?>(null)
    private var widgetSheetRequestId by mutableIntStateOf(0)

    private fun handleIntent(intent: Intent?) {
        val entityId = intent?.getStringExtra(EXTRA_OPEN_ENTITY_SHEET)?.takeIf { it.isNotBlank() }
            ?: return
        widgetSheetEntityId = entityId
        widgetSheetRequestId++
    }

    private fun registerMobileAppInBackground() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                MobileAppRegistration.registerDevice(this@MainActivity)
                MobileAppSensors.registerAllSensors(this@MainActivity)
                MobileAppSensors.updateEnabledSensors(this@MainActivity)
                MobileAppSensorUpdateWorker.schedule(this@MainActivity)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        WidgetRegistry.init(this)

        val prefs = getSharedPreferences("ha_prefs", MODE_PRIVATE)
        val token = prefs.getString("token", "") ?: ""
        val baseUrl = prefs.getString("url", "http://homeassistant.local:8123/api/") ?: ""
        val frigateUrl = prefs.getString("frigate_url", "") ?: ""
        showSetupDialog = !HomeAssistantAuth.hasCredentials(this)

        if (!showSetupDialog) {
            try {
                api = getStoredApi(this) ?: provideHAApi(token, baseUrl)
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { api.getStates() }.onFailure { e ->
                        if (e is HttpException && (e.code() == 401 || e.code() == 404)) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Login failed: ${e.message()}", Toast.LENGTH_LONG).show()
                                showSetupDialog = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Network error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                showSetupDialog = true
            }
        }

        setContent {
            // App-wide theme from the motion button library (dynamic color + googleSans
            // typography with proper Material sizes). This also fixes the tiny settings top-bar
            // title, which was caused by the old home AppTypography defining titleLarge with no
            // fontSize and motion's AppTheme inheriting it.
            com.feldman.motion.AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Mirror the app's resolved Material color scheme into ha_prefs so home-screen
                    // widgets render with the exact same colors as the in-app cards. These map
                    // 1:1 to the card color roles in FactoryCard.appWidgetColors().
                    val cs = MaterialTheme.colorScheme
                    val cardBg = com.feldman.ha.ui.cards.cardBackgroundColor()
                    val cardButtonBg = com.feldman.ha.ui.cards.cardButtonBackgroundColor()
                    LaunchedEffect(
                        cardBg, cardButtonBg, cs.onSurface, cs.onSurfaceVariant, cs.primary
                    ) {
                        prefs.edit {
                            putInt("widget_scheme_background", cardBg.toArgb())
                            putInt("widget_scheme_on_background", cs.onSurface.toArgb())
                            putInt("widget_scheme_on_background_variant", cs.onSurfaceVariant.toArgb())
                            putInt("widget_scheme_button", cardButtonBg.toArgb())
                            putInt("widget_scheme_button_on", cs.onSurfaceVariant.toArgb())
                            putInt("widget_scheme_header_icon", cs.primary.toArgb())
                            putBoolean("widget_scheme_present", true)
                        }
                        // Force every existing widget to recompose with the new colors/code.
                        // (Android does not re-render placed widgets on APK update by itself.)
                        com.feldman.ha.widgets.WidgetRefreshScheduler.updateAllProviders(this@MainActivity)
                    }

                    val backStack = remember { DestBackStack(AppDest.Dashboard()) }
                    LaunchedEffect(widgetSheetRequestId) {
                        if (widgetSheetEntityId != null) {
                            backStack.navigateTop(AppDest.Dashboard())
                        }
                    }
                    val context = LocalContext.current
                    val cards_prefs = remember { context.getSharedPreferences("cards_prefs", MODE_PRIVATE) }

                    if (showSetupDialog) {
                        SetupPage(
                            initialBaseUrl = baseUrl,
                            initialToken = token,
                            initialFrigateUrl = frigateUrl,
                            canDismiss = false,
                            onDismiss = {
                                showSetupDialog = false
                            },
                            onSave = { newBaseUrl, newToken, newFrigateUrl, oauthSession ->
                                if (oauthSession != null) {
                                    HomeAssistantAuth.saveOAuthSession(this@MainActivity, newBaseUrl, newFrigateUrl, oauthSession)
                                } else {
                                    HomeAssistantAuth.saveManualToken(this@MainActivity, newBaseUrl, newToken, newFrigateUrl)
                                }
                                registerMobileAppInBackground()
                                showSetupDialog = false
                                recreate()
                            }
                        )
                        return@Surface
                    }

                    val jsonAdapter = Moshi.Builder().build()
                        .adapter<Map<String, Set<String>>>(Types.newParameterizedType(Map::class.java, String::class.java, Set::class.java))
                    val cfgAdapter = Moshi.Builder().build()
                        .adapter<Map<String, Set<String>>>(
                            Types.newParameterizedType(
                                Map::class.java,
                                String::class.java,
                                Types.newParameterizedType(Set::class.java, String::class.java)
                            )
                        )

                    val nameAdapter = Moshi.Builder().build()
                        .adapter<Map<String, String>>(
                            Types.newParameterizedType(
                                Map::class.java,
                                String::class.java,
                                String::class.java
                            )
                        )

                    val configs = remember {
                        mutableStateMapOf<String, Map<String, Any>>().apply {
                            val type = Types.newParameterizedType(Map::class.java, String::class.java, Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
                            cards_prefs.getString("card_cfg_v2", null)
                                ?.let {
                                    Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(type).fromJson(it)
                                }
                                ?.forEach { (id, map) -> put(id, map) }

                            // One-time migration: the dashboard grid went from 2 to 4 columns.
                            // Scale saved card widths so existing layouts are preserved
                            // (old 1 = half -> 2, old 2 = full -> 4).
                            if (!cards_prefs.getBoolean("span_migrated_4col", false)) {
                                keys.toList().forEach { id ->
                                    val map = this[id] ?: return@forEach
                                    val spanX = (map["card_span_x"] as? Number)?.toInt() ?: return@forEach
                                    this[id] = map.toMutableMap().apply {
                                        put("card_span_x", (spanX * 2).coerceAtMost(4))
                                    }
                                }
                                cards_prefs.edit {
                                    putString("card_cfg_v2", Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(type).toJson(this@apply))
                                    putBoolean("span_migrated_4col", true)
                                }
                            }
                        }
                    }
                    fun saveConfigs() {
                        val type = Types.newParameterizedType(Map::class.java, String::class.java, Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
                        cards_prefs.edit { putString("card_cfg_v2", Moshi.Builder().build().adapter<Map<String, Map<String, Any>>>(type).toJson(configs)) }
                    }
                    val savedIds = cards_prefs.getString("selected_entities", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                    val savedIdsLand = cards_prefs.getString("selected_entities_land", null)?.split(",")?.filter { it.isNotBlank() } ?: savedIds

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
                        cards_prefs.edit {
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
                    
                    val names = remember {
                        mutableStateMapOf<String, String>().apply {
                            cards_prefs.getString("entity_names", null)
                                ?.let { nameAdapter.fromJson(it) }
                                ?.forEach { (id, name) -> put(id, name) }
                        }
                    }
                    fun saveNames() {
                        cards_prefs.edit { putString("entity_names", nameAdapter.toJson(names)) }
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
                                    // First failure: silently retry once, immediately.
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
                        // Seed landscape list from prefs before first network hit.
                        if (selectedLandscape.isEmpty() && savedIdsLand.isNotEmpty()) {
                            selectedLandscape.addAll(savedIdsLand.map { id ->
                                HAEntity(id, "unknown", emptyMap())
                            })
                        }
                        // Initial load via REST — retry silently on transient network errors
                        // (DNS isn't always ready within the first few hundred ms after launch).
                        var attempts = 0
                        while (isActive) {
                            attempts++
                            try {
                                loadFailed = false
                                refreshDashboardSnapshot(savedIds)
                                break
                            } catch (e: Exception) {
                                // First failure: silently retry once, immediately. A single instant
                                // retry recovers most cold-start hiccups (DNS not ready yet, token
                                // refresh in flight) before we show anything or start backing off.
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

                    val wsRef = remember { mutableStateOf<HomeAssistantWebSocket?>(null) }
                    val subscribedEntityIds by remember {
                        derivedStateOf {
                            val base = selected.map { it.entity_id }
                            // Standalone button cards are synthetic "button.<uuid>" entities; they
                            // aren't subscribed themselves, but their configs reference real
                            // entities (tap target, conditional-label rules) that must be live.
                            val buttonCardIds = configs.keys.filter { it.startsWith("button.") }
                            // Duplicate entity-card instances ("entity_id#<uuid>") subscribe
                            // their target entity.
                            val instanceTargets = configs.keys.filter { it.contains("#") }.map { it.substringBefore("#") }
                            val extras = getExtraEntityIds(base + buttonCardIds, configs)
                            (base + instanceTargets + extras).distinct()
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
                        // Bind the dashboard WebSocket to the visible (STARTED) lifecycle.
                        // repeatOnLifecycle restarts this loop every time the activity becomes
                        // visible and cancels it on stop. Previously the effect was keyed on a
                        // lifecycle-driven Compose boolean (dashboardVisible); while recomposition
                        // is paused in the background the STOP->START flip could be coalesced away,
                        // so the loop never relaunched and the socket stayed dead after exit+reopen.
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
                                            label = "UI"
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
                                                        // Keep listening; periodic reconnect attempts will try again.
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
                                        try {
                                            ws.connect()
                                        } catch (e: Throwable) {
                                            android.util.Log.e("MainActivity", "Failed to connect WebSocket: ${e.message}")
                                        }
                                        // Wait for connection (up to 8 seconds) or failure
                                        var connectCheckTime = 0
                                        while (isActive && !ws.isConnected && connectCheckTime < 8_000 && wsRef.value === ws) {
                                            delay(500)
                                            connectCheckTime += 500
                                        }
                                        if (ws.isConnected) {
                                            backoffMs = 2_000L  // reset on successful connect
                                            // Poll often so a cloud-relay drop (Nabu Casa closes the
                                            // socket, or DNS blips) is noticed within ~3s, not ~10s.
                                            while (isActive && ws.isConnected) delay(3_000)
                                        }
                                    } finally {
                                        collectJob?.cancel()
                                        activeWs?.disconnect()
                                        if (wsRef.value === activeWs) wsRef.value = null
                                    }
                                    if (isActive) {
                                        delay(backoffMs)
                                        // Cap low: this is a live dashboard, so recover fast rather
                                        // than letting the backoff crawl out to half a minute.
                                        backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                                    }
                                }
                            } finally {
                                wsRef.value?.disconnect()
                                wsRef.value = null
                            }
                        }
                    }

                    // Keep WebSocket entity subscription in sync when cards are added/removed or configs change
                    LaunchedEffect(subscribedEntityIds) {
                        wsRef.value?.updateEntities(subscribedEntityIds)
                    }

                    // While the "failed to connect" screen is showing, silently retry the connection
                    // every 30s. refreshCountdown drives the "Refreshing in Xs" label on that screen.
                    // On success loadFailed flips false, which cancels this loop.
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
                                    refreshDashboardSnapshot(
                                        selected.map { it.entity_id }.ifEmpty { savedIds }
                                    )
                                    loadFailed = false
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Still failing; the next 30s tick will try again.
                                }
                            }
                        }
                    }

                    val currentScreen = backStack.backStack.lastOrNull() ?: AppDest.Dashboard()
                    var bottomBarHeight by remember { mutableStateOf(0.dp) }
                    var isSystemFullscreen by remember { mutableStateOf(false) }
                    val dashboardEntitySheetState = rememberDashboardEntitySheetState()
                    val isDashboardEntitySheetOpen = dashboardEntitySheetState.entityId != null
                    var dashboardEditActionId by remember { mutableIntStateOf(0) }
                    var dashboardEditActionRequest by remember {
                        mutableStateOf<DashboardEditActionRequest?>(null)
                    }
                    val requestDashboardEditAction: (DashboardEditAction) -> Unit = { action ->
                        dashboardEditActionId++
                        dashboardEditActionRequest = DashboardEditActionRequest(dashboardEditActionId, action)
                    }

                    val destinations = remember(frigateUrl, token, baseUrl) {
                        listOf(
                            AppDest.Dashboard(),
                            AppDest.Grid(frigateUrl, token),
                            AppDest.Detail("", frigateUrl, token), // placeholder to register the KClass
                            AppDest.HACamera("", baseUrl, token),  // placeholder to register the KClass
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

                    // Only show destinations with valid icons in the bottom bar
                    val bottomBarDestinations = remember(destinations) {
                        destinations.filter { it.filledIcon != 0 }
                    }
                    val motionLevel by remember(context) { ThemeRepository(context).motionLevel }
                        .collectAsState(initial = MotionLevel.MEDIUM)
                    val navigationState = rememberMotionNavigationState(
                        backStack = backStack.backStack,
                        topLevelDestinations = bottomBarDestinations,
                        motionLevel = motionLevel,
                        showsBottomBar = { it.showNavigation }
                    )

                    val configuration = LocalConfiguration.current
                    val isLandscape = configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val showRail = isLandscape && !isSystemFullscreen && currentScreen.showNavigation
                    val scaffoldPadding = if (showRail || isSystemFullscreen) PaddingValues(0.dp)
                                         else PaddingValues(bottom = bottomBarHeight)
                    val expressiveCanvas = ExpressiveCanvasSetting.isEnabled(context)
                    val dashboardBaseColor = MaterialTheme.colorScheme.primaryContainer
                        .copy(alpha = 0.4f)
                        .compositeOver(MaterialTheme.colorScheme.background)
                    val dashboardEditColor = MaterialTheme.colorScheme.tertiaryContainer
                        .copy(alpha = 0.4f)
                        .compositeOver(MaterialTheme.colorScheme.background)
                    val railEditing = currentScreen is AppDest.Dashboard && edit
                    val railEditRevealProgress by animateFloatAsState(
                        targetValue = if (railEditing) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (railEditing) 800 else 200,
                            easing = FastOutSlowInEasing
                        ),
                        label = "navigationRailEditReveal"
                    )
                    val dashboardPageColor by animateColorAsState(
                        targetValue = if (railEditing) dashboardEditColor else dashboardBaseColor,
                        animationSpec = tween(
                            durationMillis = if (railEditing) 800 else 200,
                            easing = FastOutSlowInEasing
                        ),
                        label = "dashboardPageColor"
                    )
                    val railItemAccent by animateColorAsState(
                        targetValue = if (railEditing) {
                            if (expressiveCanvas) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.tertiary
                        } else {
                            if (expressiveCanvas) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.primary
                        },
                        animationSpec = tween(
                            durationMillis = if (railEditing) 800 else 200,
                            easing = FastOutSlowInEasing
                        ),
                        label = "navigationRailItemAccent"
                    )
                    val railItemOnAccent by animateColorAsState(
                        targetValue = if (railEditing) {
                            if (expressiveCanvas) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onTertiary
                        } else {
                            if (expressiveCanvas) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onPrimary
                        },
                        animationSpec = tween(
                            durationMillis = if (railEditing) 800 else 200,
                            easing = FastOutSlowInEasing
                        ),
                        label = "navigationRailItemOnAccent"
                    )
                    val onDashboardEditFabClick: () -> Unit = {
                        if (edit) {
                            saveLayout()
                            edit = false
                            activeCardKey = null
                        } else {
                            edit = true
                        }
                    }

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
                        scaffoldPadding = scaffoldPadding,
                        activeCardKey = activeCardKey,
                        onActiveCardKeyChange = { activeCardKey = it },
                        configEntityId = configEntityId,
                        onConfigEntityIdChange = { configEntityId = it },
                        onShowSettings = { backStack.navigate(AppDest.Settings) },
                        onFullScreenChange = { isSystemFullscreen = it },
                        dashboardEntitySheetState = dashboardEntitySheetState,
                        hostManagesDashboardBackground = true,
                        externalSheetEntityId = widgetSheetEntityId,
                        externalSheetRequestId = widgetSheetRequestId,
                        baseUrl = baseUrl,
                        token = token,
                        tokenProvider = { HomeAssistantAuth.currentAccessToken(context) },
                        frigateUrl = frigateUrl,
                        onSaveSettings = { newBaseUrl, newToken, newFrigateUrl, oauthSession ->
                            if (oauthSession != null) {
                                HomeAssistantAuth.saveOAuthSession(this@MainActivity, newBaseUrl, newFrigateUrl, oauthSession)
                            } else {
                                HomeAssistantAuth.saveManualToken(this@MainActivity, newBaseUrl, newToken, newFrigateUrl)
                            }
                            registerMobileAppInBackground()
                            recreate()
                        },
                        callServiceOptimistically = callServiceOptimistically,
                        loadFailed = loadFailed,
                        refreshCountdown = refreshCountdown,
                        onRetryLoad = ::retryLoad,
                        onAddEntity = onAddEntity,
                        onRemoveEntity = onRemoveEntity,
                        dashboardEditActionRequest = dashboardEditActionRequest,
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(
                                    (dashboardEntitySheetState.backgroundReveal.value *
                                        SHEET_BACKGROUND_BLUR).dp
                                )
                                .dashboardRailEditRevealBackground(
                                    baseColor = dashboardBaseColor,
                                    revealColor = dashboardEditColor,
                                    revealProgress = railEditRevealProgress,
                                    originX = configuration.screenWidthDp.dp - 72.dp,
                                    originY = configuration.screenHeightDp.dp -
                                        if (showRail) 64.dp else 72.dp
                                )
                        ) {
                            if (showRail) {
                            Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail(containerColor = Color.Transparent) {
                                Spacer(Modifier.weight(1f))
                                bottomBarDestinations.forEach { dest ->
                                    val isSelected = currentScreen::class == dest::class
                                    NavigationRailItem(
                                        selected = isSelected,
                                        onClick = { backStack.navigateTop(dest) },
                                        icon = {
                                            Icon(
                                                painter = painterResource(if (isSelected) dest.filledIcon else dest.outlineIcon),
                                                contentDescription = dest.label
                                            )
                                        },
                                        label = { Text(dest.label) },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = railItemOnAccent,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            indicatorColor = railItemAccent,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                CompositionLocalProvider(LocalAppState provides appState) {
                                    AppNavHost(backStack = backStack, destinations = destinations, navigationState = navigationState, dashboardBackgroundColor = dashboardPageColor, modifier = Modifier.fillMaxSize())
                                }
                                SnackbarHost(
                                    hostState = snackbarHostState,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                                ) { data -> AppSnackbar(data) }
                                if (currentScreen is AppDest.Dashboard) {
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .windowInsetsPadding(
                                            WindowInsets.safeDrawing.only(
                                                WindowInsetsSides.Bottom + WindowInsetsSides.End
                                            )
                                        )
                                            .padding(end = 24.dp, bottom = 16.dp)
                                            .width(96.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = edit,
                                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                                        ) {
                                            VerticalFloatingToolbar(
                                                expanded = true,
                                                colors = if (activeCardKey == null) {
                                                    FloatingToolbarDefaults.standardFloatingToolbarColors(
                                                        toolbarContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                        toolbarContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                                    )
                                                } else {
                                                    FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                                                        toolbarContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                        toolbarContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                                    )
                                                }
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    DashboardToolbarButton(
                                                        "Add",
                                                        "add",
                                                        emphasized = activeCardKey == null
                                                    ) {
                                                        requestDashboardEditAction(DashboardEditAction.ADD)
                                                    }
                                                    if (activeCardKey == null) {
                                                        DashboardToolbarButton("Layout settings", "tune") {
                                                            backStack.navigate(AppDest.DashboardSettings)
                                                        }
                                                    } else {
                                                        DashboardToolbarButton("Edit", "edit", emphasized = true) {
                                                            requestDashboardEditAction(DashboardEditAction.EDIT)
                                                        }
                                                        DashboardToolbarButton("Delete", "delete", destructive = true) {
                                                            requestDashboardEditAction(DashboardEditAction.DELETE)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        DashboardEditFab(
                                            editing = edit,
                                            large = true,
                                            onClick = onDashboardEditFabClick
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalAppState provides appState) {
                                AppNavHost(backStack = backStack, destinations = destinations, navigationState = navigationState, dashboardBackgroundColor = dashboardPageColor, modifier = Modifier.fillMaxSize())
                            }
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight + 16.dp)
                            ) { data -> AppSnackbar(data) }

                            // The shared host waits for the page transition before returning.
                            val showBottomBar = !isSystemFullscreen &&
                                !isDashboardEntitySheetOpen
                            MotionBottomBarHost(
                                navigationState = navigationState,
                                visible = showBottomBar,
                                motionLevel = motionLevel,
                                bottomBarHeight = bottomBarHeight,
                                fullyDarkened = true,
                                darkeningHeight = 240.dp,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            ) {
                                FloatingBottomAppBar(
                                    modifier = Modifier.fillMaxWidth(),
                                    visible = true,
                                    currentDest = currentScreen,
                                    destinations = bottomBarDestinations,
                                    showContrast = false,
                                    fullyDarkened = true,
                                    onNavigate = { dest -> backStack.navigateTop(dest) },
                                    onHeightChanged = { bottomBarHeight = it },
                                    darkeningHeight = 240.dp,
                                    // Editing only means anything on the dashboard, so the slot
                                    // stays empty elsewhere and the toolbar collapses back to
                                    // just its destinations.
                                    floatingActionButton = if (currentScreen is AppDest.Dashboard) {
                                        {
                                            DashboardEditFab(
                                                editing = edit,
                                                onClick = onDashboardEditFabClick
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    toolbarContent = if (currentScreen is AppDest.Dashboard && edit) {
                                        {
                                            DashboardToolbarButton("Add", "add", emphasized = activeCardKey == null) {
                                                requestDashboardEditAction(DashboardEditAction.ADD)
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            AnimatedVisibility(
                                                visible = activeCardKey == null,
                                                enter = fadeIn() + expandHorizontally(),
                                                exit = fadeOut() + shrinkHorizontally()
                                            ) {
                                                DashboardToolbarButton("Layout settings", "tune") {
                                                    backStack.navigate(AppDest.DashboardSettings)
                                                }
                                            }
                                            AnimatedVisibility(
                                                visible = activeCardKey != null,
                                                enter = fadeIn() + expandHorizontally(),
                                                exit = fadeOut() + shrinkHorizontally()
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    DashboardToolbarButton("Edit", "edit", emphasized = true) {
                                                        requestDashboardEditAction(DashboardEditAction.EDIT)
                                                    }
                                                    DashboardToolbarButton("Delete", "delete", destructive = true) {
                                                        requestDashboardEditAction(DashboardEditAction.DELETE)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    toolbarColors = if (activeCardKey == null) {
                                        FloatingToolbarDefaults.standardFloatingToolbarColors(
                                            toolbarContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            toolbarContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    } else {
                                        FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                                            toolbarContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            toolbarContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                )
                            }
                        }
                    }
                        }

                        dashboardEntitySheetState.entityId?.let { id ->
                            val sheetEntity = all.firstOrNull { it.entity_id == id }
                            val sheetSpec = sheetEntity?.let {
                                factorySpecForEntity(it, configs[it.entity_id])
                            }
                            if (sheetEntity != null && sheetSpec != null) {
                                CompositionLocalProvider(LocalAppState provides appState) {
                                    FactoryEntityStateSheet(
                                        entity = sheetEntity,
                                        spec = sheetSpec,
                                        api = api,
                                        names = names,
                                        configs = configs,
                                        allEntities = all,
                                        originCoords = dashboardEntitySheetState.originCoords,
                                        originLocal = dashboardEntitySheetState.originLocal,
                                        slideProgress = dashboardEntitySheetState.slide.value,
                                        scrimProgress = dashboardEntitySheetState.backgroundReveal.value,
                                        onUpdated = dashboardEntitySheetState.onUpdated,
                                        onDismiss = dashboardEntitySheetState::dismiss
                                    )
                                }
                            } else {
                                LaunchedEffect(id) {
                                    dashboardEntitySheetState.dismiss()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}

private fun Modifier.dashboardRailEditRevealBackground(
    baseColor: Color,
    revealColor: Color,
    revealProgress: Float,
    originX: Dp,
    originY: Dp,
): Modifier = drawBehind {
    drawRect(baseColor)
    val progress = revealProgress.coerceIn(0f, 1f)
    if (progress <= 0f) return@drawBehind

    val center = Offset(
        x = originX.toPx(),
        y = originY.toPx()
    )
    val dx = max(center.x, size.width - center.x)
    val dy = max(center.y, size.height - center.y)
    val radius = sqrt(dx * dx + dy * dy) / EDIT_REVEAL_SOLID_FRACTION * progress
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

/**
 * Large edit/save action used by the bottom bar FAB slot.
 */
@Composable
private fun DashboardEditFab(editing: Boolean, large: Boolean = false, onClick: () -> Unit) {
    // Filled while editing so the FAB reads as the primary commit action for layout changes.
    val containerColor by animateColorAsState(
        targetValue = if (editing) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        label = "editFabContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (editing) {
            MaterialTheme.colorScheme.onTertiary
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
        label = "editFabContent"
    )

    val icon: @Composable () -> Unit = {
        Icon(
            painter = rememberSymbolPainter(if (editing) "check" else "edit"),
            contentDescription = if (editing) "Save layout" else "Edit dashboard",
            modifier = Modifier.size(if (large) 36.dp else 24.dp)
        )
    }

    if (large) {
        LargeFloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = contentColor,
            content = icon
        )
    } else {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = icon
        )
    }
}

@Composable
private fun DashboardToolbarButton(
    label: String,
    icon: String,
    emphasized: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    if (emphasized || destructive) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (destructive) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiary
                },
                contentColor = if (destructive) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
        ) {
            Icon(
                painter = rememberSymbolPainter(icon),
                contentDescription = label,
                modifier = Modifier.size(if (emphasized) 28.dp else 24.dp)
            )
        }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = rememberSymbolPainter(icon),
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun AppSnackbar(data: SnackbarData) {
    val contentColor = MaterialTheme.colorScheme.onErrorContainer
    Card(
        shape = RoundedCornerShape(percent = 50),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
            contentColor = contentColor
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(0.96f),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(rememberSymbolPainter("error"), contentDescription = "Error", tint = contentColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, color = contentColor)
        }
    }
}

private fun getExtraEntityIds(selectedIds: List<String>, configs: Map<String, Map<String, Any>>): Set<String> {
    val extras = mutableSetOf<String>()
    val regex = Regex("\\{([^}.]+(?:\\.[^}.]+)+)\\}")
    val blacklist = setOf("state", "name", "attributes", "current", "remaining")
    
    selectedIds.forEach { parentId ->
        val cfg = configs[parentId] ?: return@forEach
        
        // 1. Check state_template
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
        
        // 2. Check custom_features
        val customFeatures = (cfg["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
        customFeatures.forEach { feature ->
            // targetEntity
            val targetEntity = feature["targetEntity"] as? String
            if (!targetEntity.isNullOrBlank()) {
                extras.add(targetEntity)
            }
            
            // targets list
            val targets = (feature["targets"] as? List<*>)?.filterIsInstance<Map<String, String>>().orEmpty()
            targets.forEach { tgt ->
                if (tgt["type"] == "entity") {
                    val valStr = tgt["value"]
                    if (!valStr.isNullOrBlank()) {
                        extras.add(valStr)
                    }
                }
            }
            
            // valueTemplate
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
            
            // visibility conditions
            val visibility = (feature["visibility"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            visibility.forEach { cond ->
                collectEntityIdsFromCondition(cond, extras)
            }
        }

        // 3. Button cards: the tap target and any conditional-label rule conditions.
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
