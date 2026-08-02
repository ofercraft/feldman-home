package com.feldman.ha.ui.cards

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.feldman.ha.R
import com.feldman.ha.data.CardRowPrefs
import kotlinx.coroutines.launch

data class PickerItem(val icon: Painter, val value: String, val color: Color)

@Composable
fun GenericCard(
    icon: Painter,
    title: String,
    state: String,
    modifier: Modifier = Modifier
        .width(180.dp)
        .wrapContentHeight(),
    info: String? = null,
    update: String? = null,
    showSettings: Boolean = false,
    removedRows: List<String> = emptyList(),
    // When false, the caller (e.g. FactoryCard) has already filtered and ordered the rows it
    // emits via addCustomRow, so GenericCard renders them as-is and skips its own legacy
    // rowOrderState / CardRowPrefs / removedKeys management (which otherwise fights the caller
    // and causes rows to duplicate or disappear).
    manageRows: Boolean = true,
    onSettingsDismiss: () -> Unit = {},
    onSettingsSave: (removedKeys: List<String>) -> Unit = {},
    build: @Composable GenericCardScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scopeImpl = if (manageRows) remember { GenericCardScopeImpl() } else GenericCardScopeImpl()
    scopeImpl.settings.clear()
    scopeImpl.orderSettingAdded = false
    // Legacy rows keep remembered order state; factory-managed rows are rebuilt from scratch.
    if (manageRows) {
        scopeImpl.touchedKeys.clear()
        scopeImpl.build()
        if (scopeImpl.touchedKeys.isNotEmpty()) {
            // build() ran: make rowOrder/rowMap exactly match the keys build declared.
            val live = scopeImpl.touchedKeys.toList()
            scopeImpl.rowMap.keys.retainAll(live.toSet())
            scopeImpl.rowOrder.clear()
            scopeImpl.rowOrder.addAll(live)
        }
    } else {
        // FactoryCard already applies ordering and hidden-row filtering, so the card should not
        // retain any previous row list. Rebuild the exact current row set every composition.
        scopeImpl.rowMap.clear()
        scopeImpl.rowOrder.clear()
        scopeImpl.touchedKeys.clear()
        scopeImpl.build()
    }

    val removedKeys = remember { mutableStateListOf<String>().apply { addAll(removedRows) } }
    // Persisted (user-reordered) order used only by the legacy settings reorder UI.
    var rowOrderState by remember { mutableStateOf<MutableList<String>?>(null) }

    if (manageRows) {
        LaunchedEffect(Unit) {
            val loaded = CardRowPrefs.loadRemovedRows(context, title)
            removedKeys.clear()
            removedKeys.addAll(loaded.filter { it.isNotBlank() })

            rowOrderState = mutableStateListOf<String>().apply {
                addAll(scopeImpl.rowOrder.filterNot { it in loaded || it.isBlank() }.distinct())
            }
        }
    }

    val current = scopeImpl.rowOrder.filter { it.isNotBlank() }
    // Render order. When the caller manages rows itself (manageRows == false), render exactly
    // what build() produced, in build order. Otherwise apply the legacy persisted order + the
    // CardRowPrefs-backed removed set.
    val renderOrder: List<String> = (if (!manageRows) {
        current
    } else {
        val saved = rowOrderState
        val ordered = if (saved == null) {
            current
        } else {
            saved.filter { it in current }.toMutableList().also { o ->
                current.forEach { if (it !in o) o.add(it) }
            }
        }
        ordered.filterNot { it in removedKeys }
    }).distinct()

    android.util.Log.d(
        "HA_ROW_DBG",
        "GenericCard '$title' manageRows=$manageRows buildRan=${scopeImpl.touchedKeys.isNotEmpty()} " +
            "touched=${scopeImpl.touchedKeys} rowOrder=${scopeImpl.rowOrder} renderOrder=$renderOrder"
    )

    val colorScheme = MaterialTheme.colorScheme


    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = title, tint = colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = title,
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = state, color = colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            info?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, color = colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            update?.let {
                Spacer(Modifier
                    .size(0.dp)
                    .then(Modifier.background(Color.Unspecified)))
            }

            Spacer(modifier = Modifier.height(6.dp))
            renderOrder.forEach { rowKey ->
                key(rowKey) {
                    scopeImpl.rowMap[rowKey]?.invoke(this)
                }
            }
        }
    }

    if (showSettings) {
        if (!scopeImpl.orderSettingAdded) {
            scopeImpl.settings += {
                ReorderableRowList(
                    rowOrderState = rowOrderState!!,
                    removedItems = removedKeys,
                )
            }
            scopeImpl.orderSettingAdded = true
        }

        scopeImpl.SettingsDialog(
            onDismiss = onSettingsDismiss,
            onSave = {
                scope.launch {
                    CardRowPrefs.saveRemovedRows(context, title, removedKeys.toSet())
                }
                onSettingsSave(removedKeys.toList())  // ðŸ‘ˆ pass removed
            },
            title = "Edit $title"
        )

    }
}

@Composable
fun ReorderableRowList(
    rowOrderState: MutableList<String>,
    removedItems: MutableList<String>,
    onUpdate: () -> Unit = {}
){
    val listState = rememberLazyListState()
    val draggingIndex = remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    var draggedItemOffset by remember { mutableStateOf(0f) }
    val itemHeightDp = 70.dp
    val itemHeightPx = with(density) { itemHeightDp.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp)
    ) {
        // Active Rows
        Text("Active Rows", color = colorScheme.onSurface, modifier = Modifier.padding(bottom = 4.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            itemsIndexed(rowOrderState, key = { _, item -> item }) { index, rowName ->
                val isDragging = draggingIndex.value == index
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeightDp)
                        .offset {
                            IntOffset(
                                0,
                                if (isDragging) draggedItemOffset.roundToInt() else 0
                            )
                        }
                        .zIndex(if (isDragging) 1f else 0f)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { draggingIndex.value = index },
                                onDragEnd = { draggingIndex.value = null; draggedItemOffset = 0f },
                                onDragCancel = {
                                    draggingIndex.value = null; draggedItemOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    draggedItemOffset += dragAmount.y
                                    val currentIndex =
                                        draggingIndex.value ?: return@detectDragGestures
                                    if (abs(draggedItemOffset) >= itemHeightPx) {
                                        val direction = if (draggedItemOffset > 0) 1 else -1
                                        val targetIndex = (currentIndex + direction).coerceIn(
                                            0,
                                            rowOrderState.lastIndex
                                        )
                                        if (targetIndex != currentIndex) {
                                            rowOrderState.swap(currentIndex, targetIndex)
                                            draggingIndex.value = targetIndex
                                            draggedItemOffset -= direction * itemHeightPx
                                        }
                                    }
                                    change.consume()
                                }
                            )
                        }
                        .padding(vertical = 4.dp)
                        .background(colorScheme.surfaceContainerHighest, RoundedCornerShape(itemHeightDp / 3))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // ðŸ‘‡ This Row must be inside RowScope to use .weight()
                        Row(
                            modifier = Modifier
                                .weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_drag_handle),
                                contentDescription = "Drag",
                                tint = colorScheme.primary,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(
                                text = rowName,
                                color = colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = {
                            val item = rowOrderState.removeAt(index)
                            if (!removedItems.contains(item)) {
                                removedItems.add(item)
                                onUpdate()
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete),
                                contentDescription = "Remove",
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }


                }
            }
        }

        //Removed Rows
        if (removedItems.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Removed Rows", color = colorScheme.onSurface)

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                itemsIndexed(removedItems, key = { _, item -> item }) { index, item ->
                    if(item!="")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeightDp)
                                .padding(vertical = 4.dp)
                                .background(colorScheme.surfaceVariant.copy(alpha = 0.56f), RoundedCornerShape(itemHeightDp / 3)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        removedItems.removeAt(index)
                                        rowOrderState.add(item)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.width(12.dp))
                                Text(item, color = colorScheme.onSurfaceVariant)
                            }

                            IconButton(
                                onClick = {
                                    removedItems.removeAt(index)
                                    rowOrderState.add(item)
                                },
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_add),
                                    contentDescription = "Add",
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                }
            }
        }
    }

}

private fun <T> MutableList<T>.swap(i: Int, j: Int) {
    if (i in indices && j in indices) {
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
    }
}

