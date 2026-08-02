package com.feldman.ha.ui.editors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feldman.ha.data.HAEntity
import com.feldman.ha.widgets.BUTTON_ACTIONABLE_DOMAINS
import com.feldman.ha.widgets.buttonEntityAction
import com.feldman.motion.rememberSymbolPainter
import com.feldman.ha.ui.dashboard.FullScreenAddPage

/** Preset background swatches for the button card editor. null = use the theme default. */
internal val BUTTON_CARD_PRESET_COLORS: List<Long?> = listOf(
    null,
    0xFF2196F3, 0xFF4CAF50, 0xFFFF9800, 0xFFF44336,
    0xFF9C27B0, 0xFF00BCD4, 0xFF607D8B, 0xFFE91E63
)

/** Human label for the auto-derived entity action, e.g. "Toggle" / "Trigger" / "Press". */
fun actionVerb(entityId: String): String {
    if (entityId.isBlank()) return ""
    val (_, service) = buttonEntityAction(entityId.split(".")[0])
    return when (service) {
        "toggle" -> "Toggle"
        "trigger" -> "Trigger"
        "press" -> "Press"
        "turn_on" -> "Activate"
        else -> service
    }
}

/** Full-page entity picker for the button card: search + tap to select. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityPickerDialog(
    allEntities: List<HAEntity>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, allEntities) {
        val actionable = allEntities.filter { it.entity_id.split(".")[0] in BUTTON_ACTIONABLE_DOMAINS }
        if (query.isBlank()) actionable
        else actionable.filter { ent ->
            val friendly = ent.attributes["friendly_name"]?.toString() ?: ""
            friendly.contains(query, ignoreCase = true) || ent.entity_id.contains(query, ignoreCase = true)
        }
    }

    FullScreenAddPage(title = "Choose entity", onClose = onDismiss) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search entities...") },
            leadingIcon = { Icon(rememberSymbolPainter("search"), null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(rememberSymbolPainter("close"), null) }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(16.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filtered, key = { it.entity_id }) { ent ->
                val domain = ent.entity_id.split(".")[0]
                val friendly = ent.attributes["friendly_name"]?.toString() ?: ent.entity_id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onPick(ent.entity_id) }
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(friendly, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ent.entity_id, style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(domain, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = scheme.primary)
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(rememberSymbolPainter("search_off"), null,
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No matching entities", color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}
