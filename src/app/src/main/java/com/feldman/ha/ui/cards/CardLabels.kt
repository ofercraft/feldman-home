package com.feldman.ha.ui.cards

import android.content.Context
import com.feldman.ha.data.HAEntity
import com.feldman.ha.ui.editors.stripStableIds

// A button's label can be composed from ordered blocks instead of a single string:
//   label_blocks: [
//     {"type": "text", "text": "Garage "},                          — always shown
//     {"type": "conditional",                                       — first matching rule wins
//      "rules": [{"text": "OPEN", "conditions": [condition…]}, …],
//      "else": "closed"},
//   ]
// Conditions use the same shape as custom-feature visibility (evaluateCondition).
// When label_blocks is absent/empty, the plain "label" string is used as-is.

/** Resolves a composed button label against current entity states. */
internal fun resolveLabelBlocks(
    blocks: List<Map<String, Any>>,
    allEntities: List<HAEntity>,
    context: Context
): String = buildString {
    blocks.forEach { block ->
        when (block["type"] as? String ?: "text") {
            "conditional" -> {
                val rules = (block["rules"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                val matched = rules.firstOrNull { rule ->
                    val conds = (rule["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                    conds.isNotEmpty() && conds.all { evaluateCondition(it, allEntities, context) }
                }
                append(((matched?.get("text") ?: block["else"]) as? String).orEmpty())
            }
            else -> append((block["text"] as? String).orEmpty())
        }
    }
}

/** Strips the editor-only "_id" keys so saved configs stay clean. */
internal fun cleanLabelBlocks(blocks: List<Map<String, Any>>): List<Map<String, Any>> =
    blocks.map { block ->
        when (block["type"] as? String ?: "text") {
            "conditional" -> mapOf(
                "type" to "conditional",
                "rules" to (block["rules"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                    .map { rule ->
                        val conds = (rule["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                        mapOf(
                            "text" to (rule["text"] as? String ?: ""),
                            "conditions" to conds.map { stripStableIds(it) }
                        )
                    },
                "else" to (block["else"] as? String ?: "")
            )
            else -> mapOf("type" to "text", "text" to (block["text"] as? String ?: ""))
        }
    }

/** True once the editor has at least one output string worth saving. */
internal fun labelBlocksHaveText(blocks: List<Map<String, Any>>): Boolean =
    blocks.any { block ->
        when (block["type"] as? String ?: "text") {
            "conditional" -> {
                val rulesHaveText = (block["rules"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
                    .any { (it["text"] as? String).orEmpty().isNotBlank() }
                rulesHaveText || (block["else"] as? String).orEmpty().isNotBlank()
            }
            else -> (block["text"] as? String).orEmpty().isNotBlank()
        }
    }

internal fun newBlockId() = java.util.UUID.randomUUID().toString()

private fun ensureConditionIds(cond: Map<String, Any>): Map<String, Any> {
    val withId = if (cond["_id"] == null) cond + ("_id" to newBlockId()) else cond
    val subs = (withId["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: return withId
    return withId + ("conditions" to subs.map { ensureConditionIds(it) })
}

/** Gives every block, rule and (nested) condition a stable editor id; stripped again on save. */
internal fun ensureLabelBlockIds(block: Map<String, Any>): Map<String, Any> {
    var result = if (block["_id"] == null) block + ("_id" to newBlockId()) else block
    val rules = (result["rules"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
    if (rules != null) {
        result = result + ("rules" to rules.map { rule ->
            val withId = if (rule["_id"] == null) rule + ("_id" to newBlockId()) else rule
            val conds = (withId["conditions"] as? List<*>)?.filterIsInstance<Map<String, Any>>().orEmpty()
            withId + ("conditions" to conds.map { ensureConditionIds(it) })
        })
    }
    return result
}
