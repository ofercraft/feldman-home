package com.feldman.ha.api

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HomeAssistantOAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val clientId: String
)

object HomeAssistantAuth {
    const val PREFS_NAME = "ha_prefs"
    const val KEY_ACCESS_TOKEN = "token"
    const val KEY_REFRESH_TOKEN = "oauth_refresh_token"
    const val KEY_EXPIRES_AT_MS = "oauth_expires_at_ms"
    const val KEY_CLIENT_ID = "oauth_client_id"
    const val KEY_AUTH_METHOD = "auth_method"
    const val AUTH_METHOD_MANUAL = "manual_token"
    const val AUTH_METHOD_OAUTH = "home_assistant_account"

    private const val DEFAULT_HA_URL = "http://homeassistant.local:8123/api/"
    private const val REFRESH_SKEW_MS = 60_000L

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val refreshMutex = Mutex()

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasCredentials(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty().isNotBlank() ||
            prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty().isNotBlank()
    }

    fun isOAuth(context: Context): Boolean = isOAuth(prefs(context))

    fun storedOAuthSession(context: Context): HomeAssistantOAuthSession? {
        val prefs = prefs(context)
        if (!isOAuth(prefs)) return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty()
        val clientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty()
        val expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (refreshToken.isBlank() || clientId.isBlank()) return null
        return HomeAssistantOAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAtMs,
            clientId = clientId
        )
    }

    fun saveOAuthSession(
        context: Context,
        baseUrl: String,
        frigateUrl: String,
        session: HomeAssistantOAuthSession
    ) {
        prefs(context).edit {
            putString("url", normalizeHomeAssistantRootUrl(baseUrl).trimEnd('/'))
            putString(KEY_ACCESS_TOKEN, session.accessToken)
            putString("frigate_url", frigateUrl)
            putString(KEY_REFRESH_TOKEN, session.refreshToken)
            putLong(KEY_EXPIRES_AT_MS, session.expiresAtMs)
            putString(KEY_CLIENT_ID, session.clientId)
            putString(KEY_AUTH_METHOD, AUTH_METHOD_OAUTH)
        }
    }

    fun saveManualToken(
        context: Context,
        baseUrl: String,
        token: String,
        frigateUrl: String
    ) {
        prefs(context).edit {
            putString("url", baseUrl)
            putString(KEY_ACCESS_TOKEN, token)
            putString("frigate_url", frigateUrl)
            putString(KEY_AUTH_METHOD, AUTH_METHOD_MANUAL)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT_MS)
            remove(KEY_CLIENT_ID)
        }
    }

    suspend fun currentAccessToken(context: Context): String {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        if (!isOAuth(prefs)) return prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()

        val currentToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        if (currentToken.isNotBlank() && !needsRefresh(prefs)) return currentToken

        return refreshMutex.withLock {
            val latestToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()
            if (latestToken.isNotBlank() && !needsRefresh(prefs)) return@withLock latestToken

            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty()
            val clientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty()
            val baseUrl = prefs.getString("url", DEFAULT_HA_URL).orEmpty()
            if (refreshToken.isBlank() || clientId.isBlank()) return@withLock latestToken

            val refreshed = refreshAccessToken(
                rootUrl = normalizeHomeAssistantRootUrl(baseUrl),
                refreshToken = refreshToken,
                clientId = clientId
            )
            prefs.edit {
                putString(KEY_ACCESS_TOKEN, refreshed.accessToken)
                putLong(KEY_EXPIRES_AT_MS, refreshed.expiresAtMs)
            }
            refreshed.accessToken
        }
    }

    suspend fun exchangeAuthorizationCode(
        rootUrl: String,
        clientId: String,
        code: String
    ): HomeAssistantOAuthSession = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder()
            .url(normalizeHomeAssistantRootUrl(rootUrl) + "auth/token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) throw IOException(tokenEndpointError(response.code, responseBody))
            val json = JSONObject(responseBody)
            val accessToken = json.getString("access_token")
            val refreshToken = json.getString("refresh_token")
            val expiresInSec = json.optLong("expires_in", 1800L)
            HomeAssistantOAuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000L,
                clientId = clientId
            )
        }
    }

    fun normalizeHomeAssistantRootUrl(input: String): String {
        var url = input.trim()
        if (url.isBlank() || url.equals("demo", ignoreCase = true) || url.startsWith("demo", ignoreCase = true)) return "demo"
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "http://$url"
        }
        url = url.replace(Regex("(/api)?(/lovelace)?/?$"), "")
        if (!url.endsWith("/")) url += "/"
        return url
    }

    private fun isOAuth(prefs: SharedPreferences): Boolean =
        prefs.getString(KEY_AUTH_METHOD, "").orEmpty() == AUTH_METHOD_OAUTH &&
            prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty().isNotBlank()

    private fun needsRefresh(prefs: SharedPreferences): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        return expiresAt <= System.currentTimeMillis() + REFRESH_SKEW_MS
    }

    private suspend fun refreshAccessToken(
        rootUrl: String,
        refreshToken: String,
        clientId: String
    ): RefreshedToken = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder()
            .url(normalizeHomeAssistantRootUrl(rootUrl) + "auth/token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) throw IOException(tokenEndpointError(response.code, responseBody))
            val json = JSONObject(responseBody)
            val expiresInSec = json.optLong("expires_in", 1800L)
            RefreshedToken(
                accessToken = json.getString("access_token"),
                expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000L
            )
        }
    }

    private data class RefreshedToken(
        val accessToken: String,
        val expiresAtMs: Long
    )

    private fun tokenEndpointError(code: Int, body: String): String {
        val description = runCatching {
            JSONObject(body).optString("error_description")
        }.getOrNull().orEmpty()
        return description.ifBlank { "Home Assistant returned HTTP $code." }
    }
}
