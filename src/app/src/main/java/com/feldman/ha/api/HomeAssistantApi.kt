package com.feldman.ha.api

import android.util.Log
import com.feldman.ha.data.HAEntity
import com.feldman.ha.widgets.WidgetNetworkGate
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

interface HomeAssistantApi {
    @GET("states")
    suspend fun getEntities(): List<HAEntity>
    @GET("states")
    suspend fun getStates(): List<HAEntity>
    @GET("states/{entity_id}")
    suspend fun getState(
        @Path("entity_id") entityId: String
    ): HAEntity

    @GET("services")
    suspend fun getServices(): List<Map<String, Any>>

    @POST("services/{domain}/{service}")
    suspend fun callService(
        @Path("domain") domain: String,
        @Path("service") service: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>

    /** Renders a Jinja template server-side (HA's /api/template). Returns the raw text. */
    @POST("template")
    suspend fun renderTemplate(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): ResponseBody
}

private interface AuthorizedHomeAssistantApi {
    @GET("states")
    suspend fun getEntities(@Header("Authorization") authorization: String): List<HAEntity>

    @GET("states")
    suspend fun getStates(@Header("Authorization") authorization: String): List<HAEntity>

    @GET("states/{entity_id}")
    suspend fun getState(
        @Header("Authorization") authorization: String,
        @Path("entity_id") entityId: String
    ): HAEntity

    @GET("services")
    suspend fun getServices(@Header("Authorization") authorization: String): List<Map<String, Any>>

    @POST("services/{domain}/{service}")
    suspend fun callService(
        @Header("Authorization") authorization: String,
        @Path("domain") domain: String,
        @Path("service") service: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>

    @POST("template")
    suspend fun renderTemplate(
        @Header("Authorization") authorization: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): ResponseBody
}


/* build realistic demo entities for all domains */
private val demoLivingRoomAC = HAEntity(
    "climate.living_room_ac",
    state = "cool",
    mapOf(
        "friendly_name" to "Living Room AC",
        "temperature" to 22,
        "current_temperature" to 24,
        "min_temp" to 16,
        "max_temp" to 30,
        "target_temp_step" to 1,
        "hvac_modes" to listOf("off", "cool", "heat", "auto", "dry", "fan_only"),
        "fan_modes" to listOf("auto", "low", "medium", "high"),
        "swing_modes" to listOf("off", "vertical", "horizontal", "both"),
        "hvac_action" to "cooling",
        "preset_modes" to listOf("none", "eco", "boost", "sleep")
    )
)
private val demoOfficeAC = HAEntity(
    "climate.office_ac",
    state = "off",
    mapOf(
        "friendly_name" to "Office AC",
        "temperature" to 25,
        "current_temperature" to 25,
        "min_temp" to 16,
        "max_temp" to 30,
        "target_temp_step" to 1,
        "hvac_modes" to listOf("off", "cool", "heat", "auto"),
        "fan_modes" to listOf("auto", "low", "medium", "high"),
        "hvac_action" to "off"
    )
)
private val demoLivingRoomTV = HAEntity(
    "media_player.living_room_tv",
    state = "on",
    mapOf(
        "friendly_name" to "Living Room TV",
        "volume_level" to 0.45,
        "is_volume_muted" to false,
        "media_title" to "YouTube",
        "media_artist" to "Home Assistant",
        "source_list" to listOf("HDMI 1", "HDMI 2", "YouTube", "Netflix", "Spotify"),
        "source" to "YouTube",
        "supported_features" to 1073
    )
)
private val demoBedroomLight = HAEntity(
    "light.bedroom_light",
    state = "on",
    mapOf(
        "friendly_name" to "Bedroom Light",
        "brightness" to 180,
        "rgb_color" to listOf(255, 200, 150)
    )
)
private val demoLivingRoomLights = HAEntity(
    "light.living_room_lights",
    state = "on",
    mapOf(
        "friendly_name" to "Living Room Lights",
        "brightness" to 255
    )
)
private val demoBedroomBlinds = HAEntity(
    "cover.bedroom_blinds",
    state = "open",
    mapOf(
        "friendly_name" to "Bedroom Blinds",
        "current_position" to 100,
        "supported_features" to 15
    )
)
private val demoHomeAlarm = HAEntity(
    "alarm_control_panel.home_alarm",
    state = "disarmed",
    mapOf(
        "friendly_name" to "Home Alarm",
        "code_format" to "number",
        "supported_features" to 3
    )
)
private val demoFrontDoorLock = HAEntity(
    "lock.front_door_lock",
    state = "locked",
    mapOf(
        "friendly_name" to "Front Door Lock",
        "supported_features" to 1
    )
)
private val demoRobotVacuum = HAEntity(
    "vacuum.robot_vacuum",
    state = "docked",
    mapOf(
        "friendly_name" to "Robot Vacuum",
        "battery_level" to 100,
        "fan_speed" to "Standard",
        "fan_speed_list" to listOf("Quiet", "Standard", "Turbo", "Max"),
        "supported_features" to 8191
    )
)
private val demoLivingRoomFan = HAEntity(
    "fan.living_room_fan",
    state = "on",
    mapOf(
        "friendly_name" to "Living Room Fan",
        "percentage" to 66,
        "preset_modes" to listOf("normal", "nature", "sleep"),
        "supported_features" to 1
    )
)
private val demoOutdoorTemperature = HAEntity(
    "sensor.outdoor_temperature",
    state = "24",
    mapOf(
        "friendly_name" to "Outdoor Temperature",
        "unit_of_measurement" to "°C",
        "device_class" to "temperature"
    )
)
private val demoIndoorTemperature = HAEntity(
    "sensor.indoor_temperature",
    state = "22.5",
    mapOf(
        "friendly_name" to "Indoor Temperature",
        "unit_of_measurement" to "°C",
        "device_class" to "temperature"
    )
)
private val demoLivingRoomHumidity = HAEntity(
    "sensor.living_room_humidity",
    state = "45",
    mapOf(
        "friendly_name" to "Living Room Humidity",
        "unit_of_measurement" to "%",
        "device_class" to "humidity"
    )
)
private val demoHomeWeather = HAEntity(
    "weather.home",
    state = "sunny",
    mapOf(
        "friendly_name" to "Home Weather",
        "temperature" to 25.0,
        "humidity" to 40,
        "pressure" to 1013,
        "wind_speed" to 12.0,
        "temperature_unit" to "°C"
    )
)

/* simple helper for Response<Unit> */
private fun ok(): Response<Unit> = Response.success(Unit)

private val realApiCache = ConcurrentHashMap<String, HomeAssistantApi>()
private const val HA_HTTP_BODY_LOG_LIMIT = 2_000

private fun Throwable.isExpectedNetworkFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is UnknownHostException,
            is SocketTimeoutException,
            is ConnectException,
            is SocketException,
            is IllegalArgumentException,
            is java.net.MalformedURLException,
            is javax.net.ssl.SSLException,
            is retrofit2.HttpException -> return true
            is IOException -> return true
        }
        current = current.cause
    }
    return false
}

private fun String.truncateForHaLog(): String =
    if (length <= HA_HTTP_BODY_LOG_LIMIT) this else take(HA_HTTP_BODY_LOG_LIMIT) + "...<truncated>"

private fun Any?.toHaLogValue(): String =
    when (this) {
        null -> "null"
        is String -> "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Number, is Boolean -> toString()
        is Map<*, *> -> entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":${value.toHaLogValue()}"
        }
        is Iterable<*> -> joinToString(prefix = "[", postfix = "]") { it.toHaLogValue() }
        is Array<*> -> joinToString(prefix = "[", postfix = "]") { it.toHaLogValue() }
        else -> "\"${toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

private fun Response<*>.errorBodyForLog(): String =
    try {
        errorBody()?.string().orEmpty()
    } catch (e: IOException) {
        "failed to read error body: ${e.localizedMessage ?: e.javaClass.simpleName}"
    }.ifBlank {
        "empty error body"
    }.truncateForHaLog()

private class RealHomeAssistantApi(
    private val delegate: AuthorizedHomeAssistantApi,
    private val tokenProvider: suspend () -> String,
    private val apiUrl: String,
    private val cacheKey: String
) : HomeAssistantApi {
    private suspend fun authorization(): String = "Bearer ${tokenProvider()}"

    override suspend fun getEntities(): List<HAEntity> =
        execute("GET", "${apiUrl}states") {
            delegate.getEntities(authorization())
        }

    override suspend fun getStates(): List<HAEntity> =
        execute("GET", "${apiUrl}states") {
            delegate.getStates(authorization())
        }

    override suspend fun getState(entityId: String): HAEntity =
        execute("GET", "${apiUrl}states/$entityId") {
            delegate.getState(authorization(), entityId)
        }

    override suspend fun getServices(): List<Map<String, Any>> =
        execute("GET", "${apiUrl}services") {
            delegate.getServices(authorization())
        }

    override suspend fun callService(
        domain: String,
        service: String,
        body: Map<String, Any>
    ): Response<Unit> =
        execute("POST", "${apiUrl}services/$domain/$service", requestBody = body) {
            delegate.callService(authorization(), domain, service, body)
        }

    override suspend fun renderTemplate(body: Map<String, Any>): ResponseBody =
        execute("POST", "${apiUrl}template", requestBody = body) {
            delegate.renderTemplate(authorization(), body)
        }

    private suspend fun <T> execute(
        method: String,
        requestUrl: String,
        requestBody: Any? = null,
        block: suspend () -> T
    ): T {
        Log.d("HA_HTTP", "-> $method $requestUrl")
        if (requestBody != null) {
            Log.d("HA_HTTP", "   body=${requestBody.toHaLogValue().truncateForHaLog()}")
        }
        val start = System.nanoTime()
        val result = try {
            block()
        } catch (e: Exception) {
            if (e.isExpectedNetworkFailure()) {
                realApiCache.remove(cacheKey)
                Log.w("HA_HTTP", "network unavailable: ${e.javaClass.simpleName}: ${e.localizedMessage ?: "no message"}")
            } else {
                Log.e("HA_HTTP", "request failed: ${e.localizedMessage}")
            }
            throw e
        }
        val ms = (System.nanoTime() - start) / 1e6
        WidgetNetworkGate.reportSuccess()
        val status = when (result) {
            is Response<*> -> "${result.code()} ${result.message()}"
            else -> "OK"
        }
        if (result is Response<*> && !result.isSuccessful) {
            Log.w("HA_HTTP", "<- $status (${String.format("%.1f", ms)} ms) error=${result.errorBodyForLog()}")
        } else {
            Log.d("HA_HTTP", "<- $status (${String.format("%.1f", ms)} ms)")
        }
        return result
    }
}

/* ---------------- demo implementation ---------------- */

private val demoApi = object : HomeAssistantApi {

    private val entities = mutableListOf(
        demoLivingRoomAC,
        demoOfficeAC,
        demoLivingRoomTV,
        demoBedroomLight,
        demoLivingRoomLights,
        demoBedroomBlinds,
        demoHomeAlarm,
        demoFrontDoorLock,
        demoRobotVacuum,
        demoLivingRoomFan,
        demoOutdoorTemperature,
        demoIndoorTemperature,
        demoLivingRoomHumidity,
        demoHomeWeather
    )

    override suspend fun getEntities(): List<HAEntity> = entities.toList()
    override suspend fun getStates(): List<HAEntity> = entities.toList()

    override suspend fun getState(entityId: String): HAEntity =
        entities.firstOrNull { it.entity_id == entityId } ?: demoLivingRoomAC

    override suspend fun getServices(): List<Map<String, Any>> =
        listOf(
            mapOf("domain" to "homeassistant", "services" to mapOf("toggle" to emptyMap<String, Any>())),
            mapOf("domain" to "climate", "services" to mapOf("set_temperature" to emptyMap<String, Any>(), "set_hvac_mode" to emptyMap<String, Any>(), "set_fan_mode" to emptyMap<String, Any>(), "set_swing_mode" to emptyMap<String, Any>())),
            mapOf("domain" to "light", "services" to mapOf("turn_on" to emptyMap<String, Any>(), "turn_off" to emptyMap<String, Any>(), "toggle" to emptyMap<String, Any>())),
            mapOf("domain" to "media_player", "services" to mapOf("turn_on" to emptyMap<String, Any>(), "turn_off" to emptyMap<String, Any>(), "volume_set" to emptyMap<String, Any>(), "volume_mute" to emptyMap<String, Any>(), "media_play_pause" to emptyMap<String, Any>())),
            mapOf("domain" to "cover", "services" to mapOf("open_cover" to emptyMap<String, Any>(), "close_cover" to emptyMap<String, Any>(), "stop_cover" to emptyMap<String, Any>(), "set_cover_position" to emptyMap<String, Any>())),
            mapOf("domain" to "alarm_control_panel", "services" to mapOf("alarm_arm_home" to emptyMap<String, Any>(), "alarm_arm_away" to emptyMap<String, Any>(), "alarm_disarm" to emptyMap<String, Any>())),
            mapOf("domain" to "lock", "services" to mapOf("lock" to emptyMap<String, Any>(), "unlock" to emptyMap<String, Any>())),
            mapOf("domain" to "vacuum", "services" to mapOf("start" to emptyMap<String, Any>(), "pause" to emptyMap<String, Any>(), "return_to_base" to emptyMap<String, Any>())),
            mapOf("domain" to "fan", "services" to mapOf("turn_on" to emptyMap<String, Any>(), "turn_off" to emptyMap<String, Any>(), "set_percentage" to emptyMap<String, Any>()))
        )

    override suspend fun callService(
        domain: String,
        service: String,
        body: Map<String, Any>
    ): Response<Unit> {
        val id = body["entity_id"] as? String ?: return ok()
        val index = entities.indexOfFirst { it.entity_id == id }
        if (index != -1) {
            val old = entities[index]
            val attrs = old.attributes.toMutableMap()
            var newState = old.state
            when ("$domain.$service") {
                "climate.set_temperature" -> {
                    (body["temperature"] as? Number)?.let { attrs["temperature"] = it }
                }
                "climate.set_hvac_mode" -> {
                    (body["hvac_mode"] as? String)?.let { newState = it }
                }
                "climate.set_fan_mode" -> {
                    (body["fan_mode"] as? String)?.let { attrs["fan_mode"] = it }
                }
                "climate.set_swing_mode" -> {
                    (body["swing_mode"] as? String)?.let { attrs["swing_mode"] = it }
                }
                "light.turn_on", "fan.turn_on", "media_player.turn_on" -> newState = "on"
                "light.turn_off", "fan.turn_off", "media_player.turn_off" -> newState = "off"
                "light.toggle", "fan.toggle", "homeassistant.toggle" -> newState = if (old.state == "on") "off" else "on"
                "cover.open_cover" -> { newState = "open"; attrs["current_position"] = 100 }
                "cover.close_cover" -> { newState = "closed"; attrs["current_position"] = 0 }
                "cover.set_cover_position" -> {
                    (body["position"] as? Number)?.let {
                        attrs["current_position"] = it.toInt()
                        newState = if (it.toInt() == 0) "closed" else "open"
                    }
                }
                "lock.lock" -> newState = "locked"
                "lock.unlock" -> newState = "unlocked"
                "alarm_control_panel.alarm_arm_home" -> newState = "armed_home"
                "alarm_control_panel.alarm_arm_away" -> newState = "armed_away"
                "alarm_control_panel.alarm_disarm" -> newState = "disarmed"
                "vacuum.start" -> newState = "cleaning"
                "vacuum.pause" -> newState = "paused"
                "vacuum.return_to_base" -> newState = "returning"
            }
            entities[index] = old.copy(state = newState, attributes = attrs)
        }
        return ok()
    }

    override suspend fun renderTemplate(body: Map<String, Any>): ResponseBody = "".toResponseBody(null)
}

/* ===================================================== */
/*           provideHAApi with automatic demo            */
/* ===================================================== */

fun provideHAApi(token: String, baseUrl: String): HomeAssistantApi {
    return provideRealHAApi(
        baseUrl = baseUrl,
        cacheTokenKey = token,
        tokenProvider = { token },
        demoToken = token
    )
}

private fun provideRealHAApi(
    baseUrl: String,
    cacheTokenKey: String,
    tokenProvider: suspend () -> String,
    demoToken: String? = null
): HomeAssistantApi {
    val normalized = HomeAssistantAuth.normalizeHomeAssistantRootUrl(baseUrl)
    val tokenStr = demoToken ?: cacheTokenKey

    /* Demo shortcut ----------------------------------------------------- */
    if (normalized == "demo" || tokenStr == "demo" || tokenStr.trim().equals("demo", ignoreCase = true)) {
        Log.i("HA_HTTP", "Using in-memory demo API")
        return demoApi
    }

    var url = normalized.replace(Regex("(/api)?(/lovelace)?/?$"), "")
    if (!url.endsWith("/")) url += "/"
    url += "api/"
    val apiUrl = url
    val cacheKey = "$apiUrl|$cacheTokenKey"

    realApiCache[cacheKey]?.let { return it }

    return try {
        val delegate = Retrofit.Builder()
            .baseUrl(apiUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthorizedHomeAssistantApi::class.java)

        RealHomeAssistantApi(delegate, tokenProvider, apiUrl, cacheKey)
            .also { realApiCache[cacheKey] = it }
    } catch (e: Throwable) {
        Log.e("HA_HTTP", "Failed to create Retrofit client for $apiUrl: ${e.message}, falling back to demoApi", e)
        demoApi
    }
}

fun getStoredApi(context: android.content.Context): HomeAssistantApi? {
    val appContext = context.applicationContext
    val prefs = HomeAssistantAuth.prefs(appContext)
    val token = prefs.getString("token", "") ?: ""
    val url = prefs.getString("url", "http://homeassistant.local:8123/api/") ?: ""
    if (token.isBlank() && !HomeAssistantAuth.hasCredentials(appContext)) return null
    val normUrl = HomeAssistantAuth.normalizeHomeAssistantRootUrl(url)
    if (token == "demo" || normUrl == "demo" || url.trim().equals("demo", ignoreCase = true)) return provideHAApi("demo", "demo")
    return provideRealHAApi(
        baseUrl = url,
        cacheTokenKey = "stored:${appContext.packageName}",
        tokenProvider = { HomeAssistantAuth.currentAccessToken(appContext) }
    )
}

fun getStoredWebSocket(
    context: android.content.Context,
    entityIds: List<String>,
    label: String = "SVC",
    onFailure: ((Throwable) -> Unit)? = null
): HomeAssistantWebSocket? {
    val appContext = context.applicationContext
    val prefs = HomeAssistantAuth.prefs(appContext)
    val token = runBlocking { HomeAssistantAuth.currentAccessToken(appContext) }
    val url = prefs.getString("url", "http://homeassistant.local:8123/api/") ?: ""
    val normUrl = HomeAssistantAuth.normalizeHomeAssistantRootUrl(url)
    if (token.isBlank() || token == "demo" || normUrl == "demo" || url.trim().equals("demo", ignoreCase = true)) return null
    return HomeAssistantWebSocket(url, token, entityIds, onFailure, label)
}
