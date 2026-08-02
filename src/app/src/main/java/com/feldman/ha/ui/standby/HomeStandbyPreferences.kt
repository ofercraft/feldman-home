package com.feldman.ha.ui.standby

import android.content.Context
import androidx.core.content.edit

enum class HomeStandbyOrientation { AUTO, PORTRAIT, LANDSCAPE }

enum class HomeStandbyCardStyle { FILLED, OUTLINED }

object HomeStandbyPreferences {
    private const val PREFS_NAME = "home_standby_prefs"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_CARD_STYLE = "card_style"

    fun orientation(context: Context): HomeStandbyOrientation =
        HomeStandbyOrientation.entries.getOrElse(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_ORIENTATION, HomeStandbyOrientation.AUTO.ordinal)
        ) { HomeStandbyOrientation.AUTO }

    fun setOrientation(context: Context, value: HomeStandbyOrientation) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_ORIENTATION, value.ordinal)
        }
    }

    fun cardStyle(context: Context): HomeStandbyCardStyle =
        HomeStandbyCardStyle.entries.getOrElse(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CARD_STYLE, HomeStandbyCardStyle.FILLED.ordinal)
        ) { HomeStandbyCardStyle.FILLED }

    fun setCardStyle(context: Context, value: HomeStandbyCardStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_CARD_STYLE, value.ordinal)
        }
    }
}
