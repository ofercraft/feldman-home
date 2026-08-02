package com.feldman.ha.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.feldman.ha.ui.cards.CARD_CORNER_RADIUS

// ── Grid geometry ─────────────────────────────────────────────────────────────

/** A cell position on the dashboard grid (column, row). */
data class GridPos(val x: Int, val y: Int)

/**
 * Unclipped rect of a node in root coordinates. boundsInRoot() must NOT be used for
 * drag math: it clips to ancestors, so once the grid scrolls partially off-screen its
 * "top" gets clamped to the viewport edge and drop targets go wrong.
 */
private fun LayoutCoordinates.rectInRoot(): Rect {
    val pos = positionInRoot()
    return Rect(pos, Size(size.width.toFloat(), size.height.toFloat()))
}

private data class GridParentData(val x: Int, val y: Int, val w: Int, val h: Int)

private fun Modifier.gridCell(x: Int, y: Int, w: Int, h: Int): Modifier = this.then(
    object : ParentDataModifier {
        override fun Density.modifyParentData(parentData: Any?) = GridParentData(x, y, w, h)
    }
)

// ── Layout resolution ─────────────────────────────────────────────────────────

internal data class GridSpec(
    val key: Any,
    val w: Int,
    val h: Int,
    val pos: GridPos?,        // requested cell; null = auto-place (new card)
    val pinned: Boolean = false, // dragged card: stays exactly at pos
)

private class Placed(
    val key: Any,
    var x: Int,
    var y: Int,
    val w: Int,
    val h: Int,
    val pinned: Boolean,
) {
    fun overlaps(ox: Int, oy: Int, ow: Int, oh: Int): Boolean =
        x < ox + ow && ox < x + w && y < oy + oh && oy < y + h

    fun overlaps(o: Placed): Boolean = overlaps(o.x, o.y, o.w, o.h)
}

/**
 * Launcher-style placement: cards live at explicit cells instead of flowing in
 * order, so ANY visual arrangement is reachable (e.g. a narrow card to the left of
 * a wide one, even with free space in the row above — flow layouts can't express
 * that, the narrow card gets pulled up).
 *
 * 1. Cards keep their requested cells; overlaps are pushed straight down,
 *    top-to-bottom (the pinned/dragged card never moves).
 * 2. Everything compacts upward through free space, keeping its column.
 * 3. Cards without a position (new ones) take the first free spot.
 */
internal fun resolveGridLayout(specs: List<GridSpec>, columns: Int): LinkedHashMap<Any, GridPos> {
    val positioned = ArrayList<Placed>(specs.size)
    val pending = ArrayList<Placed>()
    specs.forEach { s ->
        val w = s.w.coerceIn(1, columns)
        val h = s.h.coerceAtLeast(1)
        if (s.pos != null) {
            positioned += Placed(s.key, s.pos.x.coerceIn(0, columns - w), s.pos.y.coerceAtLeast(0), w, h, s.pinned)
        } else {
            pending += Placed(s.key, 0, 0, w, h, false)
        }
    }

    // Pinned card first so nothing can displace it, then reading order (stable sort
    // keeps the items' relative order for ties).
    positioned.sortWith(compareBy({ !it.pinned }, { it.y }, { it.x }))

    // 1) Resolve collisions.
    val resolved = ArrayList<Placed>(positioned.size)
    positioned.forEach { p ->
        if (!p.pinned) {
            if (resolved.any { it.overlaps(p) }) {
                // Collides! First try to shift horizontally on the same row.
                var shifted = false
                for (x in 0..(columns - p.w)) {
                    if (resolved.none { it.overlaps(x, p.y, p.w, p.h) }) {
                        p.x = x
                        shifted = true
                        break
                    }
                }
                if (!shifted) {
                    // If we couldn't shift horizontally on the same row, push straight down in the original column.
                    var y = p.y + 1
                    while (resolved.any { it.overlaps(p.x, y, p.w, p.h) }) {
                        y++
                    }
                    p.y = y
                }
            }
        }
        resolved += p
    }

    // 2) Compact upward, keeping each card's column.
    resolved.sortWith(compareBy({ it.y }, { it.x }))
    val compacted = ArrayList<Placed>(resolved.size)
    resolved.forEach { p ->
        while (p.y > 0 && compacted.none { it.overlaps(p.x, p.y - 1, p.w, p.h) }) p.y--
        compacted += p
    }

    // 3) First-fit placement for new cards.
    pending.forEach { p ->
        var y = 0
        outer@ while (true) {
            for (x in 0..(columns - p.w)) {
                if (compacted.none { it.overlaps(x, y, p.w, p.h) }) {
                    p.x = x
                    p.y = y
                    break@outer
                }
            }
            y++
        }
        compacted += p
    }

    val result = LinkedHashMap<Any, GridPos>(compacted.size)
    compacted.forEach { result[it.key] = GridPos(it.x, it.y) }
    return result
}

/** Positions children at explicit grid cells. Non-lazy (lazy grids crash under Navigation 3). */
@Composable
private fun DashboardGrid(
    columns: Int,
    rowHeight: Dp,
    gap: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.toPx()
        val bottomPaddingPx = bottomPadding.toPx()
        val rowHeightPx = rowHeight.toPx()
        val width = constraints.maxWidth
        val colWidth = (width - (columns - 1) * gapPx) / columns
        val cellW = colWidth + gapPx
        val cellH = rowHeightPx + gapPx

        val data = measurables.map { (it.parentData as? GridParentData) ?: GridParentData(0, 0, 2, 1) }
        val placeables = measurables.mapIndexed { i, m ->
            val d = data[i]
            m.measure(
                Constraints.fixed(
                    (d.w * colWidth + (d.w - 1) * gapPx).roundToInt(),
                    (d.h * rowHeightPx + (d.h - 1) * gapPx).roundToInt()
                )
            )
        }
        var totalHeight = 0
        val positions = data.mapIndexed { i, d ->
            val pos = IntOffset((d.x * cellW).roundToInt(), (d.y * cellH).roundToInt())
            val bottom = pos.y + placeables[i].height
            if (bottom > totalHeight) totalHeight = bottom
            pos
        }
        layout(width, totalHeight + bottomPaddingPx.roundToInt()) {
            placeables.forEachIndexed { i, p -> p.placeRelative(positions[i].x, positions[i].y) }
        }
    }
}

// ── Drag gesture ──────────────────────────────────────────────────────────────

/**
 * Long-press drag detector for cards that contain their own interactive children
 * (sliders, buttons, scrollable rows). detectDragGesturesAfterLongPress can't be used
 * here: children see pointer events before the parent, so the first finger movement
 * gets consumed by a slider inside the card and the drag is cancelled instantly.
 * Instead, once the long press fires, this claims the gesture by reading and consuming
 * all further events in the Initial pass — before any child or the page scroll sees them.
 */
private suspend fun PointerInputScope.detectLongPressCardDrag(
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        onDragStart(longPress.position)
        val pointerId = longPress.id
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId }
            if (change == null) {
                onDragCancel()
                break
            }
            if (!change.pressed) {
                // Consume the up so child clickables don't treat it as a tap.
                change.consume()
                onDragEnd()
                break
            }
            val currentPos = change.position
            change.consume()
            onDrag(currentPos)
        }
    }
}

// ── Reorderable grid ──────────────────────────────────────────────────────────

/**
 * A launcher-style dashboard grid: long-press lifts a card, the drop cell tracks the
 * finger exactly, colliding cards are pushed out of the way live, everything
 * compacts upward, and the page auto-scrolls near the screen edges.
 *
 * [savedPosition] supplies each card's committed cell (null = new card, auto-placed).
 * [onCommitLayout] fires once on drop with the full resolved layout — persist it there.
 */
@Composable
fun <T> ReorderableDashboardGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    itemSpanX: (T) -> Int,
    itemSpanY: (T) -> Int,
    savedPosition: (T) -> GridPos?,
    columns: Int,
    rowHeight: Dp,
    gap: Dp,
    bottomPadding: Dp = 0.dp,
    dragEnabled: Boolean,
    scrollState: ScrollState,
    onCommitLayout: (Map<Any, GridPos>) -> Unit,
    modifier: Modifier = Modifier,
    onDragStarted: () -> Unit = {},
    itemContent: @Composable (item: T, isDragging: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    val haptics = LocalHapticFeedback.current
    val gapPx = with(density) { gap.toPx() }
    val rowHeightPx = with(density) { rowHeight.toPx() }

    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var settlingKey by remember { mutableStateOf<Any?>(null) }
    // The dragged card's current target cell, following the finger.
    var dragTarget by remember { mutableStateOf<GridPos?>(null) }
    // Resting layout captured at drag start: per-frame resolution works from this
    // stable base so cards don't drift as they're pushed around.
    var dragSnapshot by remember { mutableStateOf<Map<Any, GridPos>?>(null) }
    // Lowest row the drag target may take (one slot below everything else).
    var dragMaxY by remember { mutableStateOf(0) }
    // Finger position in root coords, accumulated from drag deltas (deltas are
    // translation-invariant, so this stays correct while the dragged slot moves).
    var fingerRoot by remember { mutableStateOf(Offset.Zero) }
    // Where inside the card the finger grabbed it, so the card doesn't jump on pickup.
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var gridRect by remember { mutableStateOf(Rect.Zero) }
    val slotRects = remember { mutableStateMapOf<Any, Rect>() }
    val settleOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    // Resolve the visible layout. While dragging: the dragged card is pinned to its
    // target cell and the others resolve around it from the drag-start snapshot.
    val specs = items.map { item ->
        val k = itemKey(item)
        GridSpec(
            key = k,
            w = itemSpanX(item),
            h = itemSpanY(item),
            pos = when {
                draggingKey == k -> dragTarget ?: savedPosition(item)
                draggingKey != null -> dragSnapshot?.get(k) ?: savedPosition(item)
                else -> savedPosition(item)
            },
            pinned = draggingKey == k && dragTarget != null
        )
    }
    val layoutMap = resolveGridLayout(specs, columns)

    // The gesture callbacks below live inside pointerInput blocks that don't restart on
    // recomposition, so everything they touch is routed through rememberUpdatedState.
    val latestSpecs by rememberUpdatedState(specs)
    val latestLayout by rememberUpdatedState(layoutMap)
    val latestOnCommit by rememberUpdatedState(onCommitLayout)
    val latestOnDragStarted by rememberUpdatedState(onDragStarted)

    val updateTarget: () -> Unit = update@{
        val k = draggingKey ?: return@update
        val grid = gridRect
        if (grid.width <= 0f) return@update
        val spec = latestSpecs.firstOrNull { it.key == k } ?: return@update
        val colWidth = (grid.width - (columns - 1) * gapPx) / columns
        val cellW = colWidth + gapPx
        val cellH = rowHeightPx + gapPx
        val cardTopLeft = fingerRoot - grabOffset - grid.topLeft
        val targetX = (cardTopLeft.x / cellW).roundToInt().coerceIn(0, (columns - spec.w).coerceAtLeast(0))
        val rawY = cardTopLeft.y / cellH
        var targetY = rawY.roundToInt().coerceIn(0, dragMaxY)

        val snapshot = dragSnapshot
        if (snapshot != null) {
            val overlapped = latestSpecs
                .filter { it.key != k }
                .mapNotNull { s ->
                    val pos = snapshot[s.key] ?: return@mapNotNull null
                    if (targetX < pos.x + s.w && pos.x < targetX + spec.w) {
                        s to pos
                    } else {
                        null
                    }
                }
            for ((otherSpec, otherPos) in overlapped) {
                val top = otherPos.y
                val bottom = otherPos.y + otherSpec.h
                if (targetY > top && targetY < bottom) {
                    targetY = if (rawY < top + otherSpec.h / 2.0f) {
                        top
                    } else {
                        bottom
                    }
                }
            }
            targetY = targetY.coerceIn(0, dragMaxY)
        }

        val target = GridPos(targetX, targetY)
        if (target != dragTarget) dragTarget = target
    }
    val doUpdateTarget by rememberUpdatedState(updateTarget)

    val beginDrag: (Any, Offset) -> Unit = { k, local ->
        latestOnDragStarted()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        grabOffset = local
        fingerRoot = (slotRects[k]?.topLeft ?: Offset.Zero) + local
        val snapshot = latestLayout
        dragSnapshot = snapshot
        dragMaxY = latestSpecs
            .filter { it.key != k }
            .maxOfOrNull { (snapshot[it.key]?.y ?: 0) + it.h }
            ?: 0
        draggingKey = k
        dragTarget = snapshot[k]
    }
    val doBeginDrag by rememberUpdatedState(beginDrag)

    val settle: () -> Unit = settle@{
        val k = draggingKey ?: return@settle
        draggingKey = null
        val slotTopLeft = slotRects[k]?.topLeft
        if (slotTopLeft != null) {
            val from = fingerRoot - grabOffset - slotTopLeft
            settlingKey = k
            scope.launch {
                settleOffset.snapTo(from)
                settleOffset.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMediumLow))
                settlingKey = null
            }
        }
        // Commit the resting layout: same cells (mapping to their resolved layout positions from the last active frame of the drag),
        // but with the dropped card unpinned so it also compacts upward into any free space it was hovering over.
        val finalSpecs = latestSpecs.map { spec ->
            spec.copy(
                pos = latestLayout[spec.key] ?: spec.pos,
                pinned = false
            )
        }
        latestOnCommit(resolveGridLayout(finalSpecs, columns))
        dragTarget = null
        dragSnapshot = null
    }
    val doSettle by rememberUpdatedState(settle)

    // Auto-scroll the page while the finger is held near the top/bottom screen edge.
    LaunchedEffect(draggingKey) {
        if (draggingKey == null) return@LaunchedEffect
        val edgePx = with(density) { 140.dp.toPx() }
        val maxStepPx = with(density) { 18.dp.toPx() }
        while (draggingKey != null) {
            withFrameNanos { }
            val viewportH = windowInfo.containerSize.height.toFloat()
            if (viewportH <= 0f) continue
            val y = fingerRoot.y
            val step = when {
                y < edgePx -> -maxStepPx * (1f - y / edgePx).coerceIn(0f, 1f)
                y > viewportH - edgePx -> maxStepPx * (1f - (viewportH - y) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (step != 0f && scrollState.scrollBy(step) != 0f) doUpdateTarget()
        }
    }

    DashboardGrid(
        columns = columns,
        rowHeight = rowHeight,
        gap = gap,
        bottomPadding = bottomPadding,
        modifier = modifier
            // Float the whole grid above siblings while a card is in flight.
            .zIndex(if (draggingKey != null || settlingKey != null) 1f else 0f)
            .onGloballyPositioned { gridRect = it.rectInRoot() }
    ) {
        items.forEachIndexed { index, item ->
            val k = itemKey(item)
            key(k) {
                val isDragged = draggingKey == k
                val isSettling = settlingKey == k
                val pos = layoutMap[k] ?: GridPos(0, 0)
                val spec = specs.getOrNull(index)

                DisposableEffect(k) {
                    onDispose { slotRects.remove(k) }
                }

                Box(
                    modifier = Modifier
                        .gridCell(pos.x, pos.y, spec?.w ?: 2, spec?.h ?: 1)
                        .zIndex(if (isDragged) 2f else if (isSettling) 1f else 0f)
                        .animateGridPlacement(animate = !isDragged)
                        .onGloballyPositioned { slotRects[k] = it.rectInRoot() }
                        .pointerInput(dragEnabled) {
                            if (!dragEnabled) return@pointerInput
                            detectLongPressCardDrag(
                                onDragStart = { local -> doBeginDrag(k, local) },
                                onDrag = { local ->
                                    val slotTopLeft = slotRects[k]?.topLeft ?: Offset.Zero
                                    fingerRoot = slotTopLeft + local
                                    doUpdateTarget()
                                },
                                onDragEnd = { doSettle() },
                                onDragCancel = { doSettle() }
                            )
                        }
                ) {
                    if (isDragged) {
                        DropSlotGhost(Modifier.fillMaxSize(), MaterialTheme.colorScheme.primary)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (isDragged) {
                                    val slotTopLeft = slotRects[k]?.topLeft ?: Offset.Zero
                                    val t = fingerRoot - grabOffset - slotTopLeft
                                    translationX = t.x
                                    translationY = t.y
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                    shadowElevation = 16.dp.toPx()
                                    shape = RoundedCornerShape(CARD_CORNER_RADIUS)
                                } else if (isSettling) {
                                    val t = settleOffset.value
                                    translationX = t.x
                                    translationY = t.y
                                    shadowElevation = 8.dp.toPx()
                                    shape = RoundedCornerShape(CARD_CORNER_RADIUS)
                                }
                            }
                    ) {
                        itemContent(item, isDragged)
                    }
                }
            }
        }
    }
}

/**
 * Springs a card to its new slot whenever layout resolution changes its position.
 * Disabled for the card being dragged (its visuals follow the finger instead).
 */
@Composable
private fun Modifier.animateGridPlacement(animate: Boolean): Modifier {
    val scope = rememberCoroutineScope()
    var targetOffset by remember { mutableStateOf(IntOffset.Zero) }
    var anim by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    val animateNow by rememberUpdatedState(animate)
    return this
        .onPlaced {
            val target = it.positionInParent().round()
            targetOffset = target
            // Seed at the first real position so cards don't slide in from the origin.
            if (anim == null) anim = Animatable(target, IntOffset.VectorConverter)
        }
        .offset {
            val a = anim ?: return@offset IntOffset.Zero
            if (a.targetValue != targetOffset) {
                val target = targetOffset
                scope.launch {
                    if (animateNow) a.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                    else a.snapTo(target)
                }
            }
            a.value - targetOffset
        }
}

/** Dashed rounded-rectangle ghost shown in the dragged card's target cell. */
@Composable
private fun DropSlotGhost(modifier: Modifier = Modifier, color: Color) {
    val scheme = MaterialTheme.colorScheme
    val ghostColor = color.copy(alpha = 0.45f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_CORNER_RADIUS))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.35f))
            .drawBehind {
                val stroke = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(12.dp.toPx(), 8.dp.toPx()), 0f
                    )
                )
                drawRoundRect(
                    color = ghostColor,
                    style = stroke,
                    cornerRadius = CornerRadius(24.dp.toPx())
                )
            }
    )
}
