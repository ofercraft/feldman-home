package com.feldman.ha.ui.cards

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Slider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.feldman.ha.R
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.data.HAEntity
import com.feldman.ha.ui.navigation.LocalAppState
import com.feldman.ha.widgets.CardStyle
import com.feldman.ha.widgets.PickerOptionSpec
import com.feldman.ha.widgets.PickerRowSpec
import com.feldman.ha.widgets.SheetAccent
import com.feldman.ha.widgets.SheetBlockSpec
import com.feldman.ha.widgets.SheetButtonSpec
import com.feldman.ha.widgets.SheetButtonsSpec
import com.feldman.ha.widgets.SheetCardRowsSpec
import com.feldman.ha.widgets.SheetDataSpec
import com.feldman.ha.widgets.SheetDropdownSpec
import com.feldman.ha.widgets.SheetHeroKind
import com.feldman.ha.widgets.SheetHeroSpec
import com.feldman.ha.widgets.SheetSpec
import com.feldman.ha.widgets.WidgetSpec
import com.feldman.ha.widgets.mergePickerOptions
import com.feldman.ha.widgets.pickerOptionMatches
import com.feldman.motion.DropdownSettingsItem
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import com.feldman.motion.MotionLevel
import com.feldman.motion.SymbolAxes
import com.feldman.motion.ThemeRepository
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalTextApi::class)
private val thermostatTemperatureFont = FontFamily(
    Font(
        resId = R.font.google_sans_flex,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(650),
            FontVariation.Setting("ROND", 200f)
        )
    )
)

private const val THERMOSTAT_START_ANGLE = 135f
private const val THERMOSTAT_SWEEP_ANGLE = 270f
private const val THERMOSTAT_BOTTOM_GAP_CENTER_ANGLE = 90.0
private const val CLIMATE_TEMPERATURE_SEND_DELAY_MS = 450L

@Composable
fun FactoryEntityStateSheet(
    entity: HAEntity,
    spec: WidgetSpec,
    api: HomeAssistantApi,
    names: Map<String, String>,
    configs: Map<String, Map<String, Any>>,
    allEntities: List<HAEntity>,
    // The tapped card's coordinates + the tap position inside it. Translated into this
    // overlay's space with localPositionOf, which survives any host transform (the Clock
    // screensaver visually rotates the whole UI, where raw root-offset deltas break).
    originCoords: LayoutCoordinates?,
    originLocal: Offset,
    slideProgress: Float,
    scrimProgress: Float,
    onUpdated: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    var sheetHeightPx by remember { mutableStateOf(0f) }
    val fallbackSheetHeightPx = with(density) { 720.dp.toPx() }
    // Drag-to-dismiss on the sheet handle: tracked as an extra downward offset on top of
    // the dashboard-owned slide animation.
    val dragOffset = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()

    // slideProgress drives the panel (fast); scrimProgress drives the background ripple
    // (slower) — both owned by the dashboard so the two move on independent timelines.
    val hidden = 1f - slideProgress
    val scrim = scrimProgress.coerceIn(0f, 1f)
    val scrimColor = MaterialTheme.colorScheme.scrim
    // This overlay's coordinates, to translate the tap point into local space for the
    // ripple center and the side-panel anchoring.
    var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val tapInOverlay = run {
        val oc = overlayCoords
        val sc = originCoords
        if (oc != null && oc.isAttached && sc != null && sc.isAttached) {
            runCatching { oc.localPositionOf(sc, originLocal) }.getOrDefault(Offset.Unspecified)
        } else {
            Offset.Unspecified
        }
    }

    BackHandler(onBack = onDismiss)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(40f)
            .onGloballyPositioned { overlayCoords = it }
            .drawBehind {
                if (scrim <= 0f) return@drawBehind
                val center = if (tapInOverlay.isSpecified) {
                    tapInOverlay
                } else {
                    Offset(size.width / 2f, size.height / 2f)
                }
                // Overshoot the radius so the feathered rim (beyond SCRIM_SOLID_FRACTION)
                // ends up off-screen at full progress: the solid core then covers the whole
                // screen and the gradient edge is no longer visible once fully open.
                val radius = maxDistanceToCorner(center, size) / SCRIM_SOLID_FRACTION * scrim
                if (radius <= 0f) return@drawBehind
                val maxAlpha = 0.55f * scrim
                // Soft-edged radial gradient: solid through the core, feathering out to
                // transparent at the rim, so the darkening sweeps in instead of snapping.
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to scrimColor.copy(alpha = maxAlpha),
                            SCRIM_SOLID_FRACTION to scrimColor.copy(alpha = maxAlpha),
                            1f to Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
        ),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Portrait: the classic full-width bottom sheet, capped so a scrim strip always
        // stays visible and tappable above it (the Clock screensaver has no back gesture).
        // Landscape/tablet: a side panel instead — at most 440dp / half the screen wide,
        // allowed to reach almost the top, anchored to the screen side that was tapped.
        val isWide = this@BoxWithConstraints.maxWidth > this@BoxWithConstraints.maxHeight
        // This overlay is not inside the scaffold's inset padding, so keep the panel out of
        // the system bars itself: below the status bar on top, off the side cutout/gesture
        // areas horizontally (the content already handles the bottom bar).
        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        val sheetMaxHeight = if (isWide) {
            this@BoxWithConstraints.maxHeight - safeInsets.calculateTopPadding() - 24.dp
        } else {
            minOf(720.dp, this@BoxWithConstraints.maxHeight - safeInsets.calculateTopPadding() - 72.dp)
        }
        // On wide-enough landscape screens the panel grows to ~2/3 of the width so sheet
        // content (e.g. climate) can lay out in two columns; narrow landscape keeps the
        // compact panel.
        val twoColumnSheet = isWide && this@BoxWithConstraints.maxWidth >= 840.dp
        val sheetWidth = if (twoColumnSheet) {
            minOf(this@BoxWithConstraints.maxWidth * 2 / 3, 760.dp)
        } else {
            minOf(440.dp, this@BoxWithConstraints.maxWidth / 2)
        }
        // Wide layouts anchor the panel to the start side — away from the standby's exit
        // (X) control, which lives at the top end.
        val panelAlignment = if (isWide) Alignment.BottomStart else Alignment.BottomCenter
        Surface(
            modifier = Modifier
                .align(panelAlignment)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (isWide) Modifier.width(sheetWidth) else Modifier.fillMaxWidth())
                .heightIn(max = sheetMaxHeight)
                .onGloballyPositioned { sheetHeightPx = it.size.height.toFloat() }
                .offset {
                    val travel = if (sheetHeightPx > 0f) sheetHeightPx else fallbackSheetHeightPx
                    IntOffset(0, (travel * hidden + dragOffset.value).roundToInt())
                }
                .graphicsLayer {
                    alpha = (1f - 0.08f * hidden).coerceIn(0f, 1f)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            // The sheet sits on its own solid surface: card-surface overrides (the Clock
            // standby's faint gray/outline card look) must not restyle the rows in here.
            CompositionLocalProvider(LocalCardSurfaceOverride provides null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // The drag handle really drags: pull the sheet down past a third of its
                // height (or fling it) to dismiss, otherwise it springs back.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    dragScope.launch {
                                        dragOffset.snapTo((dragOffset.value + dy).coerceAtLeast(0f))
                                    }
                                },
                                onDragEnd = {
                                    val travel = if (sheetHeightPx > 0f) sheetHeightPx else fallbackSheetHeightPx
                                    if (dragOffset.value > travel * 0.3f) {
                                        onDismiss()
                                        dragScope.launch { dragOffset.animateTo(travel, tween(220)) }
                                    } else {
                                        dragScope.launch {
                                            dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dragScope.launch { dragOffset.animateTo(0f) }
                                }
                            )
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                    )
                }

                val title = names[entity.entity_id]
                    ?: entity.attributes["friendly_name"]?.toString()
                    ?: entity.entity_id
                EntitySheetHeader(
                    spec = spec,
                    title = title,
                    subtitle = entityRoomLabel(entity, title)
                )

                Spacer(Modifier.height(10.dp))

                val sheetSpec = spec.sheet
                when {
                    sheetSpec != null -> FactorySheetContent(
                        entity = entity,
                        spec = spec,
                        sheet = sheetSpec,
                        twoColumn = twoColumnSheet,
                        api = api,
                        names = names,
                        configs = configs,
                        allEntities = allEntities,
                        onUpdated = onUpdated
                    )
                    else -> GenericEntityStateContent(
                        entity = entity,
                        spec = spec,
                        api = api,
                        names = names,
                        configs = configs,
                        allEntities = allEntities,
                        onUpdated = onUpdated
                    )
                }
            }
            }
        }
    }
}

/** Fraction of the scrim circle that stays fully opaque before feathering to transparent.
 *  The radius is scaled by 1/this so the solid core reaches every screen corner at full
 *  progress, pushing the gradient rim off-screen so it's invisible once fully open. */
private const val SCRIM_SOLID_FRACTION = 0.7f

/** Largest distance from [center] to any corner of [size] — the distance the solid core
 *  of the scrim must reach to fully cover the screen during the reveal. */
private fun maxDistanceToCorner(center: Offset, size: Size): Float {
    val dx = max(center.x, size.width - center.x)
    val dy = max(center.y, size.height - center.y)
    return sqrt(dx * dx + dy * dy)
}

@Composable
private fun EntitySheetHeader(
    spec: WidgetSpec,
    title: String,
    subtitle: String
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        SheetHeaderIcon(
            spec = spec,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SheetHeaderIcon(
    spec: WidgetSpec,
    modifier: Modifier = Modifier.size(40.dp)
) {
    val iconName = when (spec.domain) {
        "climate" -> "thermostat"
        "light" -> "lightbulb"
        "fan" -> "mode_fan"
        "lock" -> "lock"
        "alarm_control_panel" -> "shield"
        "cover" -> "blinds"
        else -> "info"
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = rememberSymbolPainter(iconName),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(23.dp)
        )
    }
}

@Composable
private fun FactorySheetContent(
    entity: HAEntity,
    spec: WidgetSpec,
    sheet: SheetSpec,
    twoColumn: Boolean,
    api: HomeAssistantApi,
    names: Map<String, String>,
    configs: Map<String, Map<String, Any>>,
    allEntities: List<HAEntity>,
    onUpdated: () -> Unit
) {
    val appState = LocalAppState.current
    val scheme = MaterialTheme.colorScheme

    // Resolves a ServiceMap call, substituting {entity}/{value} (typed) into its params.
    fun fireAction(actionKey: String, value: Any?, optimistic: (HAEntity) -> HAEntity) {
        val call = spec.actions.calls[actionKey] ?: return
        val params = call.params.mapValues { (_, template) ->
            when (template) {
                "{entity}" -> entity.entity_id
                "{value}" -> value ?: ""
                else -> template
            }
        }
        appState.callServiceOptimistically(entity.entity_id, call.domain, call.service, params, optimistic)
        onUpdated()
    }

    // Dropdown blocks with no available options (attribute missing on this entity) are skipped.
    val dropdownItems = sheet.blocks.filterIsInstance<SheetDropdownSpec>().mapNotNull { block ->
        val row = spec.rows.filterIsInstance<PickerRowSpec>()
            .firstOrNull { it.id == (block.optionsFromRow ?: block.id) }
        val options = if (block.optionsAttr != null) {
            climateOptions(row, entity, block.optionsAttr)
        } else {
            // No entity attribute list: the picker row's options are the full set
            // (e.g. the fan's Off/Low/Med/High speed steps).
            row?.options.orEmpty()
        }
        if (options.isEmpty()) return@mapNotNull null
        val rawSelected = block.valueAttr?.let { entity.attributes[it] } ?: entity.state
        // Numeric attribute values ("33.0") normalize to the option-id form ("33").
        val selectedId = (rawSelected as? Number)?.toDouble()?.let {
            if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
        } ?: rawSelected?.toString()
        val accent = when (block.accent) {
            SheetAccent.CLIMATE_MODE -> null // section defaults to the hvac-mode palette
            SheetAccent.CLIMATE_FAN -> fanModeColor(selectedId)
            SheetAccent.CLIMATE_PRESET -> presetModeColor(selectedId)
            SheetAccent.CLIMATE_SWING -> swingModeColor(selectedId)
            SheetAccent.PRIMARY -> scheme.primary
        }
        ClimateDropdownItem(
            rowId = block.optionsFromRow ?: block.id,
            label = block.label,
            selectedId = selectedId,
            options = options,
            accentColor = accent,
            onPick = { picked ->
                // Numeric option ids (fan speed percentages) are sent as numbers.
                val sent: Any = picked.toDoubleOrNull()
                    ?.let { if (it % 1.0 == 0.0) it.toInt() else it }
                    ?: picked
                fireAction(block.actionKey, sent) { ent ->
                    if (block.valueAttr == null) {
                        ent.copy(state = picked)
                    } else {
                        ent.copy(attributes = ent.attributes.toMutableMap().apply { put(block.valueAttr, sent) })
                    }
                }
            }
        )
    }

    val heroBlocks = sheet.blocks.filterIsInstance<SheetHeroSpec>()
        .filter { evaluateSupported(it.supportedIf, entity) }
    val dataBlocks = sheet.blocks.filterIsInstance<SheetDataSpec>()
    val buttonsBlocks = sheet.blocks.filterIsInstance<SheetButtonsSpec>()
    val hasCardRows = sheet.blocks.any { it is SheetCardRowsSpec }

    val renderData: @Composable (SheetDataSpec) -> Unit = { block ->
        val unit = block.unitAttr?.let { entity.attributes[it]?.toString() } ?: block.unitDefault
        // The pseudo-attribute "state" reads the entity state itself, prettified.
        val raw: Any? = if (block.attribute == "state") {
            entity.state.replace("_", " ").replaceFirstChar { it.uppercase() }
        } else {
            entity.attributes[block.attribute]
        }
        val valueText = raw.asDoubleOrNull()?.let { "${formatTemperature(it)} $unit".trim() }
            ?: raw?.toString()?.takeIf { it.isNotBlank() }
            ?: "Unavailable"
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = block.label,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }

    val renderHero: @Composable (SheetHeroSpec) -> Unit = { block ->
        SheetHero(entity = entity, block = block, onCommit = { rounded, min, max ->
            val sent: Any = if (block.sendAsPercentOfRange) {
                (((rounded - min) / (max - min).takeIf { it > 0.0 }!!) * 100).roundToInt()
            } else {
                rounded.toServiceNumber()
            }
            fireAction(block.actionKey, sent) { ent ->
                val attrs = ent.attributes.toMutableMap()
                attrs[block.valueAttr] = rounded.toServiceNumber()
                ent.copy(attributes = attrs)
            }
        })
    }

    val renderButtons: @Composable (SheetButtonsSpec) -> Unit = { block ->
        val visible = block.buttons.filter { evaluateSupported(it.supportedIf, entity) }
        if (visible.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                visible.forEach { button ->
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { fireAction(button.actionKey, null) { it } },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(rememberSymbolPainter(button.icon), null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(button.label, maxLines = 1)
                    }
                }
            }
        }
    }

    val renderCardRows: @Composable () -> Unit = {
        val scope = rememberCoroutineScope()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CARD_CORNER_RADIUS),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            FactoryCard(
                entity = entity,
                spec = spec,
                api = api,
                scope = scope,
                names = names,
                configs = configs,
                modifier = Modifier.fillMaxWidth(),
                allEntities = allEntities,
                onUpdated = onUpdated,
                isEdit = false
            )
        }
    }

    if (twoColumn && heroBlocks.isNotEmpty()) {
        // Wide sheet: hero controls on the left, data + dropdowns (+ rows) on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    heroBlocks.forEach { renderHero(it) }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                dataBlocks.forEach { renderData(it) }
                if (dropdownItems.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    ClimateDropdownGrid(dropdownItems, columns = 1)
                }
                buttonsBlocks.forEach {
                    Spacer(Modifier.height(16.dp))
                    renderButtons(it)
                }
                if (hasCardRows) {
                    Spacer(Modifier.height(16.dp))
                    renderCardRows()
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            dataBlocks.forEach { renderData(it) }
            if (heroBlocks.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                heroBlocks.forEach { renderHero(it) }
            }
            if (dropdownItems.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                ClimateDropdownGrid(dropdownItems)
            }
            buttonsBlocks.forEach {
                Spacer(Modifier.height(18.dp))
                renderButtons(it)
            }
            if (hasCardRows) {
                Spacer(Modifier.height(18.dp))
                renderCardRows()
            }
        }
    }
}

/** Renders a hero block: reads range/step/unit from the entity, keeps a local (debounced)
 * target value, and reports commits back with the resolved range for value scaling. */
@Composable
private fun SheetHero(
    entity: HAEntity,
    block: SheetHeroSpec,
    onCommit: (rounded: Double, min: Double, max: Double) -> Unit
) {
    val min = block.minAttr?.let { entity.attributes[it].asDoubleOrNull() } ?: block.minDefault
    val max = (block.maxAttr?.let { entity.attributes[it].asDoubleOrNull() } ?: block.maxDefault)
        .let { if (it > min) it else min + 1.0 }
    val step = (block.stepAttr?.let { entity.attributes[it].asDoubleOrNull() } ?: block.stepDefault)
        .takeIf { it > 0.0 } ?: 1.0
    val unit = block.unitAttr?.let { entity.attributes[it]?.toString() } ?: block.unitDefault

    fun initialValue(): Double {
        val raw = entity.attributes[block.valueAttr].asDoubleOrNull()
            ?: block.fallbackAttrs.firstNotNullOfOrNull { entity.attributes[it].asDoubleOrNull() }
            ?: min
        return raw.coerceIn(min, max)
    }

    var target by remember(entity.entity_id) { mutableStateOf(initialValue()) }
    LaunchedEffect(entity.entity_id, entity.attributes[block.valueAttr]) { target = initialValue() }

    val scope = rememberCoroutineScope()
    var pendingSend by remember(entity.entity_id) { mutableStateOf<Job?>(null) }
    fun setValue(next: Double, debounce: Boolean) {
        val rounded = roundToClimateStep(next, min, max, step)
        target = rounded
        pendingSend?.cancel()
        pendingSend = scope.launch {
            if (debounce) delay(CLIMATE_TEMPERATURE_SEND_DELAY_MS)
            onCommit(rounded, min, max)
        }
    }

    when (block.kind) {
        SheetHeroKind.DIAL -> ThermostatDial(
            targetTemperature = target,
            currentTemperature = block.currentAttr?.let { entity.attributes[it].asDoubleOrNull() },
            minTemperature = min,
            maxTemperature = max,
            step = step,
            mode = entity.state,
            unit = unit,
            modifier = Modifier.size(260.dp),
            canDecrease = target > min + 0.001,
            canIncrease = target < max - 0.001,
            onDecrease = { setValue(target - step, debounce = true) },
            onIncrease = { setValue(target + step, debounce = true) },
            onTemperaturePreview = { target = it },
            onTemperatureCommit = { setValue(it, debounce = false) }
        )
        SheetHeroKind.SLIDER -> {
            val scheme = MaterialTheme.colorScheme
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val display = if (block.sendAsPercentOfRange || block.displayAsPercentOfRange) {
                    "${(((target - min) / (max - min)) * 100).roundToInt()}%"
                } else {
                    "${formatTemperature(target)} $unit".trim()
                }
                Text(
                    text = display,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Slider(
                    value = target.toFloat(),
                    onValueChange = { target = it.toDouble() },
                    onValueChangeFinished = { setValue(target, debounce = false) },
                    valueRange = min.toFloat()..max.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private data class ClimateDropdownItem(
    val rowId: String,
    val label: String,
    val selectedId: String?,
    val options: List<PickerOptionSpec>,
    val accentColor: Color?,
    val onPick: (String) -> Unit
)

@Composable
private fun ClimateDropdownGrid(items: List<ClimateDropdownItem>, columns: Int = 2) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { item ->
                    ClimateDropdownSection(
                        rowId = item.rowId,
                        label = item.label,
                        selectedId = item.selectedId,
                        options = item.options,
                        accentColor = item.accentColor,
                        onPick = item.onPick,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size < columns) {
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ThermostatDial(
    targetTemperature: Double,
    currentTemperature: Double?,
    minTemperature: Double,
    maxTemperature: Double,
    step: Double,
    mode: String,
    unit: String,
    modifier: Modifier = Modifier,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onTemperaturePreview: (Double) -> Unit,
    onTemperatureCommit: (Double) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val activeColor = climateModeColor(mode)
    val activeColorDark = activeColor.copy(alpha = 0.55f)
    val temperatureRange = (maxTemperature - minTemperature).takeIf { abs(it) > 0.001 } ?: 1.0
    val progress = ((targetTemperature - minTemperature) / temperatureRange)
        .toFloat()
        .coerceIn(0f, 1f)
    val currentProgress = currentTemperature
        ?.let { ((it.coerceIn(minTemperature, maxTemperature) - minTemperature) / temperatureRange).toFloat() }
        ?.coerceIn(0f, 1f)
    val startAngle = THERMOSTAT_START_ANGLE
    val sweepAngle = THERMOSTAT_SWEEP_ANGLE
    val activeStartProgress = if (mode == "cool") progress else 0f
    val activeEndProgress = if (mode == "cool") 1f else progress

    var dragTemperature by remember(targetTemperature) { mutableStateOf(targetTemperature) }
    val interactionModifier = Modifier.pointerInput(minTemperature, maxTemperature, step) {
        val strokeWidth = 22.dp.toPx()
        val hitSlop = 12.dp.toPx()

        fun previewTemperatureAt(offset: Offset) {
            dragTemperature = dialTemperatureForOffset(
                offset = offset,
                width = size.width.toFloat(),
                height = size.height.toFloat(),
                minTemperature = minTemperature,
                maxTemperature = maxTemperature,
                step = step
            )
            onTemperaturePreview(dragTemperature)
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startedOnDial = isThermostatArcHit(
                offset = down.position,
                width = size.width.toFloat(),
                height = size.height.toFloat(),
                strokeWidth = strokeWidth,
                hitSlop = hitSlop
            )
            if (!startedOnDial) {
                return@awaitEachGesture
            }

            previewTemperatureAt(down.position)
            down.consume()

            val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                previewTemperatureAt(change.position)
                change.consume()
            }
            if (dragStart == null) {
                onTemperatureCommit(dragTemperature)
                return@awaitEachGesture
            }

            drag(dragStart.id) { change ->
                previewTemperatureAt(change.position)
                change.consume()
            }
            onTemperatureCommit(dragTemperature)
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().then(interactionModifier)) {
            val strokeWidth = 22.dp.toPx()
            val diameter = min(size.width, size.height) - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawArc(
                color = scheme.surfaceContainerHighest,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            fun drawProgressArc(from: Float, to: Float, color: Color) {
                val segmentSweep = sweepAngle * (to - from)
                if (segmentSweep > 0.2f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle + (sweepAngle * from),
                        sweepAngle = segmentSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            if (mode == "cool") {
                val split = currentProgress
                when {
                    split == null -> drawProgressArc(activeStartProgress, activeEndProgress, activeColor)
                    split <= activeStartProgress -> drawProgressArc(activeStartProgress, activeEndProgress, activeColorDark)
                    split >= activeEndProgress -> drawProgressArc(activeStartProgress, activeEndProgress, activeColor)
                    else -> {
                        drawProgressArc(split, activeEndProgress, activeColorDark)
                        drawProgressArc(activeStartProgress, split, activeColor)
                    }
                }
            } else {
                val split = currentProgress
                when {
                    split == null -> drawProgressArc(activeStartProgress, activeEndProgress, activeColor)
                    split <= activeStartProgress -> drawProgressArc(activeStartProgress, activeEndProgress, activeColor)
                    split >= activeEndProgress -> drawProgressArc(activeStartProgress, activeEndProgress, activeColorDark)
                    else -> {
                        drawProgressArc(activeStartProgress, split, activeColorDark)
                        drawProgressArc(split, activeEndProgress, activeColor)
                    }
                }
            }

            currentProgress?.let { current ->
                val currentAngleRad = ((startAngle + (sweepAngle * current)) * PI / 180.0).toFloat()
                val currentPoint = Offset(
                    x = center.x + radius * cos(currentAngleRad),
                    y = center.y + radius * sin(currentAngleRad)
                )
                val currentInActiveRange = current >= activeStartProgress && current <= activeEndProgress
                drawCircle(
                    color = if (currentInActiveRange) {
                        activeColor
                    } else {
                        scheme.onSurfaceVariant.copy(alpha = 0.68f)
                    },
                    radius = 4.dp.toPx(),
                    center = currentPoint
                )
            }

            val angleRad = ((startAngle + (sweepAngle * progress)) * PI / 180.0).toFloat()
            val knob = Offset(
                x = center.x + radius * cos(angleRad),
                y = center.y + radius * sin(angleRad)
            )
            val knobOuterRadius = strokeWidth / 2f
            drawCircle(color = activeColor, radius = knobOuterRadius, center = knob)
            drawCircle(color = Color.White, radius = knobOuterRadius - 4.dp.toPx(), center = knob)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = mode.toClimateLabel(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface
            )
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = formatTemperature(targetTemperature),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = thermostatTemperatureFont,
                        fontSize = 58.sp,
                        lineHeight = 60.sp
                    ),
                    color = scheme.onSurface
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundTemperatureButton(
                icon = "remove",
                enabled = canDecrease,
                onClick = onDecrease
            )
            RoundTemperatureButton(
                icon = "add",
                enabled = canIncrease,
                onClick = onIncrease
            )
        }
    }
}

@Composable
private fun RoundTemperatureButton(
    icon: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var isPressed by remember { mutableStateOf(false) }
    val animatedWidth by animateDpAsState(
        targetValue = if (enabled && isPressed) 56.dp else 48.dp,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 200f),
        label = "TemperatureButtonWidth"
    )
    LaunchedEffect(enabled) {
        if (!enabled) isPressed = false
    }
    val defaultState = MotionButtonState(
        backgroundColor = scheme.primary,
        contentColor = scheme.onPrimary,
        cornerRadius = 100f,
        symbolAxes = SymbolAxes(weight = 350, fill = 0f, grad = 0f, opsz = 24f),
        outlineWidth = 0.dp,
        outlineColor = Color.Transparent
    )
    MotionButton(
        modifier = Modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                isPressed = true
                waitForUpOrCancellation()
                isPressed = false
            }
        },
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        height = 48.dp,
        width = animatedWidth,
        iconSize = 24.dp,
        contentPadding = PaddingValues(0.dp),
        defaultState = defaultState,
        defaultPressedState = defaultState.copy(
            symbolAxes = SymbolAxes(weight = 520, fill = 0f, grad = 0f, opsz = 24f)
        )
    )
}

@Composable
private fun ClimateDropdownSection(
    rowId: String,
    label: String,
    selectedId: String?,
    options: List<PickerOptionSpec>,
    accentColor: Color? = null,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeRepository = remember(context) { ThemeRepository(context) }
    val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)
    val effectiveOptions = remember(rowId, options, selectedId) {
        if (selectedId.isNullOrBlank() || options.any { pickerOptionMatches(rowId, it.id, selectedId) }) {
            options
        } else {
            options + PickerOptionSpec(selectedId, selectedId.toClimateLabel(), icon = selectedId.toClimateIcon())
        }
    }
    val duplicatedLabels = remember(effectiveOptions) {
        effectiveOptions
            .groupingBy { it.label }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }
    val labels = remember(effectiveOptions, duplicatedLabels) {
        effectiveOptions.map { it.dropdownLabel(duplicatedLabels) }
    }
    val selectedOption = effectiveOptions.firstOrNull { pickerOptionMatches(rowId, it.id, selectedId) }
    val selectedLabel = selectedOption?.dropdownLabel(duplicatedLabels)
        ?: selectedId?.takeIf { it.isNotBlank() }?.toClimateLabel()
        ?: ""
    val iconPainters = effectiveOptions.map { rememberDialogOptionPainter(it.icon ?: it.id.toClimateIcon()) }
    val leadingIcon = selectedOption?.let { rememberDialogOptionPainter(it.icon ?: it.id.toClimateIcon()) }
    val selectedAccent = accentColor ?: selectedId?.let(::climateModeColor) ?: MaterialTheme.colorScheme.primary
    val dropdownColorScheme = MaterialTheme.colorScheme.copy(
        primaryContainer = selectedAccent.copy(alpha = 0.92f),
        onPrimaryContainer = Color.Black.copy(alpha = 0.78f)
    )

    MaterialTheme(colorScheme = dropdownColorScheme) {
        if (LocalPopupsAllowed.current) {
            DropdownSettingsItem(
                label = label,
                options = labels,
                selected = selectedLabel,
                onSelected = { pickedLabel ->
                    val pickedIndex = labels.indexOf(pickedLabel)
                    val pickedOption = effectiveOptions.getOrNull(pickedIndex) ?: return@DropdownSettingsItem
                    if (!pickerOptionMatches(rowId, pickedOption.id, selectedId)) onPick(pickedOption.id)
                },
                icons = iconPainters,
                leadingIcon = leadingIcon,
                padding = 0.dp,
                motionLevel = motionLevel,
                modifier = modifier.fillMaxWidth()
            )
        } else {
            // Popup windows would ignore the host's visual rotation (Clock screensaver):
            // expand the options inline instead.
            InlineDropdownSettingsItem(
                label = label,
                options = labels,
                selected = selectedLabel,
                onSelected = { pickedLabel ->
                    val pickedIndex = labels.indexOf(pickedLabel)
                    val pickedOption = effectiveOptions.getOrNull(pickedIndex)
                    if (pickedOption != null && !pickerOptionMatches(rowId, pickedOption.id, selectedId)) {
                        onPick(pickedOption.id)
                    }
                },
                icons = iconPainters,
                leadingIcon = leadingIcon,
                modifier = modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Inline stand-in for the motion library's DropdownSettingsItem for hosts where popup
 * windows don't work (see [LocalPopupsAllowed]): tapping the row expands the options in
 * place instead of opening a popup menu.
 */
@Composable
private fun InlineDropdownSettingsItem(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    icons: List<Painter>,
    leadingIcon: Painter?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(
                    selected.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface
                )
            }
            Icon(
                rememberSymbolPainter(if (expanded) "keyboard_arrow_up" else "keyboard_arrow_down"),
                null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerHigh)
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = false
                                onSelected(option)
                            }
                            .background(if (isSelected) scheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        icons.getOrNull(index)?.let { icon ->
                            Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(rememberSymbolPainter("check"), null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDialogOptionPainter(icon: Any?): Painter {
    val context = LocalContext.current
    return when (icon) {
        is Int -> painterResource(icon)
        is String -> {
            val drawableId = resolveDrawableIcon(context, icon)
            drawableId?.let { painterResource(it) } ?: rememberSymbolPainter(icon)
        }
        else -> rememberSymbolPainter("radio_button_checked")
    }
}

private fun PickerOptionSpec.dropdownLabel(duplicatedLabels: Set<String>): String =
    if (label in duplicatedLabels) "$label ($id)" else label

@Composable
private fun GenericEntityStateContent(
    entity: HAEntity,
    spec: WidgetSpec,
    api: HomeAssistantApi,
    names: Map<String, String>,
    configs: Map<String, Map<String, Any>>,
    allEntities: List<HAEntity>,
    onUpdated: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    Text(
        text = entity.state.replace("_", " ").replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = scheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(14.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (spec.style == CardStyle.DEFAULT) {
            FactoryCard(
                entity = entity,
                spec = spec,
                api = api,
                scope = scope,
                names = names,
                configs = configs,
                modifier = Modifier.fillMaxWidth(),
                allEntities = allEntities,
                onUpdated = onUpdated,
                isEdit = false
            )
        }
    }
}

private fun climateOptions(
    row: PickerRowSpec?,
    entity: HAEntity,
    attrKey: String
): List<PickerOptionSpec> {
    val rowOptionsById = row?.options?.associateBy { it.id }.orEmpty()
    val raw = (entity.attributes[attrKey] as? Iterable<*>)
        ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        .orEmpty()
    val rowId = row?.id.orEmpty()
    if (raw.isEmpty()) return emptyList()

    val rawSet = raw.toSet()
    val builtIns = row?.options.orEmpty().filter { option ->
        option.id in rawSet
    }

    return mergePickerOptions(rowId, builtIns, raw) { id ->
        rowOptionsById[id] ?: PickerOptionSpec(id, id.toClimateLabel(), icon = id.toClimateIcon())
    }
}

private fun initialTargetTemperature(entity: HAEntity, minTemp: Double, maxTemp: Double): Double =
    (entity.attributes["temperature"].asDoubleOrNull()
        ?: entity.attributes["target_temp_high"].asDoubleOrNull()
        ?: entity.attributes["target_temp_low"].asDoubleOrNull()
        ?: entity.attributes["current_temperature"].asDoubleOrNull()
        ?: minTemp)
        .coerceIn(minTemp, maxTemp)

private fun roundToClimateStep(value: Double, minTemp: Double, maxTemp: Double, step: Double): Double {
    val rounded = round(value / step) * step
    return rounded.coerceIn(minTemp, maxTemp)
}

private fun dialTemperatureForOffset(
    offset: Offset,
    width: Float,
    height: Float,
    minTemperature: Double,
    maxTemperature: Double,
    step: Double
): Double {
    val startAngle = THERMOSTAT_START_ANGLE.toDouble()
    val sweepAngle = THERMOSTAT_SWEEP_ANGLE.toDouble()
    val centerX = width / 2f
    val centerY = height / 2f
    var angle = Math.toDegrees(
        atan2(
            y = (offset.y - centerY).toDouble(),
            x = (offset.x - centerX).toDouble()
        )
    )
    if (angle < 0.0) angle += 360.0

    val adjusted = when {
        angle in 45.0..135.0 -> if (angle < THERMOSTAT_BOTTOM_GAP_CENTER_ANGLE) startAngle + sweepAngle else startAngle
        angle < startAngle -> angle + 360.0
        else -> angle
    }
    val progress = ((adjusted - startAngle) / sweepAngle).coerceIn(0.0, 1.0)
    val value = minTemperature + ((maxTemperature - minTemperature) * progress)
    return roundToClimateStep(value, minTemperature, maxTemperature, step)
}

private fun isThermostatArcHit(
    offset: Offset,
    width: Float,
    height: Float,
    strokeWidth: Float,
    hitSlop: Float
): Boolean {
    if (width <= 0f || height <= 0f) return false
    val diameter = min(width, height) - strokeWidth
    if (diameter <= 0f) return false

    val radius = diameter / 2f
    val centerX = width / 2f
    val centerY = height / 2f
    val dx = offset.x - centerX
    val dy = offset.y - centerY
    val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    val radiusHitSlop = (strokeWidth / 2f) + hitSlop
    if (abs(distance - radius) > radiusHitSlop) return false

    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
    if (angle < 0.0) angle += 360.0

    val startAngle = THERMOSTAT_START_ANGLE.toDouble()
    val endAngle = startAngle + THERMOSTAT_SWEEP_ANGLE
    val adjusted = if (angle < startAngle) angle + 360.0 else angle
    return adjusted in startAngle..endAngle
}

private fun Any?.asDoubleOrNull(): Double? =
    when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

private fun Double.toServiceNumber(): Any =
    if (abs(this - roundToInt()) < 0.001) roundToInt() else this

private fun formatTemperature(value: Double): String =
    if (abs(value - value.roundToInt()) < 0.05) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

private fun climateModeColor(mode: String): Color =
    when (mode.lowercase(Locale.US)) {
        "cool" -> Color(0xFF4B8DFF)
        "heat" -> Color(0xFFFF7A2F)
        "auto" -> Color(0xFF00B171)
        "fan_only" -> Color(0xFF31B8E5)
        "dry" -> Color(0xFFFF9653)
        "off" -> Color(0xFF8A8F98)
        else -> Color(0xFF4B8DFF)
    }

private fun fanModeColor(mode: String?): Color =
    when (mode?.lowercase(Locale.US)) {
        "off" -> Color(0xFF8A8F98)
        else -> Color(0xFF00B171)
    }

private fun presetModeColor(mode: String?): Color =
    when (mode?.lowercase(Locale.US)) {
        "none" -> Color(0xFF8A8F98)
        "eco" -> Color(0xFF00B171)
        "away" -> Color(0xFFFFA000)
        "boost" -> Color(0xFFFF6F22)
        "comfort" -> Color(0xFF7E57C2)
        "home" -> Color(0xFF2196F3)
        "sleep" -> Color(0xFF5C6BC0)
        "activity" -> Color(0xFF26A69A)
        else -> Color(0xFF7E57C2)
    }

private fun swingModeColor(mode: String?): Color =
    when (mode?.lowercase(Locale.US)) {
        "off" -> Color(0xFF8A8F98)
        "vertical", "horizontal" -> Color(0xFF4B8DFF)
        "both" -> Color(0xFF7E57C2)
        else -> Color(0xFF31B8E5)
    }

private fun String.toClimateLabel(): String =
    when (this) {
        "fan_only" -> "Fan"
        "heat_cool" -> "Heat/Cool"
        "med", "mid", "medium" -> "Medium"
        "medium_low", "mid_low", "midlow" -> "Medium low"
        "medium_high", "mid_high", "midhigh" -> "Medium high"
        else -> replace("_", " ").replaceFirstChar { it.uppercase() }
    }

private fun String.toClimateIcon(): String =
    when (this) {
        "off" -> "power_settings_new"
        "cool" -> "mode_cool"
        "heat" -> "heat"
        "auto" -> "hdr_auto"
        "heat_cool" -> "device_thermostat"
        "fan_only" -> "mode_fan"
        "quiet" -> "bedtime"
        "med", "mid", "medium" -> "filter_2"
        "medium_low", "mid_low", "midlow" -> "filter_2"
        "medium_high", "mid_high", "midhigh" -> "filter_3"
        "turbo" -> "rocket_launch"
        "super" -> "bolt"
        "dry" -> "humidity_percentage"
        "none" -> "block"
        "eco" -> "energy_savings_leaf"
        "away" -> "directions_walk"
        "boost" -> "rocket_launch"
        "comfort" -> "weekend"
        "home" -> "home"
        "sleep" -> "bedtime"
        "activity" -> "directions_run"
        "on" -> "air"
        "vertical" -> "swap_vert"
        "horizontal" -> "swap_horiz"
        "both" -> "open_with"
        else -> "radio_button_checked"
    }

private fun entityRoomLabel(entity: HAEntity, title: String): String {
    val roomKeys = listOf(
        "room",
        "room_name",
        "area",
        "area_name",
        "area_id",
        "device_area",
        "device_area_name"
    )
    roomKeys.firstNotNullOfOrNull { key ->
        entity.attributes[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }?.let { return it.toRoomLabel() }

    Regex("^(.+)'s\\s+.+$", RegexOption.IGNORE_CASE)
        .find(title)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return "${it}'s room" }

    val titleLower = title.lowercase(Locale.US)
    val deviceSuffixes = listOf(
        " air conditioner",
        " thermostat",
        " heat pump",
        " climate",
        " heater",
        " ac"
    )
    deviceSuffixes.firstOrNull { titleLower.endsWith(it) }?.let { suffix ->
        val room = title.dropLast(suffix.length).trim()
        if (room.isNotBlank()) return "$room room"
    }

    val objectLabel = entity.entity_id.substringAfter('.', "").replace('_', ' ')
    val roomIndex = objectLabel.lowercase(Locale.US).split(" ").indexOf("room")
    if (roomIndex >= 0) {
        val room = objectLabel.split(" ").take(roomIndex + 1).joinToString(" ")
        if (room.isNotBlank()) return room.toRoomLabel()
    }

    return "Home"
}

private fun String.toRoomLabel(): String =
    replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replace(Regex("\\s+"), " ")
        .replaceFirstChar { it.uppercase() }
