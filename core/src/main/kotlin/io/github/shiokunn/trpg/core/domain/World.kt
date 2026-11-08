package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

/**
 * A user- or AI-defined world. Nothing here is constrained to a fixed genre:
 * genre, factions, magic/technology and secrets are all free text.
 */
@Serializable
data class World(
    val id: String,
    val name: String,
    val genre: String,
    val era: String = "",
    val setting: String = "",
    val atmosphere: String = "",
    val history: String = "",
    val geography: String = "",
    val socialStructure: String = "",
    val factions: List<String> = emptyList(),
    val techMagic: String = "",
    val specialSettings: List<String> = emptyList(),
    val objective: String = "",
    val problem: String = "",
    val secrets: List<String> = emptyList(),
    val scenarioTitle: String = "",
    val scenarioPremise: String = "",
    val scenarioLength: String = "",
    val difficulty: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class Location(
    val id: String,
    val name: String,
    val description: String = "",
    val isDiscovered: Boolean = false,
    val connections: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
)

@Serializable
data class Faction(
    val id: String,
    val name: String,
    val description: String = "",
    val disposition: String = "",
)
