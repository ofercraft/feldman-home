package com.feldman.ha.ui.editors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.feldman.motion.ReorderableColumn
import com.feldman.motion.rememberSymbolPainter

/** One row in the picker-options editor: option id, display label, and current hidden state. */
data class PickerOptionItem(val id: String, val label: String, val hidden: Boolean)

/**
 * Shared reorderable picker-options editor used by BOTH the app card editor
 * ([CustomFeatureDetailPage]) and the home-screen widget editor (FactoryConfigureActivity), so the
 * two surfaces look and behave identically. Drag the handle to reorder; tap a row (or its checkbox)
 * to show/hide that option.
 *
 * Emits a plain reorderable Column (no scaffold) so callers can drop it into a page section or an
 * overlay. The caller owns the ordering/hidden state and persists it however that surface stores it.
 */
@Composable
fun PickerOptionsEditor(
    items: List<PickerOptionItem>,
    onToggle: (optionId: String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    ReorderableColumn(
        list = items,
        onSettle = { from, to ->
            if (from != to) onMove(from, to)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onMove = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.fillMaxWidth()
    ) { _, item, isDragging, visualIndex ->
        ReorderableItem {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 100f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            scaleX = 1.03f
                            scaleY = 1.03f
                            shadowElevation = 16.dp.toPx()
                        }
                    }
                    .clip(optionRowShape(items.size, visualIndex))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onToggle(item.id) }
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .draggableHandle(
                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        rememberSymbolPainter("drag_handle"),
                        contentDescription = "Reorder",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
                Checkbox(checked = !item.hidden, onCheckedChange = { onToggle(item.id) })
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.hidden) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun optionRowShape(count: Int, visualIndex: Int): RoundedCornerShape =
    when {
        count <= 1 -> RoundedCornerShape(20.dp)
        visualIndex == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        visualIndex == count - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
        else -> RoundedCornerShape(4.dp)
    }
