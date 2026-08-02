package com.feldman.ha.data

data class HAEntity(
    val entity_id: String,
    var state: String,
    val attributes: Map<String, Any>
)