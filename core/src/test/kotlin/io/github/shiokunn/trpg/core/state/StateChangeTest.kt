package io.github.shiokunn.trpg.core.state

import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.CombatStats
import io.github.shiokunn.trpg.core.domain.Item
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.QuestObjective
import io.github.shiokunn.trpg.core.domain.QuestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateChangeTest {

    private fun snapshot(
        character: Character = Character(id = "pc", name = "主人公"),
        npcs: List<Npc> = listOf(
            Npc(
                id = "npc1",
                name = "商人",
                relationship = 0,
                combat = CombatStats(attackPower = 3, defense = 2, hp = 12),
            ),
        ),
        quests: List<Quest> = listOf(
            Quest(
                id = "q1",
                title = "迷子の猫",
                objectives = listOf(QuestObjective("猫を探す"), QuestObjective("猫を届ける")),
            ),
        ),
        locations: List<Location> = listOf(
            Location(id = "l1", name = "広場", isDiscovered = true),
            Location(id = "l2", name = "森"),
        ),
        flags: Map<String, String> = emptyMap(),
        currentLocationId: String? = "l1",
        activeCombat: CombatState? = null,
    ) = GameSnapshot(
        character = character,
        npcs = npcs,
        quests = quests,
        locations = locations,
        flags = flags,
        currentLocationId = currentLocationId,
        activeCombat = activeCombat,
    )

    private fun applied(result: MutationResult): GameSnapshot {
        assertTrue("expected Applied but was $result", result is MutationResult.Applied)
        return (result as MutationResult.Applied).snapshot
    }

    private fun rejected(result: MutationResult): List<String> {
        assertTrue("expected Rejected but was $result", result is MutationResult.Rejected)
        return (result as MutationResult.Rejected).errors
    }

    @Test
    fun hpDeltaClampsBetweenZeroAndMax() {
        val state = snapshot(character = Character(id = "pc", name = "主人公", hp = 10, maxHp = 10))

        val low = applied(StateChangeEngine.apply(state, listOf(StateChange(StateChangeType.HP_DELTA, amount = -99))))
        assertEquals(0, low.character.hp)
        assertTrue(low.character.isDown())

        val high = applied(StateChangeEngine.apply(state, listOf(StateChange(StateChangeType.HP_DELTA, amount = 99))))
        assertEquals(10, high.character.hp)
    }

    @Test
    fun xpDeltaClampsAtZero() {
        val state = snapshot(character = Character(id = "pc", name = "主人公", experience = 5))
        val result = applied(
            StateChangeEngine.apply(state, listOf(StateChange(StateChangeType.XP_DELTA, amount = -50))),
        )
        assertEquals(0, result.character.experience)
    }

    @Test
    fun oneInvalidChangeRejectsWholeList() {
        val state = snapshot()
        val result = StateChangeEngine.apply(
            state,
            listOf(
                StateChange(StateChangeType.HP_DELTA, amount = -2),
                StateChange(StateChangeType.NPC_RELATION_DELTA, targetId = "missing", amount = 10),
            ),
        )
        val errors = rejected(result)
        assertTrue(errors.any { it.contains("NPC") })
        // The original snapshot is untouched (immutable by construction).
        assertEquals(10, state.character.hp)
    }

    @Test
    fun hpDeltaZeroIsRejected() {
        rejected(
            StateChangeEngine.apply(snapshot(), listOf(StateChange(StateChangeType.HP_DELTA, amount = 0))),
        )
    }

    @Test
    fun itemAddMergesByNameAndGeneratesId() {
        val state = snapshot(
            character = Character(
                id = "pc",
                name = "主人公",
                inventory = listOf(Item(id = "potion", name = "ポーション", quantity = 2)),
            ),
        )
        val merged = applied(
            StateChangeEngine.apply(
                state,
                listOf(
                    StateChange(
                        StateChangeType.ITEM_ADD,
                        item = Item(id = "", name = "ポーション", quantity = 3),
                    ),
                ),
            ),
        )
        assertEquals(1, merged.character.inventory.size)
        assertEquals(5, merged.character.inventory.single().quantity)

        val added = applied(
            StateChangeEngine.apply(
                state,
                listOf(
                    StateChange(
                        StateChangeType.ITEM_ADD,
                        item = Item(id = "", name = "剣", quantity = 1),
                    ),
                ),
            ),
        )
        assertEquals(2, added.character.inventory.size)
        val sword = added.character.inventory.first { it.name == "剣" }
        assertNotEquals("", sword.id)
    }

    @Test
    fun itemAddRejectsInvalidQuantityAndBlankName() {
        val state = snapshot()
        val errors = rejected(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.ITEM_ADD, item = Item(id = "", name = "薬", quantity = 0))),
            ),
        )
        assertTrue(errors.isNotEmpty())

        rejected(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.ITEM_ADD, item = Item(id = "", name = " "))),
            ),
        )
    }

    @Test
    fun itemRemoveReducesOrDeletesAndUnequips() {
        val state = snapshot(
            character = Character(
                id = "pc",
                name = "主人公",
                inventory = listOf(
                    Item(id = "sword", name = "剣", quantity = 1, equipped = true),
                    Item(id = "potion", name = "ポーション", quantity = 3),
                ),
                equipment = listOf("sword"),
            ),
        )
        val partial = applied(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.ITEM_REMOVE, targetId = "potion", amount = 1)),
            ),
        )
        assertEquals(2, partial.character.inventory.first { it.id == "potion" }.quantity)

        val full = applied(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.ITEM_REMOVE, targetId = "sword", amount = 1)),
            ),
        )
        assertNull(full.character.itemById("sword"))
        assertFalse(full.character.equipment.contains("sword"))
    }

    @Test
    fun itemRemoveRejectsUnknownAndOverQuantity() {
        val state = snapshot(
            character = Character(
                id = "pc",
                name = "主人公",
                inventory = listOf(Item(id = "potion", name = "ポーション", quantity = 2)),
            ),
        )
        rejected(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.ITEM_REMOVE, targetId = "missing", amount = 1)),
            ),
        )
        rejected(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.ITEM_REMOVE, targetId = "potion", amount = 3)),
            ),
        )
    }

    @Test
    fun relationClampsToMinusHundredAndHundred() {
        val high = applied(
            StateChangeEngine.apply(
                snapshot(),
                listOf(StateChange(StateChangeType.NPC_RELATION_DELTA, targetId = "npc1", amount = 100)),
            ),
        )
        assertEquals(100, high.npcs.single { it.id == "npc1" }.relationship)

        val low = applied(
            StateChangeEngine.apply(
                snapshot(),
                listOf(StateChange(StateChangeType.NPC_RELATION_DELTA, targetId = "npc1", amount = -100)),
            ),
        )
        assertEquals(-100, low.npcs.single { it.id == "npc1" }.relationship)
    }

    @Test
    fun relationDeltaZeroAndUnknownNpcAreRejected() {
        rejected(
            StateChangeEngine.apply(
                snapshot(),
                listOf(StateChange(StateChangeType.NPC_RELATION_DELTA, targetId = "npc1", amount = 0)),
            ),
        )
        rejected(
            StateChangeEngine.apply(
                snapshot(),
                listOf(StateChange(StateChangeType.NPC_RELATION_DELTA, targetId = "ghost", amount = 5)),
            ),
        )
    }

    @Test
    fun questObjectiveProgressAndStatusTransition() {
        val progressed = applied(
            StateChangeEngine.apply(
                snapshot(),
                listOf(
                    StateChange(
                        StateChangeType.QUEST_OBJECTIVE_PROGRESS,
                        targetId = "q1",
                        objectiveIndex = 1,
                        amount = 1,
                    ),
                    StateChange(
                        StateChangeType.QUEST_STATUS,
                        targetId = "q1",
                        questStatus = QuestStatus.COMPLETED,
                    ),
                ),
            ),
        )
        val quest = progressed.quests.single()
        assertTrue(quest.objectives[1].completed)
        assertEquals(QuestStatus.COMPLETED, quest.status)

        // A finished quest cannot be progressed or restarted.
        rejected(
            StateChangeEngine.apply(
                progressed,
                listOf(
                    StateChange(
                        StateChangeType.QUEST_OBJECTIVE_PROGRESS,
                        targetId = "q1",
                        objectiveIndex = 0,
                        amount = 1,
                    ),
                ),
            ),
        )
        rejected(
            StateChangeEngine.apply(
                progressed,
                listOf(
                    StateChange(
                        StateChangeType.QUEST_STATUS,
                        targetId = "q1",
                        questStatus = QuestStatus.ACTIVE,
                    ),
                ),
            ),
        )
    }

    @Test
    fun questObjectiveIndexIsValidated() {
        rejected(
            StateChangeEngine.apply(
                snapshot(),
                listOf(
                    StateChange(
                        StateChangeType.QUEST_OBJECTIVE_PROGRESS,
                        targetId = "q1",
                        objectiveIndex = 99,
                        amount = 1,
                    ),
                ),
            ),
        )
    }

    @Test
    fun locationChangeMarksDestinationDiscovered() {
        val moved = applied(
            StateChangeEngine.apply(
                snapshot(),
                listOf(StateChange(StateChangeType.LOCATION_CHANGE, targetId = "l2")),
            ),
        )
        assertEquals("l2", moved.currentLocationId)
        assertTrue(moved.locations.first { it.id == "l2" }.isDiscovered)
    }

    @Test
    fun locationChangeRejectsUnknownPlaceAndCombat() {
        rejected(
            StateChangeEngine.apply(
                snapshot(),
                listOf(StateChange(StateChangeType.LOCATION_CHANGE, targetId = "nowhere")),
            ),
        )
        val inCombat = snapshot(
            activeCombat = CombatState(
                enemyNpcId = "npc1",
                enemyName = "商人",
                enemyHp = 12,
                enemyMaxHp = 12,
                enemyAttackPower = 3,
                enemyDefense = 2,
            ),
        )
        rejected(
            StateChangeEngine.apply(
                inCombat,
                listOf(StateChange(StateChangeType.LOCATION_CHANGE, targetId = "l2")),
            ),
        )
    }

    @Test
    fun flagSetValidatesKeyAndLength() {
        val state = snapshot()
        val updated = applied(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.FLAG_SET, targetId = "quest.cat_found", value = "true")),
            ),
        )
        assertEquals("true", updated.flags["quest.cat_found"])

        rejected(
            StateChangeEngine.apply(
                state,
                listOf(StateChange(StateChangeType.FLAG_SET, targetId = "bad key!", value = "x")),
            ),
        )
        rejected(
            StateChangeEngine.apply(
                state,
                listOf(
                    StateChange(
                        StateChangeType.FLAG_SET,
                        targetId = "ok",
                        value = "x".repeat(StateChangeLimits.MAX_FLAG_VALUE_LENGTH + 1),
                    ),
                ),
            ),
        )
    }

    @Test
    fun combatStartUsesStatsAndEndPersistsRemainingHp() {
        val started = applied(
            StateChangeEngine.apply(
                snapshot(),
                listOf(StateChange(StateChangeType.COMBAT_START, targetId = "npc1")),
            ),
        )
        val combat = started.activeCombat
        assertTrue(combat != null)
        assertEquals(12, combat!!.enemyHp)
        assertEquals(12, combat.enemyMaxHp)
        assertEquals(3, combat.enemyAttackPower)
        assertEquals(2, combat.enemyDefense)

        val damaged = started.copy(activeCombat = combat.copy(enemyHp = 5))
        val ended = applied(
            StateChangeEngine.apply(damaged, listOf(StateChange(StateChangeType.COMBAT_END))),
        )
        assertNull(ended.activeCombat)
        assertEquals(5, ended.npcs.single { it.id == "npc1" }.combat?.hp)
    }

    @Test
    fun combatStartRejectsSecondCombatAndNonCombatant() {
        val inCombat = snapshot(
            activeCombat = CombatState(
                enemyNpcId = "npc1",
                enemyName = "商人",
                enemyHp = 12,
                enemyMaxHp = 12,
                enemyAttackPower = 3,
                enemyDefense = 2,
            ),
        )
        rejected(
            StateChangeEngine.apply(
                inCombat,
                listOf(StateChange(StateChangeType.COMBAT_START, targetId = "npc1")),
            ),
        )
        val peacefulNpc = snapshot(npcs = listOf(Npc(id = "npc2", name = "村人")))
        rejected(
            StateChangeEngine.apply(
                peacefulNpc,
                listOf(StateChange(StateChangeType.COMBAT_START, targetId = "npc2")),
            ),
        )
    }

    @Test
    fun tooManyChangesAreRejected() {
        val changes = List(StateChangeLimits.MAX_CHANGES_PER_TURN + 1) {
            StateChange(StateChangeType.HP_DELTA, amount = -1)
        }
        val errors = rejected(StateChangeEngine.apply(snapshot(), changes))
        assertTrue(errors.any { it.contains("多すぎます") })
    }

    @Test
    fun inventoryCapacityIsEnforcedForNewItems() {
        val fullInventory = List(StateChangeLimits.MAX_INVENTORY_SIZE) { index ->
            Item(id = "i$index", name = "品$index", quantity = 1)
        }
        val state = snapshot(character = Character(id = "pc", name = "主人公", inventory = fullInventory))
        rejected(
            StateChangeEngine.apply(
                state,
                listOf(
                    StateChange(
                        StateChangeType.ITEM_ADD,
                        item = Item(id = "", name = "新しい品", quantity = 1),
                    ),
                ),
            ),
        )
    }
}
