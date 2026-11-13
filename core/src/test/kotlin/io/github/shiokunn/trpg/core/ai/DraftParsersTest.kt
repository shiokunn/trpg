package io.github.shiokunn.trpg.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftParsersTest {

    private val validWorld = """
        {
          "name": "霧の港",
          "genre": "ファンタジー",
          "era": "中世",
          "setting": "海霧に覆われた港町",
          "atmosphere": "物憂げで秘密めいた",
          "history": "百年前に灯台が建てられた",
          "geography": "入り江と断崖",
          "socialStructure": "灯台守の会が実権を持つ",
          "factions": ["灯台守の会", "密輸業者"],
          "techMagic": "潮の魔法が生活に溶け込む",
          "specialSettings": ["夜になると霧が濃くなる"],
          "objective": "消えた灯台守の行方を追う",
          "problem": "港の灯りが消え、船が遭難し始めた",
          "secrets": ["灯台守は密輸の証拠を隠している"],
          "scenario": {
            "title": "消えた灯台守",
            "premise": "灯台守が姿を消し、港は混乱している",
            "length": "1セッション",
            "difficulty": "普通",
            "openingHook": "酒場で灯台の噂を耳にする"
          },
          "opening": {
            "location": {
              "name": "港の酒場",
              "description": "潮と酒の匂いが混ざる",
              "connections": ["桟橋"],
              "secrets": []
            },
            "npcs": [
              {
                "name": "マリナ",
                "description": "日に焼けた酒場の主人",
                "personality": "面倒見がよい",
                "occupation": "酒場の主人",
                "disposition": "友好的",
                "locationName": "港の酒場",
                "secrets": ["密輸に関与している"],
                "combat": {
                  "attackPower": 2,
                  "defense": 1,
                  "attackSkillLevel": 1,
                  "defenseSkillLevel": 0,
                  "hp": 8
                }
              }
            ],
            "quests": [
              {
                "title": "灯台守を探せ",
                "description": "消えた灯台守の行方を追う",
                "objectives": ["酒場で聞き込みをする", "灯台へ向かう"],
                "rewards": ["金貨10枚"]
              }
            ]
          }
        }
    """.trimIndent()

    private val validCharacter = """
        {
          "name": "リナ",
          "species": "人間",
          "occupation": "斥候",
          "background": "森の民の末裔",
          "personality": "慎重だが好奇心旺盛",
          "attributes": {"str": 2, "dex": 4, "int": 3, "wil": 2, "per": 4, "cha": 3},
          "skills": [
            {"name": "探索", "level": 2, "description": "痕跡を見つける"},
            {"name": "弓術", "level": 2, "description": "遠距離攻撃"},
            {"name": "交渉", "level": 2, "description": "説得する"}
          ],
          "items": [
            {"name": "短弓", "description": "使い込まれた弓", "quantity": 1, "type": "weapon", "power": 2, "defense": 0, "tags": ["遠距離"]}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesWorldDraft() {
        val result = WorldDraftParser.parse(validWorld)
        assertTrue(result is AiParseResult.Success)
        val world = (result as AiParseResult.Success).value
        assertEquals("霧の港", world.name)
        assertEquals("消えた灯台守", world.scenario.title)
        assertEquals(1, world.opening.npcs.size)
        assertEquals(8, world.opening.npcs.single().combat?.hp)
        assertEquals(2, world.opening.quests.single().objectives.size)
    }

    @Test
    fun worldRequiresNameAndGenreAndScenario() {
        val noGenre = validWorld.replaceFirst("\"genre\": \"ファンタジー\",", "")
        assertTrue(WorldDraftParser.parse(noGenre) is AiParseResult.Failure)

        val blankScenario = validWorld.replaceFirst("\"title\": \"消えた灯台守\"", "\"title\": \" \"")
        val errors = (WorldDraftParser.parse(blankScenario) as AiParseResult.Failure).errors
        assertTrue(errors.any { it.contains("scenario.title") })
    }

    @Test
    fun worldRejectsInvalidCombatHp() {
        val raw = validWorld.replaceFirst("\"hp\": 8", "\"hp\": 0")
        val errors = (WorldDraftParser.parse(raw) as AiParseResult.Failure).errors
        assertTrue(errors.any { it.contains("hp") })
    }

    @Test
    fun worldRejectsEmptyQuestObjectives() {
        val raw = validWorld.replaceFirst(
            "\"objectives\": [\"酒場で聞き込みをする\", \"灯台へ向かう\"]",
            "\"objectives\": []",
        )
        val errors = (WorldDraftParser.parse(raw) as AiParseResult.Failure).errors
        assertTrue(errors.any { it.contains("objectives") })
    }

    @Test
    fun worldRejectsUnknownKey() {
        val raw = validWorld.replaceFirst("{", "{\"mood\": \"dark\",")
        assertTrue(WorldDraftParser.parse(raw) is AiParseResult.Failure)
    }

    @Test
    fun worldAcceptsNullCombat() {
        val raw = validWorld.replace(
            Regex("\"combat\": \\{[\\s\\S]*?\\}"),
            "\"combat\": null",
        )
        val result = WorldDraftParser.parse(raw)
        assertTrue(result is AiParseResult.Success)
        assertNull((result as AiParseResult.Success).value.opening.npcs.single().combat)
    }

    @Test
    fun normalizesStringOpeningLocationSecrets() {
        val raw = validWorld.replaceFirst("\"secrets\": []", "\"secrets\": \"秘密\"")
        val result = WorldDraftParser.parse(raw)
        assertTrue(result is AiParseResult.Success)
        assertEquals(
            listOf("秘密"),
            (result as AiParseResult.Success).value.opening.location.secrets,
        )
    }

    @Test
    fun doesNotNormalizeOtherStringLists() {
        val raw = validWorld.replaceFirst(
            "\"factions\": [\"灯台守の会\", \"密輸業者\"]",
            "\"factions\": \"灯台守の会\"",
        )
        assertTrue(WorldDraftParser.parse(raw) is AiParseResult.Failure)
    }

    @Test
    fun doesNotNormalizeNonStringOpeningLocationSecrets() {
        listOf("\"secrets\": null", "\"secrets\": 1", "\"secrets\": {\"value\": \"秘密\"}")
            .forEach { replacement ->
                val raw = validWorld.replaceFirst("\"secrets\": []", replacement)
                assertTrue(
                    "expected failure for $replacement",
                    WorldDraftParser.parse(raw) is AiParseResult.Failure,
                )
            }
    }

    @Test
    fun worldRejectsMissingClosingQuote() {
        val raw = validWorld.replaceFirst("\"secrets\": []", "\"secrets\": [\"秘密]")
        assertTrue(WorldDraftParser.parse(raw) is AiParseResult.Failure)
    }

    @Test
    fun worldRejectsTrailingComma() {
        val raw = validWorld.replaceFirst("\"name\": \"霧の港\",", "\"name\": \"霧の港\",,")
        assertTrue(WorldDraftParser.parse(raw) is AiParseResult.Failure)
    }

    @Test
    fun worldRejectsStringCombatNumber() {
        val raw = validWorld.replaceFirst("\"hp\": 8", "\"hp\": \"8\"")
        assertTrue(WorldDraftParser.parse(raw) is AiParseResult.Failure)
    }

    @Test
    fun parsesCharacterDraftAndConvertsToDomain() {
        val result = CharacterDraftParser.parse(validCharacter)
        assertTrue(result is AiParseResult.Success)
        val draft = (result as AiParseResult.Success).value
        val character = draft.toCharacter()
        assertEquals("リナ", character.name)
        assertEquals(18, character.attributes.total())
        assertEquals(10, character.hp)
        assertEquals(3, character.skills.size)
        assertEquals(1, character.inventory.size)
        assertTrue(character.skills.all { it.id.isNotBlank() })
        assertTrue(character.inventory.single().id.isNotBlank())
    }

    @Test
    fun characterRejectsWrongAttributeSum() {
        val raw = validCharacter.replaceFirst("\"cha\": 3", "\"cha\": 5")
        val errors = (CharacterDraftParser.parse(raw) as AiParseResult.Failure).errors
        assertTrue(errors.any { it.contains("合計") })
    }

    @Test
    fun characterRejectsWrongSkillSumAndLevel() {
        val lowSum = validCharacter.replaceFirst("\"level\": 2, \"description\": \"説得する\"", "\"level\": 1, \"description\": \"説得する\"")
        assertTrue(CharacterDraftParser.parse(lowSum) is AiParseResult.Failure)

        val tooHigh = validCharacter.replaceFirst("\"level\": 2, \"description\": \"痕跡を見つける\"", "\"level\": 3, \"description\": \"痕跡を見つける\"")
        assertTrue(CharacterDraftParser.parse(tooHigh) is AiParseResult.Failure)
    }

    @Test
    fun characterRejectsZeroQuantityItem() {
        val raw = validCharacter.replaceFirst("\"quantity\": 1", "\"quantity\": 0")
        val errors = (CharacterDraftParser.parse(raw) as AiParseResult.Failure).errors
        assertTrue(errors.any { it.contains("quantity") })
    }

    @Test
    fun characterRejectsUnknownAttributeKey() {
        val raw = validCharacter.replaceFirst(
            "\"cha\": 3",
            "\"cha\": 3, \"luck\": 1",
        )
        assertTrue(CharacterDraftParser.parse(raw) is AiParseResult.Failure)
    }
}
