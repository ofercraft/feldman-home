package com.feldman.ha.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.feldman.ha.api.CameraConfig
import com.feldman.ha.api.FrigateConfig
import com.feldman.ha.api.FrigateEvent
import com.feldman.ha.api.FrigateRecordingDay
import com.feldman.ha.api.FrigateRecordingHour
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.api.HomeAssistantOAuthSession
import com.feldman.ha.data.HAEntity
import com.feldman.motion.navigation.Dest
import com.feldman.motion.navigation.Navigator
import com.github.skydoves.navgraph.annotations.NavPreview
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import com.feldman.ha.ui.pages.CameraDetailPreviewData
import com.feldman.ha.ui.pages.CustomFeatureDetailPreviewContent
import com.feldman.ha.ui.pages.LocalCameraDetailPreviewData
import com.feldman.ha.ui.pages.LocalCameraGridPreviewConfig
import com.feldman.ha.ui.pages.LocalCustomFeatureDetailPreview

@NavPreview(route = AppDest.Dashboard::class, primary = true)
@Preview(name = "Dashboard", device = PreviewDevice, showBackground = true)
@Composable
internal fun DashboardDestinationPreview() {
    RealDestinationPreview(AppDest.Dashboard())
}

@NavPreview(route = AppDest.Grid::class, primary = true)
@Preview(name = "Camera grid", device = PreviewDevice, showBackground = true)
@Composable
internal fun CameraGridDestinationPreview() {
    RealDestinationPreview(AppDest.Grid(previewFrigateUrl, previewToken))
}

@NavPreview(route = AppDest.Detail::class, primary = true)
@Preview(name = "Camera detail", device = PreviewDevice, showBackground = true)
@Composable
internal fun CameraDetailDestinationPreview() {
    RealDestinationPreview(AppDest.Detail("front_door", previewFrigateUrl, previewToken))
}

@NavPreview(route = AppDest.Settings::class, primary = true)
@Preview(name = "Settings", device = PreviewDevice, showBackground = true)
@Composable
internal fun SettingsDestinationPreview() {
    RealDestinationPreview(AppDest.Settings)
}

@NavPreview(route = AppDest.ThemeSettings::class, primary = true)
@Preview(name = "Theme settings", device = PreviewDevice, showBackground = true)
@Composable
internal fun ThemeSettingsDestinationPreview() {
    RealDestinationPreview(AppDest.ThemeSettings)
}

@NavPreview(route = AppDest.HACamera::class, primary = true)
@Preview(name = "Home Assistant camera", device = PreviewDevice, showBackground = true)
@Composable
internal fun HACameraDestinationPreview() {
    RealDestinationPreview(AppDest.HACamera("camera.front_door", previewHomeAssistantUrl, previewToken))
}

@NavPreview(route = AppDest.CustomFeatureDetail::class, primary = true)
@Preview(name = "Custom feature detail", device = PreviewDevice, showBackground = true)
@Composable
internal fun CustomFeatureDetailDestinationPreview() {
    com.feldman.motion.AppTheme {
        CustomFeatureDetailPreviewContent(
            title = "Edit Custom Feature",
            label = "Evening scene",
            icon = "scene",
            actionType = "service",
            service = "light.turn_on",
            targets = listOf("light.kitchen"),
            saveText = "Save changes",
            isSaveEnabled = true,
            onBack = {}
        )
    }
}

@Composable
private fun RealDestinationPreview(dest: AppDest) {
    val entities = remember { previewEntities() }
    val appState = rememberPreviewAppState(entities)
    val cameraGridConfig = remember {
        FrigateConfig(
            cameras = mapOf(
                "front_door" to CameraConfig("front_door"),
                "driveway" to CameraConfig("driveway"),
                "garden" to CameraConfig("garden")
            )
        )
    }
    val cameraDetailData = remember {
        CameraDetailPreviewData(
            events = listOf(
                FrigateEvent(
                    id = "preview-person",
                    camera = "front_door",
                    label = "person",
                    start_time = 1_780_000_000.0,
                    end_time = 1_780_000_012.0,
                    top_score = 0.94
                ),
                FrigateEvent(
                    id = "preview-car",
                    camera = "front_door",
                    label = "car",
                    start_time = 1_780_000_420.0,
                    end_time = 1_780_000_440.0,
                    top_score = 0.88
                )
            ),
            summary = listOf(
                FrigateRecordingDay(
                    day = "2026-06-21",
                    hours = listOf(
                        FrigateRecordingHour(hour = 8, duration = 3200, events = 2, motion = 12, objects = 2),
                        FrigateRecordingHour(hour = 12, duration = 3600, events = 1, motion = 9, objects = 1),
                        FrigateRecordingHour(hour = 18, duration = 2800, events = 3, motion = 18, objects = 3)
                    )
                )
            )
        )
    }

    com.feldman.motion.AppTheme {
        CompositionLocalProvider(
            LocalAppState provides appState,
            LocalCameraGridPreviewConfig provides cameraGridConfig,
            LocalCameraDetailPreviewData provides cameraDetailData,
            LocalCustomFeatureDetailPreview provides true
        ) {
            dest.Content(
                onNavigate = PreviewNavigator,
                onBack = {},
                onFabAction = {}
            )
        }
    }
}

@Composable
private fun rememberPreviewAppState(entities: List<HAEntity>): AppState {
    val selected = remember {
        mutableStateListOf<HAEntity>().apply {
            addAll(
                listOf(
                    entities.first { it.entity_id == "light.kitchen" },
                    entities.first { it.entity_id == "climate.living_room" },
                    entities.first { it.entity_id == "cover.garage" },
                    entities.first { it.entity_id == "alarm_control_panel.home" }
                )
            )
        }
    }
    val selectedLandscape = remember {
        mutableStateListOf<HAEntity>().apply { addAll(selected) }
    }
    val names = remember {
        mutableStateMapOf(
            "light.kitchen" to "Kitchen lights",
            "climate.living_room" to "Living room climate",
            "cover.garage" to "Garage door",
            "alarm_control_panel.home" to "Home alarm"
        )
    }
    val configs = remember {
        mutableStateMapOf<String, Map<String, Any>>(
            "light.kitchen" to mapOf(
                "custom_features" to listOf(
                    mapOf(
                        "id" to "custom_preview",
                        "label" to "Evening scene",
                        "icon" to "scene",
                        "type" to "service",
                        "serviceName" to "light.turn_on",
                        "targets" to listOf(mapOf("type" to "entity", "value" to "light.kitchen"))
                    )
                ),
                "card_span_x" to 2,
                "card_span_y" to 4
            ),
            "climate.living_room" to mapOf("card_span_x" to 2, "card_span_y" to 4),
            "cover.garage" to mapOf("card_span_x" to 2, "card_span_y" to 3),
            "alarm_control_panel.home" to mapOf("card_span_x" to 2, "card_span_y" to 3)
        )
    }
    val api = remember(entities) { PreviewHomeAssistantApi(entities) }

    return AppState(
        api = api,
        selected = selected,
        selectedLandscape = selectedLandscape,
        all = entities,
        loading = false,
        edit = false,
        onEditToggle = {},
        names = names,
        configs = configs,
        saveLayout = {},
        saveConfigs = {},
        saveNames = {},
        scaffoldPadding = PaddingValues(0.dp),
        activeCardKey = null,
        onActiveCardKeyChange = {},
        configEntityId = null,
        onConfigEntityIdChange = {},
        onShowSettings = {},
        onFullScreenChange = {},
        baseUrl = previewHomeAssistantUrl,
        token = previewToken,
        tokenProvider = { previewToken },
        frigateUrl = previewFrigateUrl,
        onSaveSettings = { _: String, _: String, _: String, _: HomeAssistantOAuthSession? -> },
        callServiceOptimistically = { _, _, _, _, _ -> },
        loadFailed = false,
        refreshCountdown = 30,
        onRetryLoad = {},
        onAddEntity = {},
        onRemoveEntity = {}
    )
}

private object PreviewNavigator : Navigator {
    override fun invoke(dest: Dest, resetStack: Boolean) = Unit
}

private class PreviewHomeAssistantApi(
    private val entities: List<HAEntity>
) : HomeAssistantApi {
    override suspend fun getEntities(): List<HAEntity> = entities
    override suspend fun getStates(): List<HAEntity> = entities
    override suspend fun getState(entityId: String): HAEntity =
        entities.firstOrNull { it.entity_id == entityId } ?: HAEntity(entityId, "unknown", emptyMap())

    override suspend fun getServices(): List<Map<String, Any>> =
        listOf(
            mapOf(
                "domain" to "light",
                "services" to mapOf(
                    "turn_on" to emptyMap<String, Any>(),
                    "turn_off" to emptyMap<String, Any>(),
                    "toggle" to emptyMap<String, Any>()
                )
            ),
            mapOf(
                "domain" to "climate",
                "services" to mapOf(
                    "set_temperature" to emptyMap<String, Any>(),
                    "set_hvac_mode" to emptyMap<String, Any>()
                )
            ),
            mapOf(
                "domain" to "cover",
                "services" to mapOf(
                    "open_cover" to emptyMap<String, Any>(),
                    "close_cover" to emptyMap<String, Any>()
                )
            )
        )

    override suspend fun callService(
        domain: String,
        service: String,
        body: Map<String, Any>
    ): Response<Unit> = Response.success(Unit)

    override suspend fun renderTemplate(body: Map<String, Any>): ResponseBody =
        "".toResponseBody(null)
}

private fun previewEntities(): List<HAEntity> =
    listOf(
        HAEntity(
            entity_id = "light.kitchen",
            state = "on",
            attributes = mapOf(
                "friendly_name" to "Kitchen lights",
                "brightness" to 184,
                "supported_color_modes" to listOf("brightness")
            )
        ),
        HAEntity(
            entity_id = "climate.living_room",
            state = "cool",
            attributes = mapOf(
                "friendly_name" to "Living room climate",
                "temperature" to 24,
                "current_temperature" to 25,
                "hvac_modes" to listOf("off", "cool", "heat", "auto")
            )
        ),
        HAEntity(
            entity_id = "cover.garage",
            state = "closed",
            attributes = mapOf(
                "friendly_name" to "Garage door",
                "current_position" to 0
            )
        ),
        HAEntity(
            entity_id = "alarm_control_panel.home",
            state = "armed_home",
            attributes = mapOf(
                "friendly_name" to "Home alarm",
                "code_format" to "number"
            )
        ),
        HAEntity(
            entity_id = "camera.front_door",
            state = "idle",
            attributes = mapOf("friendly_name" to "Front door")
        ),
        HAEntity(
            entity_id = "sensor.living_room_temperature",
            state = "22.5",
            attributes = mapOf(
                "friendly_name" to "Living room temperature",
                "unit_of_measurement" to "C"
            )
        )
    )

private const val previewToken = "preview-token"
private const val previewHomeAssistantUrl = "https://homeassistant.example/api/"
private const val previewFrigateUrl = "https://frigate.example/"
private const val PreviewDevice = "spec:width=390dp,height=844dp,dpi=420"
