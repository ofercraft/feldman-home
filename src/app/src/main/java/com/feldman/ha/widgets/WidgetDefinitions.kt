package com.feldman.ha.widgets

import com.feldman.ha.R

/**
 * The natural "tap" action for an entity's domain, mirroring HA's default behaviour: toggle for
 * on/off entities, trigger/press/activate for the action-only domains. Anything not special-cased
 * falls back to `homeassistant.toggle`, which works for every entity that supports turn_on/off.
 */
fun buttonEntityAction(domain: String): Pair<String, String> = when (domain) {
    "automation" -> "automation" to "trigger"
    "script" -> "script" to "turn_on"
    "button" -> "button" to "press"
    "input_button" -> "input_button" to "press"
    "scene" -> "scene" to "turn_on"
    "cover" -> "cover" to "toggle"
    "lock" -> "lock" to "toggle"
    else -> "homeassistant" to "toggle"
}

/** Domains shown in the button card's entity picker (entities that support a tap/switch action). */
val BUTTON_ACTIONABLE_DOMAINS: Set<String> = setOf(
    "switch", "light", "fan", "input_boolean", "automation", "script", "button", "input_button",
    "scene", "cover", "lock", "media_player", "siren", "humidifier", "remote", "group", "climate",
    "vacuum", "valve", "water_heater",
)

/**
 * Single Kotlin source of truth for widget configuration and behavior.
 * Add/edit widgets here instead of maintaining generated registry code.
 */
/**
 * Colour modes that mean a light can be dimmed.
 *
 * Home Assistant's rule is that a light is dimmable unless its only supported colour mode is
 * `onoff`, so this is every mode except that one (and `unknown`, which a light reports before it
 * has ever been on). Listing the positives rather than excluding `onoff` keeps a light whose
 * modes are unknown out of the dimmable set instead of guessing it in.
 */
internal val DIMMABLE_LIGHT_COLOR_MODES = listOf(
    "brightness", "color_temp", "hs", "rgb", "rgbw", "rgbww", "white", "xy"
)

object WidgetDefinitions {
    fun registerAll() {
        if (WidgetRegistry.hasSpecs()) return

        WidgetRegistry.register(
            key = "button",
            spec = WidgetSpec(
                key = "button",
                title = "Action Button",
                iconRes = null,
                domain = "button",
                style = CardStyle.BUTTON,
                configureQuery = ConfigureQuery(""),
                rows = emptyList(),
                haBindings = emptyList(),
                actions = ServiceMap(emptyMap())
            )
        )

        // Standalone clock card: a synthetic "clock.card_<uuid>" entity rendered by ClockCard.
        WidgetRegistry.register(
            key = "clock",
            spec = WidgetSpec(
                key = "clock",
                title = "Clock",
                iconRes = null,
                domain = "clock",
                style = CardStyle.CLOCK,
                configureQuery = ConfigureQuery(""),
                rows = emptyList(),
                haBindings = emptyList(),
                actions = ServiceMap(emptyMap()),
                toggles = listOf(
                    ConfigToggleSpec("clock_show_seconds", "Show seconds", defaultValue = false, icon = "timer"),
                )
            )
        )

        WidgetRegistry.register(
            key = "fan",
            spec = WidgetSpec(
                key = "fan",
                title = "Fan",
                iconRes = null,
                domain = "fan",
                configureQuery = ConfigureQuery("fan."),
                rows = listOf(
                    PickerRowSpec(
                        id = "percentage",
                        options = listOf(
                            PickerOptionSpec("0", "Off", icon = R.drawable.ic_fan_off, color = 0xFF484A4E),
                            PickerOptionSpec("33", "Low", icon = R.drawable.ic_one, color = 0xFF31B8E5),
                            PickerOptionSpec("66", "Med", icon = R.drawable.ic_two, color = 0xFF31B8E5),
                            PickerOptionSpec("100", "High", icon = R.drawable.ic_three, color = 0xFF31B8E5),
                        ),
                        supportedIf = SupportedLogic("percentage"),
                        stateAliases = mapOf("off" to "0")
                    ),
                ),
                haBindings = emptyList(),
                actions = ServiceMap(
                    calls = mapOf(
                        "pick" to ServiceCall(
                            domain = "fan",
                            service = "set_percentage",
                            params = mapOf("entity_id" to "{entity}", "percentage" to "{value}")
                        ),
                        "turn_on" to ServiceCall(
                            domain = "fan",
                            service = "turn_on",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                        "turn_off" to ServiceCall(
                            domain = "fan",
                            service = "turn_off",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                        "sheet:preset_mode" to ServiceCall(
                            domain = "fan",
                            service = "set_preset_mode",
                            params = mapOf("entity_id" to "{entity}", "preset_mode" to "{value}")
                        ),
                    )
                ),
                sheet = SheetSpec(
                    blocks = listOf(
                        SheetDataSpec(id = "current_speed", label = "Current speed", attribute = "percentage", unitDefault = "%"),
                        SheetDropdownSpec("percentage", "Speed", optionsFromRow = "percentage", valueAttr = "percentage", actionKey = "pick"),
                        SheetDropdownSpec("preset_mode", "Preset", optionsAttr = "preset_modes", valueAttr = "preset_mode", actionKey = "sheet:preset_mode"),
                    )
                )
            )
        )

        WidgetRegistry.register(
            key = "media_player",
            spec = WidgetSpec(
                key = "media_player",
                title = "Media Player",
                iconRes = null,
                domain = "media_player",
                configureQuery = ConfigureQuery("media_player."),
                // Transport + volume use the standard media_player services, each gated by the
                // entity's supported_features bitmask. The remote button (header) is added
                // separately in FactoryCard when a remote exists on the same device.
                rows = listOf(
                    ButtonsRowSpec(
                        id = "transport",
                        buttons = listOf(
                            WidgetButtonSpec("prev", "Previous", icon = "skip_previous", action = "media_previous_track", supportedIf = SupportedLogic("supported_features", bitmask = 16)),
                            WidgetButtonSpec("play_pause", "Play/Pause", icon = "play_pause", action = "media_play_pause", supportedIf = SupportedLogic("supported_features", bitmask = 1)),
                            WidgetButtonSpec("next", "Next", icon = "skip_next", action = "media_next_track", supportedIf = SupportedLogic("supported_features", bitmask = 32)),
                        )
                    ),
                    ButtonsRowSpec(
                        id = "volume",
                        buttons = listOf(
                            WidgetButtonSpec("vol_down", "Volume down", icon = "volume_down", action = "volume_down", supportedIf = SupportedLogic("supported_features", bitmask = 1024)),
                            WidgetButtonSpec("vol_up", "Volume up", icon = "volume_up", action = "volume_up", supportedIf = SupportedLogic("supported_features", bitmask = 1024)),
                        )
                    ),
                    // Opens the full remote panel; auto-detects the remote on the player's device.
                    RemoteRowSpec(),
                ),
                haBindings = emptyList(),
                actions = ServiceMap(
                    calls = mapOf(
                        "sheet:volume" to ServiceCall(
                            domain = "media_player",
                            service = "volume_set",
                            params = mapOf("entity_id" to "{entity}", "volume_level" to "{value}")
                        ),
                    )
                ),
                sheet = SheetSpec(
                    blocks = listOf(
                        SheetDataSpec(id = "media_title", label = "Now playing", attribute = "media_title"),
                        SheetHeroSpec(
                            id = "volume",
                            kind = SheetHeroKind.SLIDER,
                            valueAttr = "volume_level",
                            minDefault = 0.0, maxDefault = 1.0, stepDefault = 0.01,
                            displayAsPercentOfRange = true,
                            actionKey = "sheet:volume"
                        ),
                        SheetCardRowsSpec(),
                    )
                )
            )
        )

        WidgetRegistry.register(
            key = "climate",
            spec = WidgetSpec(
                key = "climate",
                title = "Climate",
                iconRes = null,
                domain = "climate",
                configureQuery = ConfigureQuery("climate."),
                rows = listOf(
                    DataRowSpec("current_temperature", label = "Current", unit = "°C"),
                    CounterRowSpec("temperature", decIcon = "remove", incIcon = "add"),
                    PickerRowSpec(
                        id = "hvac_mode",
                        options = listOf(
                            PickerOptionSpec("off", "Off", icon = "power_settings_new", color = 0xFFa7a7a7, nightColor = 0xFF484A4E, supportedIf = SupportedLogic("hvac_modes", "off")),
                            PickerOptionSpec("cool", "Cool", icon = "mode_cool", color = 0xFF2196F3, longPressColor = 0xFF49a6f1, nightColor = 0xFF2196F3, longPressNightColor = 0xFF3d79a8, supportedIf = SupportedLogic("hvac_modes", "cool")),
                            PickerOptionSpec("heat", "Heat", icon = "heat", color = 0xFFFF6F22, nightColor = 0xFFFF6F22, supportedIf = SupportedLogic("hvac_modes", "heat")),
                            PickerOptionSpec("auto", "Auto", icon = "hdr_auto", color = 0xFF00B171, nightColor = 0xFF00B171, supportedIf = SupportedLogic("hvac_modes", "auto")),
                            PickerOptionSpec("fan_only", "Fan", icon = "mode_fan", color = 0xFF31B8E5, nightColor = 0xFF31B8E5, supportedIf = SupportedLogic("hvac_modes", "fan_only")),
                            PickerOptionSpec("dry", "Dry", icon = "humidity_percentage", color = 0xFFFF9653, nightColor = 0xFFFF9653, supportedIf = SupportedLogic("hvac_modes", "dry")),
                        )
                    ),
                    PickerRowSpec(
                        id = "fan_mode",
                        options = listOf(
                            PickerOptionSpec("auto", "Auto", icon = "hdr_auto", supportedIf = SupportedLogic("fan_modes", "auto")),
                            PickerOptionSpec("quiet", "Quiet", icon = "bedtime", supportedIf = SupportedLogic("fan_modes", "quiet")),
                            PickerOptionSpec("low", "Low", icon = R.drawable.ic_one, supportedIf = SupportedLogic("fan_modes", "low")),
                            PickerOptionSpec("medium_low", "Medium low", icon = R.drawable.ic_two, supportedIf = SupportedLogic("fan_modes", "medium_low")),
                            PickerOptionSpec("medium", "Medium", icon = R.drawable.ic_two, supportedIf = SupportedLogic("fan_modes", "medium")),
                            PickerOptionSpec("medium_high", "Medium high", icon = R.drawable.ic_three, supportedIf = SupportedLogic("fan_modes", "medium_high")),
                            PickerOptionSpec("high", "High", icon = R.drawable.ic_three, supportedIf = SupportedLogic("fan_modes", "high")),
                            PickerOptionSpec("turbo", "Turbo", icon = "rocket_launch", supportedIf = SupportedLogic("fan_modes", "turbo")),
                            PickerOptionSpec("super", "Super", icon = "bolt", supportedIf = SupportedLogic("fan_modes", "super")),
                        ),
                        supportedIf = SupportedLogic("fan_modes")
                    ),
                    CounterRowSpec(
                        id = "target_humidity",
                        step = 1,
                        min = 30,
                        max = 99,
                        decIcon = "remove",
                        incIcon = "add",
                        supportedIf = SupportedLogic("target_humidity")
                    ),
                    PickerRowSpec(
                        id = "preset_mode",
                        options = listOf(
                            PickerOptionSpec("none", "None", icon = "block", color = 0xFF8A8F98, nightColor = 0xFF555A62, supportedIf = SupportedLogic("preset_modes", "none")),
                            PickerOptionSpec("eco", "Eco", icon = "energy_savings_leaf", color = 0xFF00B171, nightColor = 0xFF00B171, supportedIf = SupportedLogic("preset_modes", "eco")),
                            PickerOptionSpec("away", "Away", icon = "directions_walk", color = 0xFFFFA000, nightColor = 0xFFFFA000, supportedIf = SupportedLogic("preset_modes", "away")),
                            PickerOptionSpec("boost", "Boost", icon = "rocket_launch", color = 0xFFFF6F22, nightColor = 0xFFFF6F22, supportedIf = SupportedLogic("preset_modes", "boost")),
                            PickerOptionSpec("comfort", "Comfort", icon = "weekend", color = 0xFF7E57C2, nightColor = 0xFF7E57C2, supportedIf = SupportedLogic("preset_modes", "comfort")),
                            PickerOptionSpec("home", "Home", icon = "home", color = 0xFF2196F3, nightColor = 0xFF2196F3, supportedIf = SupportedLogic("preset_modes", "home")),
                            PickerOptionSpec("sleep", "Sleep", icon = "bedtime", color = 0xFF5C6BC0, nightColor = 0xFF5C6BC0, supportedIf = SupportedLogic("preset_modes", "sleep")),
                            PickerOptionSpec("activity", "Activity", icon = "directions_run", color = 0xFF26A69A, nightColor = 0xFF26A69A, supportedIf = SupportedLogic("preset_modes", "activity")),
                        ),
                        supportedIf = SupportedLogic("preset_modes")
                    ),
                    PickerRowSpec(
                        id = "swing_mode",
                        options = listOf(
                            PickerOptionSpec("off", "Off", icon = "airwave", color = 0xFF8A8F98, nightColor = 0xFF555A62, supportedIf = SupportedLogic("swing_modes", "off")),
                            PickerOptionSpec("on", "On", icon = "air", color = 0xFF31B8E5, nightColor = 0xFF31B8E5, supportedIf = SupportedLogic("swing_modes", "on")),
                            PickerOptionSpec("vertical", "Vertical", icon = "swap_vert", color = 0xFF4B8DFF, nightColor = 0xFF4B8DFF, supportedIf = SupportedLogic("swing_modes", "vertical")),
                            PickerOptionSpec("horizontal", "Horizontal", icon = "swap_horiz", color = 0xFF4B8DFF, nightColor = 0xFF4B8DFF, supportedIf = SupportedLogic("swing_modes", "horizontal")),
                            PickerOptionSpec("both", "Both", icon = "open_with", color = 0xFF7E57C2, nightColor = 0xFF7E57C2, supportedIf = SupportedLogic("swing_modes", "both")),
                        ),
                        supportedIf = SupportedLogic("swing_modes")
                    ),
                    PickerRowSpec(
                        id = "swing_horizontal_mode",
                        options = listOf(
                            PickerOptionSpec("off", "Off", icon = "airwave", color = 0xFF8A8F98, nightColor = 0xFF555A62, supportedIf = SupportedLogic("swing_horizontal_modes", "off")),
                            PickerOptionSpec("on", "On", icon = "swap_horiz", color = 0xFF31B8E5, nightColor = 0xFF31B8E5, supportedIf = SupportedLogic("swing_horizontal_modes", "on")),
                        ),
                        supportedIf = SupportedLogic("swing_horizontal_modes")
                    ),
                ),
                haBindings = emptyList(),
                actions = ServiceMap(
                    calls = mapOf(
                        "pick:hvac_mode" to ServiceCall(
                            domain = "climate",
                            service = "set_hvac_mode",
                            params = mapOf("entity_id" to "{entity}", "hvac_mode" to "{value}")
                        ),
                        "pick:fan_mode" to ServiceCall(
                            domain = "climate",
                            service = "set_fan_mode",
                            params = mapOf("entity_id" to "{entity}", "fan_mode" to "{value}")
                        ),
                        "pick:preset_mode" to ServiceCall(
                            domain = "climate",
                            service = "set_preset_mode",
                            params = mapOf("entity_id" to "{entity}", "preset_mode" to "{value}")
                        ),
                        "pick:swing_mode" to ServiceCall(
                            domain = "climate",
                            service = "set_swing_mode",
                            params = mapOf("entity_id" to "{entity}", "swing_mode" to "{value}")
                        ),
                        "pick:swing_horizontal_mode" to ServiceCall(
                            domain = "climate",
                            service = "set_swing_horizontal_mode",
                            params = mapOf("entity_id" to "{entity}", "swing_horizontal_mode" to "{value}")
                        ),
                        "setCounter:temperature" to ServiceCall(
                            domain = "climate",
                            service = "set_temperature",
                            params = mapOf("entity_id" to "{entity}", "temperature" to "{value}")
                        ),
                        "setCounter:target_humidity" to ServiceCall(
                            domain = "climate",
                            service = "set_humidity",
                            params = mapOf("entity_id" to "{entity}", "humidity" to "{value}")
                        ),
                        "setCounter" to ServiceCall(
                            domain = "climate",
                            service = "set_temperature",
                            params = mapOf("entity_id" to "{entity}", "temperature" to "{value}")
                        )
                    )
                ),
                entityPickers = listOf(
                    ConfigEntityPickerSpec("battery", "Battery Sensor", "sensor.")
                ),
                sheet = SheetSpec(
                    blocks = listOf(
                        SheetDataSpec(
                            id = "current_temp",
                            label = "Current temperature",
                            attribute = "current_temperature",
                            unitAttr = "temperature_unit",
                            unitDefault = "\u00B0C"
                        ),
                        SheetHeroSpec(
                            id = "dial",
                            kind = SheetHeroKind.DIAL,
                            valueAttr = "temperature",
                            fallbackAttrs = listOf("target_temp_high", "target_temp_low", "current_temperature"),
                            currentAttr = "current_temperature",
                            minAttr = "min_temp", maxAttr = "max_temp", stepAttr = "target_temp_step",
                            minDefault = 16.0, maxDefault = 30.0, stepDefault = 1.0,
                            unitAttr = "temperature_unit", unitDefault = "\u00B0C",
                            actionKey = "setCounter:temperature"
                        ),
                        SheetDropdownSpec("hvac_mode", "Mode", optionsAttr = "hvac_modes", valueAttr = null, actionKey = "pick:hvac_mode", optionsFromRow = "hvac_mode", accent = SheetAccent.CLIMATE_MODE),
                        SheetDropdownSpec("fan_mode", "Fan mode", optionsAttr = "fan_modes", valueAttr = "fan_mode", actionKey = "pick:fan_mode", optionsFromRow = "fan_mode", accent = SheetAccent.CLIMATE_FAN),
                        SheetDropdownSpec("preset_mode", "Preset", optionsAttr = "preset_modes", valueAttr = "preset_mode", actionKey = "pick:preset_mode", optionsFromRow = "preset_mode", accent = SheetAccent.CLIMATE_PRESET),
                        SheetDropdownSpec("swing_mode", "Swing", optionsAttr = "swing_modes", valueAttr = "swing_mode", actionKey = "pick:swing_mode", optionsFromRow = "swing_mode", accent = SheetAccent.CLIMATE_SWING),
                        SheetDropdownSpec("swing_horizontal_mode", "Horizontal swing", optionsAttr = "swing_horizontal_modes", valueAttr = "swing_horizontal_mode", actionKey = "pick:swing_horizontal_mode", optionsFromRow = "swing_horizontal_mode", accent = SheetAccent.CLIMATE_SWING),
                    )
                )
            )
        )

        WidgetRegistry.register(
            key = "light",
            spec = WidgetSpec(
                key = "light",
                title = "Light",
                iconRes = null,
                domain = "light",
                configureQuery = ConfigureQuery("light."),
                rows = listOf(
                    PickerRowSpec(
                        id = "brightness_pct",
                        options = listOf(
                            PickerOptionSpec("0", "0%"),
                            PickerOptionSpec("50", "50%"),
                            PickerOptionSpec("100", "100%"),
                        ),
                        visibility = RowVisibility.WIDGET
                    ),
                    SliderRowSpec(
                        id = "brightness_pct",
                        label = "Brightness",
                        background = SliderBackground.BRIGHTNESS,
                        supportedIf = SupportedLogic(
                            "supported_color_modes",
                            containsAny = DIMMABLE_LIGHT_COLOR_MODES
                        ),
                        visibility = RowVisibility.APP
                    ),
                    SliderRowSpec(
                        id = "hs_color",
                        label = "Color",
                        background = SliderBackground.RGB,
                        max = 360f,
                        supportedIf = SupportedLogic("supported_color_modes", contains = "hs"),
                        visibility = RowVisibility.APP
                    ),
                    SliderRowSpec(
                        id = "color_temp",
                        label = "Temperature",
                        background = SliderBackground.TEMPERATURE,
                        min = 153f,
                        max = 500f,
                        supportedIf = SupportedLogic("supported_color_modes", contains = "color_temp"),
                        visibility = RowVisibility.APP
                    ),
                    // A picker rather than a plain button so the current state is visible at a
                    // glance — "On" or "Off" highlights, the way the climate mode row does.
                    // Switchable back to a single toggle from the row's feature settings.
                    PickerRowSpec(
                        id = "power",
                        options = listOf(
                            PickerOptionSpec("on", "On", icon = "lightbulb", color = 0xFFFFC107, nightColor = 0xFFFFC107),
                            PickerOptionSpec("off", "Off", icon = "power_settings_new", color = 0xFFa7a7a7, nightColor = 0xFF484A4E),
                        ),
                        style = RowStyleSpec(
                            configKey = "power_style",
                            default = "buttons",
                            options = listOf(
                                RowStyleOption("buttons", "On / Off buttons"),
                                RowStyleOption(ROW_STYLE_TOGGLE, "Single toggle"),
                            )
                        )
                    )
                ),
                haBindings = emptyList(),
                actions = ServiceMap(
                    calls = mapOf(
                        "pick" to ServiceCall(
                            domain = "light",
                            service = "turn_on",
                            params = mapOf("entity_id" to "{entity}", "brightness_pct" to "{value}")
                        ),
                        "sheet:effect" to ServiceCall(
                            domain = "light",
                            service = "turn_on",
                            params = mapOf("entity_id" to "{entity}", "effect" to "{value}")
                        ),
                        "toggle" to ServiceCall(
                            domain = "light",
                            service = "toggle",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                        // {value} is replaced with the picked option id, so "on" and "off" resolve
                        // to light.turn_on and light.turn_off without needing a call each.
                        "pick:power" to ServiceCall(
                            domain = "light",
                            service = "turn_{value}",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                        "sheet:turn_on" to ServiceCall(
                            domain = "light",
                            service = "turn_on",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                        "sheet:turn_off" to ServiceCall(
                            domain = "light",
                            service = "turn_off",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                    )
                ),
                sheet = SheetSpec(
                    blocks = listOf(
                        SheetHeroSpec(
                            id = "brightness",
                            kind = SheetHeroKind.SLIDER,
                            valueAttr = "brightness",
                            minDefault = 0.0, maxDefault = 255.0, stepDefault = 1.0,
                            sendAsPercentOfRange = true,
                            actionKey = "pick",
                            // The sheet hero is a separate spec from the card's brightness row, so
                            // it needs its own gate — an on/off-only light has nothing to slide.
                            supportedIf = SupportedLogic(
                                "supported_color_modes",
                                containsAny = DIMMABLE_LIGHT_COLOR_MODES
                            )
                        ),
                        // On/off always, not just as a fallback. A dimmable light still needs a way
                        // to switch off without dragging to zero, and for an on/off-only light —
                        // where the brightness hero above is gated out — this is the entire sheet.
                        SheetButtonsSpec(
                            id = "power",
                            buttons = listOf(
                                SheetButtonSpec("turn_on", "On", "lightbulb", "sheet:turn_on"),
                                SheetButtonSpec("turn_off", "Off", "power_settings_new", "sheet:turn_off"),
                            )
                        ),
                        SheetDropdownSpec("effect", "Effect", optionsAttr = "effect_list", valueAttr = "effect", actionKey = "sheet:effect"),
                    )
                )
            )
        )

        WidgetRegistry.register(
            key = "lock",
            spec = WidgetSpec(
                key = "lock",
                title = "Lock",
                iconRes = null,
                domain = "lock",
                configureQuery = ConfigureQuery("lock."),
                rows = listOf(
                    PickerRowSpec(
                        id = "state",
                        options = listOf(
                            PickerOptionSpec("lock", "Lock", icon = "lock", color = 0xFF4CAF50),
                            PickerOptionSpec("unlock", "Unlock", icon = "lock_open_right", color = 0xFFF44336, actionOverrideIfToggle = "unlock_opens" to "open", confirm = true),
                        ),
                        transitionalStates = mapOf(
                            "locking" to PickerOptionSpec("locking", "Locking", icon = "lock_clock", color = 0xFFFFC107),
                            "unlocking" to PickerOptionSpec("unlocking", "Unlocking", icon = "lock_open_right", color = 0xFFFFC107),
                            "opening" to PickerOptionSpec("opening", "Opening", icon = "door_open", color = 0xFFFFC107),
                            "closing" to PickerOptionSpec("closing", "Closing", icon = "door_sliding", color = 0xFFFFC107)
                        ),
                        stateAliases = mapOf(
                            "locked" to "lock",
                            "unlocked" to "unlock"
                        )
                    ),
                    ButtonsRowSpec(
                        id = "actions",
                        buttons = listOf(
                            WidgetButtonSpec("open", "Open", "door_open", "open"),
                        ),
                        hiddenIfToggle = "unlock_opens",
                        hiddenIfStates = listOf("locking", "unlocking", "opening", "closing")
                    ),
                ),
                haBindings = emptyList(),
                entityPickers = listOf(
                    ConfigEntityPickerSpec("battery", "Battery Sensor", "sensor.")
                ),
                actions = ServiceMap(
                    calls = mapOf(
                        "pick" to ServiceCall(
                            domain = "lock",
                            service = "{value}",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                        "open" to ServiceCall(
                            domain = "lock",
                            service = "open",
                            params = mapOf("entity_id" to "{entity}")
                        ),
                    )
                ),
                toggles = listOf(
                    ConfigToggleSpec("unlock_opens", "Unlock picker also opens door", true, "door_open")
                ),
                sheet = SheetSpec(
                    blocks = listOf(
                        SheetDataSpec(id = "state", label = "State", attribute = "state"),
                        // The card's own rows: the Lock/Unlock pill picker and the Open button,
                        // exactly as they appear on the card.
                        SheetCardRowsSpec(),
                    )
                )
            )
        )
        WidgetRegistry.register(
            key = "alarm",
            spec = WidgetSpec(
                key = "alarm",
                title = "Alarm",
                iconRes = null,
                domain = "alarm_control_panel",
                configureQuery = ConfigureQuery("alarm_control_panel."),
                rows = listOf(
                    PickerRowSpec(
                        id = "state",
                        options = listOf(
                            PickerOptionSpec("disarm", "Disarm", icon = "remove_moderator", color = 0xFF4CAF50),
                            PickerOptionSpec("arm_home", "Home", icon = "home", color = 0xFF2196F3, supportedIf = SupportedLogic("supported_features", bitmask = 1)),
                            PickerOptionSpec("arm_away", "Away", icon = "exit_to_app", color = 0xFFF44336, supportedIf = SupportedLogic("supported_features", bitmask = 2)),
                            PickerOptionSpec("arm_night", "Night", icon = "nights_stay", color = 0xFF673AB7, supportedIf = SupportedLogic("supported_features", bitmask = 4)),
                            PickerOptionSpec("arm_vacation", "Vacation", icon = "flight", color = 0xFFFF9800, supportedIf = SupportedLogic("supported_features", bitmask = 32)),
                            PickerOptionSpec("arm_custom_bypass", "Bypass", icon = "shield", color = 0xFF795548, supportedIf = SupportedLogic("supported_features", bitmask = 16)),
                        ),
                        transitionalStates = mapOf(
                            "arming" to PickerOptionSpec("arming", "Arming", icon = "shield_lock", color = 0xFFFFC107),
                            "disarming" to PickerOptionSpec("disarming", "Disarming", icon = "remove_moderator", color = 0xFFFFC107),
                            "pending" to PickerOptionSpec("pending", "Pending", icon = "shield", color = 0xFFFFC107),
                            "triggered" to PickerOptionSpec("triggered", "TRIGGERED", icon = "notifications_active", color = 0xFFFF5252)
                        ),
                        stateAliases = mapOf(
                            "disarmed" to "disarm",
                            "armed_home" to "arm_home",
                            "armed_away" to "arm_away",
                            "armed_night" to "arm_night",
                            "armed_vacation" to "arm_vacation",
                            "armed_custom_bypass" to "arm_custom_bypass"
                        )
                    ),
                ),
                haBindings = emptyList(),
                actions = ServiceMap(
                    calls = mapOf(
                        "pick" to ServiceCall(
                            domain = "alarm_control_panel",
                            service = "alarm_{value}",
                            params = mapOf("entity_id" to "{entity}")
                        )
                    )
                )
            )
        )

        // ── Cover (blinds / curtains / shades) ───────────────────────────────
        // Feature-gated by supported_features bitmask, per HA's cover model:
        // OPEN=1, CLOSE=2, SET_POSITION=4, STOP=8, OPEN_TILT=16, CLOSE_TILT=32,
        // STOP_TILT=64, SET_TILT_POSITION=128. Buttons/rows only show when supported.
        WidgetRegistry.register(
            key = "cover",
            spec = WidgetSpec(
                key = "cover",
                title = "Cover",
                iconRes = null,
                domain = "cover",
                configureQuery = ConfigureQuery("cover."),
                rows = listOf(
                    ButtonsRowSpec(
                        id = "controls",
                        buttons = listOf(
                            WidgetButtonSpec("open", "Open", "keyboard_arrow_up", "open_cover", supportedIf = SupportedLogic("supported_features", bitmask = 1)),
                            WidgetButtonSpec("stop", "Stop", "stop", "stop_cover", supportedIf = SupportedLogic("supported_features", bitmask = 8)),
                            WidgetButtonSpec("close", "Close", "keyboard_arrow_down", "close_cover", supportedIf = SupportedLogic("supported_features", bitmask = 2)),
                        ),
                    ),
                    SliderRowSpec(
                        id = "position",
                        label = "Position",
                        serviceOverride = "set_cover_position",
                        paramName = "position",
                        optimisticAttr = "current_position",
                        min = 0f, max = 100f,
                        supportedIf = SupportedLogic("supported_features", bitmask = 4),
                        visibility = RowVisibility.APP, // sliders aren't rendered on home-screen widgets
                    ),
                    ButtonsRowSpec(
                        id = "tilt_controls",
                        buttons = listOf(
                            WidgetButtonSpec("open_tilt", "Tilt open", "keyboard_arrow_up", "open_cover_tilt", supportedIf = SupportedLogic("supported_features", bitmask = 16)),
                            WidgetButtonSpec("stop_tilt", "Tilt stop", "stop", "stop_cover_tilt", supportedIf = SupportedLogic("supported_features", bitmask = 64)),
                            WidgetButtonSpec("close_tilt", "Tilt close", "keyboard_arrow_down", "close_cover_tilt", supportedIf = SupportedLogic("supported_features", bitmask = 32)),
                        ),
                        // Show the tilt row if any tilt feature is present (16|32|64|128 = 240).
                        supportedIf = SupportedLogic("supported_features", bitmask = 240),
                    ),
                    SliderRowSpec(
                        id = "tilt",
                        label = "Tilt",
                        min = 0f, max = 100f,
                        serviceOverride = "set_cover_tilt_position",
                        paramName = "tilt_position",
                        optimisticAttr = "current_tilt_position",
                        supportedIf = SupportedLogic("supported_features", bitmask = 128),
                        visibility = RowVisibility.APP,
                    ),
                ),
                haBindings = emptyList(),
                actions = ServiceMap(
                    calls = mapOf(
                        "sheet:open" to ServiceCall("cover", "open_cover", mapOf("entity_id" to "{entity}")),
                        "sheet:stop" to ServiceCall("cover", "stop_cover", mapOf("entity_id" to "{entity}")),
                        "sheet:close" to ServiceCall("cover", "close_cover", mapOf("entity_id" to "{entity}")),
                        "sheet:set_position" to ServiceCall(
                            domain = "cover",
                            service = "set_cover_position",
                            params = mapOf("entity_id" to "{entity}", "position" to "{value}")
                        ),
                    )
                ),
                sheet = SheetSpec(
                    blocks = listOf(
                        SheetDataSpec(id = "state", label = "State", attribute = "state"),
                        SheetHeroSpec(
                            id = "position",
                            kind = SheetHeroKind.SLIDER,
                            valueAttr = "current_position",
                            minDefault = 0.0, maxDefault = 100.0, stepDefault = 1.0,
                            displayAsPercentOfRange = true,
                            actionKey = "sheet:set_position",
                            supportedIf = SupportedLogic("supported_features", bitmask = 4)
                        ),
                        SheetButtonsSpec(
                            id = "controls",
                            buttons = listOf(
                                SheetButtonSpec("open", "Open", "keyboard_arrow_up", "sheet:open", supportedIf = SupportedLogic("supported_features", bitmask = 1)),
                                SheetButtonSpec("stop", "Stop", "stop", "sheet:stop", supportedIf = SupportedLogic("supported_features", bitmask = 8)),
                                SheetButtonSpec("close", "Close", "keyboard_arrow_down", "sheet:close", supportedIf = SupportedLogic("supported_features", bitmask = 2)),
                            )
                        ),
                    )
                )
            )
        )
        // ── Sensor ───────────────────────────────
        WidgetRegistry.register(
            key = "sensor",
            spec = WidgetSpec(
                key = "sensor",
                title = "Sensor",
                iconRes = null,
                domain = "sensor",
                configureQuery = ConfigureQuery("sensor."),
                rows = emptyList<RowSpec>(),
                haBindings = emptyList<Binding>(),
                actions = ServiceMap()
            )
        )

        // ── Binary Sensor ───────────────────────────────
        WidgetRegistry.register(
            key = "binary_sensor",
            spec = WidgetSpec(
                key = "binary_sensor",
                title = "Binary Sensor",
                iconRes = null,
                domain = "binary_sensor",
                configureQuery = ConfigureQuery("binary_sensor."),
                rows = emptyList<RowSpec>(),
                haBindings = emptyList<Binding>(),
                actions = ServiceMap()
            )
        )
        // ── Switch ───────────────────────────────
        WidgetRegistry.register(
            key = "switch",
            spec = WidgetSpec(
                key = "switch",
                title = "Switch",
                iconRes = null,
                domain = "switch",
                configureQuery = ConfigureQuery("switch."),
                rows = listOf(
                    CustomFeatureRowSpec(
                        id = "switch",
                        type = "switch",
                        label = "Power"
                    )
                ),
                haBindings = emptyList(),
                actions = ServiceMap()
            )
        )
        
        // ── Vacuum ───────────────────────────────
        WidgetRegistry.register(
            key = "vacuum",
            spec = WidgetSpec(
                key = "vacuum",
                title = "Vacuum",
                iconRes = null,
                domain = "vacuum",
                configureQuery = ConfigureQuery("vacuum."),
                rows = listOf(
                    ButtonsRowSpec(
                        id = "controls",
                        buttons = listOf(
                            WidgetButtonSpec("start", "Start", "play_arrow", "start", supportedIf = SupportedLogic("supported_features", bitmask = 8192)),
                            WidgetButtonSpec("pause", "Pause", "pause", "pause", supportedIf = SupportedLogic("supported_features", bitmask = 4)),
                            WidgetButtonSpec("stop", "Stop", "stop", "stop", supportedIf = SupportedLogic("supported_features", bitmask = 8)),
                            WidgetButtonSpec("return_to_base", "Dock", "home", "return_to_base", supportedIf = SupportedLogic("supported_features", bitmask = 16)),
                            WidgetButtonSpec("locate", "Locate", "location_on", "locate", supportedIf = SupportedLogic("supported_features", bitmask = 512))
                        )
                    )
                ),
                haBindings = emptyList<Binding>(),
                actions = ServiceMap(
                    calls = mapOf(
                        "sheet:fan_speed" to ServiceCall(
                            domain = "vacuum",
                            service = "set_fan_speed",
                            params = mapOf("entity_id" to "{entity}", "fan_speed" to "{value}")
                        ),
                    )
                ),
                sheet = SheetSpec(
                    blocks = listOf(
                        SheetDataSpec(id = "battery", label = "Battery", attribute = "battery_level", unitDefault = "%"),
                        SheetDropdownSpec("fan_speed", "Suction", optionsAttr = "fan_speed_list", valueAttr = "fan_speed", actionKey = "sheet:fan_speed"),
                        SheetCardRowsSpec(),
                    )
                )
            )
        )
    }
}
