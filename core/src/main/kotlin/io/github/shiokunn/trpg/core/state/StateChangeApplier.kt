package io.github.shiokunn.trpg.core.state

import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.Item
import java.util.UUID

/**
 * Pure, deterministic-once-validated application of state changes.
 * Call [StateChangeValidator] first (or use [StateChangeEngine]).
 */
object StateChangeApplier {

    fun apply(snapshot: GameSnapshot, changes: List<StateChange>): GameSnapshot {
        var character = snapshot.character
        var npcs = snapshot.npcs
        var quests = snapshot.quests
        var locations = snapshot.locations
        val flags = snapshot.flags.toMutableMap()
        var currentLocationId = snapshot.currentLocationId
        var activeCombat = snapshot.activeCombat

        for (change in changes) {
            when (change.type) {
                StateChangeType.HP_DELTA -> {
                    val next = (character.hp + change.amount)
                        .coerceIn(0, character.maxHp.coerceAtLeast(1))
                    character = character.copy(hp = next)
                }

                StateChangeType.XP_DELTA -> {
                    val next = (character.experience + change.amount)
                        .coerceIn(0, MAX_EXPERIENCE)
                    character = character.copy(experience = next)
                }

                StateChangeType.ITEM_ADD -> {
                    val incoming = change.item ?: continue
                    val quantity = incoming.quantity.coerceIn(1, Item.MAX_QUANTITY)
                    val index = character.inventory.indexOfFirst { existing ->
                        (incoming.id.isNotBlank() && existing.id == incoming.id) ||
                            existing.name == incoming.name
                    }
                    character = if (index >= 0) {
                        val existing = character.inventory[index]
                        val merged = existing.copy(
                            quantity = (existing.quantity + quantity).coerceAtMost(Item.MAX_QUANTITY),
                        )
                        character.copy(
                            inventory = character.inventory.toMutableList().also { it[index] = merged },
                        )
                    } else {
                        val added = incoming.copy(
                            id = incoming.id.ifBlank { UUID.randomUUID().toString() },
                            quantity = quantity,
                        )
                        character.copy(inventory = character.inventory + added)
                    }
                }

                StateChangeType.ITEM_REMOVE -> {
                    val index = character.inventory.indexOfFirst { it.id == change.targetId }
                    if (index < 0) continue
                    val existing = character.inventory[index]
                    val remove = if (change.amount == 0) 1 else change.amount
                    character = if (remove >= existing.quantity) {
                        character.copy(
                            inventory = character.inventory.filterNot { it.id == existing.id },
                            equipment = character.equipment - existing.id,
                        )
                    } else {
                        val reduced = existing.copy(quantity = existing.quantity - remove)
                        character.copy(
                            inventory = character.inventory.toMutableList().also { it[index] = reduced },
                        )
                    }
                }

                StateChangeType.NPC_RELATION_DELTA -> {
                    npcs = npcs.map { npc ->
                        if (npc.id != change.targetId) {
                            npc
                        } else {
                            npc.copy(
                                relationship = (npc.relationship + change.amount)
                                    .coerceIn(NPC_MIN_RELATION, NPC_MAX_RELATION),
                            )
                        }
                    }
                }

                StateChangeType.QUEST_OBJECTIVE_PROGRESS -> {
                    quests = quests.map { quest ->
                        if (quest.id != change.targetId) {
                            quest
                        } else {
                            val index = change.objectiveIndex
                            if (index !in quest.objectives.indices) {
                                quest
                            } else {
                                val objectives = quest.objectives.toMutableList()
                                objectives[index] = objectives[index].copy(completed = change.amount > 0)
                                quest.copy(objectives = objectives)
                            }
                        }
                    }
                }

                StateChangeType.QUEST_STATUS -> {
                    val next = change.questStatus ?: continue
                    quests = quests.map { quest ->
                        if (quest.id == change.targetId) quest.copy(status = next) else quest
                    }
                }

                StateChangeType.LOCATION_CHANGE -> {
                    currentLocationId = change.targetId
                    locations = locations.map { location ->
                        if (location.id == change.targetId) {
                            location.copy(isDiscovered = true)
                        } else {
                            location
                        }
                    }
                }

                StateChangeType.FLAG_SET -> {
                    flags[change.targetId] = change.value
                }

                StateChangeType.COMBAT_START -> {
                    val npc = npcs.firstOrNull { it.id == change.targetId } ?: continue
                    val stats = npc.combat?.normalized() ?: continue
                    activeCombat = CombatState(
                        enemyNpcId = npc.id,
                        enemyName = npc.name,
                        enemyHp = stats.hp,
                        enemyMaxHp = stats.hp.coerceAtLeast(1),
                        enemyAttackPower = stats.attackPower,
                        enemyDefense = stats.defense,
                        enemyAttackSkill = stats.attackSkillLevel,
                        enemyDefenseSkill = stats.defenseSkillLevel,
                        round = 1,
                    )
                }

                StateChangeType.COMBAT_END -> {
                    val combat = activeCombat ?: continue
                    npcs = npcs.map { npc ->
                        if (npc.id != combat.enemyNpcId || npc.combat == null) {
                            npc
                        } else {
                            npc.copy(
                                combat = npc.combat.copy(
                                    hp = combat.enemyHp.coerceIn(0, io.github.shiokunn.trpg.core.domain.CombatStats.MAX_HP),
                                ),
                            )
                        }
                    }
                    activeCombat = null
                }
            }
        }

        return snapshot.copy(
            character = character,
            npcs = npcs,
            quests = quests,
            locations = locations,
            flags = flags,
            currentLocationId = currentLocationId,
            activeCombat = activeCombat,
        )
    }

    private const val MAX_EXPERIENCE = 9_999_999
    private const val NPC_MIN_RELATION = -100
    private const val NPC_MAX_RELATION = 100
}

/**
 * Atomic entry point: validate every change, then apply them all or none.
 * The caller commits the returned snapshot in a single Room transaction.
 */
object StateChangeEngine {
    fun apply(snapshot: GameSnapshot, changes: List<StateChange>): MutationResult =
        when (val validation = StateChangeValidator.validate(snapshot, changes)) {
            is ValidationResult.Invalid -> MutationResult.Rejected(validation.errors)
            ValidationResult.Valid -> MutationResult.Applied(StateChangeApplier.apply(snapshot, changes))
        }
}
