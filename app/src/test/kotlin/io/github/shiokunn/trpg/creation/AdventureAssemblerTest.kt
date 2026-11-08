package io.github.shiokunn.trpg.creation

import io.github.shiokunn.trpg.core.ai.CharacterDraft
import io.github.shiokunn.trpg.core.ai.ItemDraft
import io.github.shiokunn.trpg.core.ai.LocationDraft
import io.github.shiokunn.trpg.core.ai.NpcDraft
import io.github.shiokunn.trpg.core.ai.OpeningDraft
import io.github.shiokunn.trpg.core.ai.QuestDraft
import io.github.shiokunn.trpg.core.ai.ScenarioDraft
import io.github.shiokunn.trpg.core.ai.SkillDraft
import io.github.shiokunn.trpg.core.ai.WorldDraft
import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.CombatStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureAssemblerTest {

    @Test
    fun assemblesWorldScenarioAndOpeningEntities() {
        val adventure = AdventureAssembler.assemble(worldDraft(), characterDraft(), now = 1234L)

        assertEquals("霧の港", adventure.world.name)
        assertEquals("海洋ファンタジー", adventure.world.genre)
        assertEquals("失われた航海日誌", adventure.world.scenarioTitle)
        assertEquals("港町の謎を解く", adventure.world.scenarioPremise)
        assertEquals(1234L, adventure.world.createdAt)
        assertEquals(listOf("GM専用の真相"), adventure.world.secrets)

        assertEquals("失われた航海日誌", adventure.sessionTitle)
        val location = adventure.locations.single()
        assertEquals(adventure.openingLocationId, location.id)
        assertTrue(location.isDiscovered)
        assertEquals("灯台町", location.name)
        assertEquals(listOf("古い桟橋"), location.connections)

        val npc = adventure.npcs.single()
        assertEquals("灯台守のエル", npc.name)
        assertEquals(location.id, npc.locationId)
        assertTrue(npc.isDiscovered)
        assertEquals(listOf("灯台の下にhidden"), npc.secrets)
        assertEquals(5, npc.combat?.attackPower)

        val quest = adventure.quests.single()
        assertEquals("日誌を探す", quest.title)
        assertEquals(listOf("灯台を調べる", "港の人に聞く"), quest.objectives.map { it.description })
        assertTrue(quest.objectives.none { it.completed })
        assertEquals(listOf("銀貨10枚"), quest.rewards)
    }

    @Test
    fun characterDraftBecomesPlayableCharacterWithIdsAndInitialHp() {
        val adventure = AdventureAssembler.assemble(worldDraft(), characterDraft())
        val character = adventure.character

        assertTrue(character.id.isNotBlank())
        assertEquals("ミナ", character.name)
        assertEquals(10, character.hp)
        assertEquals(10, character.maxHp)
        assertEquals(18, character.attributes.total())
        assertEquals(4, character.skills.size)
        assertEquals(6, character.skills.sumOf { it.level })
        assertTrue(character.skills.all { it.id.isNotBlank() })
        assertEquals(2, character.inventory.size)
        assertTrue(character.inventory.all { it.id.isNotBlank() })
        assertTrue(character.equipment.isEmpty())
    }

    @Test
    fun npcAtAnotherLocationGetsNoOpeningLocationId() {
        val draft = worldDraft().let { world ->
            world.copy(
                opening = world.opening.copy(
                    npcs = world.opening.npcs + world.opening.npcs.first().copy(
                        name = "旅人",
                        locationName = "別の町",
                    ),
                ),
            )
        }
        val adventure = AdventureAssembler.assemble(draft, characterDraft())
        val traveller = adventure.npcs.first { it.name == "旅人" }
        assertNull(traveller.locationId)
        assertEquals(2, adventure.npcs.map { it.id }.distinct().size)
    }

    @Test
    fun combatStatsAreNormalized() {
        val draft = worldDraft().let { world ->
            world.copy(
                opening = world.opening.copy(
                    npcs = listOf(
                        world.opening.npcs.first().copy(
                            combat = CombatStats(
                                attackPower = 99,
                                defense = -5,
                                attackSkillLevel = 9,
                                defenseSkillLevel = 4,
                                hp = 5000,
                            ),
                        ),
                    ),
                ),
            )
        }
        val combat = AdventureAssembler.assemble(draft, characterDraft()).npcs.single().combat!!
        assertEquals(CombatStats.MAX_HP, combat.hp)
        assertEquals(20, combat.attackPower)
        assertEquals(0, combat.defense)
        assertEquals(3, combat.attackSkillLevel)
        assertEquals(3, combat.defenseSkillLevel)
    }

    @Test
    fun blankScenarioTitleFallsBackToWorldName() {
        val draft = worldDraft().let { it.copy(scenario = it.scenario.copy(title = "  ")) }
        val adventure = AdventureAssembler.assemble(draft, characterDraft())
        assertEquals("霧の港", adventure.sessionTitle)
    }

    private fun worldDraft() = WorldDraft(
        name = "霧の港",
        genre = "海洋ファンタジー",
        era = "帆船の時代",
        setting = "霧に閉ざされた港町",
        atmosphere = "静かで不穏",
        objective = "航海日誌を見つける",
        problem = "港から船が出なくなった",
        secrets = listOf("GM専用の真相"),
        scenario = ScenarioDraft(
            title = "失われた航海日誌",
            premise = "港町の謎を解く",
            length = "2時間",
            difficulty = "普通",
            openingHook = "船が停泊している",
        ),
        opening = OpeningDraft(
            location = LocationDraft(
                name = "灯台町",
                description = "霧の灯台がある",
                connections = listOf("古い桟橋"),
                secrets = listOf("灯台の地下通路"),
            ),
            npcs = listOf(
                NpcDraft(
                    name = "灯台守のエル",
                    description = "年老いた灯台守",
                    personality = "寡黙",
                    occupation = "灯台守",
                    disposition = "警戒している",
                    locationName = "灯台町",
                    secrets = listOf("灯台の下にhidden"),
                    combat = CombatStats(attackPower = 5, defense = 1, hp = 12),
                ),
            ),
            quests = listOf(
                QuestDraft(
                    title = "日誌を探す",
                    description = "港のどこかにある",
                    objectives = listOf("灯台を調べる", "港の人に聞く"),
                    rewards = listOf("銀貨10枚"),
                ),
            ),
        ),
    )

    private fun characterDraft() = CharacterDraft(
        name = "ミナ",
        species = "人間",
        occupation = "航海士",
        background = "船を失った",
        personality = "好奇心旺盛",
        attributes = Attributes(str = 3, dex = 4, int = 3, wil = 3, per = 3, cha = 2),
        skills = listOf(
            SkillDraft("航海", 2),
            SkillDraft("観察", 2),
            SkillDraft("交渉", 1),
            SkillDraft("登攀", 1),
        ),
        items = listOf(
            ItemDraft("羅針盤", "古いが正確", 1, "tool", 0, 0),
            ItemDraft("短剣", "護身用", 1, "weapon", 2, 0),
        ),
    )
}
