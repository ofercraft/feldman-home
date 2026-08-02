package com.feldman.ha.ui.pages

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.feldman.ha.ui.standby.HomeStandbyCardStyle
import com.feldman.ha.ui.standby.HomeStandbyOrientation
import com.feldman.ha.ui.standby.HomeStandbyPreferences
import com.feldman.ha.ui.standby.HomeStandbyPreviewActivity
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.isDarkTheme
import com.feldman.motion.rememberSymbolPainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreensaverSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val isDark = isDarkTheme()
    var orientation by remember {
        mutableIntStateOf(HomeStandbyPreferences.orientation(context).ordinal)
    }
    var cardStyle by remember {
        mutableIntStateOf(HomeStandbyPreferences.cardStyle(context).ordinal)
    }

    SettingsScaffold(
        title = "Screensaver",
        scaffoldModifier = Modifier.fillMaxSize(),
        topBar = { SettingsTopBar("Screensaver", onBack) }
    ) {
        title("Dashboard")
        section {
            pageItem(
                title = "Customize dashboard",
                description = "Choose and arrange cards shown in standby",
                icon = rememberSymbolPainter("dashboard"),
                backgroundColor = SettingsCategoryColor.SCREENSAVER.container(isDark),
                iconColor = SettingsCategoryColor.SCREENSAVER.content(isDark),
                onClick = {
                    context.startActivity(Intent(context, HomeStandbyPreviewActivity::class.java))
                }
            )
        }

        title("Orientation")
        section {
            segmentedPickerItem(
                options = listOf("Auto", "Portrait", "Landscape"),
                icons = listOf(
                    rememberSymbolPainter("screen_rotation"),
                    rememberSymbolPainter("stay_current_portrait"),
                    rememberSymbolPainter("stay_current_landscape")
                ),
                selectedIndex = orientation,
                onSelected = { index ->
                    orientation = index
                    HomeStandbyPreferences.setOrientation(
                        context,
                        HomeStandbyOrientation.entries[index]
                    )
                }
            )
        }

        title("Cards")
        section {
            segmentedPickerItem(
                options = listOf("Filled", "Outlined"),
                icons = listOf(
                    rememberSymbolPainter("view_agenda"),
                    rememberSymbolPainter("select")
                ),
                selectedIndex = cardStyle,
                onSelected = { index ->
                    cardStyle = index
                    HomeStandbyPreferences.setCardStyle(
                        context,
                        HomeStandbyCardStyle.entries[index]
                    )
                }
            )
        }

        title("Android")
        section {
            pageItem(
                title = "Screensaver settings",
                description = "Select Feldman Home and choose when it starts",
                icon = rememberSymbolPainter("settings_display"),
                backgroundColor = SettingsCategoryColor.DEVICE.container(isDark),
                iconColor = SettingsCategoryColor.DEVICE.content(isDark),
                onClick = {
                    val dreamSettings = Intent(Settings.ACTION_DREAM_SETTINGS)
                    val destination = if (dreamSettings.resolveActivity(context.packageManager) != null) {
                        dreamSettings
                    } else {
                        Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    }
                    context.startActivity(destination)
                }
            )
        }
    }
}
