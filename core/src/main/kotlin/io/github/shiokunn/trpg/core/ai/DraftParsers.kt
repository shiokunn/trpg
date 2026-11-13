package io.github.shiokunn.trpg.core.ai

import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.CombatStats
import io.github.shiokunn.trpg.core.domain.Item
import io.github.shiokunn.trpg.core.domain.Skill
import io.github.shiokunn.trpg.core.rules.CharacterRules
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

object WorldDraftParser {

    fun parse(raw: String): AiParseResult<WorldDraft> {
        val candidate = AiJson.extractObject(raw)
            ?: return AiParseResult.Failure(listOf("JSONオブジェクトが見つかりません。JSONのみを出力してください。"))
        val draft = try {
            val normalized = normalizeOpeningLocationSecrets(candidate)
            AiJson.strict.decodeFromString(WorldDraft.serializer(), normalized)
        } catch (t: SerializationException) {
            return AiParseResult.Failure(listOf("JSONスキーマが不正です: ${t.message ?: "解析に失敗しました"}"))
        } catch (t: IllegalArgumentException) {
            return AiParseResult.Failure(listOf("JSONの値が不正です: ${t.message ?: "解析に失敗しました"}"))
        }
        val errors = validate(draft)
        return if (errors.isEmpty()) AiParseResult.Success(draft) else AiParseResult.Failure(errors)
    }

    /**
     * Narrow compatibility fix for AI output that uses a single string where the
     * contract requires List<String>. Only opening.location.secrets is converted;
     * every other field keeps the strict parser behaviour. null, numbers, objects and
     * already-valid arrays are left untouched so the strict decoder still rejects them.
     */
    private fun normalizeOpeningLocationSecrets(candidate: String): String {
        val root = AiJson.strict.parseToJsonElement(candidate) as? JsonObject ?: return candidate
        val opening = root["opening"] as? JsonObject ?: return candidate
        val location = opening["location"] as? JsonObject ?: return candidate
        val secrets = location["secrets"]
        if (secrets !is JsonPrimitive || !secrets.isString) return candidate

        val normalizedLocation = JsonObject(
            location + ("secrets" to JsonArray(listOf(secrets))),
        )
        val normalizedOpening = JsonObject(
            opening + ("location" to normalizedLocation),
        )
        val normalizedRoot = JsonObject(
            root + ("opening" to normalizedOpening),
        )
        println("[JsonNormalizer] normalized field=opening.location.secrets type=string->array")
        return normalizedRoot.toString()
    }

    fun validate(draft: WorldDraft): List<String> {
        val errors = mutableListOf<String>()

        requireText(errors, "name", draft.name, DraftLimits.MAX_NAME, required = true)
        requireText(errors, "genre", draft.genre, DraftLimits.MAX_NAME, required = true)
        optionalText(errors, "era", draft.era, DraftLimits.MAX_SHORT_TEXT)
        optionalText(errors, "setting", draft.setting, DraftLimits.MAX_LONG_TEXT)
        optionalText(errors, "atmosphere", draft.atmosphere, DraftLimits.MAX_SHORT_TEXT)
        optionalText(errors, "history", draft.history, DraftLimits.MAX_LONG_TEXT)
        optionalText(errors, "geography", draft.geography, DraftLimits.MAX_LONG_TEXT)
        optionalText(errors, "socialStructure", draft.socialStructure, DraftLimits.MAX_LONG_TEXT)
        optionalText(errors, "techMagic", draft.techMagic, DraftLimits.MAX_LONG_TEXT)
        optionalText(errors, "objective", draft.objective, DraftLimits.MAX_SHORT_TEXT)
        optionalText(errors, "problem", draft.problem, DraftLimits.MAX_SHORT_TEXT)

        stringList(errors, "factions", draft.factions, DraftLimits.MAX_LIST_ITEMS, DraftLimits.MAX_NAME)
        stringList(errors, "specialSettings", draft.specialSettings, DraftLimits.MAX_LIST_ITEMS, DraftLimits.MAX_SHORT_TEXT)
        stringList(errors, "secrets", draft.secrets, DraftLimits.MAX_LIST_ITEMS, DraftLimits.MAX_SHORT_TEXT)

        with(draft.scenario) {
            requireText(errors, "scenario.title", title, 100, required = true)
            requireText(errors, "scenario.premise", premise, DraftLimits.MAX_LONG_TEXT, required = true)
            optionalText(errors, "scenario.length", length, 100)
            optionalText(errors, "scenario.difficulty", difficulty, 100)
            optionalText(errors, "scenario.openingHook", openingHook, DraftLimits.MAX_SHORT_TEXT)
        }

        with(draft.opening) {
            requireText(errors, "opening.location.name", location.name, DraftLimits.MAX_NAME, required = true)
            optionalText(errors, "opening.location.description", location.description, DraftLimits.MAX_LONG_TEXT)
            stringList(errors, "opening.location.connections", location.connections, DraftLimits.MAX_LIST_ITEMS, DraftLimits.MAX_NAME)
            stringList(errors, "opening.location.secrets", location.secrets, DraftLimits.MAX_LIST_ITEMS, DraftLimits.MAX_SHORT_TEXT)

            if (npcs.size > DraftLimits.MAX_NPCS) {
                errors += "opening.npcsが多すぎます（最大${DraftLimits.MAX_NPCS}人）"
            }
            npcs.forEachIndexed { index, npc ->
                val label = "opening.npcs[$index]"
                requireText(errors, "$label.name", npc.name, DraftLimits.MAX_NAME, required = true)
                optionalText(errors, "$label.description", npc.description, DraftLimits.MAX_LONG_TEXT)
                optionalText(errors, "$label.personality", npc.personality, DraftLimits.MAX_SHORT_TEXT)
                optionalText(errors, "$label.occupation", npc.occupation, DraftLimits.MAX_NAME)
                optionalText(errors, "$label.disposition", npc.disposition, DraftLimits.MAX_SHORT_TEXT)
                optionalText(errors, "$label.locationName", npc.locationName, DraftLimits.MAX_NAME)
                stringList(errors, "$label.secrets", npc.secrets, DraftLimits.MAX_LIST_ITEMS, DraftLimits.MAX_SHORT_TEXT)
                npc.combat?.let { combat -> validateCombat(errors, "$label.combat", combat) }
            }

            if (quests.size > DraftLimits.MAX_QUESTS) {
                errors += "opening.questsが多すぎます（最大${DraftLimits.MAX_QUESTS}件）"
            }
            quests.forEachIndexed { index, quest ->
                val label = "opening.quests[$index]"
                requireText(errors, "$label.title", quest.title, DraftLimits.MAX_NAME, required = true)
                optionalText(errors, "$label.description", quest.description, DraftLimits.MAX_SHORT_TEXT)
                if (quest.objectives.isEmpty()) {
                    errors += "$label.objectivesが空です"
                } else if (quest.objectives.size > DraftLimits.MAX_OBJECTIVES) {
                    errors += "$label.objectivesが多すぎます（最大${DraftLimits.MAX_OBJECTIVES}件）"
                }
                quest.objectives.forEachIndexed { objectiveIndex, objective ->
                    if (objective.isBlank()) {
                        errors += "$label.objectives[$objectiveIndex]が空です"
                    }
                }
                stringList(errors, "$label.rewards", quest.rewards, DraftLimits.MAX_LIST_ITEMS, DraftLimits.MAX_SHORT_TEXT)
            }
        }

        return errors
    }

    private fun validateCombat(errors: MutableList<String>, label: String, combat: CombatStats) {
        if (combat.attackPower !in 0..20) errors += "$label.attackPowerは0〜20にしてください"
        if (combat.defense !in 0..20) errors += "$label.defenseは0〜20にしてください"
        if (combat.attackSkillLevel !in 0..Skill.MAX_LEVEL) errors += "$label.attackSkillLevelは0〜3にしてください"
        if (combat.defenseSkillLevel !in 0..Skill.MAX_LEVEL) errors += "$label.defenseSkillLevelは0〜3にしてください"
        if (combat.hp !in 1..CombatStats.MAX_HP) errors += "$label.hpは1〜${CombatStats.MAX_HP}にしてください"
    }
}

object CharacterDraftParser {

    fun parse(raw: String): AiParseResult<CharacterDraft> {
        val candidate = AiJson.extractObject(raw)
            ?: return AiParseResult.Failure(listOf("JSONオブジェクトが見つかりません。JSONのみを出力してください。"))
        val draft = try {
            AiJson.strict.decodeFromString(CharacterDraft.serializer(), candidate)
        } catch (t: SerializationException) {
            return AiParseResult.Failure(listOf("JSONスキーマが不正です: ${t.message ?: "解析に失敗しました"}"))
        } catch (t: IllegalArgumentException) {
            return AiParseResult.Failure(listOf("JSONの値が不正です: ${t.message ?: "解析に失敗しました"}"))
        }
        val errors = validate(draft)
        return if (errors.isEmpty()) AiParseResult.Success(draft) else AiParseResult.Failure(errors)
    }

    fun validate(draft: CharacterDraft): List<String> {
        val errors = mutableListOf<String>()

        requireText(errors, "name", draft.name, 40, required = true)
        optionalText(errors, "species", draft.species, DraftLimits.MAX_NAME)
        optionalText(errors, "occupation", draft.occupation, DraftLimits.MAX_NAME)
        optionalText(errors, "background", draft.background, DraftLimits.MAX_LONG_TEXT)
        optionalText(errors, "personality", draft.personality, DraftLimits.MAX_SHORT_TEXT)

        errors += CharacterRules.validateAttributes(draft.attributes)

        val skills = draft.skills.map { Skill(id = "", name = it.name.trim(), level = it.level) }
        errors += CharacterRules.validateSkillsForCreation(skills)
        draft.skills.forEachIndexed { index, skill ->
            if (skill.name.length > 40) {
                errors += "skills[$index].nameが長すぎます（最大40文字）"
            }
            if (skill.description.length > DraftLimits.MAX_SHORT_TEXT) {
                errors += "skills[$index].descriptionが長すぎます（最大${DraftLimits.MAX_SHORT_TEXT}文字）"
            }
        }

        if (draft.items.size > DraftLimits.MAX_ITEMS) {
            errors += "itemsが多すぎます（最大${DraftLimits.MAX_ITEMS}件）"
        }
        draft.items.forEachIndexed { index, item ->
            val label = "items[$index]"
            requireText(errors, "$label.name", item.name, DraftLimits.MAX_NAME, required = true)
            optionalText(errors, "$label.description", item.description, DraftLimits.MAX_SHORT_TEXT)
            if (item.quantity !in 1..Item.MAX_QUANTITY) {
                errors += "$label.quantityは1〜${Item.MAX_QUANTITY}にしてください"
            }
            if (item.power !in 0..20) errors += "$label.powerは0〜20にしてください"
            if (item.defense !in 0..20) errors += "$label.defenseは0〜20にしてください"
            if (item.tags.size > DraftLimits.MAX_ITEM_TAGS) {
                errors += "$label.tagsが多すぎます（最大${DraftLimits.MAX_ITEM_TAGS}件）"
            }
        }

        return errors
    }
}

/** Conversions used once a draft has passed validation. */
fun CharacterDraft.toCharacter(): Character = Character(
    id = UUID.randomUUID().toString(),
    name = name.trim(),
    species = species.trim(),
    occupation = occupation.trim(),
    background = background.trim(),
    personality = personality.trim(),
    attributes = attributes,
    skills = skills.map { skill ->
        Skill(
            id = UUID.randomUUID().toString(),
            name = skill.name.trim(),
            level = skill.level,
            description = skill.description.trim(),
        )
    },
    hp = CharacterRules.INITIAL_HP,
    maxHp = CharacterRules.INITIAL_HP,
    inventory = items.map { item ->
        Item(
            id = UUID.randomUUID().toString(),
            name = item.name.trim(),
            description = item.description.trim(),
            quantity = item.quantity.coerceIn(1, Item.MAX_QUANTITY),
            type = item.type.ifBlank { "misc" },
            power = item.power.coerceIn(0, 20),
            defense = item.defense.coerceIn(0, 20),
            tags = item.tags,
        )
    },
)

private fun requireText(
    errors: MutableList<String>,
    label: String,
    value: String,
    max: Int,
    required: Boolean,
) {
    if (required && value.isBlank()) {
        errors += "${label}が空です"
    } else if (value.length > max) {
        errors += "${label}が長すぎます（最大${max}文字）"
    }
}

private fun optionalText(errors: MutableList<String>, label: String, value: String, max: Int) {
    if (value.length > max) {
        errors += "${label}が長すぎます（最大${max}文字）"
    }
}

private fun stringList(
    errors: MutableList<String>,
    label: String,
    values: List<String>,
    maxItems: Int,
    maxLength: Int,
) {
    if (values.size > maxItems) {
        errors += "${label}が多すぎます（最大${maxItems}件）"
    }
    values.forEachIndexed { index, value ->
        if (value.isBlank()) {
            errors += "${label}[$index]が空です"
        } else if (value.length > maxLength) {
            errors += "${label}[$index]が長すぎます（最大${maxLength}文字）"
        }
    }
}
