package com.feldman.ha.ui.pages

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.mobile.MobileAppRegistration
import com.feldman.ha.widgets.WidgetSettingsActivity
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.isDarkTheme
import com.feldman.motion.rememberSymbolPainter

/**
 * Hub for everything configurable. Each row is a destination rather than an inline control, so the
 * page stays a readable map of the app instead of one long scroll mixing text fields, switches and
 * chips. Each category keeps its own icon colour so the list is scannable without reading it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenScreensaver: () -> Unit,
    onOpenCameras: () -> Unit,
    onOpenPhone: () -> Unit
) {
    val context = LocalContext.current

    // Read on each composition rather than remembered: coming back from the connection or phone
    // page should show the new state, and both are cheap prefs lookups.
    val connected = HomeAssistantAuth.hasCredentials(context)
    val phoneRegistered = MobileAppRegistration.isRegistered(context)
    val isDark = isDarkTheme()

    SettingsScaffold(
        title = "Settings",
        scaffoldModifier = Modifier.fillMaxSize(),
        topBar = { SettingsTopBar("Settings", onBack) }
    ) {
        title("Home Assistant")
        section {
            pageItem(
                title = "Connection",
                description = "Instance URL, sign-in, and local discovery",
                // The only state left in this list: whether the app can actually reach Home
                // Assistant is worth knowing before you tap in. Carried by the glyph rather than
                // the colour, so the category stays recognisable either way.
                icon = rememberSymbolPainter(if (connected) "cloud_done" else "cloud_off"),
                backgroundColor = SettingsCategoryColor.CONNECTION.container(isDark),
                iconColor = SettingsCategoryColor.CONNECTION.content(isDark),
                onClick = onOpenConnection
            )
            pageItem(
                title = "Phone",
                description = "Registration, sensors, and what this device reports",
                icon = rememberSymbolPainter(if (phoneRegistered) "smartphone" else "phonelink_setup"),
                backgroundColor = SettingsCategoryColor.DEVICE.container(isDark),
                iconColor = SettingsCategoryColor.DEVICE.content(isDark),
                onClick = onOpenPhone
            )
        }

        title("App")
        section {
            pageItem(
                title = "Appearance",
                description = "Themes, colors, and visual styles",
                icon = rememberSymbolPainter("palette"),
                backgroundColor = SettingsCategoryColor.APPEARANCE.container(isDark),
                iconColor = SettingsCategoryColor.APPEARANCE.content(isDark),
                onClick = onOpenAppearance
            )
            pageItem(
                title = "Dashboard",
                description = "Grid size, columns, and rows",
                icon = rememberSymbolPainter("dashboard"),
                backgroundColor = SettingsCategoryColor.LAYOUT.container(isDark),
                iconColor = SettingsCategoryColor.LAYOUT.content(isDark),
                onClick = onOpenDashboard
            )
            pageItem(
                title = "Screensaver",
                description = "Dashboard cards shown while your device is idle",
                icon = rememberSymbolPainter("bedtime"),
                backgroundColor = SettingsCategoryColor.SCREENSAVER.container(isDark),
                iconColor = SettingsCategoryColor.SCREENSAVER.content(isDark),
                onClick = onOpenScreensaver
            )
            pageItem(
                title = "Cameras",
                description = "Frigate server, snapshots, and recordings",
                icon = rememberSymbolPainter("videocam"),
                backgroundColor = SettingsCategoryColor.MEDIA.container(isDark),
                iconColor = SettingsCategoryColor.MEDIA.content(isDark),
                onClick = onOpenCameras
            )
            pageItem(
                title = "Widgets",
                description = "Home screen widgets and their entities",
                icon = rememberSymbolPainter("widgets"),
                backgroundColor = SettingsCategoryColor.EXTERNAL.container(isDark),
                iconColor = SettingsCategoryColor.EXTERNAL.content(isDark),
                onClick = {
                    context.startActivity(Intent(context, WidgetSettingsActivity::class.java))
                }
            )
        }
    }
}
