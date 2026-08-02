package com.feldman.ha.ui.editors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.feldman.motion.rememberSymbolPainter
import com.feldman.ha.ui.dashboard.FullScreenAddPage
import com.feldman.ha.ui.cards.resolveDrawableIcon

/**
 * Curated list of Material Symbols (Rounded) names. Every name here is a valid ligature in the
 * bundled font, so it renders as the glyph (not literal text). Includes everything the app's
 * widget specs already use, plus a broad common set for the icon picker.
 */
val MATERIAL_SYMBOL_NAMES: List<String> = listOf(
    // Home / IoT
    "lightbulb", "light", "light_mode", "dark_mode", "thermostat", "ac_unit", "mode_fan", "mode_fan_off",
    "air", "water_drop", "humidity_percentage", "heat", "mode_heat", "mode_cool", "hvac", "heat_pump",
    "sensors", "sensor_door", "sensor_window", "sensor_occupied", "motion_sensor_active", "door_front",
    "door_open", "door_sliding", "garage", "garage_door", "blinds", "blinds_closed", "roller_shades",
    "roller_shades_closed", "curtains", "curtains_closed", "window", "window_closed", "meeting_room",
    "bed", "bedroom_parent", "living", "kitchen", "bathroom", "bathtub", "shower", "weekend", "chair",
    "table_restaurant", "tv", "tv_gen", "speaker", "speaker_group", "outlet", "power", "power_off",
    "power_settings_new", "bolt", "flash_on", "flash_off", "electric_bolt", "energy_savings_leaf",
    "water_heater", "water", "propane", "gas_meter", "electric_meter", "solar_power", "battery_charging_full",
    "battery_full", "battery_alert", "ev_station", "vacuum", "cleaning_services", "iron", "coffee_maker",
    "microwave", "oven_gen", "dishwasher_gen", "laundry", "local_laundry_service",
    // Climate / weather
    "sunny", "clear_day", "cloudy", "partly_cloudy_day", "rainy", "thunderstorm", "snowing", "foggy",
    "weather_snowy", "wb_sunny", "wb_cloudy", "nights_stay", "bedtime", "wb_twilight", "whatshot",
    "local_fire_department", "fireplace", "umbrella", "beach_access", "wind_power", "tornado",
    // Security
    "shield", "security", "lock", "lock_open", "key", "vpn_key", "fingerprint", "verified_user",
    "gpp_good", "gpp_bad", "gpp_maybe", "policy", "admin_panel_settings", "no_encryption", "enhanced_encryption",
    "notification_important", "shield_lock", "shield_person", "home_health", "health_and_safety",
    // Navigation / UI
    "home", "settings", "search", "close", "check", "add", "add_circle", "remove", "delete", "edit",
    "menu", "more_vert", "more_horiz", "arrow_back", "arrow_forward", "arrow_upward", "arrow_downward",
    "chevron_left", "chevron_right", "expand_more", "expand_less", "refresh", "sync", "done", "done_all",
    "clear", "cancel", "save", "tune", "build", "handyman", "construction", "settings_remote",
    "smart_button", "smart_toy", "smart_display", "dashboard", "widgets", "apps", "grid_view", "view_list",
    "toggle_on", "toggle_off", "radio_button_checked", "radio_button_unchecked", "check_circle",
    "open_in_full", "fullscreen", "fullscreen_exit", "drag_handle", "swap_vert", "swap_horiz",
    // Favorites / status
    "favorite", "favorite_border", "star", "star_border", "bookmark", "bookmark_border", "info", "help",
    "warning", "error", "report", "notifications", "notifications_active", "notifications_off",
    "visibility", "visibility_off", "thumb_up", "thumb_down", "flag", "label", "priority_high", "verified",
    // People / account
    "account_circle", "person", "person_add", "group", "groups", "supervisor_account", "manage_accounts",
    "badge", "face", "sentiment_satisfied", "child_care", "elderly", "pets", "cruelty_free",
    // Files / cloud
    "share", "link", "content_copy", "download", "upload", "cloud", "cloud_upload", "cloud_download",
    "cloud_done", "cloud_off", "folder", "folder_open", "file_copy", "attach_file", "description",
    // Media
    "image", "photo", "photo_camera", "videocam", "videocam_off", "movie", "mic", "mic_off", "volume_up",
    "volume_down", "volume_mute", "volume_off", "play_arrow", "pause", "stop", "skip_next", "skip_previous",
    "fast_forward", "fast_rewind", "replay", "shuffle", "repeat", "queue_music", "library_music",
    "music_note", "headphones", "cast", "cast_connected", "radio", "podcasts", "live_tv", "subscriptions",
    // Devices
    "smartphone", "computer", "laptop", "tablet", "watch", "keyboard", "mouse", "print", "scanner",
    "router", "wifi", "wifi_off", "bluetooth", "bluetooth_connected", "signal_cellular_alt", "nest_cam_iq",
    "memory", "developer_board", "cable", "usb", "devices", "devices_other", "phonelink", "settings_input_antenna",
    // Time
    "schedule", "timer", "timer_off", "alarm", "alarm_on", "alarm_off", "alarm_add", "hourglass_empty",
    "hourglass_top", "history", "update", "event", "calendar_today", "calendar_month", "today", "date_range",
    "av_timer", "snooze", "nightlight", "wb_twilight",
    // Places / transport
    "place", "location_on", "location_off", "map", "navigation", "directions", "near_me", "my_location",
    "home_pin", "directions_car", "directions_bike", "directions_walk", "directions_bus", "train", "flight",
    "electric_car", "two_wheeler", "pedal_bike", "local_gas_station", "local_parking", "garage_home",
    "exit_to_app", "logout", "login", "meeting_room", "elevator", "stairs", "escalator",
    // Communication
    "call", "call_end", "phone", "phone_in_talk", "email", "mail", "message", "chat", "chat_bubble",
    "sms", "send", "forum", "contacts", "contact_phone", "voicemail", "ring_volume", "dialpad",
    // Activities / misc
    "sports_esports", "fitness_center", "restaurant", "local_cafe", "local_bar", "local_dining",
    "shopping_cart", "shopping_bag", "store", "storefront", "payments", "attach_money", "savings",
    "credit_card", "work", "school", "science", "biotech", "eco", "recycling", "park", "forest",
    "grass", "spa", "self_improvement", "celebration", "cake", "redeem", "card_giftcard", "emoji_objects",
    "lightbulb_circle", "auto_awesome", "rocket_launch", "bug_report", "code", "terminal", "data_object",
    "qr_code", "qr_code_scanner", "label_important", "category", "extension", "interests", "hub",
    "trending_up", "trending_down", "show_chart", "bar_chart", "pie_chart", "speed", "monitoring",
    "hdr_auto", "auto_mode", "tips_and_updates", "format_color_reset", "palette", "colorize", "brush",
    "wallpaper", "filter_drama", "blur_on", "gradient", "texture",
).distinct()

/** "wb_twilight" -> "Wb Twilight" for display. Search still matches the raw underscore name. */
private fun prettyIconName(name: String): String =
    name.replace('_', ' ')
        .split(' ')
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPickerDialog(
    initial: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial) }

    val filtered = remember(query) {
        val q = query.trim().lowercase().replace(' ', '_')
        if (q.isBlank()) MATERIAL_SYMBOL_NAMES
        else MATERIAL_SYMBOL_NAMES.filter { it.contains(q) }
    }

    FullScreenAddPage(
        title = "Choose icon",
        onClose = onDismiss,
        actions = {
            TextButton(onClick = { onPick(selected) }) { Text("Done") }
        }
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search icons...") },
            leadingIcon = { Icon(rememberSymbolPainter("search"), null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(rememberSymbolPainter("close"), null) }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(16.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it }) { name ->
                val isSel = name == selected
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSel) scheme.primary.copy(alpha = 0.18f)
                            else scheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .clickable { selected = name }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val context = LocalContext.current
                    val resId = remember(name) { resolveDrawableIcon(context, name) }
                    Icon(
                        painter = if (resId != null) androidx.compose.ui.res.painterResource(resId) else rememberSymbolPainter(name),
                        contentDescription = name,
                        tint = if (isSel) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = prettyIconName(name),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) scheme.primary else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (filtered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = rememberSymbolPainter("search_off"),
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No icons match", color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}
