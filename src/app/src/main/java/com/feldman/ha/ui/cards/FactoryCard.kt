package com.feldman.ha.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.ha.data.HAEntity
import com.feldman.ha.api.HomeAssistantApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max
import com.feldman.ha.R
import com.feldman.ha.widgets.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Context
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import com.feldman.motion.MotionLevel
import com.feldman.motion.MotionSpecs
import com.feldman.motion.ThemeRepository
import com.feldman.motion.isDarkTheme
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.feldman.ha.ui.navigation.LocalAppState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.feldman.ha.ui.camera.CameraCard
import com.feldman.ha.ui.camera.CameraCardConfig
import com.feldman.ha.ui.camera.cameraCardConfigFromFactory
import com.feldman.motion.rememberSymbolPainter

internal fun contentColorFor(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color.Black else Color.White

private const val COUNTER_SERVICE_SEND_DELAY_MS = 450L

/** How long an armed confirm option waits for its second tap before disarming. */
private const val CONFIRM_TIMEOUT_MS = 4_000L

/** Dead time straight after arming, during which the confirming tap is ignored. */
private const val CONFIRM_GUARD_MS = 450L

@Composable
fun FactoryCard(
    entity: HAEntity,
    spec: WidgetSpec,
    api: HomeAssistantApi,
    scope: CoroutineScope,
    names: Map<String, String>,
    configs: Map<String, Map<String, Any>>,
    modifier: Modifier = Modifier,
    allEntities: List<HAEntity> = emptyList(),
    onUpdated: () -> Unit,
    isEdit: Boolean = false,
    onCameraNavigate: ((CameraCardConfig) -> Unit)? = null,
    onMinimumHeightChanged: (Dp) -> Unit = {},
    // Per-card-instance key for config/name lookup. Equals entity_id for normal single
    // cards; a duplicate card of the same entity passes a distinct instance id.
    configKey: String = entity.entity_id
) {
    val colors = appWidgetColors()
    val scope = rememberCoroutineScope()
    val config = configs[configKey] ?: emptyMap()

    if (spec.style == CardStyle.CAMERA) {
        val appState = LocalAppState.current
        val displayName = names[configKey]
            ?: entity.attributes["friendly_name"] as? String
            ?: entity.entity_id
        val cameraConfig = cameraCardConfigFromFactory(entity.entity_id, displayName, config)
        CameraCard(
            config = cameraConfig,
            token = appState.token,
            tokenProvider = appState.tokenProvider,
            haBaseUrl = appState.baseUrl,
            frigateUrl = appState.frigateUrl,
            isEdit = isEdit || onCameraNavigate == null,
            modifier = modifier,
            onNavigateToDetail = { onCameraNavigate?.invoke(cameraConfig) }
        )
        return
    }

    if (spec.style == CardStyle.CLOCK) {
        ClockCard(config = config, modifier = modifier)
        return
    }

    if (spec.style == CardStyle.BUTTON) {
        val bg = (config["bgColor"] as? Number)?.toLong()?.let { Color(it) } ?: cardBackgroundColor()
        val contentColor = if (config["bgColor"] != null) contentColorFor(bg) else MaterialTheme.colorScheme.onSurface
        val isVertical = config["vertical"] as? Boolean ?: false
        val iconName = config["icon"] as? String ?: "bolt"
        val context = LocalContext.current
        val labelBlocks = (config["label_blocks"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
        val labelText = if (labelBlocks.isNotEmpty()) resolveLabelBlocks(labelBlocks, allEntities, context)
            else config["label"] as? String ?: "Action"
        val appState = LocalAppState.current

        val onTap = onTap@{
            val srv = (config["service"] as? String).orEmpty()
            val target = (config["entityId"] as? String).orEmpty()
            val domain: String
            val service: String
            val params = mutableMapOf<String, Any>()
            if (srv.isNotBlank()) {
                // Custom service mode: "domain.service" plus optional JSON data and target.
                val parts = srv.split(".")
                domain = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@onTap
                service = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@onTap
                val dataStr = (config["data"] as? String).orEmpty()
                if (dataStr.isNotBlank()) runCatching {
                    val obj = org.json.JSONObject(dataStr)
                    obj.keys().forEach { k ->
                        val v = obj.get(k)
                        params[k] = when (v) {
                            is Int, is Long, is Double, is Boolean, is String -> v
                            else -> v.toString()
                        }
                    }
                }
                if (target.isNotBlank()) params["entity_id"] = target
            } else {
                // Entity mode: derive the natural tap action from the target's domain.
                if (target.isBlank()) return@onTap
                val action = buttonEntityAction(target.split(".")[0])
                domain = action.first
                service = action.second
                params["entity_id"] = target
            }
            // updateFn is identity: no optimistic flip (the WS push reflects the real state);
            // a failed call still surfaces a snackbar via the existing path.
            appState.callServiceOptimistically(
                target.ifBlank { entity.entity_id }, domain, service, params
            ) { it }
        }

        if (isEdit) {
            // Edit mode renders a static, non-interactive preview so the whole-card
            // tap stays free for the parent grid to select and drag the card. (A live
            // MotionButton would consume those gestures.)
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(CARD_CORNER_RADIUS))
                    .background(bg)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isVertical) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = rememberSymbolPainter(iconName.ifBlank { "bolt" }),
                            contentDescription = labelText,
                            tint = contentColor,
                            modifier = Modifier.size(28.dp)
                        )
                        if (labelText.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = labelText,
                                color = contentColor,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = rememberSymbolPainter(iconName.ifBlank { "bolt" }),
                            contentDescription = labelText,
                            tint = contentColor,
                            modifier = Modifier.size(26.dp)
                        )
                        if (labelText.isNotBlank()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = labelText,
                                color = contentColor,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            // The button card IS a MotionButton: it fills the card and brings the
            // library's animated press/haptic feedback. Corner radius matches the card.
            MotionButton(
                text = labelText.ifBlank { null },
                icon = iconName.ifBlank { "bolt" },
                onClick = onTap,
                modifier = modifier.fillMaxSize(),
                vertical = isVertical,
                fontSize = 16.sp,
                iconSize = 26.dp,
                defaultState = MotionButtonState(
                    backgroundColor = bg,
                    contentColor = contentColor,
                    cornerRadius = CARD_CORNER_RADIUS.value,
                    outlineWidth = 0.dp,
                    outlineColor = Color.Transparent
                )
            )
        }
        return
    }
    
    val displayName = names[configKey] ?: entity.attributes["friendly_name"] as? String ?: entity.entity_id
    val removedRows = (config["removed_rows"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
    val specRowIds = spec.rows.map { it.id }
    val savedOrder = (config["row_order"] as? List<*>)?.filterIsInstance<String>()?.distinct().orEmpty()
    val customFeaturesConfig = (config["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
    @Suppress("UNCHECKED_CAST")
    val customRows = customFeaturesConfig.mapNotNull { cfg ->
        val id = (cfg["id"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        CustomFeatureRowSpec(
            id = id,
            type = cfg["type"] as? String ?: "toggle",
            label = cfg["label"] as? String ?: "Custom Feature",
            icon = cfg["icon"] as? String,
            serviceName = cfg["serviceName"] as? String,
            serviceData = cfg["serviceData"] as? Map<String, Any>,
            targetEntity = cfg["targetEntity"] as? String,
            targets = cfg["targets"] as? List<Map<String, String>>,
            valueTemplate = cfg["valueTemplate"] as? String,
            visibilityConditions = cfg["visibility"] as? List<Map<String, Any>>
        )
    }
    val customRowIds = customRows.map { it.id }
    // Start from the saved order (filtered to rows that still exist), then append
    // any built-in or custom rows missing from it. Falls back to full row order if nothing valid was saved.
    val validRowIds = specRowIds + customRowIds
    val rowOrder = (savedOrder.filter { it in validRowIds } + validRowIds.filter { it !in savedOrder }).distinct()

    val stateTemplate = config["state_template"] as? String
    val timerTicker = rememberTimerTicker(stateTemplate, entity, allEntities)
    val finalDisplayState = remember(entity.state, entity.attributes, stateTemplate, allEntities, timerTicker) {
        if (!stateTemplate.isNullOrBlank()) {
            evaluateStateTemplate(stateTemplate, entity, allEntities)
        } else {
            val stateAliasMap = spec.rows.filterIsInstance<PickerRowSpec>().firstOrNull()?.stateAliases
            val baseState = stateAliasMap?.get(entity.state)?.replaceFirstChar { it.uppercase() }
                ?: formatEntityState(entity)
            val battery = (entity.attributes["battery_level"] ?: entity.attributes["battery"])?.toString()
            if (battery != null) "$baseState • $battery%" else baseState
        }
    }

    val customRowsById = customRows.associateBy { it.id }

    val rowsById = spec.rows.associateBy { it.id }
    val context = LocalContext.current
    val visibleRows = rowOrder.mapNotNull { rowId ->
        val row = rowsById[rowId] ?: customRowsById[rowId] ?: return@mapNotNull null
        if (row.id in removedRows) return@mapNotNull null
        if (row.visibility == RowVisibility.WIDGET) return@mapNotNull null
        // Hide rows whose feature isn't supported by this entity (e.g. cover position slider when
        // SET_POSITION isn't in supported_features).
        if (!evaluateSupported(row.supportedIf, entity)) return@mapNotNull null

        val visibilityConds = if (row is CustomFeatureRowSpec) {
            row.visibilityConditions
        } else {
            (config["visibility:${row.id}"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
        }
        if (!visibilityConds.isNullOrEmpty()) {
            val matches = visibilityConds.all { evaluateCondition(it, allEntities, context) }
            if (!matches) return@mapNotNull null
        }
        
        val hiddenIfToggle = row.hiddenIfToggle
        if (hiddenIfToggle != null) {
            val toggleVal = config[hiddenIfToggle] as? Boolean
                ?: spec.toggles.find { it.id == hiddenIfToggle }?.defaultValue
                ?: false
            if (toggleVal) return@mapNotNull null
        }
        row
    }
    
    // Every picker (including the alarm) is a single segmented row now, so each visible row
    // counts as one row-height.
    val rowHeights = visibleRows.size
    val minSpanY = (rowHeights + 1).coerceIn(1, 6)
    val isDefaultHeight = config["card_height_default"] as? Boolean != false
    val cardSpanY = if (isDefaultHeight) minSpanY else ((config["card_span_y"] as? Number)?.toInt() ?: minSpanY).coerceIn(minSpanY, 6)
    val cardSpanX = ((config["card_span_x"] as? Number)?.toInt() ?: 2).coerceIn(2, 4)

    val customCardIcon = (config["icon"] as? String)?.takeIf { it.isNotBlank() }
    val cardIcon = resolveAppIcon(customCardIcon ?: resolveEntityIcon(entity) ?: resolveStateCardIcon(entity, spec) ?: spec.iconRes ?: resolveDomainIcon(spec.domain))
        ?: painterResource(R.drawable.ic_help)
    val headerTextMeasurer = rememberTextMeasurer()
    val headerDensity = LocalDensity.current
    val titleStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
    val stateStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = colors.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val textWidthPx = with(headerDensity) {
                    (maxWidth - 62.dp).coerceAtLeast(0.dp).roundToPx()
                }
                val titleHeightPx = headerTextMeasurer.measure(
                    text = displayName,
                    style = titleStyle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = textWidthPx)
                ).size.height
                val stateHeightPx = headerTextMeasurer.measure(
                    text = finalDisplayState,
                    style = stateStyle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = textWidthPx)
                ).size.height
                val textHeight = with(headerDensity) { (titleHeightPx + stateHeightPx).toDp() }
                val requiredCardHeight = 24.dp + maxOf(50.dp, textHeight) + 58.dp * visibleRows.size
                SideEffect {
                    onMinimumHeightChanged(requiredCardHeight)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(colors.headerIcon.copy(alpha = if (colors.isDark) 0.18f else 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            cardIcon,
                            contentDescription = displayName,
                            tint = colors.headerIcon,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            color = colors.onBackground,
                            style = titleStyle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = finalDisplayState,
                            color = colors.onBackgroundVariant,
                            style = stateStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            visibleRows.forEach { row ->
                key(row.id) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        FactoryCardRow(row, spec, entity, api, scope, config, cardSpanY, cardSpanX, colors, allEntities, onUpdated)
                    }
                }
            }
        }
    }
}

@Composable
private fun FactoryCardRow(
    row: RowSpec,
    spec: WidgetSpec,
    entity: HAEntity,
    api: HomeAssistantApi,
    scope: CoroutineScope,
    config: Map<String, Any>,
    cardSpanY: Int,
    cardSpanX: Int,
    colors: AppWidgetColors,
    allEntities: List<HAEntity>,
    onUpdated: () -> Unit
) {
    when (row) {
        is PickerRowSpec if row.styleChoice(config) == ROW_STYLE_TOGGLE -> {
            // Same row, drawn as one button. The service is the row id's own toggle action, so
            // this needs nothing from the spec beyond a "toggle" entry in its ServiceMap.
            AppButtonsRow(
                row = ButtonsRowSpec(
                    id = row.id,
                    buttons = listOf(WidgetButtonSpec("toggle", "Toggle", "power_settings_new", "toggle"))
                ),
                entity = entity,
                api = api,
                scope = scope,
                colors = colors,
                onUpdated = onUpdated
            )
        }
        is PickerRowSpec -> {
            val hiddenOptions = (config["picker_hidden:${row.id}"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val allOpts = pickerOptionsForEntity(row, entity)
            val allOptIds = allOpts.map { it.id }
            val visibleOptions = allOpts
                .filter { !isPickerOptionHidden(row.id, it.id, hiddenOptions, allOptIds) }
            if (visibleOptions.isNotEmpty()) {
                AppPickerRow(
                    row = row,
                    spec = spec,
                    entity = entity,
                    api = api,
                    scope = scope,
                    cardSpanX = cardSpanX,
                    colors = colors,
                    config = config,
                    hiddenOptionIds = hiddenOptions,
                    resolvedOptions = visibleOptions,
                    onUpdated = onUpdated
                )
            }
        }
        is SliderRowSpec -> {
            AppSliderRow(row, entity, api, scope, colors, onUpdated)
        }
        is CounterRowSpec -> {
            AppCounterRow(row, spec, entity, api, scope, colors, onUpdated)
        }
        is ButtonsRowSpec -> {
            AppButtonsRow(row, entity, api, scope, colors, onUpdated)
        }
        is DataRowSpec -> {
            AppDataRow(row, entity, colors)
        }
        is SpacerRowSpec -> {
            Spacer(Modifier.height(row.height.dp))
        }
        is CustomFeatureRowSpec -> {
            AppCustomFeatureRow(row, entity, api, scope, colors, allEntities, onUpdated)
        }
        is RemoteRowSpec -> {
            MediaRemoteRow(entity, api, config, colors)
        }
    }
}

@Composable
private fun AppPickerRow(
    row: PickerRowSpec,
    spec: WidgetSpec,
    entity: HAEntity,
    api: HomeAssistantApi,
    scope: CoroutineScope,
    cardSpanX: Int,
    colors: AppWidgetColors,
    config: Map<String, Any>,
    hiddenOptionIds: Set<String> = emptySet(),
    resolvedOptions: List<PickerOptionSpec>? = null,
    onUpdated: () -> Unit
) {
    val context = LocalContext.current
    val appState = LocalAppState.current
    val themeRepository = remember(context) { ThemeRepository(context) }
    val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)
    val motionSpecs = MotionSpecs.withLevel(motionLevel)

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pickerSpringSpec = remember {
        spring<Float>(
            dampingRatio = 0.45f,
            stiffness = 200f
        )
    }

    var pressedOptionId by remember { mutableStateOf<String?>(null) }

    // Which option is armed and waiting for its second tap. Cleared automatically so a card left
    // on screen never sits primed to unlock a door.
    var pendingConfirmId by remember { mutableStateOf<String?>(null) }
    // Taps are ignored for a moment after arming. Without this, a double-tap lands the second
    // press before anyone has read what changed, which is exactly the misfire being guarded
    // against — the visual alone cannot prevent it.
    var confirmArmedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pendingConfirmId) {
        if (pendingConfirmId != null) {
            confirmArmedAt = System.currentTimeMillis()
            kotlinx.coroutines.delay(CONFIRM_TIMEOUT_MS)
            pendingConfirmId = null
        }
    }

    // ── Alarm code handling ──────────────────────────────────────────────────
    // Alarm panels can require a PIN to arm/disarm; no other domain does. HA returns a 500 when
    // the code is required but missing. If a PIN is saved in the card config we send it silently;
    // otherwise we prompt for it each time. code_format is null/absent for entities with no code,
    // so this is a no-op for everything except a code-protected alarm.
    val codeFormat = (entity.attributes["code_format"] as? String)?.takeIf { it.isNotBlank() }
    val codeArmRequired = (entity.attributes["code_arm_required"] as? Boolean) ?: false
    val savedAlarmCode = (config["alarm_code"] as? String)?.takeIf { it.isNotBlank() }
    fun alarmCodeNeededFor(optionId: String): Boolean {
        if (codeFormat == null) return false
        // Disarm always needs the code when one is set; arming needs it only if HA says so.
        return optionId == "disarm" || codeArmRequired
    }
    var showCodeDialog by remember { mutableStateOf(false) }
    var pendingCodeCall by remember { mutableStateOf<((String?) -> Unit)?>(null) }

    val activeOptions = remember(row.options, hiddenOptionIds, resolvedOptions, entity.state, entity.attributes) {
        resolvedOptions?.let { return@remember it }
        val opts = pickerOptionsForEntity(row, entity)
        val optIds = opts.map { it.id }
        opts.filter { !isPickerOptionHidden(row.id, it.id, hiddenOptionIds, optIds) }
    }
    val size = activeOptions.size
    val selectedValue = remember(row.id, row.stateAliases, entity.state, entity.attributes) {
        selectedPickerValue(row, entity)
    }

    val selectedOption = remember(row.id, activeOptions, selectedValue) {
        activeOptions.find { opt -> opt.matchesPickerValue(row.id, selectedValue) }
    }

    val selectedColor = remember(selectedOption, colors) {
        if (selectedOption != null) {
            val c = if (colors.isDark) (selectedOption.nightColor ?: selectedOption.color) else selectedOption.color
            if (c != null) Color(c) else Color(0xFF4CAF50)
        } else {
            Color(0xFF4CAF50)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        val totalWidth = maxWidth
        val availableWidth = totalWidth - (3.dp * (size - 1).coerceAtLeast(0))

        val allOptionsSupportText = remember(activeOptions, availableWidth) {
            activeOptions.all { opt ->
                if (opt.icon == null) {
                    true
                } else {
                    val textLayoutResult = textMeasurer.measure(
                        text = opt.label,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    val textWidthDp = with(density) { textLayoutResult.size.width.toDp() }
                    val neededWidth = 24.dp + 6.dp + textWidthDp + 16.dp

                    val optSelectedWeight = 1.3f
                    val optOtherWeight = 0.8f
                    val totalWeight = optSelectedWeight + optOtherWeight * (size - 1).coerceAtLeast(0)
                    val itemWidth = availableWidth * (optSelectedWeight / totalWeight)
                    itemWidth >= neededWidth
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val weights = activeOptions.map { opt ->
                val isSelected = opt.matchesPickerValue(row.id, selectedValue)

                val isPressed = pressedOptionId == opt.id
                val hasPressedActive = pressedOptionId != null && pressedOptionId != selectedOption?.id

                if (isPressed) {
                    1.1f
                } else if (isSelected) {
                    if (hasPressedActive) 1.0f else 1.3f
                } else {
                    0.8f
                }
            }
            val totalWeight = weights.sum()

            activeOptions.forEachIndexed { index, opt ->
                val interactionSource = remember { MutableInteractionSource() }
                
                LaunchedEffect(interactionSource) {
                    interactionSource.interactions.collect { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> {
                                pressedOptionId = opt.id
                            }
                            is PressInteraction.Release, is PressInteraction.Cancel -> {
                                if (pressedOptionId == opt.id) {
                                    pressedOptionId = null
                                }
                            }
                        }
                    }
                }

                val isSelected = opt.matchesPickerValue(row.id, selectedValue)

                val isLeftEdge = (index == 0)
                val isRightEdge = (index == size - 1)

                val isPressed = pressedOptionId == opt.id

                val targetWeight = weights[index]
                val weight by animateFloatAsState(
                    targetValue = targetWeight,
                    animationSpec = pickerSpringSpec,
                    label = "PickerWeight"
                )

                // Calculate the exact item width allocated to this item in Dp
                // We use targetWeight to avoid layout flicker during spring animation.
                val itemWidth = availableWidth * (targetWeight / totalWeight)

                val targetRadius = if (isSelected) {
                    25f
                } else if (isPressed) {
                    16.5f // 33% corner radius of 50dp
                } else {
                    12.5f
                }

                val animatedRadius by animateFloatAsState(
                    targetValue = targetRadius,
                    animationSpec = pickerSpringSpec,
                    label = "PickerCorner"
                )

                val tl: androidx.compose.ui.unit.Dp
                val tr: androidx.compose.ui.unit.Dp
                val bl: androidx.compose.ui.unit.Dp
                val br: androidx.compose.ui.unit.Dp
                if (isPressed) {
                    val r = animatedRadius.dp
                    tl = r; tr = r; bl = r; br = r
                } else if (!isSelected) {
                    val r = animatedRadius.dp
                    tl = r; tr = r; bl = r; br = r
                } else if (isLeftEdge) {
                    tl = 25.dp; tr = animatedRadius.dp; bl = 25.dp; br = 25.dp
                } else if (isRightEdge) {
                    tl = animatedRadius.dp; tr = 25.dp; bl = 25.dp; br = 25.dp
                } else {
                    val r = animatedRadius.dp
                    tl = r; tr = r; bl = r; br = r
                }

                val pickedColor = remember(opt, colors) {
                    val c = if (colors.isDark) (opt.nightColor ?: opt.color) else opt.color
                    if (c != null) Color(c) else Color(0xFF4CAF50)
                }

                val longPressTargetColor = remember(opt, colors) {
                    val c = if (colors.isDark) (opt.longPressNightColor ?: opt.longPressColor) else opt.longPressColor
                    if (c != null) Color(c) else null
                }

                val isArmed = pendingConfirmId == opt.id
                val bgColor = if (isArmed) {
                    // Nothing: the MotionButton below is the entire visual. Painting the chip too
                    // would stack two backgrounds and two corner radii on top of each other.
                    Color.Transparent
                } else if (isSelected) {
                    selectedColor
                } else if (isPressed) {
                    longPressTargetColor ?: lerp(selectedColor, pickedColor, 0.5f)
                } else {
                    colors.buttonBackground
                }
                val selectedContentColor = Color.White

                val optionClick: () -> Unit = {
                                val call = row.id
                                var effectiveAk: String? = null
                                val override = opt.actionOverrideIfToggle
                                if (override != null) {
                                    val toggleVal = config[override.first] as? Boolean
                                        ?: spec.toggles.find { it.id == override.first }?.defaultValue
                                        ?: false
                                    if (toggleVal) {
                                        effectiveAk = override.second
                                    }
                                }

                                val serviceCall = when {
                                    effectiveAk != null -> spec.actions.calls[effectiveAk]
                                    else -> spec.actions.calls["pick:$call"] ?: spec.actions.calls["pick"]
                                }

                                // Performs the pick, attaching an alarm `code` when supplied.
                                val doPick: (String?) -> Unit = { code ->
                                    if (serviceCall != null) {
                                        val actualService = serviceCall.service.replace("{value}", opt.id)
                                        val body = mutableMapOf<String, Any>("entity_id" to entity.entity_id)

                                        serviceCall.params.forEach { (k, v) ->
                                            body[k] = when (v) {
                                                "{entity}" -> entity.entity_id
                                                "{value}"  -> opt.id.toIntOrNull() ?: opt.id
                                                else       -> v
                                            }
                                        }
                                        if (code != null) body["code"] = code

                                        appState.callServiceOptimistically(
                                            entity.entity_id,
                                            serviceCall.domain,
                                            actualService,
                                            body
                                        ) { ent ->
                                            val newState = if (row.stateAliases.containsValue(opt.id)) {
                                                row.stateAliases.entries.find { it.value == opt.id }?.key ?: opt.id
                                            } else if (row.id == "hvac_mode") {
                                                opt.id
                                            } else {
                                                ent.state
                                            }
                                            val newAttrs = ent.attributes.toMutableMap()
                                            newAttrs[call] = opt.id
                                            ent.copy(state = newState, attributes = newAttrs)
                                        }
                                    } else {
                                        val domain = entity.entity_id.split(".")[0]
                                        val body = mutableMapOf<String, Any>(
                                            "entity_id" to entity.entity_id,
                                            call to opt.id
                                        )
                                        if (code != null) body["code"] = code
                                        appState.callServiceOptimistically(
                                            entity.entity_id,
                                            domain,
                                            "set_$call",
                                            body
                                        ) { ent ->
                                            val newState = if (row.stateAliases.containsValue(opt.id)) {
                                                row.stateAliases.entries.find { it.value == opt.id }?.key ?: opt.id
                                            } else if (row.id == "hvac_mode") {
                                                opt.id
                                            } else {
                                                ent.state
                                            }
                                            val newAttrs = ent.attributes.toMutableMap()
                                            newAttrs[call] = opt.id
                                            ent.copy(state = newState, attributes = newAttrs)
                                        }
                                    }
                                }

                                // A confirm option arms on the first tap and fires on the second.
                                // Anything else tapped in between disarms it, so a stray press
                                // elsewhere on the card cannot leave it primed.
                                val guarding = pendingConfirmId == opt.id &&
                                    System.currentTimeMillis() - confirmArmedAt < CONFIRM_GUARD_MS
                                if (guarding) {
                                    // Too soon after arming to be a considered press.
                                } else if (opt.confirm && pendingConfirmId != opt.id) {
                                    pendingConfirmId = opt.id
                                } else {
                                    pendingConfirmId = null
                                    if (alarmCodeNeededFor(opt.id)) {
                                        if (savedAlarmCode != null) doPick(savedAlarmCode)
                                        else { pendingCodeCall = doPick; showCodeDialog = true }
                                    } else {
                                        doPick(null)
                                    }
                                }
                }

                Box(
                    modifier = Modifier
                        // An armed option swallows the row. The confirming tap then lands on a
                        // control that was not there a moment ago and is nowhere near the size or
                        // shape of the one just pressed, so a reflex double-tap cannot complete it.
                        .weight(if (pendingConfirmId != null) (if (isArmed) 1f else 0.0001f) else weight)
                        .height(50.dp)
                        .clip(RoundedCornerShape(tl, tr, br, bl))
                        .background(bgColor)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = !isArmed,
                            onClick = optionClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isArmed) {
                        // A MotionButton rather than the chip's own icon/label: the armed state is
                        // a distinct, deliberate action, and this is the component every other
                        // committing button in the app uses.
                        MotionButton(
                            text = "Confirm",
                            icon = "check",
                            onClick = optionClick,
                            modifier = Modifier.fillMaxSize(),
                            fontSize = 17.sp,
                            iconSize = 22.dp,
                            defaultState = MotionButtonState(
                                backgroundColor = pickedColor,
                                contentColor = Color.White,
                                outlineWidth = 0.dp,
                                outlineColor = colors.buttonBackground
                            )
                        )
                        return@Box
                    }
                    val icon = resolveAppIcon(opt.icon)
                    if (icon != null) {
                        val showText = allOptionsSupportText

                        // An armed option shows its label too, even when unselected — the whole
                        // point is that the user can see it is waiting for a second tap.
                        if ((isSelected || isPressed || pendingConfirmId == opt.id) && showText) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Icon(
                                    painter = icon,
                                    contentDescription = opt.label,
                                    modifier = Modifier.size(24.dp),
                                    tint = selectedContentColor
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (pendingConfirmId == opt.id) "Confirm?" else opt.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = selectedContentColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    ),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Icon(
                                painter = icon,
                                contentDescription = opt.label,
                                modifier = Modifier.size(28.dp),
                                tint = if (isSelected || isPressed) selectedContentColor else colors.onBackgroundVariant
                            )
                        }
                    } else {
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isSelected || isPressed) selectedContentColor else colors.onBackgroundVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // ── Alarm PIN prompt (shown only when no code is saved in the card config) ──
    if (showCodeDialog) {
        var codeInput by remember { mutableStateOf("") }
        val numeric = codeFormat == "number"
        AlertDialog(
            onDismissRequest = { showCodeDialog = false; pendingCodeCall = null },
            title = { Text("Enter alarm code") },
            text = {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = codeInput.isNotBlank(),
                    onClick = {
                        val cb = pendingCodeCall
                        showCodeDialog = false
                        pendingCodeCall = null
                        cb?.invoke(codeInput)
                    }
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showCodeDialog = false; pendingCodeCall = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AppSliderRow(
    row: SliderRowSpec,
    entity: HAEntity,
    api: HomeAssistantApi,
    scope: CoroutineScope,
    colors: AppWidgetColors,
    onUpdated: () -> Unit
) {
    val appState = LocalAppState.current
    // optimisticAttr names the HA attribute to read (e.g. "current_position" for cover position).
    // brightness_pct is special: HA stores 0-255 under "brightness", we display 0-100.
    val attrKey = if (row.id == "brightness_pct") "brightness" else row.optimisticAttr ?: row.id

    var value by remember(entity.attributes[attrKey]) {
        var v = (entity.attributes[attrKey] as? Number)?.toFloat() ?: row.min
        if (row.id == "brightness_pct") {
            v = (v / 255f) * 100f
        } else if (entity.entity_id.startsWith("cover.") && (row.id == "position" || row.id == "tilt")) {
            v = 100f - v
        }
        mutableStateOf(v)
    }

    if (row.background != SliderBackground.NONE) {
        val brush = when (row.background) {
            SliderBackground.RGB -> Brush.horizontalGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
            )
            SliderBackground.TEMPERATURE -> Brush.horizontalGradient(
                listOf(Color(0xFF8EC9FF), Color.White, Color(0xFFFFC107))
            )
            SliderBackground.BRIGHTNESS -> Brush.horizontalGradient(
                listOf(colors.buttonBackground, Color.White)
            )
            else -> null
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(colors.buttonBackground)
        ) {
            if (brush != null) Box(modifier = Modifier.fillMaxSize().background(brush, alpha = 0.3f))
            SliderContent(row, value, { value = it }, appState, entity, colors)
        }
    } else {
        SliderContent(row, value, { value = it }, appState, entity, colors)
    }
}

@Composable
private fun SliderContent(
    row: SliderRowSpec,
    value: Float,
    onValueChange: (Float) -> Unit,
    appState: com.feldman.ha.ui.navigation.AppState,
    entity: HAEntity,
    colors: AppWidgetColors,
) {
    // Custom host surfaces are not Material color roles, so contentColorFor cannot resolve
    // them reliably. Use the foreground already resolved for this card's button surface.
    val fg = colors.buttonOnBackground
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = {
            val domain = entity.entity_id.split(".")[0]
            val actualValue = if (entity.entity_id.startsWith("cover.") && (row.id == "position" || row.id == "tilt")) {
                100f - value
            } else {
                value
            }
            val params = mutableMapOf<String, Any>("entity_id" to entity.entity_id)
            val service = when {
                row.serviceOverride != null -> {
                    params[row.paramName ?: row.id] = actualValue.toInt()
                    row.serviceOverride
                }
                row.id == "brightness_pct" -> { params["brightness_pct"] = actualValue.toInt(); "turn_on" }
                row.id == "hs_color" -> { params["hs_color"] = listOf(actualValue, 100); "turn_on" }
                row.id == "color_temp" -> { params["color_temp"] = actualValue.toInt(); "turn_on" }
                else -> { params[row.id] = actualValue; "turn_on" }
            }
            appState.callServiceOptimistically(entity.entity_id, domain, service, params) { ent ->
                val newAttrs = ent.attributes.toMutableMap()
                when {
                    row.optimisticAttr != null -> newAttrs[row.optimisticAttr] = actualValue.toInt()
                    row.id == "brightness_pct" -> newAttrs["brightness"] = (actualValue / 100f) * 255f
                    row.id == "hs_color" -> newAttrs["hs_color"] = listOf(actualValue, 100)
                    row.id == "color_temp" -> newAttrs["color_temp"] = actualValue.toInt()
                    else -> newAttrs[row.id] = actualValue
                }
                ent.copy(attributes = newAttrs)
            }
        },
        valueRange = row.min..row.max,
        modifier = Modifier.fillMaxWidth(),
        colors = if (row.background != SliderBackground.NONE) {
            SliderDefaults.colors(
                thumbColor = fg,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            )
        } else {
            SliderDefaults.colors(
                thumbColor = fg,
                activeTrackColor = fg.copy(alpha = 0.9f),
                inactiveTrackColor = fg.copy(alpha = 0.25f)
            )
        }
    )
}

@Composable
private fun AppCounterRow(
    row: CounterRowSpec,
    spec: WidgetSpec,
    entity: HAEntity,
    api: HomeAssistantApi,
    scope: CoroutineScope,
    colors: AppWidgetColors,
    onUpdated: () -> Unit
) {
    val appState = LocalAppState.current
    val dynamicMin = climateCounterBound(entity, row, isMin = true)
    val dynamicMax = climateCounterBound(entity, row, isMin = false)
    val sourceValue = entity.attributes[row.id].asCounterInt() ?: dynamicMin
    var value by remember(entity.entity_id, row.id, sourceValue) { mutableIntStateOf(sourceValue) }
    var pendingCounterSend by remember(entity.entity_id, row.id) { mutableStateOf<Job?>(null) }
    val canDec = value > dynamicMin
    val canInc = value < dynamicMax

    val decIconName = when (val icon = row.decIcon) {
        is String -> icon
        else -> "remove"
    }
    val incIconName = when (val icon = row.incIcon) {
        is String -> icon
        else -> "add"
    }

    fun setCounter(nextValue: Int) {
        val coercedValue = nextValue.coerceIn(dynamicMin, dynamicMax)
        value = coercedValue
        pendingCounterSend?.cancel()
        pendingCounterSend = scope.launch {
            delay(COUNTER_SERVICE_SEND_DELAY_MS)
            val serviceCall = spec.actions.calls["setCounter:${row.id}"] ?: spec.actions.calls["setCounter"]
            if (serviceCall != null) {
                val actualService = serviceCall.service.replace("{value}", coercedValue.toString())
                val body = mutableMapOf<String, Any>("entity_id" to entity.entity_id)
                serviceCall.params.forEach { (k, v) ->
                    body[k] = when (v) {
                        "{entity}" -> entity.entity_id
                        "{value}"  -> coercedValue
                        else       -> v
                    }
                }
                appState.callServiceOptimistically(
                    entity.entity_id,
                    serviceCall.domain,
                    actualService,
                    body
                ) { ent ->
                    val newAttrs = ent.attributes.toMutableMap()
                    newAttrs[row.id] = coercedValue
                    ent.copy(attributes = newAttrs)
                }
            } else {
                val domain = entity.entity_id.split(".")[0]
                appState.callServiceOptimistically(
                    entity.entity_id,
                    domain,
                    "set_temperature",
                    mapOf(
                        "entity_id" to entity.entity_id,
                        row.id to coercedValue
                    )
                ) { ent ->
                    val newAttrs = ent.attributes.toMutableMap()
                    newAttrs[row.id] = coercedValue
                    ent.copy(attributes = newAttrs)
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Minus
        MotionButton(
            text = "",
            icon = decIconName,
            onClick = {
                if (canDec) {
                    setCounter(value - row.step)
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(50.dp),
            fontSize = 15.sp,
            iconSize = 24.dp,
            defaultState = MotionButtonState(
                backgroundColor = if (canDec) colors.buttonBackground else colors.buttonDisabledBackground,
                contentColor = if (canDec) colors.onBackground else colors.onBackgroundVariant,
                outlineWidth = 0.dp,
                outlineColor = Color.Transparent
            )
        )

        // Value
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 26.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground
                )
            )
        }

        // Plus
        MotionButton(
            text = "",
            icon = incIconName,
            onClick = {
                if (canInc) {
                    setCounter(value + row.step)
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(50.dp),
            fontSize = 15.sp,
            iconSize = 24.dp,
            defaultState = MotionButtonState(
                backgroundColor = if (canInc) colors.buttonBackground else colors.buttonDisabledBackground,
                contentColor = if (canInc) colors.onBackground else colors.onBackgroundVariant,
                outlineWidth = 0.dp,
                outlineColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun AppButtonsRow(
    row: ButtonsRowSpec,
    entity: HAEntity,
    api: HomeAssistantApi,
    scope: CoroutineScope,
    colors: AppWidgetColors,
    onUpdated: () -> Unit
) {
    val appState = LocalAppState.current
    val buttons = row.buttons.filter { evaluateSupported(it.supportedIf, entity) }
    if (buttons.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        buttons.forEach { btn ->
            // Icon-only when the spec provides an icon; MotionButton renders Material Symbol
            // names natively (animated weight/fill) so glyphs read at proper size.
            MotionButton(
                text = if (btn.icon == null) btn.label else null,
                icon = btn.icon,
                onClick = {
                    val domain = entity.entity_id.split(".")[0]
                    appState.callServiceOptimistically(
                        entity.entity_id,
                        domain,
                        btn.action,
                        mapOf("entity_id" to entity.entity_id)
                    ) { ent ->
                        val nextState = when (btn.action) {
                            "turn_on" -> "on"
                            "turn_off" -> "off"
                            "toggle" -> if (ent.state == "on") "off" else "on"
                            else -> ent.state
                        }
                        ent.copy(state = nextState)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                fontSize = 15.sp,
                iconSize = 26.dp,
                motionLevel = MotionLevel.NONE,
                defaultState = MotionButtonState(
                    backgroundColor = colors.buttonBackground,
                    contentColor = colors.onBackground,
                    outlineWidth = 0.dp,
                    outlineColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun AppDataRow(
    row: DataRowSpec,
    entity: HAEntity,
    colors: AppWidgetColors
) {
    val value = entity.attributes[row.id]?.toString() ?: "--"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (row.label != null) {
            Text(
                text = "${row.label}: ",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onBackgroundVariant
            )
        }
        Text(
            text = "$value${row.unit ?: ""}",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.onBackground
        )
    }
}

@Composable
private fun resolveAppIcon(icon: Any?): androidx.compose.ui.graphics.painter.Painter? {
    if (icon == null) return null
    val context = LocalContext.current
    return when (icon) {
        is Int -> painterResource(icon)
        is String -> {
            val resId = resolveDrawableIcon(context, icon)
            if (resId != null) {
                painterResource(resId)
            } else {
                val bitmap = renderAppMotionSymbol(context, icon)
                BitmapPainter(bitmap.asImageBitmap())
            }
        }
        else -> null
    }
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

private val appMotionSymbolCache = mutableMapOf<String, Bitmap>()

private fun renderAppMotionSymbol(context: Context, name: String): Bitmap {
    val density = context.resources.displayMetrics.density
    val sizePx = (32f * density).toInt().coerceAtLeast(32)
    val key = "$name:$sizePx"

    synchronized(appMotionSymbolCache) {
        appMotionSymbolCache[key]?.let { return it }
    }

    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sizePx * 0.8f
        typeface = ResourcesCompat.getFont(
            context,
            com.feldman.motion.R.font.material_symbols_rounded
        ) ?: Typeface.DEFAULT
        fontVariationSettings = "'FILL' 1, 'GRAD' 0, 'opsz' 24, 'wght' 500"
    }

    val fontMetrics = paint.fontMetrics
    val x = sizePx / 2f
    val y = (sizePx / 2f) - (fontMetrics.ascent + fontMetrics.descent) / 2f

    // Draw using ligature
    canvas.drawText(name, x, y, paint)

    synchronized(appMotionSymbolCache) {
        appMotionSymbolCache[key] = bitmap
    }
    return bitmap
}

private fun resolveDomainIcon(domain: String): Int {
    return when (domain) {
        "light" -> R.drawable.ic_lightbulb
        "fan" -> R.drawable.ic_fan
        "climate" -> R.drawable.ic_thermostat
        "lock" -> R.drawable.ic_lock
        "alarm_control_panel" -> R.drawable.ic_shield
        "cover" -> R.drawable.ic_blinds
        else -> R.drawable.ic_help
    }
}

private fun resolveStateCardIcon(entity: HAEntity, spec: WidgetSpec): Any? {
    spec.rows
        .filterIsInstance<PickerRowSpec>()
        .firstNotNullOfOrNull { row -> resolvePickerStateIcon(row, entity) }
        ?.let { return it }

    val state = entity.state.lowercase()
    return when (spec.domain) {
        "cover" -> resolveCoverStateIcon(entity)
        "fan" -> if (state == "off" || entity.attributes["percentage"].asHeaderNumber() == 0.0) R.drawable.ic_fan_off else R.drawable.ic_fan
        "climate" -> when (state) {
            "off" -> "power_settings_new"
            "cool" -> "mode_cool"
            "heat" -> "heat"
            "fan_only", "fan" -> "mode_fan"
            "dry" -> "humidity_percentage"
            "auto", "heat_cool" -> "device_thermostat"
            else -> null
        }
        "light" -> if (state == "on") "lightbulb" else "light_off"
        "switch", "input_boolean" -> if (state == "on") "toggle_on" else "toggle_off"
        "binary_sensor" -> binarySensorDeviceClassIcon(entity)
        "sensor" -> sensorDeviceClassIcon(entity)
        else -> null
    }
}

fun resolveEntityIcon(entity: HAEntity): Any? {
    val rawIcon = entity.attributes["icon"]?.toString()?.takeIf { it.isNotBlank() }
    return rawIcon?.let { normalizeHomeAssistantIcon(it, entity) }
}

private fun normalizeHomeAssistantIcon(raw: String, entity: HAEntity): String {
    val icon = raw.removePrefix("mdi:").replace("-", "_")
    return when (icon) {
        "door_closed" -> "door_front"
        "door_open" -> "door_open"
        "window_closed" -> "window_closed"
        "window_open" -> "window_open"
        "garage" -> "garage"
        "garage_open" -> "garage_home"
        "lock", "lock_outline" -> "lock"
        "lock_open", "lock_open_outline" -> "lock_open"
        "motion_sensor" -> "sensors"
        "thermometer" -> "thermostat"
        "water_percent" -> "humidity_percentage"
        "battery", "battery_unknown" -> "battery_unknown"
        "power_plug", "power_socket" -> "power"
        else -> icon.ifBlank {
            entity.entity_id.substringBefore(".")
        }
    }
}

private fun binarySensorDeviceClassIcon(entity: HAEntity): String {
    val deviceClass = entity.attributes["device_class"]?.toString()
    val on = entity.state.equals("on", ignoreCase = true)
    return when (deviceClass) {
        "door" -> if (on) "door_open" else "door_front"
        "garage_door" -> if (on) "garage_home" else "garage"
        "window" -> if (on) "window_open" else "window_closed"
        "lock" -> if (on) "lock_open" else "lock"
        "motion", "occupancy", "presence" -> "sensors"
        "opening" -> if (on) "door_open" else "door_front"
        "plug", "power" -> "power"
        "battery" -> "battery_unknown"
        "problem", "safety", "tamper" -> if (on) "warning" else "check_circle"
        "smoke" -> "detector_smoke"
        "moisture" -> "water_drop"
        "gas" -> "gas_meter"
        else -> if (on) "radio_button_checked" else "radio_button_unchecked"
    }
}

private fun sensorDeviceClassIcon(entity: HAEntity): String? =
    when (entity.attributes["device_class"]?.toString()) {
        "temperature" -> "thermostat"
        "humidity" -> "humidity_percentage"
        "illuminance" -> "light_mode"
        "battery" -> "battery_unknown"
        "power", "energy" -> "bolt"
        "voltage" -> "electric_bolt"
        "current" -> "offline_bolt"
        "signal_strength" -> "signal_cellular_alt"
        else -> null
    }

private fun resolvePickerStateIcon(row: PickerRowSpec, entity: HAEntity): Any? {
    row.transitionalStates[entity.state]?.icon?.let { return it }

    val rawValue = entity.attributes[row.id]?.toString()?.takeIf { it.isNotBlank() }
        ?: row.stateAliases[entity.state]
        ?: if (row.id == "state" || row.id == "hvac_mode") entity.state else null
        ?: return null

    return row.options.firstOrNull { opt ->
        pickerOptionMatches(row.id, opt.id, row.stateAliases[rawValue] ?: rawValue)
    }?.icon
}

private fun resolveCoverStateIcon(entity: HAEntity): Any {
    val state = entity.state.lowercase()
    val position = entity.attributes["current_position"].asHeaderNumber()
    val isClosed = state == "closed" || state == "closing" || position == 0.0
    return if (isClosed) R.drawable.ic_blinds else R.drawable.ic_blinds_open
}

private fun Any?.asHeaderNumber(): Double? =
    when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

internal fun pickerOptionsForEntity(row: PickerRowSpec, entity: HAEntity): List<PickerOptionSpec> {
    val builtIns = row.options.filter { evaluateSupported(it.supportedIf, entity) }
    val dynamicIds = pickerAttributeForRow(row.id)
        ?.let { attr -> entity.attributes[attr] as? Iterable<*> }
        ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        .orEmpty()

    return mergePickerOptions(row.id, builtIns, dynamicIds) { defaultPickerOption(row.id, it) }
}

private fun pickerAttributeForRow(rowId: String): String? =
    when (rowId) {
        "hvac_mode" -> "hvac_modes"
        "fan_mode" -> "fan_modes"
        "preset_mode" -> "preset_modes"
        "swing_mode" -> "swing_modes"
        "swing_horizontal_mode" -> "swing_horizontal_modes"
        else -> null
    }

private fun selectedPickerValue(row: PickerRowSpec, entity: HAEntity): String? {
    val attrValue = entity.attributes[row.id]?.toString()?.takeIf { it.isNotBlank() }
    if (attrValue != null) return row.stateAliases[attrValue] ?: attrValue

    val stateValue = row.stateAliases[entity.state] ?: entity.state
    return if (
        row.id == "state" ||
        row.id == "hvac_mode" ||
        row.options.any { opt -> opt.matchesPickerValue(row.id, stateValue) }
    ) {
        stateValue
    } else {
        null
    }
}

private fun PickerOptionSpec.matchesPickerValue(rowId: String, value: String?): Boolean =
    pickerOptionMatches(rowId, id, value)

private fun defaultPickerOption(rowId: String, optionId: String): PickerOptionSpec =
    when (rowId) {
        "hvac_mode" -> when (optionId) {
            "off" -> PickerOptionSpec(optionId, "Off", icon = "power_settings_new", color = 0xFFa7a7a7, nightColor = 0xFF484A4E)
            "cool" -> PickerOptionSpec(optionId, "Cool", icon = "mode_cool", color = 0xFF2196F3, nightColor = 0xFF2196F3)
            "heat" -> PickerOptionSpec(optionId, "Heat", icon = "heat", color = 0xFFFF6F22, nightColor = 0xFFFF6F22)
            "auto", "heat_cool" -> PickerOptionSpec(optionId, "Auto", icon = "hdr_auto", color = 0xFF00B171, nightColor = 0xFF00B171)
            "fan", "fan_only" -> PickerOptionSpec(optionId, "Fan", icon = "mode_fan", color = 0xFF31B8E5, nightColor = 0xFF31B8E5)
            "dry" -> PickerOptionSpec(optionId, "Dry", icon = "humidity_percentage", color = 0xFFFF9653, nightColor = 0xFFFF9653)
            else -> PickerOptionSpec(optionId, optionId.toPickerLabel())
        }
        "fan_mode" -> when (optionId) {
            "auto" -> PickerOptionSpec(optionId, "Auto", icon = "hdr_auto")
            "quiet" -> PickerOptionSpec(optionId, "Quiet", icon = "bedtime")
            "low" -> PickerOptionSpec(optionId, "Low", icon = R.drawable.ic_one)
            "medium", "med", "mid" -> PickerOptionSpec(optionId, "Medium", icon = R.drawable.ic_two)
            "medium_low", "medium low", "medium-low", "med_low", "med low", "med-low",
            "mid_low", "mid low", "mid-low" ->
                PickerOptionSpec(optionId, "Medium low", icon = R.drawable.ic_two)
            "medium_high", "medium high", "medium-high", "med_high", "med high", "med-high",
            "mid_high", "mid high", "mid-high" ->
                PickerOptionSpec(optionId, "Medium high", icon = R.drawable.ic_three)
            "high" -> PickerOptionSpec(optionId, "High", icon = R.drawable.ic_three)
            "turbo" -> PickerOptionSpec(optionId, "Turbo", icon = "rocket_launch")
            "super" -> PickerOptionSpec(optionId, "Super", icon = "bolt")
            else -> PickerOptionSpec(optionId, optionId.toPickerLabel(), icon = "mode_fan")
        }
        "preset_mode" -> when (optionId) {
            "none" -> PickerOptionSpec(optionId, "None", icon = "block", color = 0xFF8A8F98, nightColor = 0xFF555A62)
            "eco" -> PickerOptionSpec(optionId, "Eco", icon = "energy_savings_leaf", color = 0xFF00B171, nightColor = 0xFF00B171)
            "away" -> PickerOptionSpec(optionId, "Away", icon = "directions_walk", color = 0xFFFFA000, nightColor = 0xFFFFA000)
            "boost" -> PickerOptionSpec(optionId, "Boost", icon = "rocket_launch", color = 0xFFFF6F22, nightColor = 0xFFFF6F22)
            "comfort" -> PickerOptionSpec(optionId, "Comfort", icon = "weekend", color = 0xFF7E57C2, nightColor = 0xFF7E57C2)
            "home" -> PickerOptionSpec(optionId, "Home", icon = "home", color = 0xFF2196F3, nightColor = 0xFF2196F3)
            "sleep" -> PickerOptionSpec(optionId, "Sleep", icon = "bedtime", color = 0xFF5C6BC0, nightColor = 0xFF5C6BC0)
            "activity" -> PickerOptionSpec(optionId, "Activity", icon = "directions_run", color = 0xFF26A69A, nightColor = 0xFF26A69A)
            else -> PickerOptionSpec(optionId, optionId.toPickerLabel(), icon = "tune", color = 0xFF7E57C2, nightColor = 0xFF7E57C2)
        }
        "swing_mode", "swing_horizontal_mode" -> when (optionId) {
            "off" -> PickerOptionSpec(optionId, "Off", icon = "airwave", color = 0xFF8A8F98, nightColor = 0xFF555A62)
            "on" -> PickerOptionSpec(optionId, "On", icon = "air", color = 0xFF31B8E5, nightColor = 0xFF31B8E5)
            "vertical" -> PickerOptionSpec(optionId, "Vertical", icon = "swap_vert", color = 0xFF4B8DFF, nightColor = 0xFF4B8DFF)
            "horizontal" -> PickerOptionSpec(optionId, "Horizontal", icon = "swap_horiz", color = 0xFF4B8DFF, nightColor = 0xFF4B8DFF)
            "both" -> PickerOptionSpec(optionId, "Both", icon = "open_with", color = 0xFF7E57C2, nightColor = 0xFF7E57C2)
            else -> PickerOptionSpec(optionId, optionId.toPickerLabel(), icon = "air", color = 0xFF31B8E5, nightColor = 0xFF31B8E5)
        }
        else -> PickerOptionSpec(optionId, optionId.toPickerLabel())
    }

private fun String.toPickerLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

/** Which presentation this row is set to, falling back to the spec's default. */
internal fun PickerRowSpec.styleChoice(config: Map<String, Any>): String? {
    val styleSpec = style ?: return null
    val chosen = config[styleSpec.configKey] as? String
    return chosen?.takeIf { choice -> styleSpec.options.any { it.id == choice } } ?: styleSpec.default
}

internal fun evaluateSupported(logic: SupportedLogic?, entity: HAEntity): Boolean {
    if (logic == null) return true
    val attr = entity.attributes[logic.attribute]
    if (attr == null) {
        if (logic.attribute == "supported_features") return true
        return false
    }
    
    fun attributeAsList(): List<String> = when (attr) {
        is Iterable<*> -> attr.map { it.toString().lowercase() }
        is String -> attr.split("|").map { it.trim().lowercase() }
        else -> emptyList()
    }

    if (logic.contains != null) {
        return attributeAsList().contains(logic.contains.lowercase())
    }

    if (logic.containsAny != null) {
        val wanted = logic.containsAny.map { it.lowercase() }
        return attributeAsList().any { it in wanted }
    }
    
    if (logic.bitmask != null) {
        val features = (attr as? Number)?.toInt()
            ?: (attr as? String)?.toIntOrNull()
            ?: (attr as? String)?.toDoubleOrNull()?.toInt()
            ?: return true
        return (features and logic.bitmask) != 0
    }
    
    return true
}

private fun climateCounterBound(entity: HAEntity, row: CounterRowSpec, isMin: Boolean): Int {
    val attrName = when {
        row.id == "target_humidity" && isMin -> "min_humidity"
        row.id == "target_humidity" -> "max_humidity"
        isMin -> "min_temp"
        else -> "max_temp"
    }
    return entity.attributes[attrName].asCounterInt()
        ?: entity.attributes[if (isMin) "min" else "max"].asCounterInt()
        ?: if (isMin) row.min else row.max
}

private fun Any?.asCounterInt(): Int? =
    when (this) {
        is Number -> toInt()
        is String -> toIntOrNull() ?: toDoubleOrNull()?.toInt()
        else -> null
    }

data class AppWidgetColors(
    val isDark: Boolean,
    val background: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val buttonBackground: Color,
    val buttonOnBackground: Color,
    val buttonDisabledBackground: Color,
    val headerIcon: Color
)

@Composable
private fun appWidgetColors(): AppWidgetColors {
    val scheme = MaterialTheme.colorScheme
    val isDark = isDarkTheme()
    return AppWidgetColors(
        isDark = isDark,
        background = cardBackgroundColor(),
        onBackground = scheme.onSurface,
        onBackgroundVariant = scheme.onSurfaceVariant,
        buttonBackground = cardButtonBackgroundColor(),
        buttonOnBackground = scheme.onSurfaceVariant,
        buttonDisabledBackground = cardButtonBackgroundColor().copy(alpha = 0.52f),
        headerIcon = scheme.primary
    )
}

private fun putRestTarget(params: MutableMap<String, Any>, key: String, values: List<String>) {
    val cleaned = values.filter { it.isNotBlank() }.distinct()
    if (cleaned.isEmpty()) return
    params[key] = cleaned.singleOrNull() ?: cleaned
}
@Composable
private fun AppBigPillSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: AppWidgetColors,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.buttonBackground)
            .padding(4.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val thumbWidth = maxWidth / 2
            val travelDp = maxWidth - thumbWidth
            val travelPx = with(density) { travelDp.toPx() }

            // Thumb position (0 = off, 1 = on) is the single source of truth: a drag snaps
            // it to the finger, release settles it, and external state changes animate it.
            // All interaction lives on the thumb, so the surrounding pill isn't a hitbox.
            val thumbPos = remember { Animatable(if (checked) 1f else 0f) }
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(checked) {
                if (!dragging) {
                    thumbPos.animateTo(
                        if (checked) 1f else 0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                }
            }

            val progress = thumbPos.value
            val draggableState = rememberDraggableState { deltaPx ->
                if (travelPx > 0f) {
                    scope.launch {
                        thumbPos.snapTo((thumbPos.value + deltaPx / travelPx).coerceIn(0f, 1f))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .offset(x = travelDp * progress)
                    .width(thumbWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.onBackground.copy(alpha = 0.2f))
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Horizontal,
                        onDragStarted = { dragging = true },
                        onDragStopped = {
                            val settled = thumbPos.value >= 0.5f
                            dragging = false
                            scope.launch {
                                thumbPos.animateTo(
                                    if (settled) 1f else 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                            if (settled != checked) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCheckedChange(settled)
                            }
                        }
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCheckedChange(!checked)
                    }
            ) {
                // Crossfade icons with the live thumb position
                val offAlpha = 1f - progress
                val onAlpha = progress
                if (offAlpha > 0f) {
                    Icon(
                        painter = rememberSymbolPainter("radio_button_unchecked"),
                        contentDescription = "Off",
                        modifier = Modifier.align(Alignment.Center).size(20.dp).graphicsLayer(alpha = offAlpha),
                        tint = colors.onBackground
                    )
                }
                if (onAlpha > 0f) {
                    Icon(
                        painter = rememberSymbolPainter("remove"),
                        contentDescription = "On",
                        modifier = Modifier.align(Alignment.Center).size(20.dp).graphicsLayer(alpha = onAlpha, rotationZ = 90f),
                        tint = colors.onBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun AppCustomFeatureRow(
    row: CustomFeatureRowSpec,
    entity: HAEntity,
    api: HomeAssistantApi,
    scope: CoroutineScope,
    colors: AppWidgetColors,
    allEntities: List<HAEntity>,
    onUpdated: () -> Unit
) {
    if (row.type == "value") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.buttonBackground.copy(alpha = 0.4f))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = resolveAppIcon(row.icon)
            if (icon != null) {
                Icon(
                    painter = icon,
                    contentDescription = row.label,
                    modifier = Modifier.size(20.dp),
                    tint = colors.headerIcon
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onBackground,
                modifier = Modifier.weight(1f)
            )
            val template = row.valueTemplate ?: ""
            val timerTicker = rememberTimerTicker(template, entity, allEntities)
            val displayValue = remember(entity, allEntities, template, timerTicker) {
                if (template.isNotEmpty()) {
                    evaluateStateTemplate(template, entity, allEntities)
                } else {
                    "--"
                }
            }
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.onBackground
            )
        }
    } else if (row.type == "switch") {
        val appState = LocalAppState.current
        val targetEntityId = row.targetEntity?.takeIf { it.isNotEmpty() } ?: entity.entity_id
        val targetEntityState = allEntities.find { it.entity_id == targetEntityId }?.state ?: entity.state
        val isChecked = targetEntityState == "on"

        AppBigPillSwitch(
            checked = isChecked,
            onCheckedChange = { _ ->
                val params = mutableMapOf<String, Any>()
                val targetsConfig = row.targets ?: emptyList()
                if (targetsConfig.isNotEmpty()) {
                    val entities = targetsConfig.filter { it["type"] == "entity" }.mapNotNull { it["value"] }
                    val devices = targetsConfig.filter { it["type"] == "device" }.mapNotNull { it["value"] }
                    val areas = targetsConfig.filter { it["type"] == "area" }.mapNotNull { it["value"] }
                    val labels = targetsConfig.filter { it["type"] == "label" }.mapNotNull { it["value"] }
                    
                    putRestTarget(params, "entity_id", entities)
                    putRestTarget(params, "device_id", devices)
                    putRestTarget(params, "area_id", areas)
                    putRestTarget(params, "label_id", labels)
                } else {
                    params["entity_id"] = targetEntityId
                }

                appState.callServiceOptimistically(
                    entity.entity_id,
                    "homeassistant",
                    "toggle",
                    params
                ) { ent ->
                    val nextState = if (ent.state == "on") "off" else "on"
                    ent.copy(state = nextState)
                }
            },
            colors = colors
        )
    } else {
        val appState = LocalAppState.current
        val context = LocalContext.current
        val themeRepository = remember(context) { ThemeRepository(context) }
        val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)

        MotionButton(
            text = row.label,
            icon = row.icon ?: "bolt",
            onClick = {
                val params = mutableMapOf<String, Any>()
                val targetsConfig = row.targets ?: emptyList()
                if (targetsConfig.isNotEmpty()) {
                    val entities = targetsConfig.filter { it["type"] == "entity" }.mapNotNull { it["value"] }
                    val devices = targetsConfig.filter { it["type"] == "device" }.mapNotNull { it["value"] }
                    val areas = targetsConfig.filter { it["type"] == "area" }.mapNotNull { it["value"] }
                    val labels = targetsConfig.filter { it["type"] == "label" }.mapNotNull { it["value"] }
                    
                    putRestTarget(params, "entity_id", entities)
                    putRestTarget(params, "device_id", devices)
                    putRestTarget(params, "area_id", areas)
                    putRestTarget(params, "label_id", labels)
                } else {
                    val targetEntityId = row.targetEntity?.takeIf { it.isNotEmpty() } ?: entity.entity_id
                    params["entity_id"] = targetEntityId
                }

                if (row.type == "toggle") {
                    val domain = "homeassistant"
                    appState.callServiceOptimistically(
                        entity.entity_id,
                        domain,
                        "toggle",
                        params
                    ) { ent ->
                        val nextState = if (ent.state == "on") "off" else "on"
                        ent.copy(state = nextState)
                    }
                } else if (row.type == "service" && row.serviceName != null) {
                    val parts = row.serviceName.split(".")
                    val sDomain = parts.getOrNull(0) ?: "homeassistant"
                    val sService = parts.getOrNull(1) ?: "toggle"
                    
                    row.serviceData?.let { params.putAll(it) }
                    appState.callServiceOptimistically(
                        entity.entity_id,
                        sDomain,
                        sService,
                        params
                    ) { ent ->
                        ent
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            fontSize = 15.sp,
            iconSize = 20.dp,
            defaultState = MotionButtonState(
                backgroundColor = colors.buttonBackground,
                contentColor = colors.onBackground,
                outlineWidth = 0.dp,
                outlineColor = Color.Transparent
            )
        )
    }
}

fun formatEntityState(entity: HAEntity): String {
    if (entity.entity_id.startsWith("timer.")) {
        return when (entity.state) {
            "active" -> {
                val finishesAt = entity.attributes["finishes_at"]?.toString()
                if (finishesAt != null) {
                    val remainingSecs = getTimerRemainingSeconds(finishesAt)
                    formatRemainingTime(remainingSecs)
                } else {
                    entity.attributes["remaining"]?.toString() ?: entity.state
                }
            }
            "paused" -> {
                entity.attributes["remaining"]?.toString() ?: "Paused"
            }
            else -> {
                entity.state.replace("_", " ").replaceFirstChar { it.uppercase() }
            }
        }
    }
    if (entity.entity_id.startsWith("binary_sensor.")) {
        val on = entity.state.equals("on", ignoreCase = true)
        val off = entity.state.equals("off", ignoreCase = true)
        if (on || off) {
            return when (entity.attributes["device_class"]?.toString()) {
                "battery" -> if (on) "Low" else "Normal"
                "cold" -> if (on) "Cold" else "Normal"
                "connectivity" -> if (on) "Connected" else "Disconnected"
                "door", "garage_door", "opening", "window" -> if (on) "Open" else "Closed"
                "gas" -> if (on) "Detected" else "Clear"
                "heat" -> if (on) "Hot" else "Normal"
                "light" -> if (on) "Light" else "No light"
                "lock" -> if (on) "Unlocked" else "Locked"
                "moisture" -> if (on) "Wet" else "Dry"
                "motion", "occupancy", "presence" -> if (on) "Detected" else "Clear"
                "plug", "power" -> if (on) "On" else "Off"
                "problem", "safety", "smoke", "tamper" -> if (on) "Problem" else "Clear"
                "sound" -> if (on) "Detected" else "Clear"
                "vibration" -> if (on) "Vibrating" else "Clear"
                else -> if (on) "On" else "Off"
            }
        }
    }
    val state = entity.state
    val formatted = state.replace("_", " ").replaceFirstChar { it.uppercase() }
    val unit = entity.attributes["unit_of_measurement"]?.toString()
    return if (unit != null) "$formatted$unit" else formatted
}

private fun resolvePlaceholderValue(
    path: String,
    currentEntity: HAEntity,
    allEntities: List<HAEntity>
): String {
    if (currentEntity.entity_id.startsWith("timer.")) {
        if (path == "remaining" || path == "attributes.remaining" || path == "current.attributes.remaining") {
            return formatEntityState(currentEntity)
        }
    }
    if (path == "state" || path == "current.state") {
        return formatEntityState(currentEntity)
    }
    if (path == "name" || path == "current.name") {
        return currentEntity.attributes["friendly_name"]?.toString() ?: currentEntity.entity_id
    }
    if (path == "attributes" || path == "current.attributes") {
        return currentEntity.attributes.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }
    if (path.startsWith("attributes.") || path.startsWith("current.attributes.")) {
        val attrKey = path.removePrefix("attributes.").removePrefix("current.attributes.")
        return currentEntity.attributes[attrKey]?.toString() ?: ""
    }
    // Check if it's a direct attribute on current entity
    if (currentEntity.attributes.containsKey(path)) {
        return currentEntity.attributes[path]?.toString() ?: ""
    }

    // Check if it matches another entity
    val matchingEntity = allEntities.find { path.startsWith(it.entity_id) }
    if (matchingEntity != null) {
        val remaining = path.removePrefix(matchingEntity.entity_id).removePrefix(".")
        if (matchingEntity.entity_id.startsWith("timer.")) {
            if (remaining == "remaining" || remaining == "attributes.remaining") {
                return formatEntityState(matchingEntity)
            }
        }
        if (remaining.isEmpty() || remaining == "state") {
            return formatEntityState(matchingEntity)
        }
        if (remaining == "name") {
            return matchingEntity.attributes["friendly_name"]?.toString() ?: matchingEntity.entity_id
        }
        if (remaining == "attributes") {
            return matchingEntity.attributes.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        }
        if (remaining.startsWith("attributes.")) {
            val attrKey = remaining.removePrefix("attributes.")
            return matchingEntity.attributes[attrKey]?.toString() ?: ""
        }
        // Direct attribute on matching entity
        if (matchingEntity.attributes.containsKey(remaining)) {
            return matchingEntity.attributes[remaining]?.toString() ?: ""
        }
        return ""
    }

    return "{$path}"
}

private fun evaluateStateTemplate(
    template: String,
    currentEntity: HAEntity,
    allEntities: List<HAEntity>
): String {
    val regex = Regex("\\{([^}]+)\\}")
    return regex.replace(template) { matchResult ->
        val path = matchResult.groupValues[1].trim()
        resolvePlaceholderValue(path, currentEntity, allEntities)
    }
}

@Composable
private fun rememberTimerTicker(
    template: String?,
    currentEntity: HAEntity,
    allEntities: List<HAEntity>
): Int {
    var ticks by remember { mutableStateOf(0) }
    
    val isCurrentTimer = currentEntity.entity_id.startsWith("timer.")
    val templateHasTimer = template != null && template.contains("timer.")
    
    val currentIsActiveTimer = isCurrentTimer && currentEntity.state == "active"
    val hasActiveTimerInEntities = allEntities.any { it.entity_id.startsWith("timer.") && it.state == "active" }
    
    val needsTicker = currentIsActiveTimer || (templateHasTimer && hasActiveTimerInEntities)
    
    if (needsTicker) {
        LaunchedEffect(needsTicker) {
            while (true) {
                delay(1000L)
                ticks++
            }
        }
    }
    return ticks
}

private fun getTimerRemainingSeconds(finishesAtStr: String): Long {
    return try {
        val finishesAt = if (finishesAtStr.endsWith("Z")) {
            Instant.parse(finishesAtStr)
        } else {
            OffsetDateTime.parse(finishesAtStr).toInstant()
        }
        val now = Instant.now()
        val diff = ChronoUnit.SECONDS.between(now, finishesAt)
        max(0L, diff)
    } catch (e: Exception) {
        0L
    }
}

private fun formatRemainingTime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, secs)
}

internal fun evaluateCondition(
    condition: Map<String, Any>,
    allEntities: List<HAEntity>,
    context: Context
): Boolean {
    val condType = condition["condition"] as? String ?: return true
    return when (condType) {
        "state" -> {
            val entityId = condition["entity"] as? String ?: return true
            val expectedState = condition["state"]
            val stateNot = condition["state_not"]
            val actualEntity = allEntities.find { it.entity_id == entityId }
            val actualState = actualEntity?.state ?: "unavailable"
            
            if (expectedState != null) {
                val expectedList = when (expectedState) {
                    is List<*> -> expectedState.map { it.toString() }
                    else -> listOf(expectedState.toString())
                }
                actualState in expectedList
            } else if (stateNot != null) {
                val notList = when (stateNot) {
                    is List<*> -> stateNot.map { it.toString() }
                    else -> listOf(stateNot.toString())
                }
                actualState !in notList
            } else {
                true
            }
        }
        "numeric_state" -> {
            val entityId = condition["entity"] as? String ?: return true
            val above = (condition["above"] as? Number)?.toDouble() ?: (condition["above"] as? String)?.toDoubleOrNull()
            val below = (condition["below"] as? Number)?.toDouble() ?: (condition["below"] as? String)?.toDoubleOrNull()
            val actualEntity = allEntities.find { it.entity_id == entityId }
            val actualVal = actualEntity?.state?.toDoubleOrNull() ?: 0.0
            
            val isAbove = above == null || actualVal > above
            val isBelow = below == null || actualVal < below
            isAbove && isBelow
        }
        "and" -> {
            val subConds = condition["conditions"] as? List<Map<String, Any>> ?: return true
            subConds.all { evaluateCondition(it, allEntities, context) }
        }
        "or" -> {
            val subConds = condition["conditions"] as? List<Map<String, Any>> ?: return true
            subConds.any { evaluateCondition(it, allEntities, context) }
        }
        "not" -> {
            val subConds = condition["conditions"] as? List<Map<String, Any>> ?: return true
            subConds.none { evaluateCondition(it, allEntities, context) }
        }
        "time" -> {
            try {
                val now = java.time.LocalTime.now()
                val afterStr = condition["after"] as? String
                val beforeStr = condition["before"] as? String
                val weekdayList = condition["weekday"] as? List<String>
                
                val afterOk = if (!afterStr.isNullOrBlank()) {
                    val afterTime = java.time.LocalTime.parse(afterStr)
                    now.isAfter(afterTime)
                } else true
                
                val beforeOk = if (!beforeStr.isNullOrBlank()) {
                    val beforeTime = java.time.LocalTime.parse(beforeStr)
                    now.isBefore(beforeTime)
                } else true
                
                val weekdayOk = if (!weekdayList.isNullOrEmpty()) {
                    val today = java.time.LocalDate.now().dayOfWeek.name.lowercase()
                    weekdayList.any { it.lowercase() == today || it.lowercase().startsWith(today.substring(0, 3)) }
                } else true
                
                afterOk && beforeOk && weekdayOk
            } catch (e: Exception) {
                true
            }
        }
        "location", "screen", "user" -> true
        else -> true
    }
}
