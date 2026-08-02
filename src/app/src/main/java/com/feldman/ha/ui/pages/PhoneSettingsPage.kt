package com.feldman.ha.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.ha.mobile.MobileAppRegistration
import com.feldman.ha.mobile.MobileAppSensorService
import com.feldman.ha.mobile.MobileAppSensorUpdateWorker
import com.feldman.ha.mobile.MobileAppSensors
import com.feldman.ha.mobile.MobileSensorDefinition
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Registering this phone with Home Assistant's Mobile App integration, and which of its sensors
 * report back. Sensors are only useful once registration succeeded, so both live here rather than
 * being split across two pages.
 *
 * Everything applies immediately — there is no save dock, because each toggle is its own commit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var registered by remember(context) { mutableStateOf(MobileAppRegistration.isRegistered(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sensorEnabled by remember(context) { mutableStateOf(MobileAppSensors.enabledMap(context)) }
    var liveUpdates by remember(context) { mutableStateOf(MobileAppSensorService.isEnabled(context)) }
    var deviceName by remember(context) { mutableStateOf(MobileAppRegistration.deviceName(context)) }
    var renaming by remember { mutableStateOf(false) }
    var pendingSensorPermission by remember { mutableStateOf<MobileSensorDefinition?>(null) }

    /** Re-registers so Home Assistant learns about the new sensor set. No-op until registered. */
    fun syncSensors() {
        if (!registered) return
        scope.launch {
            withContext(Dispatchers.IO) {
                MobileAppSensors.registerAllSensors(context)
                MobileAppSensorUpdateWorker.enqueueNow(context, fullSync = true)
            }
        }
    }

    // Opening this page is the clearest signal that someone is looking at their sensors and
    // expects what Home Assistant shows to match what is on screen. Cheap: registration only
    // sends the sensors whose signature changed.
    LaunchedEffect(registered) { if (registered) syncSensors() }

    val sensorPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val sensor = pendingSensorPermission ?: return@rememberLauncherForActivityResult
        pendingSensorPermission = null
        if (MobileAppSensors.hasRequiredPermissions(context, sensor)) {
            MobileAppSensors.setEnabled(context, sensor.id, true)
            sensorEnabled = MobileAppSensors.enabledMap(context)
            status = "${sensor.name} enabled."
            syncSensors()
        } else {
            status = "${sensor.name} needs permission before it can be enabled."
        }
    }

    fun registerPhone() {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    MobileAppRegistration.registerDevice(context)
                    MobileAppSensors.registerAllSensors(context)
                    MobileAppSensors.updateEnabledSensors(context)
                    MobileAppSensorUpdateWorker.schedule(context)
                }
            }.fold(
                onSuccess = {
                    busy = false
                    registered = true
                    // The service refuses to start until registration exists, so this is the
                    // first moment it can come up.
                    MobileAppSensorService.start(context)
                    status = "Phone registered in Home Assistant Mobile App integration."
                },
                onFailure = { error ->
                    busy = false
                    registered = MobileAppRegistration.isRegistered(context)
                    status = error.localizedMessage ?: "Could not register this phone."
                }
            )
        }
    }

    fun setSensorEnabled(sensor: MobileSensorDefinition, enabled: Boolean) {
        if (enabled && !MobileAppSensors.hasRequiredPermissions(context, sensor)) {
            pendingSensorPermission = sensor
            sensorPermissionLauncher.launch(MobileAppSensors.requiredPermissions(sensor))
            return
        }
        MobileAppSensors.setEnabled(context, sensor.id, enabled)
        sensorEnabled = MobileAppSensors.enabledMap(context)
        status = if (enabled) "${sensor.name} enabled." else "${sensor.name} disabled."
        syncSensors()
    }

    SettingsScaffold(
        title = "Phone",
        scaffoldModifier = Modifier.fillMaxSize(),
        topBar = { SettingsTopBar("Phone", onBack) }
    ) {
        title("Mobile app")
        section {
            item(padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (registered) {
                            "This phone is registered with Home Assistant."
                        } else {
                            "Register this phone in Home Assistant's Mobile App integration."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = if (registered) {
                            "Sensors update through the mobile app webhook. Register again after changing Home Assistant URL or account."
                        } else {
                            "Registration works with either Home Assistant account login or a long-lived access token."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    MotionButton(
                        text = when {
                            busy -> "Registering…"
                            registered -> "Register again"
                            else -> "Register phone"
                        },
                        icon = "smartphone",
                        enabled = !busy,
                        onClick = ::registerPhone,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        fontSize = 18.sp,
                        iconSize = 20.dp,
                        defaultState = MotionButtonState(
                            backgroundColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary,
                            outlineWidth = 0.dp,
                            outlineColor = colorScheme.outline
                        )
                    )
                    if (status != null) {
                        Text(
                            text = status.orEmpty(),
                            color = if (registered) colorScheme.onSurfaceVariant else colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item(padding = 16.dp, visible = registered) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = "Name in Home Assistant"
                    )
                    MotionButton(
                        text = "Rename",
                        icon = "edit",
                        enabled = !renaming && deviceName.isNotBlank(),
                        onClick = {
                            renaming = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        MobileAppRegistration.setDeviceName(context, deviceName)
                                    }
                                }.fold(
                                    onSuccess = {
                                        renaming = false
                                        deviceName = MobileAppRegistration.deviceName(context)
                                        status = "Renamed to $deviceName."
                                    },
                                    onFailure = { error ->
                                        renaming = false
                                        status = error.localizedMessage ?: "Could not rename this phone."
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        fontSize = 16.sp,
                        iconSize = 18.dp,
                        defaultState = MotionButtonState(
                            backgroundColor = colorScheme.secondaryContainer,
                            contentColor = colorScheme.onSecondaryContainer,
                            outlineWidth = 0.dp,
                            outlineColor = colorScheme.outline
                        )
                    )
                }
            }
            switchItem(
                title = "Live updates",
                description = "Report changes as they happen instead of every 15 minutes. " +
                    "Shows a permanent notification, which Android requires to watch device state.",
                checked = liveUpdates,
                onCheckedChange = { checked ->
                    liveUpdates = checked
                    MobileAppSensorService.setEnabled(context, checked)
                },
                visible = registered
            )
        }

        // Grouped rather than one flat list: there are around fifty sensors, and scrolling past
        // every Wi-Fi reading to reach the battery ones is the thing that makes a settings page
        // feel like a dump.
        MobileAppSensors.definitions.groupBy { it.category }.forEach { (category, sensors) ->
        title(category.label)
        section {
            sensors.forEach { sensor ->
                item(key = sensor.id, padding = 16.dp) {
                    val enabled = sensorEnabled[sensor.id] == true
                    val hasPermission = MobileAppSensors.hasRequiredPermissions(context, sensor)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = rememberSymbolPainter(mobileSensorIcon(sensor)),
                            contentDescription = null,
                            tint = if (enabled) colorScheme.primary else colorScheme.onSurfaceVariant
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = sensor.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = sensorDescription(sensor, enabled, hasPermission),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { setSensorEnabled(sensor, it) }
                        )
                    }
                }
            }
        }
        }
    }
}
