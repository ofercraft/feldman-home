package com.feldman.ha.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider as GlanceBackgroundColorProvider
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import androidx.glance.layout.*
import androidx.glance.material3.ColorProviders
import androidx.glance.text.TextStyle
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.text.FontWeight
import androidx.glance.text.FontFamily
import androidx.glance.text.Text

private val GoogleSansFlex = FontFamily("google_sans_flex")

// ---------- Public DSL ----------

@Composable
fun WidgetCard(
    title: String,
    subtitle: String? = null,
    icon: ImageProvider? = null,
    updateCount: Int = 0,
    headerTint: Color = Color.White,
    theme: String = "auto",
    onClick: Action? = null,
    headerAction: @Composable (() -> Unit)? = null,
    body: @Composable WidgetCardScope.() -> Unit
) {
    @Composable
    fun renderCard(widgetColors: WidgetColors) {
        val size = LocalSize.current
        val scope = WidgetCardScope()
        scope.body()

        // Base header height: ~70dp at scale 1.0
        val baseHeaderHeight = 70.dp
        val totalRowCount = scope.rows.size

        val safeWidth = if (size.width == Dp.Unspecified || size.width.value.isNaN() || size.width < 10.dp) 180.dp else size.width
        val safeHeight = if (size.height == Dp.Unspecified || size.height.value.isNaN() || size.height < 10.dp) (baseHeaderHeight + scope.requiredHeight).coerceAtLeast(110.dp) else size.height

        // Dynamic scale based on height. When "keep original size" is enabled, cap the upper
        // bound at 1.0 so a larger widget cell keeps the controls near their native size.
        val noEnlarge = widgetNoEnlarge(LocalContext.current)
        val maxScaleY = if (noEnlarge) 1.0f else 1.6f
        val maxScaleX = if (noEnlarge) 1.0f else 1.5f
        val scaleY = (safeHeight.value / (baseHeaderHeight.value + scope.requiredHeight.value)).coerceIn(0.6f, maxScaleY)
        val scaleX = (safeWidth.value / 180f).coerceIn(0.7f, maxScaleX)

        val isCompact = scaleY < 0.85f

        val headerIconSize = (44 * scaleY).dp
        val headerSpacing = (6 * scaleY).dp
        val outerPadding = (minOf(safeWidth.value, safeHeight.value) * 0.06f).coerceIn(8f, 14f).dp

        // Calculate common button height from the interior height, not the full widget bounds.
        // Dense widgets may grow freely; sparse widgets can grow too, but their rows are bounded
        // by width so a fan/lock/cover control stays pill-shaped in a resized launcher cell.
        val contentHeight = (safeHeight - outerPadding * 2).coerceAtLeast(0.dp)
        val headerHeight = maxOf(headerIconSize, (34 * scaleY).dp)
        val baseRowPadding = (2 * scaleY).coerceIn(2f, 5f).dp
        val rowsHeightAvailable = (contentHeight - headerHeight - headerSpacing).coerceAtLeast(0.dp)
        val minimumRowHeight = (24f + baseRowPadding.value * 2f).dp
        val visibleRowCount = if (totalRowCount == 0) {
            0
        } else {
            (rowsHeightAvailable.value / minimumRowHeight.value).toInt().coerceIn(0, totalRowCount)
        }
        val sizingRowCount = visibleRowCount.coerceAtLeast(1)
        val interiorWidth = (safeWidth - outerPadding * 2).coerceAtLeast(80.dp)
        val maxButton = when {
            noEnlarge -> 50.dp
            visibleRowCount <= 1 -> (interiorWidth.value * 0.48f).coerceIn(52f, 92f).dp
            visibleRowCount == 2 -> (interiorWidth.value * 0.46f).coerceIn(50f, 84f).dp
            else -> (interiorWidth.value * 0.52f).coerceIn(64f, 96f).dp
        }
        val targetButtonHeight = rowsHeightAvailable / sizingRowCount - baseRowPadding * 2
        val buttonHeight = targetButtonHeight.coerceIn(24.dp, maxButton)
        val usedRowsHeight = (buttonHeight + baseRowPadding * 2) * visibleRowCount
        val extraRowsHeight = (rowsHeightAvailable - usedRowsHeight).coerceAtLeast(0.dp)
        val maxRowPadding = if (visibleRowCount <= 2) 7.dp else 8.dp
        val rowVerticalPadding = if (visibleRowCount == 0) {
            baseRowPadding
        } else {
            (baseRowPadding + extraRowsHeight / (visibleRowCount * 2)).coerceAtMost(maxRowPadding)
        }
        val finalRowsHeight = (buttonHeight + rowVerticalPadding * 2) * visibleRowCount
        val bodyExtraHeight = (rowsHeightAvailable - finalRowsHeight).coerceAtLeast(0.dp)
        val bodyTopBias = when {
            visibleRowCount == 0 -> 0.dp
            visibleRowCount == 1 -> (bodyExtraHeight.value * 0.14f).coerceAtMost(12f).dp
            visibleRowCount == 2 -> (bodyExtraHeight.value * 0.10f).coerceAtMost(10f).dp
            else -> 0.dp
        }

        val info = LayoutInfo(scaleX, scaleY, safeWidth, buttonHeight.value, rowVerticalPadding, outerPadding, visibleRowCount <= 2, isCompact, updateCount, widgetColors)

        val displaySubtitle = subtitle ?: ""

        val cardModifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(widgetColors.background)
                .let { modifier -> if (onClick != null) modifier.clickable(onClick) else modifier }
                .padding(outerPadding)

        Column(
            modifier = cardModifier
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(
                        modifier = GlanceModifier
                            .size(headerIconSize)
                            .background(widgetColors.buttonBackground)
                            .cornerRadius(headerIconSize / 2),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = icon,
                            contentDescription = "count_$updateCount",
                            modifier = GlanceModifier.size(headerIconSize * 0.55f),
                            colorFilter = ColorFilter.tint(widgetColors.onBackground)
                        )
                    }
                    Spacer(GlanceModifier.width((10 * scaleX).dp))
                }
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        title,
                        style = TextStyle(
                            color = widgetColors.onBackground,
                            fontFamily = GoogleSansFlex,
                            fontSize = (16 * scaleY).sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        displaySubtitle,
                        style = TextStyle(
                            color = widgetColors.onBackgroundVariant,
                            fontFamily = GoogleSansFlex,
                            fontSize = (14 * scaleY).sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                if (headerAction != null) {
                    headerAction()
                }
            }

            Spacer(GlanceModifier.height(headerSpacing))

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(top = bodyTopBias),
                verticalAlignment = Alignment.CenterVertically
            ) {
                scope.rows.take(visibleRowCount).forEach { rowContent ->
                    rowContent.invoke(info)
                }
            }
        }
    }

    WithWidgetColors(theme) { widgetColors -> renderCard(widgetColors) }
}

/**
 * Resolves the widget color roles exactly like [WidgetCard] and provides them inside the
 * matching GlanceTheme. For widget styles that draw their own chrome (e.g. the button
 * widget) so they still share the standard widget background/content colors.
 *
 * "auto" → use the EXACT colors the app last rendered (seed, dynamic, vibrant palette,
 * light/dark — all baked in). Falls back to Material You dynamic colors if the app has
 * never run. "light"/"dark" force a Material You scheme as an explicit override.
 */
@Composable
fun WithWidgetColors(theme: String = "auto", content: @Composable (WidgetColors) -> Unit) {
    val context = LocalContext.current
    val appColors = if (theme == "auto") readAppSchemeColors(context) else null
    val lightScheme = dynamicLightColorScheme(context)
    val darkScheme = dynamicDarkColorScheme(context)
    val dynamicColors = when (theme) {
        "light" -> ColorProviders(lightScheme, lightScheme)
        "dark"  -> ColorProviders(darkScheme, darkScheme)
        else    -> ColorProviders(lightScheme, darkScheme)
    }

    GlanceTheme(colors = dynamicColors) {
        val widgetColors = if (appColors != null) {
            appWidgetColors(appColors)
        } else {
            // Fallback before the app has ever run — mirror the card roles as closely as
            // Glance's M3 color set allows (surfaceVariant for buttons, not secondaryContainer).
            // Glance has no surfaceContainer role, so the neutral variant falls back to surface.
            // Tinted mode reads the raw secondary accent palette. Once the app has run,
            // readAppSchemeColors() above supplies the exact card colors instead — including the
            // seed-derived tint cardBackgroundColor() uses when dynamic color is off. There is no
            // stored theme preference to honour yet on this path, so the wallpaper palette stands.
            val tintedBackground =
                com.feldman.ha.ui.cards.CardBackgroundSetting.useSecondary(context)
            fun accentProvider(lightRes: Int, darkRes: Int): GlanceColorProvider {
                val light = Color(context.getColor(lightRes))
                val dark = Color(context.getColor(darkRes))
                return when (theme) {
                    "light" -> GlanceBackgroundColorProvider(light, light)
                    "dark" -> GlanceBackgroundColorProvider(dark, dark)
                    else -> GlanceBackgroundColorProvider(light, dark)
                }
            }
            val tintedBg = accentProvider(android.R.color.system_accent2_0, android.R.color.system_accent2_800)
            val tintedButtonBg = accentProvider(android.R.color.system_accent2_100, android.R.color.system_accent2_900)
            WidgetColors(
                background               = if (tintedBackground) tintedBg else GlanceTheme.colors.surface,
                onBackground             = GlanceTheme.colors.onSurface,
                onBackgroundVariant      = GlanceTheme.colors.onSurfaceVariant,
                buttonBackground         = if (tintedBackground) tintedButtonBg else GlanceTheme.colors.surfaceVariant,
                buttonDisabledBackground = if (tintedBackground) tintedButtonBg else GlanceTheme.colors.surfaceVariant,
                buttonOnBackground       = GlanceTheme.colors.onSurfaceVariant,
                headerIcon               = GlanceTheme.colors.primary,
                theme                    = theme
            )
        }
        content(widgetColors)
    }
}

data class LayoutInfo(
    val scaleX: Float,
    val scaleY: Float,
    val width: Dp,
    val buttonHeightDp: Float,
    val rowVerticalPadding: Dp,
    val contentPadding: Dp,
    val isSparse: Boolean,
    val isCompact: Boolean,
    val updateCount: Int,
    val widgetColors: WidgetColors
)

class WidgetCardScope internal constructor() {
    internal val rows = mutableListOf<@Composable (info: LayoutInfo) -> Unit>()
    internal var requiredHeight = 0.dp

    fun customRow(content: @Composable () -> Unit) {
        rows += { _ -> content() }
    }

    fun chipsRow(
        chips: List<ChipSpec>,
        onClick: (ChipSpec) -> Action
    ) {
        requiredHeight += 70.dp
        rows += { info ->
            val safeChips = chips.take(6)
            val n = safeChips.size.coerceAtLeast(1)
            val anySelected = safeChips.any { it.isSelected }

            // Mirror the card's emphasis weights (selected 1.3, others 0.8). Glance has no
            // float weights, so derive explicit Dp widths from the widget width. There is no
            // animation API in app widgets — this renders the card's settled end-state.
            val spacing = (3 * info.scaleX).dp
            val outerPad = info.contentPadding
            val avail = (info.width - outerPad * 2 - spacing * (n - 1)).coerceAtLeast(0.dp)
            val selW = 1.3f
            val othW = 0.8f
            val totalW = if (anySelected) selW + othW * (n - 1) else n.toFloat()
            val selWidth = if (anySelected) avail * (selW / totalW) else avail / n
            val othWidth = if (anySelected) avail * (othW / totalW) else avail / n
            val baseRowHeight = info.buttonHeightDp.dp
            val averageChipWidth = if (n > 0) avail / n else avail
            val shapedRowHeight = if (info.isSparse) {
                minOf(baseRowHeight, averageChipWidth * 0.86f)
            } else {
                baseRowHeight
            }
            val rowHeight = maxOf(shapedRowHeight, minOf(36.dp, baseRowHeight))
            val iconSize = rowHeight * 0.5f
            val fontSize = (rowHeight.value * 0.35f).sp

            // Show the selected label (icon + text) only when every option could fit its text
            // at the selected width — same rule as the card's allOptionsSupportText.
            val allSupportText = anySelected && avail > 0.dp && safeChips.all { c ->
                if (c.icon == null) true
                else {
                    val estText = (c.label.length * fontSize.value * 0.62f).dp
                    selWidth >= iconSize + 6.dp + estText + 16.dp
                }
            }

            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                safeChips.forEachIndexed { idx, c ->
                    val chipWidth = if (c.isSelected) selWidth else othWidth
                    val chipColor = info.widgetColors.resolvePickerColor(c.color, c.nightColor) ?: info.widgetColors.buttonBackground
                    val content = if (c.isSelected) info.widgetColors.onSelected else info.widgetColors.onBackground

                    // The gap is end-padding on a transparent wrapper, NOT a separate Spacer
                    // child. A Glance Row maps to RemoteViews, which caps a container at 10 direct
                    // children; N chips + (N-1) spacers reached 11 at 6 options (off/cool/heat/
                    // auto/fan_only/dry, or the 6 alarm modes) and Glance truncated the row. One
                    // wrapper per chip keeps the Row at exactly N children.
                    val endPad = if (idx != safeChips.lastIndex) spacing else 0.dp
                    Box(modifier = GlanceModifier.padding(end = endPad)) {
                    Box(
                        modifier = GlanceModifier
                            .width(chipWidth)
                            .height(rowHeight)
                            .background(chipColor)
                            .cornerRadius(if (c.isSelected) (rowHeight / 2) else (rowHeight / 4))
                            .clickable(onClick = onClick(c)),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            c.icon != null && c.isSelected && allSupportText -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = GlanceModifier.padding(horizontal = 4.dp)
                                ) {
                                    Image(
                                        provider = c.icon,
                                        contentDescription = c.label,
                                        modifier = GlanceModifier.size(iconSize),
                                        colorFilter = ColorFilter.tint(content)
                                    )
                                    Spacer(GlanceModifier.width(6.dp))
                                    Text(
                                        text = c.label,
                                        maxLines = 1,
                                        style = TextStyle(
                                            color = content,
                                            fontSize = fontSize,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = GoogleSansFlex
                                        )
                                    )
                                }
                            }
                            c.icon != null -> {
                                Image(
                                    provider = c.icon,
                                    contentDescription = c.label,
                                    modifier = GlanceModifier.size(iconSize),
                                    colorFilter = ColorFilter.tint(content)
                                )
                            }
                            else -> {
                                Text(
                                    text = c.label,
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = content,
                                        fontSize = fontSize,
                                        fontWeight = if (c.isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontFamily = GoogleSansFlex
                                    )
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    fun counterRow(
        value: Int,
        min: Int = Int.MIN_VALUE,
        max: Int = Int.MAX_VALUE,
        decIcon: Any? = null,
        incIcon: Any? = null,
        onDec: Action,
        onInc: Action,
        label: String = ""
    ) {
        requiredHeight += 70.dp
        rows += { info ->
            val btnHeight = info.buttonHeightDp.dp
            val iconSize = (info.buttonHeightDp * 0.45f).dp
            val fontSize = (info.buttonHeightDp * 0.35f).sp

            val canDec = value > min
            val canInc = value < max

            val ctx = androidx.glance.LocalContext.current
            val dIcon = resolvePickerIcon(ctx, decIcon ?: "remove") ?: ImageProvider(com.feldman.ha.R.drawable.ic_minus)
            val iIcon = resolvePickerIcon(ctx, incIcon ?: "add") ?: ImageProvider(com.feldman.ha.R.drawable.ic_plus)

            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Minus button
                val decModifier = GlanceModifier
                    .defaultWeight()
                    .height(btnHeight)
                    .background(if (canDec) info.widgetColors.buttonBackground else info.widgetColors.buttonDisabledBackground)
                    .cornerRadius(btnHeight / 2)

                Box(
                    modifier = if (canDec) decModifier.clickable(onDec) else decModifier,
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = dIcon,
                        contentDescription = "Decrease",
                        modifier = GlanceModifier.size(iconSize),
                        colorFilter = ColorFilter.tint(if (canDec) info.widgetColors.onBackground else info.widgetColors.onBackgroundVariant)
                    )
                }

                // Value Display
                Box(
                    modifier = GlanceModifier.defaultWeight().height(btnHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = value.toString(),
                            style = TextStyle(
                                color = info.widgetColors.onBackground,
                                fontSize = (fontSize.value * 1.2f).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GoogleSansFlex
                            )
                        )
                        if (label.isNotEmpty()) {
                            Text(
                                text = label,
                                style = TextStyle(
                                    color = info.widgetColors.onBackgroundVariant,
                                    fontSize = (fontSize.value * 0.7f).sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = GoogleSansFlex
                                )
                            )
                        }
                    }
                }

                // Plus button
                val incModifier = GlanceModifier
                    .defaultWeight()
                    .height(btnHeight)
                    .background(if (canInc) info.widgetColors.buttonBackground else info.widgetColors.buttonDisabledBackground)
                    .cornerRadius(btnHeight / 2)

                Box(
                    modifier = if (canInc) incModifier.clickable(onInc) else incModifier,
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = iIcon,
                        contentDescription = "Increase",
                        modifier = GlanceModifier.size(iconSize),
                        colorFilter = ColorFilter.tint(if (canInc) info.widgetColors.onBackground else info.widgetColors.onBackgroundVariant)
                    )
                }
            }
        }
    }

    /** Custom-feature "value" row: label on the left, computed value on the right. */
    fun valueRow(label: String, icon: String?, value: String) {
        requiredHeight += 50.dp
        rows += { info ->
            val h = info.buttonHeightDp.dp
            val ctx = androidx.glance.LocalContext.current
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(h)
                        .background(info.widgetColors.buttonBackground)
                        .cornerRadius(h / 3)
                        .padding(horizontal = (14 * info.scaleX).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val resolved = resolvePickerIcon(ctx, icon)
                    if (resolved != null) {
                        Image(
                            provider = resolved,
                            contentDescription = label,
                            modifier = GlanceModifier.size((info.buttonHeightDp * 0.42f).dp),
                            colorFilter = ColorFilter.tint(info.widgetColors.headerIcon)
                        )
                        Spacer(GlanceModifier.width((10 * info.scaleX).dp))
                    }
                    Text(
                        text = label,
                        maxLines = 1,
                        style = TextStyle(
                            color = info.widgetColors.onBackground,
                            fontSize = (info.buttonHeightDp * 0.30f).sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = GoogleSansFlex
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = value,
                        maxLines = 1,
                        style = TextStyle(
                            color = info.widgetColors.onBackground,
                            fontSize = (info.buttonHeightDp * 0.32f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GoogleSansFlex
                        )
                    )
                }
            }
        }
    }

    fun dataRow(value: String, unit: String) {
        requiredHeight += 40.dp
        rows += { info ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = value,
                    style = TextStyle(
                        color = info.widgetColors.onBackground,
                        fontSize = (24 * info.scaleY).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GoogleSansFlex
                    )
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    text = unit,
                    style = TextStyle(
                        color = info.widgetColors.onBackgroundVariant,
                        fontSize = (14 * info.scaleY).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GoogleSansFlex
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }

    /** Custom-feature toggle/service button: icon + label (like the in-app card button). */
    fun customButtonRow(label: String, icon: String?, action: Action) {
        requiredHeight += 60.dp
        rows += { info ->
            val h = (info.buttonHeightDp * 0.95f).dp
            val ctx = androidx.glance.LocalContext.current
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(h)
                        .background(info.widgetColors.buttonBackground)
                        .cornerRadius(h / 2)
                        .clickable(action),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val resolved = resolvePickerIcon(ctx, icon)
                        if (resolved != null) {
                            Image(
                                provider = resolved,
                                contentDescription = label,
                                modifier = GlanceModifier.size((info.buttonHeightDp * 0.42f).dp),
                                colorFilter = ColorFilter.tint(info.widgetColors.onBackground)
                            )
                            Spacer(GlanceModifier.width((8 * info.scaleX).dp))
                        }
                        Text(
                            text = label,
                            maxLines = 1,
                            style = TextStyle(
                                color = info.widgetColors.onBackground,
                                fontSize = (info.buttonHeightDp * 0.30f).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GoogleSansFlex
                            )
                        )
                    }
                }
            }
        }
    }

    /** Custom-feature switch: big pill switch (static representation for Glance). */
    fun switchRow(checked: Boolean, action: Action) {
        requiredHeight += 60.dp
        rows += { info ->
            val h = (info.buttonHeightDp * 1.0f).dp
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(h)
                        .background(info.widgetColors.buttonBackground)
                        .cornerRadius(h / 2)
                        .clickable(action)
                        .padding(4.dp)
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (checked) Spacer(GlanceModifier.defaultWeight())
                        
                        Box(
                            modifier = GlanceModifier
                                .fillMaxHeight()
                                .defaultWeight()
                                .cornerRadius((h.value / 2 - 4).dp)
                                .background(if (checked) info.widgetColors.headerIcon else info.widgetColors.buttonDisabledBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            val iconName = if (checked) "remove" else "radio_button_unchecked"
                            val resolved = resolvePickerIcon(androidx.glance.LocalContext.current, iconName)
                            if (resolved != null) {
                                Image(
                                    provider = resolved,
                                    contentDescription = if (checked) "On" else "Off",
                                    modifier = GlanceModifier.size((info.buttonHeightDp * 0.42f).dp),
                                    colorFilter = ColorFilter.tint(if (checked) info.widgetColors.onSelected else info.widgetColors.onBackground)
                                )
                            }
                        }
                        
                        if (!checked) Spacer(GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }

    fun buttonsRow(buttons: List<WidgetButton>) {
        requiredHeight += 60.dp
        rows += { info ->
            val buttonCount = buttons.size.coerceAtLeast(1)
            val spacing = (4 * info.scaleX).dp
            val availableWidth = (info.width - info.contentPadding * 2 - spacing * (buttonCount - 1)).coerceAtLeast(0.dp)
            val perButtonWidth = availableWidth / buttonCount
            val baseBtnHeight = (info.buttonHeightDp * 0.9f).dp
            val shapedBtnHeight = if (info.isSparse) {
                minOf(baseBtnHeight, perButtonWidth * 0.82f)
            } else {
                baseBtnHeight
            }
            val btnHeight = maxOf(shapedBtnHeight, minOf(40.dp, baseBtnHeight))
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                buttons.forEachIndexed { idx, btn ->
                    val endPad = if (idx != buttons.lastIndex) spacing else 0.dp
                    Box(modifier = GlanceModifier.defaultWeight().padding(end = endPad)) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(btnHeight)
                            .background(info.widgetColors.buttonBackground)
                            .cornerRadius(btnHeight / 2)
                            .clickable(btn.onClick),
                        contentAlignment = Alignment.Center
                    ) {
                        val ctx = androidx.glance.LocalContext.current
                        val icon = resolvePickerIcon(ctx, btn.icon)
                        if (icon != null) {
                            Image(
                                provider = icon,
                                contentDescription = btn.label,
                                modifier = GlanceModifier.size(btnHeight * 0.5f),
                                colorFilter = ColorFilter.tint(info.widgetColors.onBackground)
                            )
                        } else {
                            Text(
                                text = btn.label,
                                style = TextStyle(
                                    color = info.widgetColors.onBackground,
                                    fontSize = (14 * info.scaleY).sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = GoogleSansFlex
                                )
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    fun pickerRow(
        options: List<PickerOptionSpec>,
        selectedId: String?,
        onClick: (PickerOptionSpec) -> Action
    ) {
        requiredHeight += 70.dp
        rows += { info ->
            val optionCount = options.size.coerceAtLeast(1)
            val spacing = (4 * info.scaleX).dp
            val availableWidth = (info.width - info.contentPadding * 2 - spacing * (optionCount - 1)).coerceAtLeast(0.dp)
            val perOptionWidth = availableWidth / optionCount
            val baseRowHeight = info.buttonHeightDp.dp
            val shapedRowHeight = if (info.isSparse) {
                minOf(baseRowHeight, perOptionWidth * 0.86f)
            } else {
                baseRowHeight
            }
            val rowHeight = maxOf(shapedRowHeight, minOf(36.dp, baseRowHeight))
            val iconSize = rowHeight * 0.5f

            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = info.rowVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { idx, option ->
                    val isSelected = option.id == selectedId
                    val paddingEnd = if (idx != options.lastIndex) spacing else 0.dp

                    val selectedBgColor = info.widgetColors.resolvePickerColor(option.color, option.nightColor) ?: info.widgetColors.headerIcon
                    val bgColor = if (isSelected) selectedBgColor else info.widgetColors.buttonBackground

                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .padding(end = paddingEnd)
                            .height(rowHeight)
                            .background(bgColor)
                            .cornerRadius(rowHeight / 2)
                            .clickable(onClick(option)),
                        contentAlignment = Alignment.Center
                    ) {
                        val ctx = androidx.glance.LocalContext.current
                        val icon = resolvePickerIcon(ctx, option.icon)
                        if (icon != null) {
                            Image(
                                provider = icon,
                                contentDescription = option.label,
                                modifier = GlanceModifier.size(iconSize),
                                colorFilter = ColorFilter.tint(if (isSelected) info.widgetColors.onSelected else info.widgetColors.onBackground)
                            )
                        } else {
                            Text(
                                text = option.label,
                                style = TextStyle(
                                    color = if (isSelected) info.widgetColors.onSelected else info.widgetColors.onBackground,
                                    fontSize = (info.buttonHeightDp * 0.32f).sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = GoogleSansFlex
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun errorRow(msg: String) {
        requiredHeight += 24.dp
        rows += { info ->
            Text(
                text = msg,
                style = TextStyle(
                    color = ColorProvider(Color(0xFFFF5252), Color(0xFFFF5252)),
                    fontSize = (12 * info.scaleY).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GoogleSansFlex
                ),
                modifier = GlanceModifier.padding(top = (4 * info.scaleY).dp).fillMaxWidth()
            )
        }
    }

    fun spacerRow(height: Int) {
        requiredHeight += height.dp
        rows += { _ ->
            Spacer(GlanceModifier.height(height.dp))
        }
    }
}

data class WidgetButton(val label: String, val icon: String? = null, val onClick: Action)

data class ChipSpec(
    val id: String,
    val color: Long? = null,
    val nightColor: Long? = null,
    val label: String = id,
    val icon: ImageProvider? = null,
    val isSelected: Boolean = false
)

private val motionSymbolCache = mutableMapOf<String, Bitmap>()

fun resolvePickerIcon(context: Context, icon: Any?): ImageProvider? =
    when (icon) {
        is Int -> ImageProvider(icon)
        is String -> resolveDrawableIcon(context, icon)?.let { ImageProvider(it) }
            ?: ImageProvider(renderMotionSymbol(context, icon))
        else -> null
    }

fun resolveDrawableIcon(context: Context, raw: String): Int? {
    val name = when {
        raw.startsWith("R.drawable.") -> raw.removePrefix("R.drawable.")
        raw.startsWith("@drawable/") -> raw.removePrefix("@drawable/")
        raw.startsWith("drawable/") -> raw.removePrefix("drawable/")
        else -> "ic_$raw"
    }
    var id = context.resources.getIdentifier(name, "drawable", context.packageName)
    if (id == 0 && !raw.startsWith("R.drawable.") && !raw.startsWith("@drawable/") && !raw.startsWith("drawable/")) {
        id = context.resources.getIdentifier(raw, "drawable", context.packageName)
    }
    return id.takeIf { it != 0 }
}

private fun renderMotionSymbol(context: Context, name: String): Bitmap {
    val density = context.resources.displayMetrics.density
    val sizePx = (28f * density).toInt().coerceAtLeast(28)
    val key = "$name:$sizePx"

    synchronized(motionSymbolCache) {
        motionSymbolCache[key]?.let { return it }
    }

    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sizePx * 0.78f
        typeface = ResourcesCompat.getFont(
            context,
            com.feldman.motion.R.font.material_symbols_rounded
        ) ?: Typeface.DEFAULT
        fontVariationSettings = "'FILL' 1, 'GRAD' 0, 'opsz' 24, 'wght' 500"
    }
    val centerY = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(name, sizePx / 2f, centerY, paint)

    synchronized(motionSymbolCache) {
        motionSymbolCache[key] = bitmap
    }
    return bitmap
}

/**
 * The app's last-rendered card color roles, mirrored into ha_prefs by MainActivity.
 * Mapped 1:1 from FactoryCard.appWidgetColors() so widgets match the in-app cards.
 */
private data class AppSchemeColors(
    val background: Int,          // surfaceContainer
    val onBackground: Int,        // onSurface
    val onBackgroundVariant: Int, // onSurfaceVariant
    val button: Int,              // surfaceVariant
    val buttonOn: Int,            // onSurfaceVariant
    val headerIcon: Int,          // primary
)

/** Global "keep original size" preference — when true, widgets never scale above 1.0. */
private fun widgetNoEnlarge(context: Context): Boolean =
    context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE).getBoolean("widget_no_enlarge", false)

private fun readAppSchemeColors(context: Context): AppSchemeColors? {
    val p = context.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)
    if (!p.getBoolean("widget_scheme_present", false)) return null
    return AppSchemeColors(
        background          = p.getInt("widget_scheme_background", 0),
        onBackground        = p.getInt("widget_scheme_on_background", 0),
        onBackgroundVariant = p.getInt("widget_scheme_on_background_variant", 0),
        button              = p.getInt("widget_scheme_button", 0),
        buttonOn            = p.getInt("widget_scheme_button_on", 0),
        headerIcon          = p.getInt("widget_scheme_header_icon", 0),
    )
}

/** Builds WidgetColors from the exact card color ints (single mode → day == night). */
private fun appWidgetColors(c: AppSchemeColors): WidgetColors {
    fun cp(argb: Int) = ColorProvider(Color(argb), Color(argb))
    // Disabled button = surfaceVariant @ 0.52 alpha, matching the card's mapping.
    val disabled = Color(c.button).copy(alpha = 0.52f)
    return WidgetColors(
        background               = cp(c.background),
        onBackground             = cp(c.onBackground),
        onBackgroundVariant      = cp(c.onBackgroundVariant),
        buttonBackground         = cp(c.button),
        buttonDisabledBackground = ColorProvider(disabled, disabled),
        buttonOnBackground       = cp(c.buttonOn),
        headerIcon               = cp(c.headerIcon),
        theme                    = "auto"
    )
}

class WidgetColors(
    val background: GlanceColorProvider,
    val onBackground: GlanceColorProvider,
    val onBackgroundVariant: GlanceColorProvider,
    val buttonBackground: GlanceColorProvider,
    val buttonDisabledBackground: GlanceColorProvider,
    val buttonOnBackground: GlanceColorProvider,
    // Accent (= card's headerIcon / scheme.primary). Used as the selected-picker bg
    // fallback when an option has no custom color.
    val headerIcon: GlanceColorProvider = ColorProvider(Color(0xFF6750A4), Color(0xFFD0BCFF)),
    // Content drawn on a selected (colored) picker — matches the card's Color.White.
    val onSelected: GlanceColorProvider = ColorProvider(Color.White, Color.White),
    val theme: String = "auto"
) {
    companion object {
        operator fun invoke(theme: String): WidgetColors = when (theme) {
            "light" -> WidgetColors(
                background            = GlanceBackgroundColorProvider(Color.White, Color.White),
                onBackground          = ColorProvider(Color.Black, Color.Black),
                onBackgroundVariant   = ColorProvider(Color(0xFF757575), Color(0xFF757575)),
                buttonBackground      = ColorProvider(Color(0xFFe1e1e6), Color(0xFFe1e1e6)),
                buttonDisabledBackground = ColorProvider(Color(0xFFb7b7b7), Color(0xFFb7b7b7)),
                buttonOnBackground    = ColorProvider(Color.Black, Color.Black),
                theme                 = "light"
            )
            "dark" -> WidgetColors(
                background            = GlanceBackgroundColorProvider(Color(0xFF28282A), Color(0xFF28282A)),
                onBackground          = ColorProvider(Color.White, Color.White),
                onBackgroundVariant   = ColorProvider(Color(0xFFBDBDBD), Color(0xFFBDBDBD)),
                buttonBackground      = ColorProvider(Color(0xFF202223), Color(0xFF202223)),
                buttonDisabledBackground = ColorProvider(Color(0xFF161617), Color(0xFF161617)),
                buttonOnBackground    = ColorProvider(Color.White, Color.White),
                theme                 = "dark"
            )
            else -> WidgetColors(  // fallback used when not inside a GlanceTheme
                background            = GlanceBackgroundColorProvider(Color.White, Color(0xFF28282A)),
                onBackground          = ColorProvider(Color.Black, Color.White),
                onBackgroundVariant   = ColorProvider(Color(0xFF757575), Color(0xFFBDBDBD)),
                buttonBackground      = ColorProvider(Color(0xFFe1e1e6), Color(0xFF202223)),
                buttonDisabledBackground = ColorProvider(Color(0xFFb7b7b7), Color(0xFF161617)),
                buttonOnBackground    = ColorProvider(Color.Black, Color.White),
                theme                 = "auto"
            )
        }
    }

    fun resolvePickerColor(day: Long?, night: Long?): GlanceColorProvider? {
        if (day == null && night == null) return null
        val d = day ?: night!!
        val n = night ?: day!!
        return when (theme) {
            "light" -> ColorProvider(Color(d), Color(d))
            "dark"  -> ColorProvider(Color(n), Color(n))
            else    -> ColorProvider(day = Color(d), night = Color(n))
        }
    }
}
