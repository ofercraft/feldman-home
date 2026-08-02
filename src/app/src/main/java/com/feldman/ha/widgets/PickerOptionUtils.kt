package com.feldman.ha.widgets

private val FAN_MODE_ORDER = listOf(
    "auto",
    "off",
    "quiet",
    "low",
    "medium_low",
    "medium",
    "medium_high",
    "high",
    "turbo",
    "super",
)

fun pickerOptionKey(rowId: String, optionId: String): String =
    if (rowId == "fan_mode") normalizedFanModeId(optionId) else optionId

/**
 * Sort rank for a fan-mode option. Named speeds (auto/low/medium/high…) sort by [FAN_MODE_ORDER];
 * numeric speeds ("1", "2", "10", "25%") sort by their number *after* the named ones, so HA's
 * arbitrary `fan_modes` ordering doesn't surface as e.g. "auto 1 3 2". Anything else keeps its
 * original relative position (after the numeric block) via [fallbackIndex].
 */
private fun fanModeRank(rowId: String, optionId: String, fallbackIndex: Int): Double {
    val key = pickerOptionKey(rowId, optionId)
    val named = FAN_MODE_ORDER.indexOf(key)
    if (named >= 0) return named.toDouble()
    val numeric = key.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
    if (numeric != null) return FAN_MODE_ORDER.size + numeric
    return FAN_MODE_ORDER.size + 1_000_000.0 + fallbackIndex
}

/**
 * Whether [optionId] should be treated as hidden, given the user's [hiddenIds] and the entity's
 * [availableIds] (its actual current option ids). Matching is by normalized key so genuine aliases
 * line up (hiding "medium" hides an entity that reports "med"). But a hidden id that is a *phantom
 * alias* — one that isn't itself a current option yet collides by key with a real one — is ignored:
 * a legacy hidden built-in "medium" must not suppress an entity whose real mode is "mid". So a hidden
 * id suppresses an option only when it is that option's own id, or is itself one of the entity's
 * current options.
 */
fun isPickerOptionHidden(
    rowId: String,
    optionId: String,
    hiddenIds: Collection<String>,
    availableIds: Collection<String>
): Boolean {
    if (hiddenIds.isEmpty()) return false
    val optKey = pickerOptionKey(rowId, optionId)
    val availableSet = availableIds.toHashSet()
    return hiddenIds.any { h ->
        pickerOptionKey(rowId, h) == optKey && (h == optionId || h in availableSet)
    }
}

fun pickerOptionMatches(rowId: String, optionId: String, value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    if (pickerOptionKey(rowId, optionId) == pickerOptionKey(rowId, value)) return true

    val valueNumber = value.toDoubleOrNull() ?: return false
    return valueNumber == optionId.toDoubleOrNull()
}

fun pickerOptionFallbackLabel(rowId: String, optionId: String): String =
    when {
        rowId == "fan_mode" && pickerOptionKey(rowId, optionId) == "medium" -> "Medium"
        rowId == "fan_mode" && pickerOptionKey(rowId, optionId) == "medium_low" -> "Medium low"
        rowId == "fan_mode" && pickerOptionKey(rowId, optionId) == "medium_high" -> "Medium high"
        else -> optionId
            .replace("_", " ")
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }
    }

fun mergePickerOptions(
    rowId: String,
    builtIns: List<PickerOptionSpec>,
    dynamicIds: List<String>,
    defaultFor: (String) -> PickerOptionSpec
): List<PickerOptionSpec> {
    val merged = mutableListOf<PickerOptionSpec>()
    val seen = mutableSetOf<String>()

    fun add(option: PickerOptionSpec) {
        if (seen.add(pickerOptionKey(rowId, option.id))) {
            merged.add(option)
        }
    }

    builtIns.forEach(::add)
    dynamicIds.filter { it.isNotBlank() }.distinct().map(defaultFor).forEach(::add)

    if (rowId != "fan_mode") return merged

    return merged.sortedBy { option -> fanModeRank(rowId, option.id, merged.indexOf(option)) }
}

private fun normalizedFanModeId(optionId: String): String {
    val normalized = optionId
        .trim()
        .lowercase()
        .replace('-', '_')
        .replace(' ', '_')
        .replace(Regex("_+"), "_")

    return when (normalized) {
        "med", "mid" -> "medium"
        "med_low", "mediumlow", "medlow", "mid_low", "midlow" -> "medium_low"
        "med_high", "mediumhigh", "medhigh", "mid_high", "midhigh" -> "medium_high"
        else -> normalized
    }
}

fun mergePickerOptionIds(rowId: String, ids: List<String>): List<String> {
    val merged = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    ids.filter { it.isNotBlank() }.forEach { id ->
        if (seen.add(pickerOptionKey(rowId, id))) {
            merged.add(id)
        }
    }

    if (rowId != "fan_mode") return merged

    return merged.sortedBy { id -> fanModeRank(rowId, id, merged.indexOf(id)) }
}

fun normalizePickerOptionIds(
    rowId: String,
    ids: List<String>,
    availableIds: List<String>
): List<String> {
    if (availableIds.isEmpty()) return mergePickerOptionIds(rowId, ids)

    val availableByKey = availableIds.associateBy { pickerOptionKey(rowId, it) }
    return mergePickerOptionIds(rowId, ids).mapNotNull { id ->
        availableByKey[pickerOptionKey(rowId, id)]
    }
}

/**
 * Order [available] option ids by the user's [savedOrder], appending anything not listed (in its
 * existing relative order). Unlike [normalizePickerOptionIds] this does NOT re-apply the canonical
 * fan-mode sort, so an explicit drag-reorder is respected. Matching is by normalized key so aliases
 * (e.g. "med"/"medium") line up. When [savedOrder] is empty the input order is preserved as-is.
 */
fun orderPickerOptionIds(
    rowId: String,
    available: List<String>,
    savedOrder: List<String>
): List<String> {
    if (savedOrder.isEmpty()) return available
    val byKey = available.associateBy { pickerOptionKey(rowId, it) }
    val seen = LinkedHashSet<String>()
    val ordered = mutableListOf<String>()
    savedOrder.forEach { id ->
        val key = pickerOptionKey(rowId, id)
        val match = byKey[key]
        if (match != null && seen.add(key)) ordered.add(match)
    }
    available.forEach { id ->
        val key = pickerOptionKey(rowId, id)
        if (seen.add(key)) ordered.add(id)
    }
    return ordered
}

/** [orderPickerOptionIds] over full [PickerOptionSpec]s. */
fun orderPickerOptions(
    rowId: String,
    options: List<PickerOptionSpec>,
    savedOrder: List<String>
): List<PickerOptionSpec> {
    if (savedOrder.isEmpty()) return options
    val byKey = options.associateBy { pickerOptionKey(rowId, it.id) }
    val seen = LinkedHashSet<String>()
    val ordered = mutableListOf<PickerOptionSpec>()
    savedOrder.forEach { id ->
        val key = pickerOptionKey(rowId, id)
        val match = byKey[key]
        if (match != null && seen.add(key)) ordered.add(match)
    }
    options.forEach { opt ->
        val key = pickerOptionKey(rowId, opt.id)
        if (seen.add(key)) ordered.add(opt)
    }
    return ordered
}
