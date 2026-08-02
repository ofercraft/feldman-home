package com.feldman.ha.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.ha.data.HAEntity
import com.feldman.ha.ui.navigation.AppState
import com.feldman.ha.widgets.ROW_STYLE_TOGGLE
import com.feldman.ha.widgets.orderPickerOptions
import com.feldman.ha.widgets.pickerOptionKey
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.rememberSymbolPainter
import kotlin.math.roundToInt
import com.feldman.ha.ui.cards.pickerOptionsForEntity
import com.feldman.ha.ui.editors.ConditionEditor
import com.feldman.ha.ui.editors.addStableIdsToNested
import com.feldman.ha.ui.editors.evaluateSettingsCondition
import com.feldman.ha.ui.editors.stripStableIds
import com.feldman.ha.ui.editors.ConfigureCardSaveDock
import com.feldman.ha.ui.editors.IconPickerDialog
import com.feldman.ha.ui.editors.POPULAR_SERVICES
import com.feldman.ha.ui.editors.SERVICE_SCHEMAS
import com.feldman.ha.ui.editors.parseValueType

internal val LocalCustomFeatureDetailPreview = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFeatureDetailPage(
    entityId: String,
    featureId: String?,
    onBack: () -> Unit,
    appState: AppState,
    showSaveDock: Boolean = true,
    onSaveDockStateChange: ((String, Boolean, () -> Unit) -> Unit)? = null
) {
    val config = appState.configs[entityId] ?: emptyMap()
    val isCustomFeature = featureId == null || featureId.startsWith("custom_")
    val customFeatures = (config["custom_features"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
    val existingCF = if (featureId != null && isCustomFeature) customFeatures.find { it["id"] == featureId } else null

    val domain = remember(entityId) { entityId.split(".")[0] }
    val spec = remember(domain) { com.feldman.ha.widgets.WidgetRegistry.spec(domain) }
    val specRow = remember(spec, featureId) { spec?.rows?.find { it.id == featureId } }
    val isPicker = specRow is com.feldman.ha.widgets.PickerRowSpec
    val hiddenOptions = remember { mutableStateListOf<String>() }
    /** Chosen presentation for rows whose spec offers more than one; null means the default. */
    var rowStyle by remember { mutableStateOf<String?>(null) }

    val displayName = remember(specRow, featureId) {
        if (specRow != null) {
            val label = when (specRow) {
                is com.feldman.ha.widgets.DataRowSpec -> specRow.label
                is com.feldman.ha.widgets.SliderRowSpec -> specRow.label
                else -> null
            }
            label ?: featureId?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: ""
        } else {
            featureId?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: ""
        }
    }

    var cfLabel by remember { mutableStateOf(existingCF?.get("label") as? String ?: "") }
    var cfIcon by remember { mutableStateOf(existingCF?.get("icon") as? String ?: "bolt") }
    var showCfIconPicker by remember { mutableStateOf(false) }
    var cfType by remember { mutableStateOf(existingCF?.get("type") as? String ?: "toggle") }
    var cfValueTemplate by remember { mutableStateOf(existingCF?.get("valueTemplate") as? String ?: "") }
    var cfServiceSearch by remember { mutableStateOf(existingCF?.get("serviceName") as? String ?: "") }
    var cfSelectedService by remember { mutableStateOf(existingCF?.get("serviceName") as? String ?: "") }
    var availableServices by remember { mutableStateOf(POPULAR_SERVICES) }

    val predefinedParams = remember { mutableStateMapOf<String, String>() }
    val customParams = remember { mutableStateListOf<Pair<String, String>>() }
    val targets = remember { mutableStateListOf<Map<String, String>>() }
    val visibilityConditions = remember { mutableStateListOf<Map<String, Any>>() }

    val allEntities = appState.all
    val isPreview = LocalInspectionMode.current || LocalCustomFeatureDetailPreview.current

    if (!isPreview) {
        BackHandler(onBack = onBack)
    }

    LaunchedEffect(existingCF, config, featureId) {
        targets.clear()
        if (isCustomFeature) {
            val existingTargets = existingCF?.get("targets") as? List<*>
            if (existingTargets != null) {
                existingTargets.filterIsInstance<Map<String, String>>().forEach { item ->
                    targets.add(item)
                }
            } else {
                val oldTarget = existingCF?.get("targetEntity") as? String
                if (!oldTarget.isNullOrEmpty()) {
                    targets.add(mapOf("type" to "entity", "value" to oldTarget))
                }
            }
        }

        visibilityConditions.clear()
        val existingConds = if (isCustomFeature) {
            existingCF?.get("visibility") as? List<*>
        } else {
            config["visibility:$featureId"] as? List<*>
        }
        if (existingConds != null) {
            existingConds.filterIsInstance<Map<String, Any>>().forEach { item ->
                val itemWithId = item.toMutableMap()
                addStableIdsToNested(itemWithId)
                visibilityConditions.add(itemWithId)
            }
        }

        if (!isCustomFeature && featureId != null) {
            hiddenOptions.clear()
            val savedHidden = (config["picker_hidden:$featureId"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            hiddenOptions.addAll(savedHidden)
            val styleSpec = (specRow as? com.feldman.ha.widgets.PickerRowSpec)?.style
            rowStyle = styleSpec?.let { config[it.configKey] as? String ?: it.default }
        }
    }
    LaunchedEffect(targets.size) {
        if (cfType == "value" && cfValueTemplate.isEmpty() && targets.isNotEmpty()) {
            val primary = targets.firstOrNull()?.get("value")
            if (primary != null) {
                cfValueTemplate = "{$primary}"
            }
        }
    }


    LaunchedEffect(appState.api) {
        runCatching {
            parseHomeAssistantServices(appState.api.getServices())
        }.onSuccess { serviceNames ->
            if (serviceNames.isNotEmpty()) {
                availableServices = mergeServiceNames(serviceNames, cfSelectedService)
            }
        }
    }

    LaunchedEffect(cfSelectedService) {
        predefinedParams.clear()
        customParams.clear()
        val existingData = existingCF?.get("serviceData") as? Map<*, *>
        val schema = SERVICE_SCHEMAS[cfSelectedService].orEmpty()
        val schemaKeys = schema.map { it.key }.toSet()

        schema.forEach { param ->
            predefinedParams[param.key] = param.defaultValue ?: when (param.type) {
                "boolean" -> "false"
                "slider" -> (param.min ?: 0f).toString()
                "dropdown" -> param.options?.firstOrNull() ?: ""
                else -> ""
            }
        }

        if (existingData != null) {
            existingData.forEach { (k, v) ->
                val keyStr = k.toString()
                val valStr = v.toString()
                if (keyStr in schemaKeys) {
                    predefinedParams[keyStr] = valStr
                } else {
                    customParams.add(keyStr to valStr)
                }
            }
        }
    }

    val isSaveEnabled = if (isCustomFeature) {
        cfLabel.trim().isNotEmpty() &&
                targets.isNotEmpty() &&
                (cfType == "toggle" || (cfType == "service" && cfSelectedService.isNotEmpty()) || (cfType == "value" && cfValueTemplate.trim().isNotEmpty()))
    } else {
        true
    }

    fun saveFeature() {
        if (!isSaveEnabled) return

        val newConfig = config.toMutableMap()

        if (isCustomFeature) {
            val finalServiceData = mutableMapOf<String, Any>()
            if (cfType == "service" && cfSelectedService.isNotEmpty()) {
                val schema = SERVICE_SCHEMAS[cfSelectedService].orEmpty()
                schema.forEach { param ->
                    val valueStr = predefinedParams[param.key]
                    if (valueStr != null && valueStr.isNotEmpty()) {
                        when (param.type) {
                            "boolean" -> finalServiceData[param.key] = valueStr == "true"
                            "slider" -> {
                                val f = valueStr.toFloatOrNull() ?: 0f
                                if (param.max != null && param.max <= 1f) {
                                    finalServiceData[param.key] = f
                                } else {
                                    finalServiceData[param.key] = f.roundToInt()
                                }
                            }
                            "number" -> {
                                val floatVal = valueStr.toFloatOrNull()
                                if (floatVal != null) {
                                    if (floatVal % 1f == 0f) {
                                        finalServiceData[param.key] = floatVal.toInt()
                                    } else {
                                        finalServiceData[param.key] = floatVal
                                    }
                                } else {
                                    finalServiceData[param.key] = valueStr
                                }
                            }
                            else -> finalServiceData[param.key] = valueStr
                        }
                    }
                }
                customParams.forEach { (k, v) ->
                    if (k.trim().isNotEmpty() && v.trim().isNotEmpty()) {
                        finalServiceData[k.trim()] = parseValueType(v.trim())
                    }
                }
            }

            val updatedList = customFeatures.toMutableList()
            val targetId = featureId ?: "custom_${System.currentTimeMillis()}"

            // Maintain backward compatibility for targetEntity attribute
            val primaryEntity = targets.firstOrNull { it["type"] == "entity" }?.get("value") ?: ""

            val cleanConditions = visibilityConditions.map { stripStableIds(it) }
            val featureMap = mutableMapOf<String, Any>(
                "id" to targetId,
                "type" to cfType,
                "label" to cfLabel.trim(),
                "icon" to cfIcon.trim(),
                "serviceName" to (if (cfType == "service") cfSelectedService else ""),
                "targetEntity" to primaryEntity,
                "targets" to targets.toList(),
                "visibility" to cleanConditions
            )
            if (cfType == "service") {
                featureMap["serviceData"] = finalServiceData
            }
            if (cfType == "value") {
                featureMap["valueTemplate"] = cfValueTemplate.trim()
            }

            val existingIndex = updatedList.indexOfFirst { it["id"] == targetId }
            if (existingIndex != -1) {
                updatedList[existingIndex] = featureMap
            } else {
                updatedList.add(featureMap)
            }

            newConfig["custom_features"] = updatedList

            // Sync visible rows if it's a new feature
            val currentRows = (newConfig["row_order"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            if (targetId !in currentRows) {
                newConfig["row_order"] = currentRows + targetId
            }
        } else {
            val cleanConditions = visibilityConditions.map { stripStableIds(it) }
            if (cleanConditions.isEmpty()) {
                newConfig.remove("visibility:$featureId")
            } else {
                newConfig["visibility:$featureId"] = cleanConditions
            }
            if (isPicker) {
                newConfig["picker_hidden:$featureId"] = hiddenOptions.toList()
                (specRow as? com.feldman.ha.widgets.PickerRowSpec)?.style?.let { styleSpec ->
                    newConfig[styleSpec.configKey] = rowStyle ?: styleSpec.default
                }
            }
        }

        appState.configs[entityId] = newConfig
        appState.saveConfigs()
        onBack()
    }

    val saveText = if (featureId != null) "Save changes" else "Add feature"
    val currentSaveFeature by rememberUpdatedState(newValue = { saveFeature() })

    if (isPreview) {
        CustomFeatureDetailPreviewContent(
            title = if (isCustomFeature) {
                if (featureId != null) "Edit Custom Feature" else "Add Custom Feature"
            } else {
                "Edit $displayName"
            },
            label = cfLabel.ifBlank { "Evening scene" },
            icon = cfIcon.ifBlank { "scene" },
            actionType = cfType,
            service = cfSelectedService.ifBlank { cfServiceSearch },
            targets = targets.mapNotNull { it["value"] },
            saveText = saveText,
            isSaveEnabled = isSaveEnabled,
            onBack = onBack
        )
        return
    }

    SideEffect {
        onSaveDockStateChange?.invoke(saveText, isSaveEnabled) {
            currentSaveFeature()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (showCfIconPicker) {
            IconPickerDialog(
                initial = cfIcon.ifBlank { "bolt" },
                onDismiss = { showCfIconPicker = false },
                onPick = { cfIcon = it; showCfIconPicker = false }
            )
        }
        SettingsScaffold(
            scaffoldModifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (isCustomFeature) (if (featureId != null) "Edit Custom Feature" else "Add Custom Feature") else "Edit ${displayName}") },
                    navigationIcon = {
                        FilledIconButton(
                            onClick = onBack,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            }
        ) {
            if (isCustomFeature) {
                title("General")
            section {
                item {
                    OutlinedTextField(
                        value = cfLabel,
                        onValueChange = { cfLabel = it },
                        label = { Text("Display Label") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                item {
                    OutlinedTextField(
                        value = cfIcon,
                        onValueChange = { cfIcon = it },
                        label = { Text("Icon (Material Symbol)") },
                        trailingIcon = {
                            IconButton(onClick = { showCfIconPicker = true }) {
                                Icon(rememberSymbolPainter(cfIcon.ifBlank { "bolt" }), "Pick icon")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

        title("Action Type")
        section {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { cfType = "toggle" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (cfType == "toggle") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (cfType == "toggle") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Toggle")
                        }
                        Button(
                            onClick = { cfType = "switch" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (cfType == "switch") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (cfType == "switch") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Switch")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { cfType = "service" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (cfType == "service") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (cfType == "service") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Call Service")
                        }
                        Button(
                            onClick = { cfType = "value" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (cfType == "value") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (cfType == "value") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Display Value")
                        }
                    }
                }
            }
        }

        if (cfType == "service") {
            title("Service Selection")
            section {
                item {
                    OutlinedTextField(
                        value = cfServiceSearch,
                        onValueChange = {
                            cfServiceSearch = it
                            val typedService = it.trim()
                            if (typedService in availableServices || typedService.isServiceName()) {
                                cfSelectedService = typedService
                            }
                        },
                        label = { Text("Search HA Service") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                item {
                    val filteredServices = remember(cfServiceSearch, availableServices) {
                        val query = cfServiceSearch.trim()
                        if (query.isEmpty()) {
                            availableServices
                        } else {
                            availableServices.filter { it.contains(query, ignoreCase = true) }
                        }
                    }

                    Text(
                        text = if (cfSelectedService.isNotEmpty()) "Selected: $cfSelectedService" else "Please select a service:",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cfSelectedService.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(4.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredServices.size) { index ->
                                val service = filteredServices[index]
                                  val isSelected = cfSelectedService == service
                                Text(
                                    text = service,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { cfSelectedService = service }
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .padding(vertical = 8.dp, horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        if (cfType == "value") {
            title("Value Configuration")
            section {
                item {
                    OutlinedTextField(
                        value = cfValueTemplate,
                        onValueChange = { cfValueTemplate = it },
                        label = { Text("Value Template") },
                        placeholder = { Text("e.g. {sensor.temperature} °C") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                val primaryEntityId = targets.firstOrNull { it["type"] == "entity" }?.get("value")
                val primaryEntity = allEntities.find { it.entity_id == primaryEntityId }
                if (primaryEntity != null) {
                    item {
                        Text(
                            text = "Quick placeholders for ${primaryEntity.attributes["friendly_name"] ?: primaryEntity.entity_id}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                SuggestionChip(
                                    onClick = { cfValueTemplate += "{${primaryEntity.entity_id}}" },
                                    label = { Text("{${primaryEntity.entity_id}}") }
                                )
                            }
                            item {
                                SuggestionChip(
                                    onClick = { cfValueTemplate += "{${primaryEntity.entity_id}.name}" },
                                    label = { Text("{${primaryEntity.entity_id}.name}") }
                                )
                            }
                            item {
                                SuggestionChip(
                                    onClick = { cfValueTemplate += "{${primaryEntity.entity_id}.attributes}" },
                                    label = { Text("{${primaryEntity.entity_id}.attributes}") }
                                )
                            }
                            primaryEntity.attributes.keys.forEach { attrKey ->
                                item {
                                    SuggestionChip(
                                        onClick = { cfValueTemplate += "{${primaryEntity.entity_id}.$attrKey}" },
                                        label = { Text("{${primaryEntity.entity_id}.$attrKey}") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        title("Target Selector")
        section {
            item {
                var targetSearch by remember { mutableStateOf("") }

                Column(modifier = Modifier.fillMaxWidth()) {
                    // Unified search field for all types
                    OutlinedTextField(
                        value = targetSearch,
                        onValueChange = { targetSearch = it },
                        label = { Text("Search or Add Target") },
                        placeholder = { Text("Search entities or type target name...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (targetSearch.isNotEmpty()) {
                                IconButton(onClick = { targetSearch = "" }) {
                                    Icon(rememberSymbolPainter("close"), "Clear")
                                }
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // Autocomplete suggestions for all types
                    if (targetSearch.trim().isNotEmpty()) {
                        val query = targetSearch.trim()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            val matches = allEntities.filter { it.entity_id.contains(query, ignoreCase = true) }.take(5)
                            if (matches.isNotEmpty()) {
                                Text(
                                    text = "Matching Entities",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                matches.forEach { ent ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (targets.none { it["type"] == "entity" && it["value"] == ent.entity_id }) {
                                                    targets.add(mapOf("type" to "entity", "value" to ent.entity_id))
                                                }
                                                targetSearch = ""
                                            }
                                            .padding(vertical = 10.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            painter = rememberSymbolPainter("adjust"),
                                            contentDescription = "Entity",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = ent.entity_id,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }

                            Text(
                                text = "Add Custom Target",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            listOf("entity", "device", "area", "label").forEach { type ->
                                val iconName = when (type) {
                                    "entity" -> "adjust"
                                    "device" -> "devices"
                                    "area" -> "room"
                                    else -> "label"
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (targets.none { it["type"] == type && it["value"] == query }) {
                                                targets.add(mapOf("type" to type, "value" to query))
                                            }
                                            targetSearch = ""
                                        }
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = rememberSymbolPainter(iconName),
                                        contentDescription = type,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Add '$query' as ${type.replaceFirstChar { it.uppercase() }}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Active targets list representation
                    if (targets.isEmpty()) {
                        Text(
                            text = "No targets selected (required).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Text(
                            text = "Active Targets (${targets.size}):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(4.dp))
                        targets.forEachIndexed { idx, tgt ->
                            val type = tgt["type"] ?: "entity"
                            val valStr = tgt["value"] ?: ""
                            val iconName = when (type) {
                                "entity" -> "adjust"
                                "device" -> "devices"
                                "area" -> "room"
                                else -> "label"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = rememberSymbolPainter(iconName),
                                    contentDescription = type,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(valStr, style = MaterialTheme.typography.bodyMedium)
                                    Text(type.uppercase(), style = MaterialTheme.typography.bodySmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                }
                                IconButton(
                                    onClick = { targets.removeAt(idx) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        painter = rememberSymbolPainter("delete"),
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (cfType == "service" && cfSelectedService.isNotEmpty()) {
            val schema = SERVICE_SCHEMAS[cfSelectedService].orEmpty()
            if (schema.isNotEmpty()) {
                title("Service Parameters")
                section {
                    schema.forEach { param ->
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = param.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(4.dp))

                                when (param.type) {
                                    "boolean" -> {
                                        val isChecked = predefinedParams[param.key] == "true"
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Switch(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    predefinedParams[param.key] = checked.toString()
                                                }
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (isChecked) "Enabled" else "Disabled")
                                        }
                                    }
                                    "slider" -> {
                                        val minVal = param.min ?: 0f
                                        val maxVal = param.max ?: 100f
                                        val currentVal = predefinedParams[param.key]?.toFloatOrNull() ?: minVal
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Slider(
                                                value = currentVal,
                                                onValueChange = { predefinedParams[param.key] = it.toString() },
                                                valueRange = minVal..maxVal,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (maxVal <= 1f) String.format("%.2f", currentVal) else currentVal.roundToInt().toString(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.widthIn(min = 40.dp)
                                            )
                                        }
                                    }
                                    "dropdown" -> {
                                        val options = param.options.orEmpty()
                                        val selectedOpt = predefinedParams[param.key] ?: options.firstOrNull() ?: ""
                                        var expanded by remember { mutableStateOf(false) }

                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            OutlinedButton(
                                                onClick = { expanded = true },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(selectedOpt.ifEmpty { "Select Option" })
                                            }
                                            DropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false }
                                            ) {
                                                options.forEach { opt ->
                                                    DropdownMenuItem(
                                                        text = { Text(opt) },
                                                        onClick = {
                                                            predefinedParams[param.key] = opt
                                                            expanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    else -> { // "text", "number"
                                        val currentText = predefinedParams[param.key] ?: ""
                                        OutlinedTextField(
                                            value = currentText,
                                            onValueChange = { predefinedParams[param.key] = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp),
                                            placeholder = { Text(param.defaultValue ?: "") }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            title("Custom Parameters")
            section {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Define dynamic keys/values",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        IconButton(
                            onClick = { customParams.add("" to "") }
                        ) {
                            Icon(
                                rememberSymbolPainter("add_circle"),
                                contentDescription = "Add Custom Parameter"
                            )
                        }
                    }
                }

                if (customParams.isEmpty()) {
                    item {
                        Text(
                            text = "No custom parameters added.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    customParams.forEachIndexed { idx, pair ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = pair.first,
                                    onValueChange = { newKey ->
                                        customParams[idx] = newKey to pair.second
                                    },
                                    label = { Text("Key") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                OutlinedTextField(
                                    value = pair.second,
                                    onValueChange = { newVal ->
                                        customParams[idx] = pair.first to newVal
                                    },
                                    label = { Text("Value") },
                                    modifier = Modifier.weight(1.2f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                IconButton(
                                    onClick = { customParams.removeAt(idx) }
                                ) {
                                    Icon(
                                        rememberSymbolPainter("delete"),
                                        contentDescription = "Remove Parameter",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        if (isPicker && specRow is com.feldman.ha.widgets.PickerRowSpec) {
            specRow.style?.let { styleSpec ->
                title("Style")
                section {
                    styleSpec.options.forEach { option ->
                        val selected = (rowStyle ?: styleSpec.default) == option.id
                        choiceItem(
                            key = option.id,
                            title = option.label,
                            selected = selected,
                            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            onClick = { rowStyle = option.id }
                        )
                    }
                }
            }
            // A single toggle has no options to show or hide, so the list below would be a
            // control with no effect.
            if (specRow.style == null || (rowStyle ?: specRow.style!!.default) != ROW_STYLE_TOGGLE) {
            title("Options Visibility")
            section {
                // Only the options this entity actually supports (built-ins filtered by
                // supportedIf + the entity's real dynamic modes) — the same list the card and
                // sheet render. Iterating the static spec options listed every theoretical mode
                // (e.g. "Medium low"/"Medium high") even when the entity doesn't report them.
                val pickerEntity = allEntities.find { it.entity_id == entityId }
                    ?: HAEntity(entityId, "", emptyMap())
                pickerOptionsForEntity(specRow, pickerEntity).forEach { opt ->
                    item {
                        val isHidden = opt.id in hiddenOptions
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isHidden) {
                                        hiddenOptions.remove(opt.id)
                                    } else {
                                        hiddenOptions.add(opt.id)
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp)
                        ) {
                            Checkbox(checked = !isHidden, onCheckedChange = null)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = opt.label ?: opt.id.replace("_", " ").replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            }
        }
    }
        val context = LocalContext.current
        val isOverallVisible = remember(visibilityConditions.toList(), allEntities) {
            visibilityConditions.all { evaluateSettingsCondition(it, allEntities, context) }
        }
        val overallStatus = if (visibilityConditions.isEmpty()) "" else if (isOverallVisible) " (Visible)" else " (Hidden)"
        title("Visibility Conditions$overallStatus")
        section {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    var showDropdown by remember { mutableStateOf(false) }
                    
                    if (visibilityConditions.isEmpty()) {
                        Text(
                            text = "Always visible. Add conditions to hide/show dynamically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        visibilityConditions.forEachIndexed { index, cond ->
                            val condId = cond["_id"] as? String ?: remember(cond) { java.util.UUID.randomUUID().toString() }
                            key(condId) {
                                ConditionEditor(
                                    condition = cond,
                                    allEntities = allEntities,
                                    onChange = { updated -> visibilityConditions[index] = updated },
                                    onDelete = { visibilityConditions.removeAt(index) }
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { showDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(rememberSymbolPainter("add"), null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Visibility Condition")
                        }
                        
                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false }
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
                                        visibilityConditions.add(newCond)
                                        showDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(128.dp))
        }
    }

        if (showSaveDock) {
            ConfigureCardSaveDock(
                text = saveText,
                onSave = ::saveFeature,
                enabled = isSaveEnabled,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomFeatureDetailPreviewContent(
    title: String,
    label: String,
    icon: String,
    actionType: String,
    service: String,
    targets: List<String>,
    saveText: String,
    isSaveEnabled: Boolean,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    FilledIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            ConfigureCardSaveDock(
                text = saveText,
                onSave = {},
                enabled = isSaveEnabled,
                modifier = Modifier.fillMaxWidth()
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "General",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PreviewSummaryRow("Display Label", label)
                        PreviewSummaryRow("Icon", icon)
                    }
                }
            }
            item {
                Text(
                    text = "Action Type",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PreviewActionChip("Toggle", actionType == "toggle", Modifier.weight(1f))
                            PreviewActionChip("Switch", actionType == "switch", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PreviewActionChip("Call Service", actionType == "service", Modifier.weight(1f))
                            PreviewActionChip("Display Value", actionType == "value", Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Text(
                    text = "Service Selection",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PreviewSummaryRow("Selected service", service.ifBlank { "light.turn_on" })
                        PreviewSummaryRow(
                            "Targets",
                            targets.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "light.kitchen"
                        )
                    }
                }
            }
            item {
                Text(
                    text = "Visibility",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Visible when Kitchen lights is on",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            item {
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun PreviewSummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PreviewActionChip(text: String, selected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

private fun parseHomeAssistantServices(rawServices: List<Map<String, Any>>): List<String> =
    rawServices.flatMap { domainBlock ->
        val domain = domainBlock["domain"] as? String ?: return@flatMap emptyList()
        val services = domainBlock["services"] as? Map<*, *> ?: return@flatMap emptyList()
        services.keys.mapNotNull { service ->
            service?.toString()?.takeIf(String::isNotBlank)?.let { "$domain.$it" }
        }
    }.distinct().sorted()

private fun mergeServiceNames(loadedServices: List<String>, selectedService: String): List<String> =
    (loadedServices + POPULAR_SERVICES + selectedService.takeIf(String::isNotBlank))
        .filterNotNull()
        .distinct()
        .sorted()

private fun String.isServiceName(): Boolean {
    val parts = split(".")
    return parts.size == 2 && parts.all { part ->
        part.isNotBlank() && part.all { it.isLetterOrDigit() || it == '_' }
    }
}
