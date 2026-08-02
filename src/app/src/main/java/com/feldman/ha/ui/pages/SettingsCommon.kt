package com.feldman.ha.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.ha.mobile.MobileSensorDefinition
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import androidx.compose.ui.text.font.FontWeight

/** Runtime permission the local-network scan needs on API 37+. */
internal const val SettingsLocalNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Fixed icon-chip colours for the settings hub, one hue per category.
 *
 * Deliberately literal rather than theme roles. The scheme only offers three container roles, so
 * six categories drawn from it repeat and stop being recognisable; hand-picked tonal pairs give
 * each row its own identity and stay put when the seed colour changes, which is what makes the
 * list scannable by colour alone. Values are Material tonal-palette steps — container around
 * tone 30 in dark and tone 90 in light, with content at the opposite end.
 */
internal enum class SettingsCategoryColor(
    private val darkContainer: Long,
    private val darkContent: Long,
    private val lightContainer: Long,
    private val lightContent: Long
) {
    /** Blue. The connection everything else depends on. */
    CONNECTION(0xFF004A77, 0xFFC2E7FF, 0xFFD7E3FF, 0xFF005AC1),
    /** Teal, for the device itself. */
    DEVICE(0xFF004D61, 0xFFACEFEE, 0xFFACEFEE, 0xFF002022),
    /** Pink, matching where the theme controls live. */
    APPEARANCE(0xFF7D5260, 0xFFFFD8E4, 0xFFFFD8E4, 0xFF631835),
    /** Indigo, for layout and behaviour. */
    LAYOUT(0xFF3E4C63, 0xFFD7E3FF, 0xFFD7E3FF, 0xFF253347),
    /** Violet, for the dashboard shown while the device is idle. */
    SCREENSAVER(0xFF553F6B, 0xFFECD8FF, 0xFFECD8FF, 0xFF3D1D55),
    /** Magenta, the media category. */
    MEDIA(0xFF633B48, 0xFFFFD8EC, 0xFFFFD8EC, 0xFF631B4B),
    /** Green, for things that live outside the app. */
    EXTERNAL(0xFF324F34, 0xFFCBEFD0, 0xFFCBEFD0, 0xFF042106);

    fun container(isDark: Boolean): Color = Color(if (isDark) darkContainer else lightContainer)
    fun content(isDark: Boolean): Color = Color(if (isDark) darkContent else lightContent)
}

/**
 * The back arrow every settings sub-page shares. Written once here so a new sub-page cannot
 * accidentally drift from the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            FilledIconButton(
                onClick = onBack,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colorScheme.surface,
                    contentColor = colorScheme.onSurface
                )
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.surfaceContainer)
    )
}

internal fun sensorDescription(
    sensor: MobileSensorDefinition,
    enabled: Boolean,
    hasPermission: Boolean
): String {
    if (sensor.permissionGroup != null && !hasPermission) {
        return if (enabled) "Permission needed" else "Requires permission"
    }
    return when {
        enabled && sensor.unit != null -> "Enabled, ${sensor.unit}"
        enabled -> "Enabled"
        else -> "Disabled"
    }
}

internal fun mobileSensorIcon(sensor: MobileSensorDefinition): String =
    when (sensor.id) {
        "battery_level", "battery_state", "battery_charging", "battery_temperature", "battery_voltage" -> "battery_full"
        "charger_type" -> "power"
        "power_save" -> "battery_saver"
        "interactive" -> "phone_android"
        "network_type", "wifi_connection", "wifi_bssid" -> "wifi"
        "storage_free" -> "storage"
        "memory_available" -> "memory"
        "location_accuracy" -> "my_location"
        "app_version" -> "apps"
        "os_version" -> "android"
        "device_model" -> "smartphone"
        "last_update" -> "schedule"
        else -> "sensors"
    }

/**
 * Bottom dock for pages that batch their edits behind an explicit save. Fades the content out
 * behind it rather than sitting on a hard edge.
 */
@Composable
internal fun SettingsSaveDock(
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dockGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to colorScheme.surface.copy(alpha = 0f),
            0.42f to colorScheme.surface.copy(alpha = 0.58f),
            1f to colorScheme.surface.copy(alpha = 0.96f)
        )
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val buttonWidth = maxWidth - 32.dp

        Spacer(modifier = Modifier.matchParentSize().background(dockGradient))

        MotionButton(
            text = "Save changes",
            icon = "check",
            onClick = onSave,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 12.dp)
                .navigationBarsPadding(),
            width = buttonWidth,
            height = 64.dp,
            fontSize = 20.sp,
            iconSize = 22.dp,
            defaultState = MotionButtonState(
                backgroundColor = colorScheme.primary,
                contentColor = colorScheme.onPrimary,
                outlineWidth = 0.dp,
                outlineColor = colorScheme.outline
            )
        )
    }
}

@Composable
internal fun GridSettingRow(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, optionLabel) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(optionLabel) }
                )
            }
        }
    }
}

@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 4,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colorScheme.surfaceVariant,
            unfocusedContainerColor = colorScheme.surfaceVariant,
            focusedBorderColor = colorScheme.primary,
            unfocusedBorderColor = colorScheme.outline
        )
    )
}
