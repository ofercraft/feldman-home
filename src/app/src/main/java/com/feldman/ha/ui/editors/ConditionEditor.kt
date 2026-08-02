package com.feldman.ha.ui.editors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.ha.data.HAEntity
import com.feldman.motion.rememberSymbolPainter

// Reusable visibility/label condition tree editor. Shared by the custom-feature page,
// the button label editor and the widget configure activity, so it lives outside all of them.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConditionEditor(
    condition: Map<String, Any>,
    allEntities: List<HAEntity>,
    onChange: (Map<String, Any>) -> Unit,
    onDelete: () -> Unit,
    level: Int = 0
) {
    val type = condition["condition"] as? String ?: "state"
    val scheme = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (level % 2 == 0) scheme.surfaceContainerHigh else scheme.surfaceContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = rememberSymbolPainter(
                        when (type) {
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
                    ),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (type) {
                        "state" -> "Entity State"
                        "numeric_state" -> "Entity Numeric State"
                        "time" -> "Time"
                        "location" -> "Location"
                        "screen" -> "Screen"
                        "user" -> "User"
                        "and" -> "Logical AND"
                        "or" -> "Logical OR"
                        "not" -> "Logical NOT"
                        else -> type.uppercase()
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                val context = LocalContext.current
                val isMet = remember(condition, allEntities) {
                    evaluateSettingsCondition(condition, allEntities, context)
                }
                val isDark = isSystemInDarkTheme()
                val successBg = if (isDark) Color(0xFF1E3520) else Color(0xFFE8F5E9)
                val successText = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                val failBg = if (isDark) Color(0xFF3C1E1E) else Color(0xFFFFEBEE)
                val failText = if (isDark) Color(0xFFE57373) else Color(0xFFC62828)

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isMet) successBg else failBg,
                    border = BorderStroke(1.dp, if (isMet) successText.copy(alpha = 0.5f) else failText.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = rememberSymbolPainter(if (isMet) "check" else "close"),
                            contentDescription = null,
                            tint = if (isMet) successText else failText,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isMet) "Satisfied" else "Unsatisfied",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMet) successText else failText
                        )
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        painter = rememberSymbolPainter("delete"),
                        contentDescription = "Delete",
                        tint = scheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            when (type) {
                "state" -> {
                    val entityId = condition["entity"] as? String ?: ""
                    val expectedState = when (val s = condition["state"]) {
                        is List<*> -> s.filterNotNull().joinToString(", ")
                        else -> s?.toString() ?: ""
                    }
                    val stateNot = when (val s = condition["state_not"]) {
                        is List<*> -> s.filterNotNull().joinToString(", ")
                        else -> s?.toString() ?: ""
                    }
                    
                    var entityQuery by remember(entityId) { mutableStateOf(entityId) }
                    var showSuggestions by remember { mutableStateOf(false) }
                    
                    OutlinedTextField(
                        value = entityQuery,
                        onValueChange = { 
                            entityQuery = it
                            onChange(condition + mapOf("entity" to it))
                            showSuggestions = true
                        },
                        label = { Text("Entity ID") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    
                    if (showSuggestions && entityQuery.isNotEmpty() && entityQuery != entityId) {
                        val matches = allEntities.filter { it.entity_id.contains(entityQuery, ignoreCase = true) }.take(3)
                        if (matches.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                matches.forEach { ent ->
                                    Text(
                                        text = ent.entity_id,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { 
                                                entityQuery = ent.entity_id
                                                onChange(condition + mapOf("entity" to ent.entity_id))
                                                showSuggestions = false
                                            }
                                            .padding(8.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    
                    val actualEntity = allEntities.find { it.entity_id == entityId }
                    if (actualEntity != null) {
                        val currentState = actualEntity.state
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Current State: $currentState (tap to copy to 'State is')",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.primary,
                            modifier = Modifier
                                .clickable {
                                    onChange(condition - "state_not" + mapOf("state" to currentState))
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                    
                    Spacer(Modifier.height(8.dp))

                    val stateOptions = remember(actualEntity?.entity_id, actualEntity?.state) {
                        possibleEntityStates(actualEntity)
                    }

                    StateValueField(
                        label = "State is",
                        placeholder = "e.g. on, off (comma separated for multiple)",
                        value = expectedState,
                        options = stateOptions,
                        onValueChange = { input ->
                            val cleanVal = if (input.contains(",")) {
                                input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            } else {
                                input
                            }
                            onChange(condition - "state_not" + mapOf("state" to cleanVal))
                        },
                        onToggleOption = { opt ->
                            val current = expectedState.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                            if (opt in current) current.remove(opt) else current.add(opt)
                            when {
                                current.isEmpty() -> onChange(condition - "state" - "state_not")
                                current.size == 1 -> onChange(condition - "state_not" + mapOf("state" to current[0]))
                                else -> onChange(condition - "state_not" + mapOf("state" to current.toList()))
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    StateValueField(
                        label = "State is NOT",
                        placeholder = "e.g. unavailable, unknown (comma separated)",
                        value = stateNot,
                        options = stateOptions,
                        onValueChange = { input ->
                            val cleanVal = if (input.contains(",")) {
                                input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            } else {
                                input
                            }
                            onChange(condition - "state" + mapOf("state_not" to cleanVal))
                        },
                        onToggleOption = { opt ->
                            val current = stateNot.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                            if (opt in current) current.remove(opt) else current.add(opt)
                            when {
                                current.isEmpty() -> onChange(condition - "state" - "state_not")
                                current.size == 1 -> onChange(condition - "state" + mapOf("state_not" to current[0]))
                                else -> onChange(condition - "state" + mapOf("state_not" to current.toList()))
                            }
                        }
                    )
                }
                "numeric_state" -> {
                    val entityId = condition["entity"] as? String ?: ""
                    val above = condition["above"]?.toString() ?: ""
                    val below = condition["below"]?.toString() ?: ""
                    
                    var entityQuery by remember(entityId) { mutableStateOf(entityId) }
                    var showSuggestions by remember { mutableStateOf(false) }
                    
                    OutlinedTextField(
                        value = entityQuery,
                        onValueChange = { 
                            entityQuery = it
                            onChange(condition + mapOf("entity" to it))
                            showSuggestions = true
                        },
                        label = { Text("Entity ID") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    
                    if (showSuggestions && entityQuery.isNotEmpty() && entityQuery != entityId) {
                        val matches = allEntities.filter { it.entity_id.contains(entityQuery, ignoreCase = true) }.take(3)
                        if (matches.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                matches.forEach { ent ->
                                    Text(
                                        text = ent.entity_id,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { 
                                                entityQuery = ent.entity_id
                                                onChange(condition + mapOf("entity" to ent.entity_id))
                                                showSuggestions = false
                                            }
                                            .padding(8.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    
                    val actualEntity = allEntities.find { it.entity_id == entityId }
                    if (actualEntity != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Current Value: ${actualEntity.state}",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = above,
                            onValueChange = { valStr ->
                                val cleanVal = valStr.toDoubleOrNull() ?: valStr
                                onChange(condition + mapOf("above" to cleanVal))
                            },
                            label = { Text("Above") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = below,
                            onValueChange = { valStr ->
                                val cleanVal = valStr.toDoubleOrNull() ?: valStr
                                onChange(condition + mapOf("below" to cleanVal))
                            },
                            label = { Text("Below") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
                "time" -> {
                    val after = condition["after"]?.toString() ?: ""
                    val before = condition["before"]?.toString() ?: ""
                    val selectedWeekdays = (condition["weekday"] as? List<*>)?.map { it.toString().lowercase() }.orEmpty()
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = after,
                            onValueChange = { onChange(condition + mapOf("after" to it)) },
                            label = { Text("After Time") },
                            placeholder = { Text("e.g. 18:00:00") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = before,
                            onValueChange = { onChange(condition + mapOf("before" to it)) },
                            label = { Text("Before Time") },
                            placeholder = { Text("e.g. 06:00:00") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    Text("Weekdays", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    val weekdaysList = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(weekdaysList.size) { idx ->
                            val day = weekdaysList[idx]
                            val isSelected = day in selectedWeekdays
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    val newList = if (isSelected) {
                                        selectedWeekdays - day
                                    } else {
                                        selectedWeekdays + day
                                    }
                                    onChange(condition + mapOf("weekday" to newList))
                                },
                                label = { Text(day.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }
                "location" -> {
                    val zone = condition["zone"]?.toString() ?: ""
                    OutlinedTextField(
                        value = zone,
                        onValueChange = { onChange(condition + mapOf("zone" to it)) },
                        label = { Text("Zone Name") },
                        placeholder = { Text("e.g. zone.home") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                "screen" -> {
                    val mediaquery = condition["mediaquery"]?.toString() ?: ""
                    OutlinedTextField(
                        value = mediaquery,
                        onValueChange = { onChange(condition + mapOf("mediaquery" to it)) },
                        label = { Text("Media Query") },
                        placeholder = { Text("e.g. min-width: 768px") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                "user" -> {
                    val userId = condition["user_id"]?.toString() ?: ""
                    OutlinedTextField(
                        value = userId,
                        onValueChange = { onChange(condition + mapOf("user_id" to it)) },
                        label = { Text("User ID") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                "and", "or", "not" -> {
                    val subConds = condition["conditions"] as? List<Map<String, Any>> ?: emptyList()
                    var showAddDropdown by remember { mutableStateOf(false) }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp)
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        subConds.forEachIndexed { idx, sub ->
                            val subId = sub["_id"] as? String ?: remember(sub) { java.util.UUID.randomUUID().toString() }
                            key(subId) {
                                ConditionEditor(
                                    condition = sub,
                                    allEntities = allEntities,
                                    onChange = { updatedSub ->
                                        val newList = subConds.toMutableList()
                                        newList[idx] = updatedSub
                                        onChange(condition + mapOf("conditions" to newList))
                                    },
                                    onDelete = {
                                        val newList = subConds.toMutableList()
                                        newList.removeAt(idx)
                                        onChange(condition + mapOf("conditions" to newList))
                                    },
                                    level = level + 1
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showAddDropdown = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(rememberSymbolPainter("add"), null)
                                Spacer(Modifier.width(4.dp))
                                Text("Add nested condition")
                            }
                            
                            DropdownMenu(
                                expanded = showAddDropdown,
                                onDismissRequest = { showAddDropdown = false }
                            ) {
                                listOf(
                                    "state" to "Entity State",
                                    "numeric_state" to "Entity Numeric State",
                                    "time" to "Time",
                                    "location" to "Location",
                                    "screen" to "Screen",
                                    "user" to "User",
                                    "and" to "Logical AND",
                                    "or" to "Logical OR",
                                    "not" to "Logical NOT"
                                ).forEach { (cType, cLabel) ->
                                    DropdownMenuItem(
                                        text = { Text(cLabel) },
                                        onClick = {
                                            val newCond = if (cType in listOf("and", "or", "not")) {
                                                mapOf(
                                                    "_id" to java.util.UUID.randomUUID().toString(),
                                                    "condition" to cType,
                                                    "conditions" to emptyList<Map<String, Any>>()
                                                )
                                            } else {
                                                mapOf(
                                                    "_id" to java.util.UUID.randomUUID().toString(),
                                                    "condition" to cType
                                                )
                                            }
                                            onChange(condition + mapOf("conditions" to (subConds + newCond)))
                                            showAddDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun addStableIdsToNested(cond: MutableMap<String, Any>) {
    if ("_id" !in cond) {
        cond["_id"] = java.util.UUID.randomUUID().toString()
    }
    val subConds = cond["conditions"] as? List<*>
    if (subConds != null) {
        val updatedSubConds = subConds.map { sub ->
            val subMap = (sub as? Map<*, *>)?.entries?.mapNotNull { entry ->
                val key = entry.key?.toString() ?: return@mapNotNull null
                val value = entry.value ?: return@mapNotNull null
                key to value
            }?.toMap()?.toMutableMap() ?: mutableMapOf()
            addStableIdsToNested(subMap)
            subMap
        }
        cond["conditions"] = updatedSubConds
    }
}

/** Plausible states for an entity: current state, attribute-provided options, domain defaults. */
private fun possibleEntityStates(entity: HAEntity?): List<String> {
    if (entity == null) return emptyList()
    val attrOptions = listOf("options", "hvac_modes", "preset_modes", "fan_modes", "swing_modes")
        .flatMap { key -> (entity.attributes[key] as? List<*>)?.map { it.toString() }.orEmpty() }
    val domainStates = when (entity.entity_id.substringBefore(".")) {
        "light", "switch", "input_boolean", "binary_sensor", "fan", "humidifier",
        "siren", "remote", "group", "automation", "script", "update" -> listOf("on", "off")
        "cover" -> listOf("open", "opening", "closed", "closing")
        "lock" -> listOf("locked", "unlocked", "locking", "unlocking", "jammed")
        "media_player" -> listOf("playing", "paused", "idle", "standby", "buffering", "on", "off")
        "alarm_control_panel" -> listOf(
            "disarmed", "armed_home", "armed_away", "armed_night",
            "armed_vacation", "arming", "disarming", "pending", "triggered"
        )
        "person", "device_tracker" -> listOf("home", "not_home")
        "vacuum" -> listOf("cleaning", "docked", "idle", "paused", "returning", "error")
        "timer" -> listOf("active", "idle", "paused")
        "sun" -> listOf("above_horizon", "below_horizon")
        else -> emptyList()
    }
    return (listOf(entity.state) + attrOptions + domainStates + listOf("unavailable", "unknown")).distinct()
}

/** State value field with a dropdown of the entity's possible states; tapping toggles membership. */
@Composable
private fun StateValueField(
    label: String,
    placeholder: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    onToggleOption: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = remember(value) { value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = {
                if (options.isNotEmpty()) {
                    IconButton(onClick = { expanded = true }) {
                        Icon(rememberSymbolPainter("arrow_drop_down"), "Show possible states")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )
        // Stays open while picking so several states can be toggled in one go.
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    leadingIcon = {
                        if (opt in selected) {
                            Icon(rememberSymbolPainter("check"), contentDescription = "Selected")
                        }
                    },
                    onClick = { onToggleOption(opt) }
                )
            }
        }
    }
}

internal fun stripStableIds(cond: Map<String, Any>): Map<String, Any> {
    val clean = cond.toMutableMap()
    clean.remove("_id")
    val subConds = clean["conditions"] as? List<*>
    if (subConds != null) {
        clean["conditions"] = subConds.map { sub ->
            val subMap = (sub as? Map<*, *>)?.entries?.mapNotNull { entry ->
                val key = entry.key?.toString() ?: return@mapNotNull null
                val value = entry.value ?: return@mapNotNull null
                key to value
            }?.toMap().orEmpty()
            stripStableIds(subMap)
        }
    }
    return clean
}

internal fun evaluateSettingsCondition(
    condition: Map<String, Any>,
    allEntities: List<HAEntity>,
    context: android.content.Context
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
            subConds.all { evaluateSettingsCondition(it, allEntities, context) }
        }
        "or" -> {
            val subConds = condition["conditions"] as? List<Map<String, Any>> ?: return true
            subConds.any { evaluateSettingsCondition(it, allEntities, context) }
        }
        "not" -> {
            val subConds = condition["conditions"] as? List<Map<String, Any>> ?: return true
            subConds.none { evaluateSettingsCondition(it, allEntities, context) }
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
