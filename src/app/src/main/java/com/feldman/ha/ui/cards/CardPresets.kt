package com.feldman.ha.ui.cards

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved card setups ("presets"): a card's full config map + display name, keyed by the card
 * it was saved from. Saving is manual (from the card settings page) and applying creates a
 * brand-new card from the snapshot — presets never auto-update.
 *
 * Presets are stored locally per app. A host can additionally surface presets from a sibling
 * app by setting [remoteLoader] (the Clock standby reads Feldman Home's presets through the
 * signature-protected cards bridge).
 */
object CardPresets {
    private const val PREFS = "card_presets"
    private const val KEY = "presets_v1"

    data class CardPreset(
        val id: String,
        val name: String,
        /** The card instance id it was saved from: an entity_id, or a synthetic button./clock.card_/camera.card_ id. */
        val sourceId: String,
        val config: Map<String, Any>,
        /** Set on presets that came from another app (display label, e.g. "Home"). */
        val remoteSource: String? = null
    )

    /** Optional loader for presets stored in a sibling app; queried when the picker opens. */
    @Volatile
    var remoteLoader: ((Context) -> List<CardPreset>)? = null

    fun load(context: Context): List<CardPreset> =
        parse(prefs(context).getString(KEY, null))

    fun loadRemote(context: Context): List<CardPreset> =
        runCatching { remoteLoader?.invoke(context) }.getOrNull() ?: emptyList()

    fun save(context: Context, preset: CardPreset) {
        val all = load(context).filterNot { it.id == preset.id } + preset
        prefs(context).edit().putString(KEY, serialize(all)).apply()
    }

    fun delete(context: Context, id: String) {
        val all = load(context).filterNot { it.id == id }
        prefs(context).edit().putString(KEY, serialize(all)).apply()
    }

    /** Raw stored JSON, for handing to sibling apps over the bridge. */
    fun rawJson(context: Context): String = prefs(context).getString(KEY, null) ?: "[]"

    /** Parses bridge/stored JSON into presets; [remoteSource] labels them as foreign. */
    fun parse(json: String?, remoteSource: String? = null): List<CardPreset> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        CardPreset(
                            id = id,
                            name = obj.optString("name").ifBlank { "Card" },
                            sourceId = obj.optString("source_id"),
                            config = obj.optJSONObject("config")?.toPlainMap() ?: emptyMap(),
                            remoteSource = remoteSource
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serialize(presets: List<CardPreset>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("source_id", preset.sourceId)
                    put("config", preset.config.toJson())
                }
            )
        }
        return array.toString()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── JSON <-> plain Kotlin maps (configs nest lists/maps: custom features, label blocks) ──

    private fun Map<String, Any>.toJson(): JSONObject {
        val obj = JSONObject()
        forEach { (k, v) -> obj.put(k, v.toJsonValue()) }
        return obj
    }

    private fun Any.toJsonValue(): Any = when (this) {
        is Map<*, *> -> JSONObject().also { obj ->
            forEach { (k, v) -> if (k is String && v != null) obj.put(k, v.toJsonValue()) }
        }
        is List<*> -> JSONArray().also { arr -> forEach { v -> if (v != null) arr.put(v.toJsonValue()) } }
        else -> this
    }

    private fun JSONObject.toPlainMap(): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = opt(key)
            if (value != null && value != JSONObject.NULL) out[key] = value.toPlainValue()
        }
        return out
    }

    private fun JSONArray.toPlainList(): List<Any> {
        val out = mutableListOf<Any>()
        for (i in 0 until length()) {
            val value = opt(i)
            if (value != null && value != JSONObject.NULL) out += value.toPlainValue()
        }
        return out
    }

    private fun Any.toPlainValue(): Any = when (this) {
        is JSONObject -> toPlainMap()
        is JSONArray -> toPlainList()
        else -> this
    }
}
