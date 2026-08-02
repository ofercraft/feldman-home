package com.feldman.ha.ui.camera

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class CameraSource { HA, FRIGATE }

const val FACTORY_CAMERA_IDS_KEY = "camera_card_ids_v2"
const val FACTORY_CAMERA_SOURCE_KEY = "camera_source"
const val FACTORY_CAMERA_ID_KEY = "camera_id"
const val FACTORY_CAMERA_NAME_KEY = "camera_name"
const val FACTORY_CAMERA_REFRESH_INTERVAL_KEY = "camera_refresh_interval_sec"

private const val FACTORY_CARD_CONFIGS_KEY = "card_cfg_v2"
private const val FACTORY_CARD_SPAN_X_KEY = "card_span_x"
private const val FACTORY_CARD_SPAN_Y_KEY = "card_span_y"
private const val FACTORY_CARD_HEIGHT_DEFAULT_KEY = "card_height_default"

data class CameraCardConfig(
    val id: String,       // "camera.front_door" for HA, "driveway" for Frigate
    val source: CameraSource,
    val name: String,
    val spanX: Int = 2,
    val spanY: Int = 3,
    // How often the dashboard snapshot is refreshed, in seconds. Defaults to 3 minutes;
    // clamped to a sane range on load so the camera is never hammered nor effectively frozen.
    val refreshIntervalSec: Int = 180,
) {
    /** Stable key used for the snapshot file/cache (independent of the cache-busting timestamp). */
    val snapshotKey: String get() = "${source.name}_$id"
}

const val CAMERA_REFRESH_MIN_SEC = 30
const val CAMERA_REFRESH_MAX_SEC = 3600

fun cameraCardConfigFromFactory(
    entityId: String,
    displayName: String,
    config: Map<String, Any>,
    gridColumns: Int = 8,
): CameraCardConfig {
    val source = runCatching {
        CameraSource.valueOf(config[FACTORY_CAMERA_SOURCE_KEY] as? String ?: CameraSource.HA.name)
    }.getOrDefault(CameraSource.HA)
    val id = (config[FACTORY_CAMERA_ID_KEY] as? String)?.takeIf { it.isNotBlank() }
        ?: if (source == CameraSource.HA) entityId else entityId.removePrefix("camera.")
    val name = displayName.ifBlank {
        (config[FACTORY_CAMERA_NAME_KEY] as? String)?.takeIf { it.isNotBlank() } ?: id
    }
    val spanX = ((config[FACTORY_CARD_SPAN_X_KEY] as? Number)?.toInt() ?: 2)
        .coerceIn(1, gridColumns.coerceAtLeast(1))
    val spanY = ((config[FACTORY_CARD_SPAN_Y_KEY] as? Number)?.toInt() ?: 3)
        .coerceIn(1, 24)
    val refreshIntervalSec = ((config[FACTORY_CAMERA_REFRESH_INTERVAL_KEY] as? Number)?.toInt() ?: 180)
        .coerceIn(CAMERA_REFRESH_MIN_SEC, CAMERA_REFRESH_MAX_SEC)
    return CameraCardConfig(id, source, name, spanX, spanY, refreshIntervalSec)
}

fun factoryConfigForCameraCard(config: CameraCardConfig): Map<String, Any> = mapOf(
    FACTORY_CAMERA_SOURCE_KEY to config.source.name,
    FACTORY_CAMERA_ID_KEY to config.id,
    FACTORY_CAMERA_NAME_KEY to config.name,
    FACTORY_CAMERA_REFRESH_INTERVAL_KEY to config.refreshIntervalSec,
    FACTORY_CARD_SPAN_X_KEY to config.spanX,
    FACTORY_CARD_SPAN_Y_KEY to config.spanY,
    FACTORY_CARD_HEIGHT_DEFAULT_KEY to false,
)

fun loadCameraCards(prefs: SharedPreferences): List<CameraCardConfig> {
    val json = prefs.getString("camera_cards_v1", null) ?: return emptyList()
    return runCatching {
        JSONArray(json).let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val source = if (obj.optString("source") == "FRIGATE") CameraSource.FRIGATE else CameraSource.HA
                    val name = obj.optString("name").takeIf { it.isNotBlank() } ?: id
                    val spanX = obj.optInt("spanX", 2).coerceIn(1, 8)
                    val spanY = obj.optInt("spanY", 3).coerceIn(1, 24)
                    val refreshIntervalSec = obj.optInt("refreshIntervalSec", 180)
                        .coerceIn(CAMERA_REFRESH_MIN_SEC, CAMERA_REFRESH_MAX_SEC)
                    CameraCardConfig(id, source, name, spanX, spanY, refreshIntervalSec)
                } catch (_: JSONException) { null }
            }
        }
    }.getOrElse { emptyList() }
}

fun saveCameraCards(prefs: SharedPreferences, cards: List<CameraCardConfig>) {
    val arr = JSONArray()
    cards.forEach { c ->
        arr.put(JSONObject().apply {
            put("id", c.id)
            put("source", c.source.name)
            put("name", c.name)
            put("spanX", c.spanX)
            put("spanY", c.spanY)
            put("refreshIntervalSec", c.refreshIntervalSec)
        })
    }
    prefs.edit().putString("camera_cards_v1", arr.toString()).apply()
}

fun loadFactoryCameraCards(prefs: SharedPreferences): List<CameraCardConfig> {
    val ids = prefs.getString(FACTORY_CAMERA_IDS_KEY, "")
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    if (ids.isEmpty()) return emptyList()

    val configsJson = prefs.getString(FACTORY_CARD_CONFIGS_KEY, null) ?: return emptyList()
    return runCatching {
        val configsObj = JSONObject(configsJson)
        ids.mapNotNull { entityId ->
            val configObj = configsObj.optJSONObject(entityId) ?: return@mapNotNull null
            val configMap = configObj.toMap()
            val name = (configMap[FACTORY_CAMERA_NAME_KEY] as? String)?.takeIf { it.isNotBlank() }
                ?: entityId
            cameraCardConfigFromFactory(entityId, name, configMap)
        }
    }.getOrElse { emptyList() }
}

fun loadConfiguredCameraCards(prefs: SharedPreferences): List<CameraCardConfig> {
    val factoryCards = loadFactoryCameraCards(prefs)
    val legacyCards = loadCameraCards(prefs)
    return (factoryCards + legacyCards).distinctBy { it.snapshotKey }
}

private fun JSONObject.toMap(): Map<String, Any> {
    val out = mutableMapOf<String, Any>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        val value = opt(key)
        if (value != null && value != JSONObject.NULL) {
            out[key] = value
        }
    }
    return out
}
