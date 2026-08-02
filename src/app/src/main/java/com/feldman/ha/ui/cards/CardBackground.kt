package com.feldman.ha.ui.cards

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.feldman.motion.ThemeRepository
import com.feldman.motion.isDarkTheme
import com.feldman.motion.themeColors
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.rememberDynamicScheme

/** Corner radius shared by every in-app card surface (and its selection border/drag shapes). */
val CARD_CORNER_RADIUS = 32.dp

/**
 * App-wide choice of card and widget background:
 * the system secondary accent palette (tinted, the default) or surfaceContainer (neutral).
 */
object CardBackgroundSetting {
    private const val PREF_KEY = "card_bg_secondary"

    // Bumped on change so composables that read the setting recompose live
    // (SharedPreferences alone wouldn't trigger recomposition).
    private val revision = mutableIntStateOf(0)

    fun useSecondary(context: Context): Boolean {
        revision.intValue // snapshot read → subscribers recompose when the setting changes
        return context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, true)
    }

    fun setUseSecondary(context: Context, value: Boolean) {
        context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, value).apply()
        revision.intValue++
    }
}

/**
 * Toggleable preference for the Expressive 40% primaryContainer tinted header canvas
 * and rounded inset content sheet layout.
 */
object ExpressiveCanvasSetting {
    private const val PREF_KEY = "expressive_canvas_enabled"
    private val revision = mutableIntStateOf(0)

    fun isEnabled(context: Context): Boolean {
        revision.intValue
        return context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, true)
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, value).apply()
        revision.intValue++
    }
}

/**
 * Optional host override of the card surface. The Clock standby screensaver renders cards
 * as faint gray fills or outlines over a pure black background; Home leaves this null and
 * keeps the regular tinted/neutral card surfaces.
 */
data class CardSurface(
    val background: Color,
    val buttonBackground: Color,
    val border: Color? = null
)

val LocalCardSurfaceOverride = androidx.compose.runtime.compositionLocalOf<CardSurface?> { null }

/**
 * False when the host cannot show popup windows correctly. The Clock screensaver visually
 * rotates the whole UI inside a portrait DreamService window; Popups/DropdownMenus open in
 * their own (unrotated) window and would render sideways, so such hosts get inline
 * fallbacks instead.
 */
val LocalPopupsAllowed = androidx.compose.runtime.compositionLocalOf { true }

/**
 * A tone from the secondary palette the app is themed with.
 *
 * With dynamic color on, this reads the platform's own palette resources
 * (android.R.color.system_accent2_*) so cards match launchers and System UI. Deliberately NOT
 * colorScheme.onSecondary: on Android 16+ the platform computes that role with the newer color
 * spec, so it diverges from the wallpaper palette the rest of the home screen is tinted with.
 *
 * Those resources are framework-owned and always track the wallpaper, so with dynamic color off
 * they would ignore the seed color picked in theme settings. In that case derive the same tones
 * from that seed instead, using the palette AppTheme builds the rest of the scheme from.
 */
@Composable
private fun secondaryTint(lightTone: Int, darkTone: Int, lightRes: Int, darkRes: Int): Color {
    val context = LocalContext.current
    val themeRepository = remember(context) { ThemeRepository(context) }
    val dynamicColor by themeRepository.dynamicColor.collectAsState(initial = true)
    val dark = isDarkTheme()

    if (dynamicColor) return colorResource(if (dark) darkRes else lightRes)

    val themeColorIndex by themeRepository.themeColor.collectAsState(initial = 0)
    val vibrantPalette by themeRepository.tintPalette.collectAsState(initial = false)
    val seedPair = themeColors[themeColorIndex.coerceIn(0, themeColors.lastIndex)]
    val scheme = rememberDynamicScheme(
        seedColor = if (dark) seedPair.dark else seedPair.light,
        isDark = dark,
        style = if (vibrantPalette) PaletteStyle.Vibrant else PaletteStyle.TonalSpot,
        specVersion = ColorSpec.SpecVersion.SPEC_2025
    )
    return Color(scheme.secondaryPalette.tone(if (dark) darkTone else lightTone))
}

/** The selected card/widget background color. */
@Composable
fun cardBackgroundColor(): Color =
    LocalCardSurfaceOverride.current?.let { return it.background } ?:
    if (CardBackgroundSetting.useSecondary(LocalContext.current)) {
        secondaryTint(
            lightTone = 100, darkTone = 20,
            lightRes = android.R.color.system_accent2_0,
            darkRes = android.R.color.system_accent2_800
        )
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

/** Background for off/unselected buttons and controls sitting on a card. */
@Composable
fun cardButtonBackgroundColor(): Color =
    LocalCardSurfaceOverride.current?.let { return it.buttonBackground } ?:
    if (CardBackgroundSetting.useSecondary(LocalContext.current)) {
        // A step deeper than the tinted card background, so controls read as wells sunk into
        // the card — neutral surfaceVariant looks washed out here.
        secondaryTint(
            lightTone = 90, darkTone = 10,
            lightRes = android.R.color.system_accent2_100,
            darkRes = android.R.color.system_accent2_900
        )
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
