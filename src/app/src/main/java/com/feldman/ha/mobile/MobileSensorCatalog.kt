package com.feldman.ha.mobile

/**
 * Every sensor this app can report to Home Assistant's Mobile App integration.
 *
 * Ids are the `unique_id` Home Assistant keys each entity by, so they are permanent: renaming one
 * orphans the existing entity and creates a second. The ids that were here before this catalog was
 * split out are kept exactly as they were for that reason, even where the official companion app
 * spells them differently (`battery_charging` rather than `is_charging`).
 *
 * `defaultEnabled = false` is for anything that needs a runtime permission, costs battery to
 * sample, or is noisy enough that most people would not want it — the companion app makes the same
 * distinction, registering them as `disabled` so Home Assistant creates the entity but leaves it
 * switched off until asked for.
 */
internal object MobileSensorCatalog {

    val definitions: List<MobileSensorDefinition> = buildList {
        addAll(battery.inCategory(MobileSensorCategory.BATTERY))
        addAll(device.inCategory(MobileSensorCategory.DEVICE))
        addAll(audio.inCategory(MobileSensorCategory.AUDIO))
        addAll(network.inCategory(MobileSensorCategory.NETWORK))
        addAll(storage.inCategory(MobileSensorCategory.STORAGE))
        addAll(environment.inCategory(MobileSensorCategory.ENVIRONMENT))
        addAll(diagnostics.inCategory(MobileSensorCategory.DIAGNOSTICS))
    }

    /** Stamped on the whole group rather than each entry, so a sensor cannot be filed wrongly. */
    private fun List<MobileSensorDefinition>.inCategory(category: MobileSensorCategory) =
        map { it.copy(category = category) }

    private val battery
        get() = listOf(
            MobileSensorDefinition(
                id = "battery_level",
                name = "Battery Level",
                icon = "mdi:battery",
                deviceClass = "battery",
                unit = "%",
                stateClass = "measurement"
            ),
            MobileSensorDefinition(
                id = "battery_state",
                name = "Battery State",
                icon = "mdi:battery-charging"
            ),
            MobileSensorDefinition(
                id = "battery_charging",
                name = "Battery Charging",
                type = "binary_sensor",
                icon = "mdi:battery-charging",
                deviceClass = "battery_charging"
            ),
            MobileSensorDefinition(
                id = "battery_health",
                name = "Battery Health",
                icon = "mdi:battery-heart-variant"
            ),
            MobileSensorDefinition(
                id = "charger_type",
                name = "Charger Type",
                icon = "mdi:power-plug"
            ),
            MobileSensorDefinition(
                id = "charger_power",
                name = "Charger Power",
                icon = "mdi:flash",
                deviceClass = "power",
                unit = "W",
                stateClass = "measurement"
            ),
            MobileSensorDefinition(
                id = "battery_temperature",
                name = "Battery Temperature",
                icon = "mdi:thermometer",
                deviceClass = "temperature",
                unit = "°C",
                stateClass = "measurement"
            ),
            MobileSensorDefinition(
                id = "battery_voltage",
                name = "Battery Voltage",
                icon = "mdi:sine-wave",
                deviceClass = "voltage",
                unit = "V",
                stateClass = "measurement"
            ),
            MobileSensorDefinition(
                id = "battery_cycles",
                name = "Battery Cycles",
                icon = "mdi:battery-sync",
                stateClass = "measurement",
                // BatteryManager only reports this from Android 14.
                minSdk = 34
            ),
            MobileSensorDefinition(
                id = "power_save",
                name = "Power Save",
                type = "binary_sensor",
                icon = "mdi:battery-heart"
            ),
            MobileSensorDefinition(
                id = "battery_current",
                name = "Battery Current",
                icon = "mdi:current-dc",
                deviceClass = "current",
                unit = "mA",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "remaining_charge_time",
                name = "Remaining Charge Time",
                icon = "mdi:battery-clock",
                deviceClass = "duration",
                unit = "min",
                defaultEnabled = false
            )
        )

    private val device
        get() = listOf(
            MobileSensorDefinition(
                id = "interactive",
                name = "Screen Interactive",
                type = "binary_sensor",
                icon = "mdi:cellphone-screenshot"
            ),
            MobileSensorDefinition(
                id = "device_locked",
                name = "Device Locked",
                type = "binary_sensor",
                icon = "mdi:cellphone-lock"
            ),
            MobileSensorDefinition(
                id = "device_secure",
                name = "Device Secure",
                type = "binary_sensor",
                icon = "mdi:shield-lock",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "doze_mode",
                name = "Doze Mode",
                type = "binary_sensor",
                icon = "mdi:sleep"
            ),
            MobileSensorDefinition(
                id = "screen_brightness",
                name = "Screen Brightness",
                icon = "mdi:brightness-6",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "screen_off_timeout",
                name = "Screen Off Timeout",
                icon = "mdi:timer-outline",
                unit = "ms",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "last_reboot",
                name = "Last Reboot",
                icon = "mdi:restart",
                deviceClass = "timestamp"
            ),
            MobileSensorDefinition(
                id = "next_alarm",
                name = "Next Alarm",
                icon = "mdi:alarm",
                deviceClass = "timestamp",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "device_uptime",
                name = "Device Uptime",
                icon = "mdi:timer-play",
                deviceClass = "duration",
                unit = "h",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "app_standby_bucket",
                name = "App Standby Bucket",
                icon = "mdi:android",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "app_importance",
                name = "App Importance",
                icon = "mdi:android-studio",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "app_memory",
                name = "App Memory Used",
                icon = "mdi:memory",
                deviceClass = "data_size",
                unit = "MB",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "screen_resolution",
                name = "Screen Resolution",
                icon = "mdi:monitor",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "display_density",
                name = "Display Density",
                icon = "mdi:monitor-screenshot",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "font_scale",
                name = "Font Scale",
                icon = "mdi:format-size",
                defaultEnabled = false
            )
        )

    private val audio
        get() = listOf(
            MobileSensorDefinition(
                id = "audio_mode",
                name = "Audio Mode",
                icon = "mdi:volume-high"
            ),
            MobileSensorDefinition(
                id = "ringer_mode",
                name = "Ringer Mode",
                icon = "mdi:bell-ring"
            ),
            MobileSensorDefinition(
                id = "do_not_disturb_sensor",
                name = "Do Not Disturb",
                icon = "mdi:minus-circle"
            ),
            MobileSensorDefinition(
                id = "is_music_active",
                name = "Music Active",
                type = "binary_sensor",
                icon = "mdi:music"
            ),
            MobileSensorDefinition(
                id = "headphone",
                name = "Headphones",
                type = "binary_sensor",
                icon = "mdi:headphones"
            ),
            MobileSensorDefinition(
                id = "mic_muted",
                name = "Mic Muted",
                type = "binary_sensor",
                icon = "mdi:microphone-off",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "speakerphone",
                name = "Speakerphone",
                type = "binary_sensor",
                icon = "mdi:volume-high",
                defaultEnabled = false
            ),
            volume("volume_level_ring", "Ring Volume", "mdi:bell-ring"),
            volume("volume_level_music", "Music Volume", "mdi:music"),
            volume("volume_level_alarm", "Alarm Volume", "mdi:alarm"),
            volume("volume_level_notification", "Notification Volume", "mdi:bell"),
            volume("volume_level_call", "Call Volume", "mdi:phone")
        )

    private fun volume(id: String, name: String, icon: String) = MobileSensorDefinition(
        id = id,
        name = name,
        icon = icon,
        stateClass = "measurement",
        defaultEnabled = false
    )

    private val network
        get() = listOf(
            MobileSensorDefinition(
                id = "network_type",
                name = "Network Type",
                icon = "mdi:network"
            ),
            // Everything derived from WifiInfo is gated behind location: since Android 10 the
            // system returns placeholders for SSID and BSSID without it, and the companion app
            // groups the whole set the same way.
            MobileSensorDefinition(
                id = "wifi_connection",
                name = "Wi-Fi Connection",
                icon = "mdi:wifi",
                defaultEnabled = false,
                permissionGroup = MobileSensorPermissionGroup.LOCATION
            ),
            MobileSensorDefinition(
                id = "wifi_bssid",
                name = "Wi-Fi BSSID",
                icon = "mdi:wifi-marker",
                defaultEnabled = false,
                permissionGroup = MobileSensorPermissionGroup.LOCATION
            ),
            MobileSensorDefinition(
                id = "wifi_signal_strength",
                name = "Wi-Fi Signal Strength",
                icon = "mdi:wifi-strength-3",
                deviceClass = "signal_strength",
                unit = "dBm",
                stateClass = "measurement",
                defaultEnabled = false,
                permissionGroup = MobileSensorPermissionGroup.LOCATION
            ),
            MobileSensorDefinition(
                id = "wifi_frequency",
                name = "Wi-Fi Frequency",
                icon = "mdi:wifi",
                unit = "MHz",
                stateClass = "measurement",
                defaultEnabled = false,
                permissionGroup = MobileSensorPermissionGroup.LOCATION
            ),
            MobileSensorDefinition(
                id = "wifi_link_speed",
                name = "Wi-Fi Link Speed",
                icon = "mdi:wifi-arrow-up-down",
                unit = "Mbit/s",
                stateClass = "measurement",
                defaultEnabled = false,
                permissionGroup = MobileSensorPermissionGroup.LOCATION
            ),
            MobileSensorDefinition(
                id = "wifi_ip_address",
                name = "Wi-Fi IP Address",
                icon = "mdi:ip-network",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "airplane_mode",
                name = "Airplane Mode",
                type = "binary_sensor",
                icon = "mdi:airplane"
            ),
            MobileSensorDefinition(
                id = "data_saver",
                name = "Data Saver",
                icon = "mdi:network-strength-2-alert",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "bluetooth_state",
                name = "Bluetooth",
                type = "binary_sensor",
                icon = "mdi:bluetooth",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "nfc_state",
                name = "NFC",
                type = "binary_sensor",
                icon = "mdi:nfc",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "carrier_name",
                name = "Carrier",
                icon = "mdi:sim",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "sim_state",
                name = "SIM State",
                icon = "mdi:sim-outline",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "traffic_total_rx",
                name = "Total Data Received",
                icon = "mdi:download-network",
                deviceClass = "data_size",
                unit = "GB",
                stateClass = "total_increasing",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "traffic_total_tx",
                name = "Total Data Sent",
                icon = "mdi:upload-network",
                deviceClass = "data_size",
                unit = "GB",
                stateClass = "total_increasing",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "location_accuracy",
                name = "Location Accuracy",
                icon = "mdi:crosshairs-gps",
                deviceClass = "distance",
                unit = "m",
                stateClass = "measurement",
                defaultEnabled = false,
                permissionGroup = MobileSensorPermissionGroup.LOCATION
            )
        )

    private val storage
        get() = listOf(
            MobileSensorDefinition(
                id = "storage_free",
                name = "Internal Storage Free",
                icon = "mdi:harddisk",
                deviceClass = "data_size",
                unit = "GB",
                stateClass = "measurement"
            ),
            MobileSensorDefinition(
                id = "storage_external",
                name = "External Storage Free",
                icon = "mdi:sd",
                deviceClass = "data_size",
                unit = "GB",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "memory_available",
                name = "Memory Available",
                icon = "mdi:memory",
                deviceClass = "data_size",
                unit = "GB",
                stateClass = "measurement"
            )
        )

    /**
     * Hardware sensors. Off by default and sampled with a short one-shot listener: they have no
     * readable "current value", so each reading costs a subscription and a wait.
     */
    private val environment
        get() = listOf(
            MobileSensorDefinition(
                id = "light_sensor",
                name = "Light Level",
                icon = "mdi:brightness-5",
                deviceClass = "illuminance",
                unit = "lx",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "pressure_sensor",
                name = "Pressure",
                icon = "mdi:gauge",
                deviceClass = "pressure",
                unit = "hPa",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "proximity_sensor",
                name = "Proximity",
                icon = "mdi:leak",
                deviceClass = "distance",
                unit = "cm",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "ambient_temperature_sensor",
                name = "Ambient Temperature",
                icon = "mdi:thermometer",
                deviceClass = "temperature",
                unit = "°C",
                stateClass = "measurement",
                defaultEnabled = false
            ),
            MobileSensorDefinition(
                id = "humidity_sensor",
                name = "Humidity",
                icon = "mdi:water-percent",
                deviceClass = "humidity",
                unit = "%",
                stateClass = "measurement",
                defaultEnabled = false
            )
        )

    private val diagnostics
        get() = listOf(
            MobileSensorDefinition(
                id = "app_version",
                name = "App Version",
                icon = "mdi:application-cog"
            ),
            MobileSensorDefinition(
                id = "os_version",
                name = "Android Version",
                icon = "mdi:android"
            ),
            MobileSensorDefinition(
                id = "device_model",
                name = "Device Model",
                icon = "mdi:cellphone"
            ),
            MobileSensorDefinition(
                id = "last_update",
                name = "Last Sensor Update",
                icon = "mdi:clock-outline",
                deviceClass = "timestamp"
            )
        )
}
