package com.feldman.ha.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.dataStore by preferencesDataStore("card_settings")

object CardRowPrefs {
    private fun keyFor(title: String) = stringPreferencesKey("removed_card_rows_$title")
    private fun initKeyFor(title: String) = stringPreferencesKey("card_rows_initialized_$title")

    suspend fun saveRemovedRows(context: Context, title: String, removed: Set<String>) {
        context.dataStore.edit {
            it[keyFor(title)] = removed.joinToString("|")
        }
    }

    suspend fun loadRemovedRows(context: Context, title: String): Set<String> {
        val stored = context.dataStore.data.first()[keyFor(title)] ?: return emptySet()
        return stored.split("|").filter { it.isNotBlank() }.toSet()
    }

    suspend fun hasEverSaved(context: Context, title: String): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[initKeyFor(title)] == "true"
    }

    suspend fun markAsSaved(context: Context, title: String) {
        context.dataStore.edit {
            it[initKeyFor(title)] = "true"
        }
    }
}
