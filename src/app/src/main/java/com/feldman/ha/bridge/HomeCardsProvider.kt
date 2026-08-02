package com.feldman.ha.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.api.getStoredApi
import com.feldman.ha.data.HAEntity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException

private const val TAG = "HomeCardsProvider"

private const val METHOD_GET_SNAPSHOT = "get_snapshot"
private const val METHOD_CALL_SERVICE = "call_service"
private const val METHOD_GET_CREDENTIALS = "get_credentials"
private const val METHOD_GET_PRESETS = "get_presets"

private const val CARDS_PREFS = "cards_prefs"
private const val SELECTED_ENTITIES = "selected_entities"
private const val SELECTED_ENTITIES_LAND = "selected_entities_land"
private const val CARD_CONFIGS = "card_cfg_v2"
private const val ENTITY_NAMES = "entity_names"
private const val BUTTON_CARD_IDS = "button_card_ids_v2"
private const val CAMERA_CARD_IDS = "camera_card_ids_v2"

private const val ORIENTATION_LANDSCAPE = "landscape"

class HomeCardsProvider : ContentProvider() {
    private val gson = Gson()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_GET_SNAPSHOT -> getSnapshot(extras?.getString("orientation").orEmpty())
            METHOD_CALL_SERVICE -> callService(extras)
            METHOD_GET_CREDENTIALS -> getCredentials()
            METHOD_GET_PRESETS -> getPresets()
            else -> errorBundle("Unknown method: $method")
        }
    }

    /**
     * Hands the stored Home Assistant connection to sibling Feldman apps (e.g. Clock's
     * standby dashboard) so the user logs in once. Safe to expose: the provider is
     * guarded by the signature-level CARDS_BRIDGE permission.
     */
    private fun getCredentials(): Bundle {
        val ctx = context?.applicationContext ?: return errorBundle("Provider is not attached")
        if (!HomeAssistantAuth.hasCredentials(ctx)) {
            return Bundle().apply {
                putBoolean("ok", false)
                putBoolean("has_credentials", false)
                putString("error", "Home Assistant is not configured in Feldman Home")
            }
        }
        val prefs = HomeAssistantAuth.prefs(ctx)
        return Bundle().apply {
            putBoolean("ok", true)
            putBoolean("has_credentials", true)
            putString("url", prefs.getString("url", "").orEmpty())
            putString("frigate_url", prefs.getString("frigate_url", "").orEmpty())
            putString(HomeAssistantAuth.KEY_ACCESS_TOKEN, prefs.getString(HomeAssistantAuth.KEY_ACCESS_TOKEN, "").orEmpty())
            putString(HomeAssistantAuth.KEY_REFRESH_TOKEN, prefs.getString(HomeAssistantAuth.KEY_REFRESH_TOKEN, "").orEmpty())
            putString(HomeAssistantAuth.KEY_CLIENT_ID, prefs.getString(HomeAssistantAuth.KEY_CLIENT_ID, "").orEmpty())
            putString(HomeAssistantAuth.KEY_AUTH_METHOD, prefs.getString(HomeAssistantAuth.KEY_AUTH_METHOD, "").orEmpty())
            putLong(HomeAssistantAuth.KEY_EXPIRES_AT_MS, prefs.getLong(HomeAssistantAuth.KEY_EXPIRES_AT_MS, 0L))
        }
    }

    private fun getSnapshot(orientation: String): Bundle {
        val ctx = context?.applicationContext ?: return errorBundle("Provider is not attached")
        if (!HomeAssistantAuth.hasCredentials(ctx)) {
            return Bundle().apply {
                putBoolean("ok", false)
                putBoolean("has_credentials", false)
                putString("error", "Home Assistant is not configured in Feldman Home")
            }
        }

        val cardsPrefs = ctx.getSharedPreferences(CARDS_PREFS, Context.MODE_PRIVATE)
        val portraitIds = cardsPrefs.csvList(SELECTED_ENTITIES)
        val selectedIds = if (orientation == ORIENTATION_LANDSCAPE) {
            cardsPrefs.csvList(SELECTED_ENTITIES_LAND).ifEmpty { portraitIds }
        } else {
            portraitIds
        }
        val buttonIds = cardsPrefs.csvList(BUTTON_CARD_IDS)
        val cameraIds = cardsPrefs.csvList(CAMERA_CARD_IDS)
        val cardIds = (selectedIds + buttonIds + cameraIds).distinct()
        val configsJson = cardsPrefs.getString(CARD_CONFIGS, null).orEmpty()
        val namesJson = cardsPrefs.getString(ENTITY_NAMES, null).orEmpty()
        val configs = configsJson.toJSONObjectOrEmpty()

        val referencedIds = collectReferencedEntityIds(cardIds, configs)
        val stateIds = (selectedIds + referencedIds).distinct().filterNot(::isSyntheticCardId)

        return try {
            val api = getStoredApi(ctx) ?: return errorBundle("Home Assistant API is unavailable")
            val states = runBlocking(Dispatchers.IO) { api.getStates() }
            val stateById = states.associateBy { it.entity_id }
            val referencedStates = stateIds.map { id ->
                stateById[id] ?: HAEntity(id, "unknown", emptyMap())
            }
            val snapshotStates = (states + referencedStates).distinctBy { it.entity_id }

            Bundle().apply {
                putBoolean("ok", true)
                putBoolean("has_credentials", true)
                putStringArrayList("card_ids", ArrayList(cardIds))
                putStringArrayList("selected_ids", ArrayList(selectedIds))
                putStringArrayList("button_ids", ArrayList(buttonIds))
                putStringArrayList("camera_ids", ArrayList(cameraIds))
                putString("states_json", gson.toJson(snapshotStates))
                putString("configs_json", configsJson.ifBlank { "{}" })
                putString("names_json", namesJson.ifBlank { "{}" })
                putLong("updated_at", System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot failed", e)
            errorBundle(e.userMessage())
        }
    }

    /** Saved card setups, so sibling Feldman apps (Clock standby) can list them in their picker. */
    private fun getPresets(): Bundle {
        val ctx = context?.applicationContext ?: return errorBundle("Provider is not attached")
        return Bundle().apply {
            putBoolean("ok", true)
            putString("presets_json", com.feldman.ha.ui.cards.CardPresets.rawJson(ctx))
        }
    }

    private fun callService(extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return errorBundle("Provider is not attached")
        val domain = extras?.getString("domain").orEmpty()
        val service = extras?.getString("service").orEmpty()
        val bodyJson = extras?.getString("body_json").orEmpty()
        if (domain.isBlank() || service.isBlank()) return errorBundle("Missing service target")

        return try {
            val body = bodyJson.toJSONObjectOrEmpty().toMap()
            val api = getStoredApi(ctx) ?: return errorBundle("Home Assistant API is unavailable")
            val response = runBlocking(Dispatchers.IO) {
                api.callService(domain, service, body)
            }
            Bundle().apply {
                putBoolean("ok", response.isSuccessful)
                putInt("status_code", response.code())
                if (!response.isSuccessful) putString("error", "Home Assistant returned ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service call failed", e)
            errorBundle(e.userMessage())
        }
    }

    private fun collectReferencedEntityIds(cardIds: List<String>, configs: JSONObject): Set<String> {
        val ids = linkedSetOf<String>()
        cardIds.forEach { cardId ->
            val cfg = configs.optJSONObject(cardId) ?: return@forEach

            cfg.optString("entityId").takeIf { it.isNotBlank() }?.let(ids::add)
            collectTemplateEntityIds(cfg.optString("state_template")).forEach(ids::add)

            cfg.optJSONArray("custom_features")?.forEachObject { feature ->
                feature.optString("targetEntity").takeIf { it.isNotBlank() }?.let(ids::add)
                feature.optJSONArray("targets")?.forEachObject { target ->
                    if (target.optString("type") == "entity") {
                        target.optString("value").takeIf { it.isNotBlank() }?.let(ids::add)
                    }
                }
                collectTemplateEntityIds(feature.optString("valueTemplate")).forEach(ids::add)
                feature.optJSONArray("visibility")?.forEachObject { collectConditionEntityIds(it, ids) }
            }

            cfg.optJSONArray("label_blocks")?.forEachObject { block ->
                block.optJSONArray("rules")?.forEachObject { rule ->
                    rule.optJSONArray("conditions")?.forEachObject { collectConditionEntityIds(it, ids) }
                }
            }

            if (cfg.optString("camera_source") == "HA") {
                cfg.optString("camera_id").takeIf { it.isNotBlank() }?.let(ids::add)
            }
        }
        return ids
    }

    private fun collectConditionEntityIds(condition: JSONObject, out: MutableSet<String>) {
        when (condition.optString("condition")) {
            "state", "numeric_state" -> {
                condition.optString("entity").takeIf { it.isNotBlank() }?.let(out::add)
            }
            "and", "or", "not" -> {
                condition.optJSONArray("conditions")?.forEachObject { collectConditionEntityIds(it, out) }
            }
        }
    }

    private fun collectTemplateEntityIds(template: String?): Set<String> {
        if (template.isNullOrBlank()) return emptySet()
        val blacklist = setOf("state", "name", "attributes", "current", "remaining")
        return Regex("\\{([^}.]+(?:\\.[^}.]+)+)\\}")
            .findAll(template)
            .mapNotNull { match ->
                val parts = match.groupValues[1].trim().split(".")
                if (parts.size >= 2 && parts[0] !in blacklist) "${parts[0]}.${parts[1]}" else null
            }
            .toSet()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

private fun android.content.SharedPreferences.csvList(key: String): List<String> =
    getString(key, "").orEmpty()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun String.toJSONObjectOrEmpty(): JSONObject =
    runCatching { if (isBlank()) JSONObject() else JSONObject(this) }.getOrDefault(JSONObject())

private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        optJSONObject(i)?.let(block)
    }
}

private fun JSONObject.toMap(): Map<String, Any> {
    val out = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = opt(key)
        if (value != null && value != JSONObject.NULL) out[key] = value.toKotlinValue()
    }
    return out
}

private fun JSONArray.toListValue(): List<Any> {
    val out = mutableListOf<Any>()
    for (i in 0 until length()) {
        val value = opt(i)
        if (value != null && value != JSONObject.NULL) out += value.toKotlinValue()
    }
    return out
}

private fun Any.toKotlinValue(): Any =
    when (this) {
        is JSONObject -> toMap()
        is JSONArray -> toListValue()
        else -> this
    }

private fun isSyntheticCardId(id: String): Boolean =
    id.startsWith("button.") || id.startsWith("camera.card_")

private fun errorBundle(message: String): Bundle =
    Bundle().apply {
        putBoolean("ok", false)
        putString("error", message)
    }

private fun Throwable.userMessage(): String =
    when (this) {
        is HttpException -> "Home Assistant returned ${code()}"
        else -> localizedMessage ?: javaClass.simpleName
    }
