package io.github.shiokunn.trpg.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SessionStatus(val label: String) {
    ACTIVE("進行中"),
    COMPLETED("完了"),
    ARCHIVED("保管"),
}

@Serializable
data class CombatState(
    val enemyNpcId: String,
    val enemyName: String,
    val enemyHp: Int,
    val enemyMaxHp: Int,
    val enemyAttackPower: Int,
    val enemyDefense: Int,
    val enemyAttackSkill: Int = 0,
    val enemyDefenseSkill: Int = 0,
    val round: Int = 1,
)

@Serializable
data class GameSession(
    val id: String,
    val title: String,
    val worldId: String,
    val characterId: String,
    val currentLocationId: String? = null,
    val currentChapter: Int = 1,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val turnCount: Int = 0,
    val summary: String = "",
    val activeCombat: CombatState? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastPlayedAt: Long = 0L,
)
