package com.feldman.ha.mobile

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.feldman.ha.api.HomeAssistantAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MobileAppRegistrationInfo(
    val deviceId: String,
    val webhookId: String,
    val cloudhookUrl: String,
    val remoteUiUrl: String,
    val registeredBaseUrl: String
)

object MobileAppRegistration {
    private const val KEY_DEVICE_ID = "mobile_app_device_id"
    private const val KEY_WEBHOOK_ID = "mobile_app_webhook_id"
    private const val KEY_CLOUDHOOK_URL = "mobile_app_cloudhook_url"
    private const val KEY_REMOTE_UI_URL = "mobile_app_remote_ui_url"
    private const val KEY_SECRET = "mobile_app_secret"
    private const val KEY_REGISTERED_BASE_URL = "mobile_app_registered_base_url"
    private const val KEY_DEVICE_NAME = "mobile_app_device_name"
    private const val APP_ID = "com.feldman.ha"
    private const val APP_NAME = "Feldman Home"

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun registrationInfo(context: Context): MobileAppRegistrationInfo? {
        val prefs = HomeAssistantAuth.prefs(context)
        val webhookId = prefs.getString(KEY_WEBHOOK_ID, "").orEmpty()
        if (webhookId.isBlank()) return null
        return MobileAppRegistrationInfo(
            deviceId = getOrCreateDeviceId(context),
            webhookId = webhookId,
            cloudhookUrl = prefs.getString(KEY_CLOUDHOOK_URL, "").orEmpty(),
            remoteUiUrl = prefs.getString(KEY_REMOTE_UI_URL, "").orEmpty(),
            registeredBaseUrl = prefs.getString(KEY_REGISTERED_BASE_URL, "").orEmpty()
        )
    }

    fun isRegistered(context: Context): Boolean = registrationInfo(context) != null

    suspend fun registerDevice(context: Context): MobileAppRegistrationInfo = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = HomeAssistantAuth.prefs(appContext)
        val rootUrl = HomeAssistantAuth.normalizeHomeAssistantRootUrl(
            prefs.getString("url", "http://homeassistant.local:8123/api/").orEmpty()
        )
        val token = HomeAssistantAuth.currentAccessToken(appContext)
        if (rootUrl.isBlank() || token.isBlank()) {
            throw IOException("Home Assistant is not configured.")
        }

        val responseJson = postAuthenticatedJson(
            url = rootUrl + "api/mobile_app/registrations",
            token = token,
            payload = buildRegistrationPayload(appContext)
        )

        val info = MobileAppRegistrationInfo(
            deviceId = getOrCreateDeviceId(appContext),
            webhookId = responseJson.optString("webhook_id"),
            cloudhookUrl = responseJson.optString("cloudhook_url"),
            remoteUiUrl = responseJson.optString("remote_ui_url"),
            registeredBaseUrl = rootUrl.trimEnd('/')
        )
        if (info.webhookId.isBlank()) {
            throw IOException("Home Assistant did not return a mobile app webhook.")
        }

        prefs.edit {
            putString(KEY_WEBHOOK_ID, info.webhookId)
            putString(KEY_CLOUDHOOK_URL, info.cloudhookUrl)
            putString(KEY_REMOTE_UI_URL, info.remoteUiUrl)
            putString(KEY_SECRET, responseJson.optString("secret"))
            putString(KEY_REGISTERED_BASE_URL, info.registeredBaseUrl)
        }
        info
    }

    suspend fun sendWebhook(context: Context, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val info = registrationInfo(context) ?: throw IOException("This phone is not registered with Home Assistant.")
        val candidates = webhookUrls(context, info)
        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                return@withContext postJson(url, payload)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException(lastError?.localizedMessage ?: "Could not reach the Home Assistant mobile app webhook.")
    }

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = HomeAssistantAuth.prefs(context)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    /** The hardware name, used whenever nothing has been chosen. */
    fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }

    /** What this phone calls itself in Home Assistant. */
    fun deviceName(context: Context): String =
        HomeAssistantAuth.prefs(context).getString(KEY_DEVICE_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()

    /**
     * Renames this phone in Home Assistant.
     *
     * Sent through `update_registration` rather than by registering again: re-registering mints a
     * new webhook, which Home Assistant treats as a different device and which would orphan every
     * sensor entity already attached to this one.
     */
    suspend fun setDeviceName(context: Context, name: String) {
        val appContext = context.applicationContext
        val trimmed = name.trim().ifBlank { defaultDeviceName() }
        sendWebhook(
            appContext,
            JSONObject()
                .put("type", "update_registration")
                .put("data", JSONObject().put("device_name", trimmed))
        )
        HomeAssistantAuth.prefs(appContext).edit { putString(KEY_DEVICE_NAME, trimmed) }
    }

    private fun buildRegistrationPayload(context: Context): JSONObject {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val deviceName = deviceName(context)

        return JSONObject()
            .put("device_id", getOrCreateDeviceId(context))
            .put("app_id", APP_ID)
            .put("app_name", APP_NAME)
            .put("app_version", versionName)
            .put("device_name", deviceName)
            .put("manufacturer", Build.MANUFACTURER.ifBlank { "Android" })
            .put("model", Build.MODEL.ifBlank { "Android" })
            .put("os_name", "Android")
            .put("os_version", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
            .put("supports_encryption", false)
            .put("app_data", JSONObject())
    }

    private fun webhookUrls(context: Context, info: MobileAppRegistrationInfo): List<String> {
        val prefs = HomeAssistantAuth.prefs(context)
        val localRoot = HomeAssistantAuth.normalizeHomeAssistantRootUrl(
            prefs.getString("url", info.registeredBaseUrl).orEmpty()
        ).trimEnd('/')
        val remoteRoot = info.remoteUiUrl.trimEnd('/')
        return listOfNotNull(
            info.cloudhookUrl.takeIf { it.isNotBlank() },
            remoteRoot.takeIf { it.isNotBlank() }?.let { "$it/api/webhook/${info.webhookId}" },
            localRoot.takeIf { it.isNotBlank() }?.let { "$it/api/webhook/${info.webhookId}" }
        ).distinct()
    }

    private fun postAuthenticatedJson(url: String, token: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        return executeJson(request)
    }

    private fun postJson(url: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        return executeJson(request)
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val hint = if (response.code == 404) {
                    "Home Assistant mobile_app integration is not loaded."
                } else {
                    body.ifBlank { response.message }
                }
                throw IOException(hint)
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }
}
