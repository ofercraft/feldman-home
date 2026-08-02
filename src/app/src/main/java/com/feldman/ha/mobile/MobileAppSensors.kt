package com.feldman.ha.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.feldman.ha.api.HomeAssistantAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class MobileSensorDefinition(
    val id: String,
    val name: String,
    val type: String = "sensor",
    val icon: String = "mdi:cellphone",
    val deviceClass: String? = null,
    val unit: String? = null,
    val stateClass: String? = null,
    val entityCategory: String? = "diagnostic",
    val defaultEnabled: Boolean = true,
    val permissionGroup: MobileSensorPermissionGroup? = null,
    /** Lowest API level that can report this at all; below it the sensor is hidden entirely. */
    val minSdk: Int = 0,
    val category: MobileSensorCategory = MobileSensorCategory.DIAGNOSTICS
)

/** Groups the sensor list on the Phone settings page. Purely presentational. */
enum class MobileSensorCategory(val label: String) {
    BATTERY("Battery"),
    DEVICE("Device"),
    AUDIO("Audio"),
    NETWORK("Network"),
    STORAGE("Storage"),
    ENVIRONMENT("Environment"),
    DIAGNOSTICS("Diagnostics")
}

data class MobileSensorReading(
    val definition: MobileSensorDefinition,
    val state: Any,
    val attributes: Map<String, Any?> = emptyMap()
)

enum class MobileSensorPermissionGroup {
    LOCATION
}

object MobileAppSensors {
    private const val TAG = "MobileAppSensors"
    private const val SENSOR_PREF_PREFIX = "mobile_sensor_enabled_"
    private const val SENSOR_REGISTERED_PREFIX = "mobile_sensor_registered_"

    /** Which webhook the stored registration signatures were made against. */
    private const val SENSOR_REGISTRATION_OWNER = "mobile_sensor_registration_webhook"

    /** How long to wait for a hardware sensor to deliver its first sample before giving up. */
    private const val HARDWARE_SENSOR_TIMEOUT_MS = 1_500L

    val definitions: List<MobileSensorDefinition> =
        MobileSensorCatalog.definitions.filter { Build.VERSION.SDK_INT >= it.minSdk }

    fun enabledMap(context: Context): Map<String, Boolean> =
        definitions.associate { it.id to isEnabled(context, it) }

    fun isEnabled(context: Context, definition: MobileSensorDefinition): Boolean {
        val prefs = sensorPrefs(context)
        return prefs.getBoolean(SENSOR_PREF_PREFIX + definition.id, definition.defaultEnabled)
    }

    fun setEnabled(context: Context, sensorId: String, enabled: Boolean) {
        val prefs = sensorPrefs(context)
        val registeredKey = SENSOR_REGISTERED_PREFIX + sensorId
        val known = prefs.contains(registeredKey)
        prefs.edit {
            putBoolean(SENSOR_PREF_PREFIX + sensorId, enabled)
            // Blanked rather than removed. Home Assistant only learns that a sensor changed state
            // through another register_sensor call, and a blank value can never match a real
            // signature so the next pass always re-sends. Removing the key instead would make the
            // sensor look unknown again, and a switched-off sensor that looks unknown is skipped —
            // so Home Assistant would never be told it was turned off.
            if (known) putString(registeredKey, "")
        }
    }

    fun requiredPermissions(definition: MobileSensorDefinition): Array<String> =
        when (definition.permissionGroup) {
            MobileSensorPermissionGroup.LOCATION -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            null -> emptyArray()
        }

    fun hasRequiredPermissions(context: Context, definition: MobileSensorDefinition): Boolean =
        when (definition.permissionGroup) {
            MobileSensorPermissionGroup.LOCATION ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            null -> true
        }

    /**
     * Registers anything Home Assistant has not been told about yet.
     *
     * Registration is idempotent on the server but not free: this used to re-register all ~40
     * sensors on every 15-minute pass, which is 40 round trips an hour doing nothing. A signature
     * of the fields Home Assistant actually stores is kept per sensor, so a sensor is re-sent only
     * when it is new, when its definition changed in an app update, or when it was enabled or
     * disabled.
     */
    suspend fun registerAllSensors(context: Context) {
        val prefs = sensorPrefs(context)
        forgetRegistrationsIfDeviceChanged(context, prefs)
        var firstFailure: Throwable? = null
        var sent = 0
        var skippedOff = 0
        var upToDate = 0
        var failed = 0

        for (definition in definitions) {
            val enabled = isEnabled(context, definition) && hasRequiredPermissions(context, definition)
            val key = SENSOR_REGISTERED_PREFIX + definition.id
            val known = prefs.contains(key)

            // Never announce a sensor nobody asked for. Registering a switched-off sensor makes
            // Home Assistant create a disabled entity, and with two thirds of this catalog off by
            // default that is dozens of dead rows on the device page. A sensor Home Assistant
            // already knows about is still re-registered when switched off, so it gets marked
            // disabled rather than silently going stale.
            if (!enabled && !known) {
                skippedOff++
                continue
            }

            val signature = definition.signature(disabled = !enabled)
            if (prefs.getString(key, null) == signature) {
                upToDate++
                continue
            }

            // Each sensor stands alone. Registration is one HTTP call per sensor, so across ~50 of
            // them a single transient failure is likely — and letting it abort the loop would strand
            // every sensor after it, permanently: the ones before it have their signature recorded,
            // so the next pass skips straight back to the same failure.
            try {
                val reading = runCatching { readSensor(context, definition) }.getOrNull()
                MobileAppRegistration.sendWebhook(
                    context,
                    JSONObject()
                        .put("type", "register_sensor")
                        .put("data", definition.registrationJson(reading?.state ?: "unknown", disabled = !enabled))
                )
                // Recorded only after the call returns, so a failure leaves no signature and is
                // retried on the next pass.
                prefs.edit { putString(key, signature) }
                sent++
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "register: ${definition.id} failed", e)
                if (firstFailure == null) firstFailure = e
            }
        }

        Log.d(
            TAG,
            "register: sent=$sent upToDate=$upToDate skippedOff=$skippedOff failed=$failed " +
                "of ${definitions.size}"
        )

        // Surfaced only after everything else has had its turn, so the worker still schedules a
        // retry for whatever did not make it.
        firstFailure?.let { throw it }
    }

    /**
     * Pushes a state for every sensor Home Assistant has a registration for, including ones
     * switched off in this app.
     *
     * A registered sensor that has never received a state shows up in Home Assistant with no
     * value — and that is exactly what happens to a sensor registered as disabled and then
     * enabled from the Home Assistant side, since nothing tells this app about that. Running a
     * full push at startup gives every known entity a value regardless of which side switched it
     * on. It is still a single batched webhook call; only the reads cost more.
     */
    suspend fun updateAllKnownSensors(context: Context) {
        val prefs = sensorPrefs(context)
        updateSensors(context) { prefs.contains(SENSOR_REGISTERED_PREFIX + it.id) }
    }

    suspend fun updateEnabledSensors(context: Context) {
        updateSensors(context) { isEnabled(context, it) }
    }

    private suspend fun updateSensors(
        context: Context,
        include: (MobileSensorDefinition) -> Boolean
    ) {
        val readings = definitions
            .filter { include(it) && hasRequiredPermissions(context, it) }
            // A reader that throws must not take the whole batch with it — one unlucky sensor
            // would otherwise mean no sensor at all gets an update.
            .mapNotNull { definition ->
                runCatching { readSensor(context, definition) }
                    .onFailure { Log.w(TAG, "Could not read sensor ${definition.id}", it) }
                    .getOrNull()
            }

        Log.d(TAG, "update: sending ${readings.size} sensor states")
        if (readings.isNotEmpty()) {
            MobileAppRegistration.sendWebhook(
                context,
                JSONObject()
                    .put("type", "update_sensor_states")
                    .put("data", JSONArray().apply {
                        readings.forEach { put(it.updateJson()) }
                    })
            )
        }

        sendLocationUpdateIfEnabled(context)
    }

    suspend fun readSensor(context: Context, definition: MobileSensorDefinition): MobileSensorReading? {
        if (!hasRequiredPermissions(context, definition)) return null
        val appContext = context.applicationContext
        val battery by lazy { appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        val audio by lazy { appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        val power by lazy { appContext.getSystemService(Context.POWER_SERVICE) as PowerManager }

        return when (definition.id) {
            // ── Battery ─────────────────────────────────────────────────────────────────────
            "battery_level" -> batteryLevel(battery)?.let { reading(definition, it) }
            "battery_state" -> reading(definition, batteryState(battery))
            "battery_charging" -> reading(definition, batteryCharging(battery))
            "battery_health" -> reading(definition, batteryHealth(battery))
            "charger_type" -> reading(definition, chargerType(battery))
            "charger_power" -> chargerPowerWatts(battery)?.let { reading(definition, it) }
            "battery_temperature" -> battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { reading(definition, round1(it / 10f)) }
            "battery_voltage" -> battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { reading(definition, round2(it / 1000f)) }
            "battery_cycles" -> battery?.getIntExtra("android.os.extra.CYCLE_COUNT", Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { reading(definition, it) }
            "power_save" -> reading(definition, power.isPowerSaveMode)
            "battery_current" -> batteryProperty(appContext, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                // Reported in microamps, and negative while discharging on most devices.
                ?.let { reading(definition, round1(it / 1000f)) }
            "remaining_charge_time" -> batteryManager(appContext).computeChargeTimeRemaining()
                .takeIf { it > 0 }
                ?.let { reading(definition, (it / 60_000L).toInt()) }

            // ── Device state ────────────────────────────────────────────────────────────────
            "interactive" -> reading(definition, power.isInteractive)
            "doze_mode" -> reading(definition, power.isDeviceIdleMode)
            "device_locked" -> reading(definition, keyguard(appContext).isKeyguardLocked)
            "device_secure" -> reading(definition, keyguard(appContext).isDeviceSecure)
            "screen_brightness" -> systemSetting(appContext, Settings.System.SCREEN_BRIGHTNESS)
                ?.let { reading(definition, it) }
            "screen_off_timeout" -> systemSetting(appContext, Settings.System.SCREEN_OFF_TIMEOUT)
                ?.let { reading(definition, it) }
            "last_reboot" -> reading(definition, lastReboot())
            // No state at all rather than a placeholder: Home Assistant validates a timestamp
            // device class and rejects the whole registration if the value will not parse.
            "next_alarm" -> nextAlarm(appContext)?.let { reading(definition, it) }
            "device_uptime" -> reading(definition, round1(SystemClock.elapsedRealtime() / 3_600_000f))
            "app_standby_bucket" -> standbyBucket(appContext)?.let { reading(definition, it) }
            "app_importance" -> reading(definition, appImportance())
            "app_memory" -> reading(definition, appMemoryUsedMb())
            "screen_resolution" -> appContext.resources.displayMetrics.let {
                reading(definition, "${it.widthPixels}x${it.heightPixels}")
            }
            "display_density" -> reading(definition, appContext.resources.displayMetrics.densityDpi)
            "font_scale" -> reading(definition, round2(appContext.resources.configuration.fontScale))

            // ── Audio ───────────────────────────────────────────────────────────────────────
            "audio_mode" -> reading(definition, audioMode(audio))
            "ringer_mode" -> reading(definition, ringerMode(audio))
            "do_not_disturb_sensor" -> reading(definition, doNotDisturb(appContext))
            "is_music_active" -> reading(definition, audio.isMusicActive)
            "headphone" -> reading(definition, headphonesConnected(audio))
            "mic_muted" -> reading(definition, audio.isMicrophoneMute)
            @Suppress("DEPRECATION")
            "speakerphone" -> reading(definition, audio.isSpeakerphoneOn)
            "volume_level_ring" -> reading(definition, audio.getStreamVolume(AudioManager.STREAM_RING))
            "volume_level_music" -> reading(definition, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            "volume_level_alarm" -> reading(definition, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            "volume_level_notification" -> reading(definition, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
            "volume_level_call" -> reading(definition, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))

            // ── Network ─────────────────────────────────────────────────────────────────────
            "network_type" -> reading(definition, networkType(appContext))
            "wifi_connection" -> reading(definition, wifiInfoString(appContext) { it.ssid?.trim('"') })
            "wifi_bssid" -> reading(definition, wifiInfoString(appContext) { it.bssid })
            "wifi_signal_strength" -> wifiInfo(appContext)?.rssi?.let { reading(definition, it) }
            "wifi_frequency" -> wifiInfo(appContext)?.frequency?.let { reading(definition, it) }
            "wifi_link_speed" -> wifiInfo(appContext)?.linkSpeed?.takeIf { it >= 0 }?.let { reading(definition, it) }
            "wifi_ip_address" -> reading(definition, wifiIpAddress(appContext))
            "airplane_mode" -> reading(
                definition,
                Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            )
            "data_saver" -> reading(definition, dataSaverState(appContext))
            "bluetooth_state" -> bluetoothEnabled(appContext)?.let { reading(definition, it) }
            "nfc_state" -> nfcEnabled(appContext)?.let { reading(definition, it) }
            "carrier_name" -> telephony(appContext)?.networkOperatorName
                ?.takeIf { it.isNotBlank() }?.let { reading(definition, it) }
            "sim_state" -> telephony(appContext)?.let { reading(definition, simState(it)) }
            "traffic_total_rx" -> trafficGb(android.net.TrafficStats.getTotalRxBytes())
                ?.let { reading(definition, it) }
            "traffic_total_tx" -> trafficGb(android.net.TrafficStats.getTotalTxBytes())
                ?.let { reading(definition, it) }
            "location_accuracy" -> lastKnownLocation(appContext)?.let {
                reading(definition, round1(it.accuracy), mapOf("provider" to it.provider))
            }

            // ── Storage ─────────────────────────────────────────────────────────────────────
            "storage_free" -> reading(definition, freeGigabytes(Environment.getDataDirectory().path))
            "storage_external" -> externalStorageFreeGb()?.let { reading(definition, it) }
            "memory_available" -> reading(definition, memoryAvailableGb(appContext))

            // ── Hardware ────────────────────────────────────────────────────────────────────
            "light_sensor" -> sampleHardwareSensor(appContext, Sensor.TYPE_LIGHT)
                ?.let { reading(definition, round1(it)) }
            "pressure_sensor" -> sampleHardwareSensor(appContext, Sensor.TYPE_PRESSURE)
                ?.let { reading(definition, round2(it)) }
            "proximity_sensor" -> sampleHardwareSensor(appContext, Sensor.TYPE_PROXIMITY)
                ?.let { reading(definition, round1(it)) }
            "ambient_temperature_sensor" -> sampleHardwareSensor(appContext, Sensor.TYPE_AMBIENT_TEMPERATURE)
                ?.let { reading(definition, round1(it)) }
            "humidity_sensor" -> sampleHardwareSensor(appContext, Sensor.TYPE_RELATIVE_HUMIDITY)
                ?.let { reading(definition, round1(it)) }

            // ── Diagnostics ─────────────────────────────────────────────────────────────────
            "app_version" -> reading(definition, appVersion(appContext))
            "os_version" -> reading(definition, Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
            "device_model" -> reading(definition, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            "last_update" -> reading(definition, Instant.now().toString())
            else -> null
        }
    }

    private suspend fun sendLocationUpdateIfEnabled(context: Context) {
        val accuracySensor = definitions.firstOrNull { it.id == "location_accuracy" } ?: return
        if (!isEnabled(context, accuracySensor) || !hasRequiredPermissions(context, accuracySensor)) return
        val location = lastKnownLocation(context) ?: return
        val battery = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val data = JSONObject()
            .put("gps", JSONArray().put(location.latitude).put(location.longitude))
            .put("gps_accuracy", location.accuracy)
            .put("altitude", location.altitude)
            .put("course", location.bearing)
            .put("speed", location.speed)
        batteryLevel(battery)?.let { data.put("battery", it) }
        MobileAppRegistration.sendWebhook(
            context,
            JSONObject()
                .put("type", "update_location")
                .put("data", data)
        )
    }

    /**
     * Throws away every stored registration signature when the webhook this phone talks through
     * has changed.
     *
     * "Register again" mints a new webhook id, which Home Assistant treats as a new device — the
     * sensor entities belonging to the old one are dropped. The signatures are a claim about what
     * *that* device knew, so keeping them makes the app believe all 67 sensors are still
     * registered, register nothing, and then push states at a device that has never heard of them.
     *
     * Tying the cache to the webhook id makes this self-healing for any cause, not just the
     * button: if the id ever changes, the next pass re-registers from scratch.
     */
    private fun forgetRegistrationsIfDeviceChanged(context: Context, prefs: SharedPreferences) {
        val webhookId = MobileAppRegistration.registrationInfo(context)?.webhookId ?: return
        if (prefs.getString(SENSOR_REGISTRATION_OWNER, null) == webhookId) return

        val stale = prefs.all.keys.filter { it.startsWith(SENSOR_REGISTERED_PREFIX) }
        Log.i(TAG, "register: webhook changed, forgetting ${stale.size} registrations")
        prefs.edit {
            stale.forEach { remove(it) }
            putString(SENSOR_REGISTRATION_OWNER, webhookId)
        }
    }

    /** The fields Home Assistant stores at registration; a change to any of them needs a re-send. */
    private fun MobileSensorDefinition.signature(disabled: Boolean): String =
        listOf(name, type, icon, deviceClass, unit, stateClass, entityCategory, disabled)
            .joinToString("|")

    private fun MobileSensorDefinition.registrationJson(initialState: Any, disabled: Boolean): JSONObject {
        val json = JSONObject()
            .put("unique_id", id)
            .put("name", name)
            .put("type", type)
            .put("state", initialState)
            .put("icon", icon)
            .put("disabled", disabled)
        deviceClass?.let { json.put("device_class", it) }
        unit?.let { json.put("unit_of_measurement", it) }
        stateClass?.let { json.put("state_class", it) }
        entityCategory?.let { json.put("entity_category", it) }
        return json
    }

    private fun MobileSensorReading.updateJson(): JSONObject {
        val json = JSONObject()
            .put("unique_id", definition.id)
            .put("type", definition.type)
            .put("state", state)
        if (attributes.isNotEmpty()) {
            json.put("attributes", JSONObject().apply {
                attributes.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
            })
        }
        return json
    }

    private fun reading(
        definition: MobileSensorDefinition,
        state: Any,
        attributes: Map<String, Any?> = emptyMap()
    ) = MobileSensorReading(definition, state, attributes)

    // ── Battery helpers ────────────────────────────────────────────────────────────────────

    private fun batteryLevel(intent: Intent?): Int? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return null
        return ((level / scale.toFloat()) * 100f).roundToInt()
    }

    private fun batteryState(intent: Intent?): String =
        when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }

    private fun batteryCharging(intent: Intent?): Boolean =
        when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }

    private fun batteryHealth(intent: Intent?): String =
        when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheated"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failed"
            else -> "unknown"
        }

    private fun chargerType(intent: Intent?): String =
        when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }

    /**
     * Charging current and voltage are reported in micro-units, so the product needs scaling twice.
     *
     * The extra names are spelled out rather than referenced: `EXTRA_MAX_CHARGING_CURRENT` and
     * `EXTRA_MAX_CHARGING_VOLTAGE` are not in the public SDK, but the values are present on the
     * sticky ACTION_BATTERY_CHANGED intent and reading an extra by name is ordinary API use. A
     * device that does not populate them reports -1 and the sensor stays unavailable.
     */
    private fun chargerPowerWatts(intent: Intent?): Float? {
        val currentMicroAmps = intent?.getIntExtra("max_charging_current", -1) ?: -1
        val voltageMicroVolts = intent?.getIntExtra("max_charging_voltage", -1) ?: -1
        if (currentMicroAmps <= 0 || voltageMicroVolts <= 0) return null
        return round1(currentMicroAmps.toFloat() / 1_000_000f * (voltageMicroVolts.toFloat() / 1_000_000f))
    }

    private fun batteryManager(context: Context) =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    /** Battery properties return Int.MIN_VALUE or 0 on devices that do not measure them. */
    private fun batteryProperty(context: Context, property: Int): Int? =
        batteryManager(context).getIntProperty(property)
            .takeIf { it != Int.MIN_VALUE && it != 0 }

    // ── Device helpers ─────────────────────────────────────────────────────────────────────

    /** Android's own guess at how aggressively it will throttle this app's background work. */
    private fun standbyBucket(context: Context): String? {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        return when (runCatching { usage.appStandbyBucket }.getOrNull()) {
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> "unknown"
        }
    }

    private fun appImportance(): String {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return when (state.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground_service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
            else -> "unknown"
        }
    }

    private fun appMemoryUsedMb(): Float {
        val runtime = Runtime.getRuntime()
        return round2((runtime.totalMemory() - runtime.freeMemory()) / 1024f / 1024f)
    }

    // ── Connectivity helpers ───────────────────────────────────────────────────────────────

    private fun dataSaverState(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return when (runCatching { cm.restrictBackgroundStatus }.getOrNull()) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "disabled"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
            else -> "unknown"
        }
    }

    /**
     * Reading the adapter's on/off state needs no runtime permission — unlike naming or scanning
     * devices — but a device without Bluetooth has no adapter at all, hence the null.
     */
    private fun bluetoothEnabled(context: Context): Boolean? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager ?: return null
        return runCatching { manager.adapter?.isEnabled }.getOrNull()
    }

    private fun nfcEnabled(context: Context): Boolean? =
        runCatching { android.nfc.NfcAdapter.getDefaultAdapter(context)?.isEnabled }.getOrNull()

    private fun telephony(context: Context): android.telephony.TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager

    private fun simState(telephony: android.telephony.TelephonyManager): String =
        when (runCatching { telephony.simState }.getOrNull()) {
            android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
            android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
            android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
            android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
            android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
            android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "not_ready"
            android.telephony.TelephonyManager.SIM_STATE_PERM_DISABLED -> "disabled"
            android.telephony.TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "io_error"
            else -> "unknown"
        }

    /** TrafficStats counts since boot, and returns UNSUPPORTED (-1) on devices that do not track it. */
    private fun trafficGb(bytes: Long): Float? =
        bytes.takeIf { it >= 0 }?.let { round2(it / 1024f / 1024f / 1024f) }

    private fun keyguard(context: Context) =
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private fun systemSetting(context: Context, key: String): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()

    /** Wall-clock time of boot, derived from uptime — there is no direct API for it. */
    private fun lastReboot(): String =
        Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime()).toString()

    private fun nextAlarm(context: Context): String? {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = runCatching { alarms.nextAlarmClock }.getOrNull() ?: return null
        return Instant.ofEpochMilli(next.triggerTime).toString()
    }

    // ── Audio helpers ──────────────────────────────────────────────────────────────────────

    private fun audioMode(audio: AudioManager): String =
        when (audio.mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "ringing"
            AudioManager.MODE_IN_CALL -> "in_call"
            AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
            else -> "unknown"
        }

    private fun ringerMode(audio: AudioManager): String =
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_SILENT -> "silent"
            else -> "unknown"
        }

    private fun doNotDisturb(context: Context): String {
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return when (runCatching { notifications.currentInterruptionFilter }.getOrNull()) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority_only"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            else -> "unknown"
        }
    }

    private fun headphonesConnected(audio: AudioManager): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

    // ── Network helpers ────────────────────────────────────────────────────────────────────

    private fun networkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun wifiInfo(context: Context): android.net.wifi.WifiInfo? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return runCatching { wifi?.connectionInfo }.getOrNull()
    }

    private fun wifiInfoString(context: Context, select: (android.net.wifi.WifiInfo) -> String?): String {
        val raw = wifiInfo(context)?.let(select).orEmpty()
        return raw.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "02:00:00:00:00:00" }
            ?: "unknown"
    }

    @Suppress("DEPRECATION")
    private fun wifiIpAddress(context: Context): String {
        val raw = wifiInfo(context)?.ipAddress ?: return "unknown"
        if (raw == 0) return "unknown"
        return listOf(raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff)
            .joinToString(".")
    }

    // ── Storage helpers ────────────────────────────────────────────────────────────────────

    private fun freeGigabytes(path: String): Float =
        round2(StatFs(path).availableBytes / 1024f / 1024f / 1024f)

    private fun externalStorageFreeGb(): Float? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        val path = Environment.getExternalStorageDirectory()?.path ?: return null
        return runCatching { freeGigabytes(path) }.getOrNull()
    }

    private fun memoryAvailableGb(context: Context): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return round2(info.availMem / 1024f / 1024f / 1024f)
    }

    // ── Hardware sensor sampling ───────────────────────────────────────────────────────────

    /**
     * Reads one value from a hardware sensor.
     *
     * These have no queryable current value — you subscribe and wait for the system to push a
     * sample. The timeout matters: a proximity sensor that is not being triggered may not report
     * for a long time, and a worker cannot sit blocked on it.
     */
    private suspend fun sampleHardwareSensor(context: Context, type: Int): Float? {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = manager.getDefaultSensor(type) ?: return null
        return withTimeoutOrNull(HARDWARE_SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val value = event.values.firstOrNull()
                        manager.unregisterListener(this)
                        if (continuation.isActive && value != null) continuation.resume(value)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                continuation.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        }
    }

    // ── Misc ───────────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true)
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun appVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"

    private fun round1(value: Float): Float =
        String.format(Locale.US, "%.1f", value).toFloat()

    private fun round2(value: Float): Float =
        String.format(Locale.US, "%.2f", value).toFloat()

    private fun sensorPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(HomeAssistantAuth.PREFS_NAME, Context.MODE_PRIVATE)
}
