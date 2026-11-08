package io.github.shiokunn.trpg.core.backup

import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.CombatStats
import io.github.shiokunn.trpg.core.domain.Item
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.Skill

/**
 * Validates a decoded backup before a single row is written. Import must fail closed:
 * one invalid session rejects the whole file so existing saves cannot be half-replaced.
 */
object BackupValidator {

    fun validate(bundle: BackupBundle): List<String> {
        val errors = mutableListOf<String>()

        if (bundle.format != BackupBundle.FORMAT) {
            errors += "このファイルはTRPGオフラインのバックアップではありません"
            return errors
        }
        if (bundle.version !in 1..BackupBundle.CURRENT_VERSION) {
            errors += "対応していないバックアップバージョンです（${bundle.version}）"
            return errors
        }
        if (bundle.worlds.size > MAX_WORLDS) {
            errors += "世界データが多すぎます（最大${MAX_WORLDS}件）"
        }
        if (bundle.sessions.size > MAX_SESSIONS) {
            errors += "セーブデータが多すぎます（最大${MAX_SESSIONS}件）"
        }

        val worldIds = mutableSetOf<String>()
        bundle.worlds.forEachIndexed { index, world ->
            val label = "worlds[$index]"
            requireId(worldIds, world.id, "${label}.id", errors)
            requireText(world.name, "${label}.name", 120, errors)
            requireText(world.genre, "${label}.genre", 120, errors)
            checkLengths(errors, label, world.setting, world.history, world.geography, world.socialStructure)
        }

        val sessionIds = mutableSetOf<String>()
        val characterIds = mutableSetOf<String>()
        val npcIds = mutableSetOf<String>()
        val locationIds = mutableSetOf<String>()
        val questIds = mutableSetOf<String>()
        val messageIds = mutableSetOf<String>()
        val memoryIds = mutableSetOf<String>()
        val npcMemoryIds = mutableSetOf<String>()
        val eventIds = mutableSetOf<String>()

        bundle.sessions.forEachIndexed { index, backup ->
            val label = "sessions[$index]"
            val session = backup.session
            requireId(sessionIds, session.id, "${label}.session.id", errors)
            if (session.worldId !in worldIds) {
                errors += "${label}.session.worldId「${session.worldId}」に対応する世界がありません"
            }
            if (session.title.isBlank()) errors += "${label}.session.titleが空です"
            if (session.turnCount < 0) errors += "${label}.session.turnCountが負の値です"

            validateCharacter(backup.character, session.characterId, "${label}.character", characterIds, errors)

            if (backup.locations.size > MAX_LOCATIONS) errors += "${label}.locationsが多すぎます"
            backup.locations.forEachIndexed { i, location ->
                val place = "${label}.locations[$i]"
                requireId(locationIds, location.id, "$place.id", errors)
                requireText(location.name, "$place.name", 120, errors)
            }
            if (backup.npcs.size > MAX_NPCS) errors += "${label}.npcsが多すぎます"
            backup.npcs.forEachIndexed { i, npc ->
                validateNpc(npc, "${label}.npcs[$i]", npcIds, errors)
                if (npc.locationId != null && backup.locations.none { it.id == npc.locationId }) {
                    errors += "${label}.npcs[$i].locationId「${npc.locationId}」が存在しません"
                }
            }
            if (backup.quests.size > MAX_QUESTS) errors += "${label}.questsが多すぎます"
            backup.quests.forEachIndexed { i, quest ->
                val place = "${label}.quests[$i]"
                requireId(questIds, quest.id, "$place.id", errors)
                requireText(quest.title, "$place.title", 120, errors)
                if (quest.objectives.size > MAX_OBJECTIVES) errors += "$place.objectivesが多すぎます"
            }

            if (session.currentLocationId != null &&
                backup.locations.none { it.id == session.currentLocationId }
            ) {
                errors += "${label}.session.currentLocationId「${session.currentLocationId}」が存在しません"
            }
            session.activeCombat?.let { combat ->
                if (backup.npcs.none { it.id == combat.enemyNpcId }) {
                    errors += "${label}.session.activeCombat.enemyNpcId「${combat.enemyNpcId}」が存在しません"
                }
                if (combat.enemyHp < 0 || combat.enemyHp > combat.enemyMaxHp) {
                    errors += "${label}.session.activeCombatのHPが不正です"
                }
            }

            if (backup.flags.size > MAX_FLAGS) errors += "${label}.flagsが多すぎます"
            backup.flags.forEach { (key, value) ->
                if (key.isBlank() || key.length > MAX_FLAG_KEY) errors += "${label}.flagsのキーが不正です"
                if (value.length > MAX_FLAG_VALUE) errors += "${label}.flags「$key」の値が長すぎます"
            }

            if (backup.chat.size > MAX_CHAT) errors += "${label}.chatが多すぎます"
            backup.chat.forEachIndexed { i, message ->
                requireId(messageIds, message.id, "${label}.chat[$i].id", errors)
                if (message.content.length > MAX_CHAT_CONTENT) errors += "${label}.chat[$i].contentが長すぎます"
                if (message.turn < 0) errors += "${label}.chat[$i].turnが負の値です"
            }

            if (backup.dice.size > MAX_DICE) errors += "${label}.diceが多すぎます"
            backup.dice.forEachIndexed { i, roll ->
                if (roll.dice.isEmpty() || roll.dice.size > 2 || roll.dice.any { it !in 1..6 }) {
                    errors += "${label}.dice[$i]の出目が不正です"
                }
                if (roll.difficulty !in 1..99) errors += "${label}.dice[$i].difficultyが不正です"
            }

            if (backup.memories.size > MAX_MEMORIES) errors += "${label}.memoriesが多すぎます"
            backup.memories.forEachIndexed { i, memory ->
                requireId(memoryIds, memory.id, "${label}.memories[$i].id", errors)
                if (memory.content.isBlank() || memory.content.length > MAX_MEMORY_CONTENT) {
                    errors += "${label}.memories[$i].contentが不正です"
                }
                if (memory.importance !in 1..5) errors += "${label}.memories[$i].importanceが1〜5ではありません"
                if (memory.relatedIds.size > MAX_RELATED_IDS) errors += "${label}.memories[$i].relatedIdsが多すぎます"
            }

            if (backup.npcMemories.size > MAX_NPC_MEMORIES) errors += "${label}.npcMemoriesが多すぎます"
            backup.npcMemories.forEachIndexed { i, memory ->
                requireId(npcMemoryIds, memory.id, "${label}.npcMemories[$i].id", errors)
                if (backup.npcs.none { it.id == memory.npcId }) {
                    errors += "${label}.npcMemories[$i].npcId「${memory.npcId}」が存在しません"
                }
                if (memory.content.isBlank() || memory.content.length > MAX_MEMORY_CONTENT) {
                    errors += "${label}.npcMemories[$i].contentが不正です"
                }
                if (memory.importance !in 1..5) errors += "${label}.npcMemories[$i].importanceが1〜5ではありません"
            }

            if (backup.events.size > MAX_EVENTS) errors += "${label}.eventsが多すぎます"
            backup.events.forEachIndexed { i, event ->
                requireId(eventIds, event.id, "${label}.events[$i].id", errors)
                if (event.type.isBlank() || event.type.length > 40) errors += "${label}.events[$i].typeが不正です"
                if (event.summary.length > MAX_EVENT_SUMMARY) errors += "${label}.events[$i].summaryが長すぎます"
                if (event.payloadJson.length > MAX_EVENT_PAYLOAD) errors += "${label}.events[$i].payloadJsonが長すぎます"
            }
        }

        return errors
    }

    private fun validateCharacter(
        character: Character,
        expectedId: String,
        label: String,
        characterIds: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        requireId(characterIds, character.id, "${label}.id", errors)
        if (character.id != expectedId) {
            errors += "${label}.id「${character.id}」がセッションのcharacterId「$expectedId」と一致しません"
        }
        requireText(character.name, "${label}.name", 80, errors)
        if (!character.attributes.allInRange()) {
            errors += "${label}.attributesは各1〜5にしてください"
        }
        if (character.maxHp !in 1..MAX_HP) errors += "${label}.maxHpが不正です"
        if (character.hp !in 0..character.maxHp) errors += "${label}.hpが不正です"
        if (character.experience !in 0..MAX_XP) errors += "${label}.experienceが不正です"

        if (character.skills.size > MAX_SKILLS) errors += "${label}.skillsが多すぎます"
        val skillIds = mutableSetOf<String>()
        val skillNames = mutableSetOf<String>()
        character.skills.forEachIndexed { i, skill ->
            requireId(skillIds, skill.id, "${label}.skills[$i].id", errors)
            if (skill.name.isBlank() || skill.name.length > 40) errors += "${label}.skills[$i].nameが不正です"
            if (!skillNames.add(skill.name)) errors += "${label}.skills[$i].nameが重複しています"
            if (skill.level !in Skill.MIN_LEVEL..Skill.MAX_LEVEL) errors += "${label}.skills[$i].levelが不正です"
        }

        if (character.inventory.size > MAX_ITEMS) errors += "${label}.inventoryが多すぎます"
        val itemIds = mutableSetOf<String>()
        character.inventory.forEachIndexed { i, item ->
            requireId(itemIds, item.id, "${label}.inventory[$i].id", errors)
            if (item.name.isBlank() || item.name.length > 80) errors += "${label}.inventory[$i].nameが不正です"
            if (item.quantity !in 1..Item.MAX_QUANTITY) errors += "${label}.inventory[$i].quantityが不正です"
            if (item.power !in 0..20 || item.defense !in 0..20) errors += "${label}.inventory[$i]の戦闘値が不正です"
        }
        if (character.equipment.any { it !in itemIds }) {
            errors += "${label}.equipmentに存在しないアイテムIDがあります"
        }
    }

    private fun validateNpc(
        npc: Npc,
        label: String,
        npcIds: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        requireId(npcIds, npc.id, "${label}.id", errors)
        requireText(npc.name, "${label}.name", 120, errors)
        if (npc.relationship !in -100..100) errors += "${label}.relationshipが-100〜100ではありません"
        npc.combat?.let { combat ->
            if (combat.attackPower !in 0..20) errors += "${label}.combat.attackPowerが不正です"
            if (combat.defense !in 0..20) errors += "${label}.combat.defenseが不正です"
            if (combat.attackSkillLevel !in 0..Skill.MAX_LEVEL) errors += "${label}.combat.attackSkillLevelが不正です"
            if (combat.defenseSkillLevel !in 0..Skill.MAX_LEVEL) errors += "${label}.combat.defenseSkillLevelが不正です"
            if (combat.hp !in 1..CombatStats.MAX_HP) errors += "${label}.combat.hpが不正です"
        }
    }

    private fun requireId(
        seen: MutableSet<String>,
        id: String,
        label: String,
        errors: MutableList<String>,
    ) {
        if (id.isBlank()) {
            errors += "${label}が空です"
        } else if (!seen.add(id)) {
            errors += "${label}が重複しています（$id）"
        }
    }

    private fun requireText(value: String, label: String, max: Int, errors: MutableList<String>) {
        if (value.isBlank()) {
            errors += "${label}が空です"
        } else if (value.length > max) {
            errors += "${label}が長すぎます（最大${max}文字）"
        }
    }

    private fun checkLengths(errors: MutableList<String>, label: String, vararg values: String) {
        values.forEach { value ->
            if (value.length > MAX_LONG_TEXT) errors += "${label}のテキストが長すぎます（最大${MAX_LONG_TEXT}文字）"
        }
    }

    private const val MAX_WORLDS = 200
    private const val MAX_SESSIONS = 200
    private const val MAX_NPCS = 200
    private const val MAX_LOCATIONS = 200
    private const val MAX_QUESTS = 200
    private const val MAX_OBJECTIVES = 50
    private const val MAX_CHAT = 20_000
    private const val MAX_CHAT_CONTENT = 20_000
    private const val MAX_DICE = 10_000
    private const val MAX_MEMORIES = 5_000
    private const val MAX_MEMORY_CONTENT = 1_000
    private const val MAX_RELATED_IDS = 50
    private const val MAX_NPC_MEMORIES = 2_000
    private const val MAX_EVENTS = 10_000
    private const val MAX_EVENT_SUMMARY = 1_000
    private const val MAX_EVENT_PAYLOAD = 100_000
    private const val MAX_FLAGS = 500
    private const val MAX_FLAG_KEY = 64
    private const val MAX_FLAG_VALUE = 200
    private const val MAX_SKILLS = 50
    private const val MAX_ITEMS = 100
    private const val MAX_HP = 999
    private const val MAX_XP = 9_999_999
    private const val MAX_LONG_TEXT = 8_000
}
