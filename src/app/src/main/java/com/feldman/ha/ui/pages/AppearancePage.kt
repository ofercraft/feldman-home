package com.feldman.ha.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.feldman.ha.ui.cards.CardBackgroundSetting
import com.feldman.ha.ui.cards.ExpressiveCanvasSetting
import com.feldman.motion.MotionLevel
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.ThemeRepository
import com.feldman.motion.isDarkTheme
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.themeColors
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Switch
import androidx.compose.material3.ripple
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearancePage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val themeRepository = remember(context) { ThemeRepository(context) }
    val scope = rememberCoroutineScope()

    // One switch over what used to be two settings: tinted card/widget surfaces and the tinted
    // page canvas. They were always turned on together in practice, and neither reads as a
    // meaningful choice on its own. Considered on only when both underlying flags are.
    var expressiveDesign by remember {
        mutableStateOf(
            CardBackgroundSetting.useSecondary(context) && ExpressiveCanvasSetting.isEnabled(context)
        )
    }

    val themeMode by themeRepository.themeMode.collectAsState(initial = 0)
    val themeColor by themeRepository.themeColor.collectAsState(initial = 0)
    val dynamicColor by themeRepository.dynamicColor.collectAsState(initial = true)
    val tintPalette by themeRepository.tintPalette.collectAsState(initial = false)
    val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)
    val useDark = isDarkTheme()

    SettingsScaffold(
        scaffoldModifier = Modifier.fillMaxSize(),
        topBar = { SettingsTopBar("Appearance", onBack) }
    ) {
        title("Appearance")
        section {
            listOf(
                Triple(0, "System", "brightness_auto"),
                Triple(1, "Light", "light_mode"),
                Triple(2, "Dark", "dark_mode")
            ).forEach { (id, label, icon) ->
                choiceItem(
                    key = id,
                    title = label,
                    icon = rememberSymbolPainter(icon),
                    selected = themeMode == id,
                    // Same constraint as the Motion picker below: the item's selected content is
                    // always onPrimary, so the selected fill has to be primary.
                    containerColor = if (themeMode == id) colorScheme.primary else colorScheme.surfaceContainerHigh,
                    onClick = {
                        scope.launch { themeRepository.setThemeMode(id) }
                    }
                )
            }
        }

        title("Theme color")
        section {
            item(padding = 0.dp) {
                ExpressiveDesignSetting(
                    enabled = expressiveDesign,
                    onEnabledChange = { enabled ->
                        expressiveDesign = enabled
                        CardBackgroundSetting.setUseSecondary(context, enabled)
                        ExpressiveCanvasSetting.setEnabled(context, enabled)
                    }
                )
            }
            switchItem(
                title = "Dynamic color",
                description = "Match colors to your wallpaper",
                checked = dynamicColor == true,
                onCheckedChange = { checked ->
                    scope.launch { themeRepository.setDynamicColor(checked) }
                }
            )
            switchItem(
                title = "Vibrant palette",
                description = "Use higher saturation tones",
                checked = tintPalette,
                onCheckedChange = { checked ->
                    scope.launch { themeRepository.setTintPalette(checked) }
                },
                visible = dynamicColor != true
            )
            pickerItem(
                visible = dynamicColor != true,
                padding = 12.dp
            ) {
                val pickerEnabled = dynamicColor != true

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (pickerEnabled) 1f else 0.45f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(themeColors) { index, pair ->
                        val displayColor = if (useDark) pair.dark else pair.light
                        ColorPickerItem(
                            color = displayColor,
                            selected = themeColor == index,
                            outlineColor = colorScheme.onBackground,
                            onClick = {
                                if (pickerEnabled) {
                                    scope.launch { themeRepository.setThemeColor(index) }
                                }
                            }
                        )
                    }
                }
            }
        }

        title("Motion")
        section {
            listOf(
                Triple(MotionLevel.NONE, "None", "stop_circle"),
                Triple(MotionLevel.LOW, "Low", "trail_length_short"),
                Triple(MotionLevel.MEDIUM, "Medium", "trail_length_medium"),
                Triple(MotionLevel.HIGH, "High", "trail_length")
            ).forEach { (level, label, icon) ->
                choiceItem(
                    key = level.id,
                    title = label,
                    icon = rememberSymbolPainter(icon),
                    selected = motionLevel == level,
                    // ChoiceSettingsItem hardcodes its selected label and icon to onPrimary, so
                    // the container has to be primary for the pair to have any contrast —
                    // onPrimary over primaryContainer is two dark tones in a dark theme.
                    containerColor = if (motionLevel == level) colorScheme.primary else colorScheme.surfaceContainerHigh,
                    onClick = {
                        scope.launch { themeRepository.setMotionLevel(level) }
                    }
                )
            }
        }

        item {
            Spacer(Modifier.height(120.dp))
        }
    }
}

/**
 * Switch row with a leading icon. The settings DSL's own [switchItem] has no icon slot, so this
 * mirrors its layout — same 15sp medium title, same onSurfaceVariant description, one shared
 * interaction source so the ripple covers the whole row.
 */
@Composable
private fun ExpressiveDesignSetting(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = colorScheme.primary.copy(alpha = 0.12f)),
                role = Role.Switch
            ) { onEnabledChange(!enabled) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = rememberSymbolPainter("animation"),
            contentDescription = null,
            tint = if (enabled) colorScheme.primary else colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Expressive design",
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Tinted cards and widgets, with an accented page canvas",
                fontSize = 15.sp,
                lineHeight = 18.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            interactionSource = interactionSource
        )
    }
}

@Composable
private fun ColorPickerItem(
    color: Color,
    selected: Boolean,
    outlineColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(3.dp, outlineColor, CircleShape)
                else Modifier
            )
    )
}
