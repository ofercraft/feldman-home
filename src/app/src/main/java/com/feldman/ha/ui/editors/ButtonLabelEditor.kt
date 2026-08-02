package com.feldman.ha.ui.editors

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.feldman.ha.data.HAEntity
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.rememberSymbolPainter
import com.feldman.ha.ui.cards.evaluateCondition
import com.feldman.ha.ui.cards.resolveLabelBlocks
import com.feldman.ha.ui.cards.newBlockId


/**
 * Full-page editor for a button card's label. The label is either the plain text field
 * (simple case) or, once blocks are added, a sequence of text and if/else blocks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ButtonLabelEditorPage(
    staticLabel: String,
    blocks: List<Map<String, Any>>,
    onBlocksChange: (List<Map<String, Any>>) -> Unit,
    allEntities: List<HAEntity>,
    defaultEntityId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val resolved = if (blocks.isEmpty()) "" else resolveLabelBlocks(blocks, allEntities, context)

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target < 0 || target >= blocks.size) return
        onBlocksChange(blocks.toMutableList().also { it.add(target, it.removeAt(index)) })
    }

    SettingsScaffold(
        scaffoldModifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Button label") },
                navigationIcon = {
                    FilledIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = scheme.surface,
                            contentColor = scheme.onSurface
                        )
                    ) {
                        Icon(rememberSymbolPainter("arrow_back"), "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainer)
            )
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) {
        title("Preview")
        section {
            item(padding = 16.dp) {
                Text(
                    text = resolved.ifBlank { "(empty)" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (resolved.isBlank()) scheme.outline else scheme.onSurface
                )
            }
        }

        title("Blocks")
        section {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (blocks.isNotEmpty()) {
                        Text(
                            text = "Blocks are joined in order: text blocks always show; if/else blocks show the first matching rule's text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    blocks.forEachIndexed { index, block ->
                        key(block["_id"] ?: index) {
                            LabelBlockCard(
                                block = block,
                                index = index,
                                count = blocks.size,
                                allEntities = allEntities,
                                defaultEntityId = defaultEntityId,
                                onChange = { updated ->
                                    onBlocksChange(blocks.toMutableList().also { it[index] = updated })
                                },
                                onDelete = {
                                    onBlocksChange(blocks.filterIndexed { i, _ -> i != index })
                                },
                                onMove = ::move
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onBlocksChange(blocks + mapOf(
                                    "_id" to newBlockId(),
                                    "type" to "text",
                                    // Seed the first text block from the plain label so
                                    // switching to blocks starts from what's already there.
                                    "text" to (if (blocks.isEmpty()) staticLabel else "")
                                ))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(rememberSymbolPainter("text_fields"), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Text")
                        }
                        OutlinedButton(
                            onClick = {
                                onBlocksChange(blocks + mapOf(
                                    "_id" to newBlockId(),
                                    "type" to "conditional",
                                    "rules" to listOf(
                                        mapOf(
                                            "_id" to newBlockId(),
                                            "text" to "",
                                            "conditions" to emptyList<Map<String, Any>>()
                                        )
                                    ),
                                    "else" to ""
                                ))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(rememberSymbolPainter("alt_route"), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("If / else")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(128.dp)) }
    }
}

/**
 * A labelled branch (IF / ELSE IF / ELSE / group) connected to the block-level
 * rail with a rounded horizontal line instead of another nested background.
 */
@Composable
private fun BranchSection(
    label: String,
    modifier: Modifier = Modifier,
    railColor: Color = MaterialTheme.colorScheme.outlineVariant,
    header: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Rounded elbow tapping off the spine at x=1.5dp: down to the row
            // center, round the corner, then across to the label. cx matches the
            // spine so they form one continuous (non-overlapping-looking) line.
            Canvas(
                modifier = Modifier
                    .width(24.dp)
                    .height(24.dp)
            ) {
                val w = 2.dp.toPx()
                val r = 8.dp.toPx()
                val cx = 1.5.dp.toPx()
                val cy = size.height / 2f
                val path = Path().apply {
                    moveTo(cx, 0f)
                    lineTo(cx, cy - r)
                    quadraticBezierTo(cx, cy, cx + r, cy)
                    lineTo(size.width, cy)
                }
                drawPath(
                    path = path,
                    color = railColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = w, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            header()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp)
        ) { content() }
    }
}

@Composable
private fun ConditionalBlockBody(
    railColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    var collapsed by remember { mutableStateOf(false) }

    if (collapsed) {
        // Tapping the rail collapsed the branch; offer a way back.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 5.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp).height(18.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(railColor)
            )
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(
                onClick = { collapsed = false },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(rememberSymbolPainter("unfold_more"), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Show")
            }
        }
        return
    }

    val spineX = 1.5.dp
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val x = spineX.toPx()
                    // Start above the top (up into the card's rounded bottom-left
                    // corner) so the line flows out of the corner, and run down to
                    // just shy of the bottom. Elbows tap off this same x.
                    drawLine(
                        color = railColor,
                        start = Offset(x, -12.dp.toPx()),
                        end = Offset(x, size.height - 4.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
        ) { content() }
        // Tappable strip over the spine; tapping the line collapses the branch.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(18.dp)
                .fillMaxHeight()
                .clickable { collapsed = true }
        )
    }
}

@Composable
private fun LabelBlockCard(
    block: Map<String, Any>,
    index: Int,
    count: Int,
    allEntities: List<HAEntity>,
    defaultEntityId: String,
    onChange: (Map<String, Any>) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val isConditional = (block["type"] as? String ?: "text") == "conditional"
    val latestIndex by rememberUpdatedState(index)
    val latestCount by rememberUpdatedState(count)
    val latestOnMove by rememberUpdatedState(onMove)
    val blockKey = block["_id"] ?: index
    val reorderThresholdPx = with(density) { 56.dp.toPx() }
    var dragTranslationY by remember { mutableStateOf(0f) }
    val isDragging = dragTranslationY != 0f
    val headerContainerColor = if (isConditional) scheme.primary else scheme.surfaceContainerHigh
    val headerContentColor = if (isConditional) scheme.onPrimary else scheme.primary
    // Connecting rails/branches: primary, full opacity (overlapping translucent
    // lines would compound and look brighter at the elbow junctions).
    val blockRailColor = scheme.primary

    fun moveDuringDrag(delta: Int) {
        val current = latestIndex
        if (current + delta !in 0 until latestCount) return
        latestOnMove(current, delta)
        dragTranslationY = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    translationY = dragTranslationY
                    shadowElevation = 10.dp.toPx()
                    alpha = 0.96f
                }
            }
    ) {
        if (index > 0) {
            HorizontalDivider(
                modifier = Modifier.padding(bottom = 8.dp),
                color = scheme.outlineVariant.copy(alpha = 0.6f)
            )
        }
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(headerContainerColor)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    rememberSymbolPainter(if (isConditional) "alt_route" else "text_fields"),
                    contentDescription = null,
                    tint = headerContentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (isConditional) "Conditionally execute an action" else "TEXT",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = headerContentColor,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(blockKey) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragTranslationY = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragTranslationY += amount.y
                                    when {
                                        dragTranslationY <= -reorderThresholdPx -> moveDuringDrag(-1)
                                        dragTranslationY >= reorderThresholdPx -> moveDuringDrag(1)
                                    }
                                },
                                onDragEnd = { dragTranslationY = 0f },
                                onDragCancel = { dragTranslationY = 0f }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        rememberSymbolPainter("drag_indicator"),
                        contentDescription = "Drag block",
                        tint = headerContentColor.copy(alpha = 0.78f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        rememberSymbolPainter("delete"),
                        "Remove block",
                        tint = if (isConditional) headerContentColor else scheme.error
                    )
                }
            }

            if (!isConditional) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = block["text"] as? String ?: "",
                    onValueChange = { onChange(block + ("text" to it)) },
                    placeholder = { Text("Fixed text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            } else {
                ConditionalBlockBody(railColor = blockRailColor) {
                    val rules = (block["rules"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                    rules.forEachIndexed { ri, rule ->
                        key(rule["_id"] ?: ri) {
                            ButtonLabelRuleCard(
                                rule = rule,
                                index = ri,
                                allEntities = allEntities,
                                defaultEntityId = defaultEntityId,
                                railColor = blockRailColor,
                                onChange = { updated ->
                                    onChange(block + ("rules" to rules.toMutableList().also { it[ri] = updated }))
                                },
                                onDelete = {
                                    onChange(block + ("rules" to rules.filterIndexed { i, _ -> i != ri }))
                                }
                            )
                        }
                    }
                    TextButton(onClick = {
                        onChange(block + ("rules" to rules + mapOf(
                            "_id" to newBlockId(),
                            "text" to "",
                            "conditions" to emptyList<Map<String, Any>>()
                        )))
                    }) {
                        Icon(rememberSymbolPainter("add"), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add else-if")
                    }

                    // ELSE branch: shown when no rule above matched.
                    BranchSection(label = "ELSE", railColor = blockRailColor) {
                        OutlinedTextField(
                            value = block["else"] as? String ?: "",
                            onValueChange = { onChange(block + ("else" to it)) },
                            placeholder = { Text("Text when no rule matches (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * One IF/ELSE-IF rule of a conditional block: a set of AND-ed conditions plus the text to
 * show when they match. Conditions are a draggable tree — see [ConditionTreeEditor].
 */
@Composable
private fun ButtonLabelRuleCard(
    rule: Map<String, Any>,
    index: Int,
    allEntities: List<HAEntity>,
    defaultEntityId: String,
    railColor: Color,
    onChange: (Map<String, Any>) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val conditions = (rule["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
    val text = rule["text"] as? String ?: ""
    val isActive = conditions.isNotEmpty() && conditions.all { evaluateCondition(it, allEntities, context) }

    BranchSection(
        label = if (index == 0) "IF" else "ELSE IF",
        railColor = railColor,
        header = {
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        when {
                            conditions.isEmpty() -> "No conditions"
                            isActive -> "Matching now"
                            else -> "Not matching"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(rememberSymbolPainter("delete"), "Remove rule", tint = scheme.error)
            }
        }
    ) {
        ConditionTreeEditor(
            conditions = conditions,
            onChange = { onChange(rule + ("conditions" to it)) },
            allEntities = allEntities,
            defaultEntityId = defaultEntityId
        )

        Text(
            text = "THEN show",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        OutlinedTextField(
            value = text,
            onValueChange = { onChange(rule + ("text" to it)) },
            placeholder = { Text("Text to display") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
    }
}

// ── Draggable condition tree ──────────────────────────────────────────────────
// Conditions form a tree: leaves (state/numeric_state/time) and groups (and/or/not)
// holding sub-conditions. Every node has a drag handle; long-press and drag to
// reorder, drop onto a group to move the condition inside it, or drop outside the
// group (anywhere in the list) to pull it back out.

private class ConditionDragState {
    /** "_id" of the node being dragged, or null. */
    var draggingId by mutableStateOf<String?>(null)
    var draggingPath: List<Int> = emptyList()
    var dragTranslation by mutableStateOf(Offset.Zero)
    var pointerInRoot by mutableStateOf(Offset.Zero)

    /** Group "_id" currently hovered as a drop target (null = top level). */
    var hoverGroupId by mutableStateOf<String?>(null)
    var hovering by mutableStateOf(false)

    /** Bounds (root coords) of every node card, keyed by path like "0/2". */
    val nodeBounds = mutableMapOf<String, Rect>()

    /** Bounds (root coords) of drop containers: group "_id" → bounds; "" = top level. */
    val groupBounds = mutableMapOf<String, Rect>()
    val groupDepth = mutableMapOf<String, Int>()
}

private fun pathKey(path: List<Int>) = path.joinToString("/")

private fun isSelfOrDescendant(parent: List<Int>, child: List<Int>): Boolean =
    child.size >= parent.size && child.subList(0, parent.size) == parent

private fun nodeAt(conds: List<Map<String, Any>>, path: List<Int>): Map<String, Any>? {
    var current: Map<String, Any>? = null
    var list = conds
    path.forEach { idx ->
        current = list.getOrNull(idx) ?: return null
        list = (current?.get("conditions") as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
    }
    return current
}

private fun removeNodeAt(
    conds: List<Map<String, Any>>,
    path: List<Int>
): List<Map<String, Any>> {
    val idx = path.firstOrNull() ?: return conds
    if (idx !in conds.indices) return conds
    return if (path.size == 1) {
        conds.filterIndexed { i, _ -> i != idx }
    } else {
        val child = conds[idx]
        val sub = (child["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
        conds.toMutableList().also { it[idx] = child + ("conditions" to removeNodeAt(sub, path.drop(1))) }
    }
}

/** Inserts [node] into the group with [groupId] (null = top level) at [index]. */
private fun insertIntoGroup(
    conds: List<Map<String, Any>>,
    groupId: String?,
    index: Int,
    node: Map<String, Any>
): List<Map<String, Any>> {
    if (groupId == null) {
        return conds.toMutableList().also { it.add(index.coerceIn(0, it.size), node) }
    }
    return conds.map { cond ->
        if (cond["_id"] == groupId) {
            val sub = (cond["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            cond + ("conditions" to sub.toMutableList().also { it.add(index.coerceIn(0, it.size), node) })
        } else {
            val sub = (cond["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
            if (sub != null) cond + ("conditions" to insertIntoGroup(sub, groupId, index, node)) else cond
        }
    }
}

private fun isGroupType(type: String) = type == "and" || type == "or" || type == "not"

/** Material Symbol name for a condition type (shared by the row and the editor). */
private fun conditionIcon(type: String): String = when (type) {
    "state" -> "adjust"
    "numeric_state" -> "pin"
    "time" -> "schedule"
    "location" -> "my_location"
    "screen" -> "desktop_windows"
    "user" -> "person"
    "and" -> "join_inner"
    "or" -> "join_outer"
    "not" -> "close"
    else -> "help"
}

/** Short human label for the condition type, shown as the row subtitle. */
private fun conditionTypeLabel(type: String): String = when (type) {
    "state" -> "Entity state"
    "numeric_state" -> "Numeric state"
    "time" -> "Time"
    "location" -> "Location"
    "screen" -> "Screen"
    "user" -> "User"
    "and" -> "All of"
    "or" -> "Any of"
    "not" -> "None of"
    else -> type
}

/** A readable one-line name for a leaf condition, e.g. "Battery level < 20". */
internal fun conditionSummary(cond: Map<String, Any>, allEntities: List<HAEntity>): String {
    fun fname(id: String?): String {
        if (id.isNullOrBlank()) return "Entity"
        return allEntities.find { it.entity_id == id }?.attributes?.get("friendly_name")?.toString() ?: id
    }
    fun listOrScalar(v: Any?): String? = when (v) {
        null -> null
        is List<*> -> v.filterNotNull().joinToString(", ").ifBlank { null }
        else -> v.toString().ifBlank { null }
    }
    return when (cond["condition"] as? String ?: "state") {
        "state" -> {
            val e = fname(cond["entity"] as? String)
            val st = listOrScalar(cond["state"])
            val snot = listOrScalar(cond["state_not"])
            when {
                st != null -> "$e is $st"
                snot != null -> "$e is not $snot"
                else -> "$e state"
            }
        }
        "numeric_state" -> {
            val e = fname(cond["entity"] as? String)
            val above = listOrScalar(cond["above"])
            val below = listOrScalar(cond["below"])
            when {
                above != null && below != null -> "$e between $above and $below"
                above != null -> "$e > $above"
                below != null -> "$e < $below"
                else -> "$e value"
            }
        }
        "time" -> {
            val after = listOrScalar(cond["after"])
            val before = listOrScalar(cond["before"])
            when {
                after != null && before != null -> "Time $after–$before"
                after != null -> "Time after $after"
                before != null -> "Time before $before"
                else -> "Time"
            }
        }
        "location" -> "Location"
        "screen" -> "Screen"
        "user" -> "User"
        else -> "Condition"
    }
}

/**
 * A leaf condition shown as a compact, tappable row: icon + readable name + a
 * live satisfied/unsatisfied dot. Tapping opens a bottom sheet hosting the full
 * [ConditionEditor], so the tree stays scannable while editing happens on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeafConditionRow(
    condition: Map<String, Any>,
    allEntities: List<HAEntity>,
    onChange: (Map<String, Any>) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val type = condition["condition"] as? String ?: "state"
    var showSheet by remember { mutableStateOf(false) }
    val isMet = remember(condition, allEntities) { evaluateCondition(condition, allEntities, context) }
    val dotColor = if (isMet) Color(0xFF4CAF50) else scheme.error

    Surface(
        onClick = { showSheet = true },
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                rememberSymbolPainter(conditionIcon(type)),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conditionSummary(condition, allEntities),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = scheme.onSurface
                )
                Text(
                    text = conditionTypeLabel(type),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                rememberSymbolPainter("chevron_right"),
                contentDescription = "Edit",
                tint = scheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Edit condition",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ConditionEditor(
                    condition = condition,
                    allEntities = allEntities,
                    onChange = onChange,
                    onDelete = {
                        onDelete()
                        showSheet = false
                    }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Done")
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ConditionTreeEditor(
    conditions: List<Map<String, Any>>,
    onChange: (List<Map<String, Any>>) -> Unit,
    allEntities: List<HAEntity>,
    defaultEntityId: String
) {
    val dragState = remember { ConditionDragState() }
    val currentConditions by rememberUpdatedState(conditions)

    fun updateAt(path: List<Int>, updated: Map<String, Any>) {
        fun rec(list: List<Map<String, Any>>, p: List<Int>): List<Map<String, Any>> {
            val idx = p.first()
            if (idx !in list.indices) return list
            return list.toMutableList().also {
                it[idx] = if (p.size == 1) updated else {
                    val child = it[idx]
                    val sub = (child["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                    child + ("conditions" to rec(sub, p.drop(1)))
                }
            }
        }
        onChange(rec(currentConditions, path))
    }

    fun deleteAt(path: List<Int>) = onChange(removeNodeAt(currentConditions, path))

    fun resolveDropTarget(): Pair<String?, Boolean> {
        // Deepest container under the pointer wins; the top-level list ("") is depth 0.
        val pointer = dragState.pointerInRoot
        var best: String? = null
        var bestDepth = -1
        var found = false
        dragState.groupBounds.forEach { (id, rect) ->
            if (!rect.contains(pointer)) return@forEach
            val depth = dragState.groupDepth[id] ?: 0
            if (depth > bestDepth) {
                bestDepth = depth
                best = id
                found = true
            }
        }
        return (best?.takeIf { it.isNotEmpty() }) to found
    }

    fun finishDrag() {
        val dragged = dragState.draggingPath
        val draggedNode = nodeAt(currentConditions, dragged)
        val (targetGroupId, found) = resolveDropTarget()
        if (draggedNode != null && found) {
            // Refuse drops into the dragged node itself or its own subtree.
            val intoSelf = targetGroupId != null && run {
                fun contains(node: Map<String, Any>): Boolean {
                    if (node["_id"] == targetGroupId) return true
                    val sub = (node["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                    return sub.any { contains(it) }
                }
                contains(draggedNode)
            }
            if (!intoSelf) {
                // Insertion index: count remaining (non-dragged) siblings of the target
                // container whose card center sits above the pointer. Bounds entries are
                // validated against the current tree to ignore stale paths.
                val pointerY = dragState.pointerInRoot.y
                var insertIndex = 0
                dragState.nodeBounds.forEach { (key, bounds) ->
                    val path = key.split("/").mapNotNull { it.toIntOrNull() }
                    if (path.isEmpty() || nodeAt(currentConditions, path) == null) return@forEach
                    if (isSelfOrDescendant(dragged, path)) return@forEach
                    val parentPath = path.dropLast(1)
                    val parentId = if (parentPath.isEmpty()) null
                        else nodeAt(currentConditions, parentPath)?.get("_id") as? String
                    if (parentId != targetGroupId) return@forEach
                    if (pointerY > bounds.center.y) insertIndex++
                }
                val without = removeNodeAt(currentConditions, dragged)
                onChange(insertIntoGroup(without, targetGroupId, insertIndex, draggedNode))
            }
        }
        dragState.draggingId = null
        dragState.draggingPath = emptyList()
        dragState.dragTranslation = Offset.Zero
        dragState.hoverGroupId = null
        dragState.hovering = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                dragState.groupBounds[""] = it.boundsInRoot()
                dragState.groupDepth[""] = 0
            }
    ) {
        if (conditions.isEmpty()) {
            Text(
                text = "No conditions yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        conditions.forEachIndexed { index, cond ->
            key(cond["_id"] ?: index) {
                ConditionTreeNode(
                    condition = cond,
                    path = listOf(index),
                    depth = 1,
                    dragState = dragState,
                    allEntities = allEntities,
                    defaultEntityId = defaultEntityId,
                    onUpdate = ::updateAt,
                    onDelete = ::deleteAt,
                    onDragEnd = ::finishDrag
                )
            }
        }
        AddConditionButton(
            label = if (conditions.isEmpty()) "Add condition" else "Add condition (AND)",
            defaultEntityId = defaultEntityId,
            onAdd = { onChange(currentConditions + it) }
        )
    }
}

@Composable
private fun ConditionTreeNode(
    condition: Map<String, Any>,
    path: List<Int>,
    depth: Int,
    dragState: ConditionDragState,
    allEntities: List<HAEntity>,
    defaultEntityId: String,
    onUpdate: (List<Int>, Map<String, Any>) -> Unit,
    onDelete: (List<Int>) -> Unit,
    onDragEnd: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val type = condition["condition"] as? String ?: "state"
    val id = condition["_id"] as? String
    val key = pathKey(path)
    val isDragging = dragState.draggingId != null && dragState.draggingId == id
    var handleOrigin by remember { mutableStateOf(Offset.Zero) }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 0f)
            .onGloballyPositioned { dragState.nodeBounds[key] = it.boundsInRoot() }
            .graphicsLayer {
                if (isDragging) {
                    translationX = dragState.dragTranslation.x
                    translationY = dragState.dragTranslation.y
                    shadowElevation = 16.dp.toPx()
                    alpha = 0.92f
                }
            }
    ) {
        Box(
            modifier = Modifier
                .padding(top = 14.dp)
                .size(28.dp)
                .onGloballyPositioned { handleOrigin = it.boundsInRoot().topLeft }
                .pointerInput(key, id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragState.draggingId = id
                            dragState.draggingPath = path
                            dragState.dragTranslation = Offset.Zero
                            dragState.pointerInRoot = handleOrigin + offset
                            dragState.hovering = true
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragState.dragTranslation += amount
                            dragState.pointerInRoot = handleOrigin + change.position + dragState.dragTranslation
                            // Live hover highlight: deepest group under the pointer.
                            var best: String? = null
                            var bestDepth = -1
                            dragState.groupBounds.forEach { (gid, rect) ->
                                if (rect.contains(dragState.pointerInRoot)) {
                                    val d = dragState.groupDepth[gid] ?: 0
                                    if (d > bestDepth) { bestDepth = d; best = gid }
                                }
                            }
                            dragState.hoverGroupId = best?.takeIf { it.isNotEmpty() }
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                rememberSymbolPainter("drag_indicator"),
                contentDescription = "Drag condition",
                tint = scheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (isGroupType(type)) {
                GroupConditionCard(
                    condition = condition,
                    path = path,
                    depth = depth,
                    dragState = dragState,
                    allEntities = allEntities,
                    defaultEntityId = defaultEntityId,
                    onUpdate = onUpdate,
                    onDelete = onDelete,
                    onDragEnd = onDragEnd
                )
            } else {
                LeafConditionRow(
                    condition = condition,
                    allEntities = allEntities,
                    onChange = { updated -> onUpdate(path, updated) },
                    onDelete = { onDelete(path) }
                )
            }
        }
    }
}

@Composable
private fun GroupConditionCard(
    condition: Map<String, Any>,
    path: List<Int>,
    depth: Int,
    dragState: ConditionDragState,
    allEntities: List<HAEntity>,
    defaultEntityId: String,
    onUpdate: (List<Int>, Map<String, Any>) -> Unit,
    onDelete: (List<Int>) -> Unit,
    onDragEnd: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val type = condition["condition"] as? String ?: "and"
    val id = condition["_id"] as? String ?: pathKey(path)
    val subConditions = (condition["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
    val isHovered = dragState.hoverGroupId == id && dragState.draggingId != null
    val isMet = remember(condition, allEntities) { evaluateCondition(condition, allEntities, context) }
    // Branch rail instead of a nested card: a vertical line marks the group's
    // children by indentation, so deep nesting stays readable. The rail lights
    // up when it's a hover drop target.
    val railColor = scheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onGloballyPositioned {
                dragState.groupBounds[id] = it.boundsInRoot()
                dragState.groupDepth[id] = depth
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                rememberSymbolPainter(
                    when (type) {
                        "or" -> "join_outer"
                        "not" -> "close"
                        else -> "join_inner"
                    }
                ),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when (type) {
                    "or" -> "ANY OF (OR)"
                    "not" -> "NONE OF (NOT)"
                    else -> "ALL OF (AND)"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        if (isMet) "Satisfied" else "Unsatisfied",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
            IconButton(onClick = { onDelete(path) }) {
                Icon(rememberSymbolPainter("delete"), "Delete group", tint = scheme.error, modifier = Modifier.size(18.dp))
            }
        }

        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .padding(start = 9.dp, top = 2.dp, bottom = 2.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(railColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (subConditions.isEmpty()) {
                    Text(
                        text = "Empty group — drag a condition here or add one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.outline,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                subConditions.forEachIndexed { index, sub ->
                    key(sub["_id"] ?: index) {
                        ConditionTreeNode(
                            condition = sub,
                            path = path + index,
                            depth = depth + 1,
                            dragState = dragState,
                            allEntities = allEntities,
                            defaultEntityId = defaultEntityId,
                            onUpdate = onUpdate,
                            onDelete = onDelete,
                            onDragEnd = onDragEnd
                        )
                    }
                }
                AddConditionButton(
                    label = "Add to group",
                    defaultEntityId = defaultEntityId,
                    onAdd = { newCond ->
                        onUpdate(path, condition + ("conditions" to subConditions + newCond))
                    }
                )
            }
        }
    }
}

@Composable
private fun AddConditionButton(
    label: String,
    defaultEntityId: String,
    onAdd: (Map<String, Any>) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(rememberSymbolPainter("add"), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                "state" to "Entity state",
                "numeric_state" to "Numeric state",
                "time" to "Time",
                "and" to "Group: ALL OF (AND)",
                "or" to "Group: ANY OF (OR)",
                "not" to "Group: NONE OF (NOT)"
            ).forEach { (cType, cLabel) ->
                DropdownMenuItem(
                    text = { Text(cLabel) },
                    onClick = {
                        val newCond = mutableMapOf<String, Any>(
                            "_id" to newBlockId(),
                            "condition" to cType
                        )
                        if (isGroupType(cType)) {
                            newCond["conditions"] = emptyList<Map<String, Any>>()
                        } else if (cType != "time" && defaultEntityId.isNotBlank()) {
                            newCond["entity"] = defaultEntityId
                        }
                        onAdd(newCond)
                        expanded = false
                    }
                )
            }
        }
    }
}
