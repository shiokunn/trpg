package io.github.shiokunn.trpg.core.state

import io.github.shiokunn.trpg.core.domain.QuestStatus
import kotlin.math.abs

/**
 * Validates an entire turn's state changes without mutating anything.
 *
 * A single invalid entry rejects the whole list: the caller must then keep the
 * previous save untouched. Values that are merely out of range are rejected rather
 * than silently clamped where the intent is ambiguous (HP/XP are clamped by the
 * applier because their bounds are known).
 */
object StateChangeValidator {

    private val flagKey = Regex("[A-Za-z0-9_.:-]{1,${StateChangeLimits.MAX_FLAG_KEY_LENGTH}}")

    fun validate(snapshot: GameSnapshot, changes: List<StateChange>): ValidationResult {
        if (changes.isEmpty()) return ValidationResult.Valid
        val errors = mutableListOf<String>()

        if (changes.size > StateChangeLimits.MAX_CHANGES_PER_TURN) {
            errors += "状態変更が多すぎます（${changes.size}件、最大${StateChangeLimits.MAX_CHANGES_PER_TURN}件）"
        }

        changes.forEachIndexed { index, change ->
            val label = "${index + 1}件目 ${change.type}"
            when (change.type) {
                StateChangeType.HP_DELTA -> validateHp(change, label, errors)
                StateChangeType.XP_DELTA -> validateXp(change, label, errors)
                StateChangeType.ITEM_ADD -> validateItemAdd(snapshot, change, label, errors)
                StateChangeType.ITEM_REMOVE -> validateItemRemove(snapshot, change, label, errors)
                StateChangeType.NPC_RELATION_DELTA -> validateRelation(snapshot, change, label, errors)
                StateChangeType.QUEST_OBJECTIVE_PROGRESS -> validateObjective(snapshot, change, label, errors)
                StateChangeType.QUEST_STATUS -> validateQuestStatus(snapshot, change, label, errors)
                StateChangeType.LOCATION_CHANGE -> validateLocation(snapshot, change, label, errors)
                StateChangeType.FLAG_SET -> validateFlag(change, label, errors)
                StateChangeType.COMBAT_START -> validateCombatStart(snapshot, change, label, errors)
                StateChangeType.COMBAT_END -> {
                    if (snapshot.activeCombat == null) {
                        errors += "$label: 戦闘中ではありません"
                    }
                }
            }
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateHp(change: StateChange, label: String, errors: MutableList<String>) {
        if (change.amount == 0) {
            errors += "$label: HP変化量が0です"
        } else if (abs(change.amount) > StateChangeLimits.MAX_HP_DELTA) {
            errors += "$label: HP変化量が大きすぎます（最大${StateChangeLimits.MAX_HP_DELTA}）"
        }
    }

    private fun validateXp(change: StateChange, label: String, errors: MutableList<String>) {
        if (change.amount == 0) {
            errors += "$label: 経験値変化量が0です"
        } else if (abs(change.amount) > StateChangeLimits.MAX_XP_DELTA) {
            errors += "$label: 経験値変化量が大きすぎます（最大${StateChangeLimits.MAX_XP_DELTA}）"
        }
    }

    private fun validateItemAdd(
        snapshot: GameSnapshot,
        change: StateChange,
        label: String,
        errors: MutableList<String>,
    ) {
        val item = change.item
        if (item == null) {
            errors += "$label: itemがありません"
            return
        }
        if (item.name.isBlank()) {
            errors += "$label: アイテム名が空です"
        }
        if (item.name.length > StateChangeLimits.MAX_ITEM_NAME_LENGTH) {
            errors += "$label: アイテム名が長すぎます（最大${StateChangeLimits.MAX_ITEM_NAME_LENGTH}文字）"
        }
        if (item.description.length > StateChangeLimits.MAX_ITEM_DESCRIPTION_LENGTH) {
            errors += "$label: アイテム説明が長すぎます（最大${StateChangeLimits.MAX_ITEM_DESCRIPTION_LENGTH}文字）"
        }
        if (item.quantity !in 1..io.github.shiokunn.trpg.core.domain.Item.MAX_QUANTITY) {
            errors += "$label: アイテム個数が範囲外です（1..${io.github.shiokunn.trpg.core.domain.Item.MAX_QUANTITY}）"
        }
        val merges = snapshot.character.inventory.any { existing ->
            (item.id.isNotBlank() && existing.id == item.id) || existing.name == item.name
        }
        if (!merges && snapshot.character.inventory.size >= StateChangeLimits.MAX_INVENTORY_SIZE) {
            errors += "$label: 所持品がいっぱいです（最大${StateChangeLimits.MAX_INVENTORY_SIZE}種類）"
        }
    }

    private fun validateItemRemove(
        snapshot: GameSnapshot,
        change: StateChange,
        label: String,
        errors: MutableList<String>,
    ) {
        if (change.targetId.isBlank()) {
            errors += "$label: アイテムIDが空です"
            return
        }
        val existing = snapshot.character.itemById(change.targetId)
        if (existing == null) {
            errors += "$label: 所持していないアイテムです（${change.targetId}）"
            return
        }
        val quantity = if (change.amount == 0) 1 else change.amount
        if (quantity <= 0) {
            errors += "$label: 個数は1以上にしてください"
        } else if (quantity > existing.quantity) {
            errors += "$label: 所持数を超えて削除できません（所持${existing.quantity}）"
        }
    }

    private fun validateRelation(
        snapshot: GameSnapshot,
        change: StateChange,
        label: String,
        errors: MutableList<String>,
    ) {
        if (snapshot.npcs.none { it.id == change.targetId }) {
            errors += "$label: NPCが存在しません（${change.targetId}）"
            return
        }
        if (change.amount == 0) {
            errors += "$label: 関係変化量が0です"
        } else if (abs(change.amount) > StateChangeLimits.MAX_RELATION_DELTA) {
            errors += "$label: 関係変化量が大きすぎます（最大${StateChangeLimits.MAX_RELATION_DELTA}）"
        }
    }

    private fun validateObjective(
        snapshot: GameSnapshot,
        change: StateChange,
        label: String,
        errors: MutableList<String>,
    ) {
        val quest = snapshot.quests.firstOrNull { it.id == change.targetId }
        if (quest == null) {
            errors += "$label: クエストが存在しません（${change.targetId}）"
            return
        }
        if (quest.status != QuestStatus.ACTIVE) {
            errors += "$label: 終了したクエストは進められません（${quest.status.label}）"
            return
        }
        if (change.objectiveIndex !in quest.objectives.indices) {
            errors += "$label: 目標番号が範囲外です（${change.objectiveIndex}）"
        }
        if (change.amount == 0) {
            errors += "$label: 進捗変化量が0です"
        }
    }

    private fun validateQuestStatus(
        snapshot: GameSnapshot,
        change: StateChange,
        label: String,
        errors: MutableList<String>,
    ) {
        val quest = snapshot.quests.firstOrNull { it.id == change.targetId }
        if (quest == null) {
            errors += "$label: クエストが存在しません（${change.targetId}）"
            return
        }
        val next = change.questStatus
        if (next == null) {
            errors += "$label: questStatusがありません"
            return
        }
        if (next == quest.status) {
            errors += "$label: 同じ状態には変更できません（${next.label}）"
            return
        }
        if (quest.status != QuestStatus.ACTIVE && quest.status != QuestStatus.UNKNOWN) {
            errors += "$label: 終了済みクエストの状態は変更できません（${quest.status.label}）"
            return
        }
        if (next != QuestStatus.COMPLETED && next != QuestStatus.FAILED) {
            errors += "$label: クエストは完了または失敗にのみ変更できます"
        }
    }

    private fun validateLocation(
        snapshot: GameSnapshot,
        change: StateChange,
        label: String,
        errors: MutableList<String>,
    ) {
        if (snapshot.activeCombat != null) {
            errors += "$label: 戦闘中は移動できません"
            return
        }
        if (snapshot.locations.none { it.id == change.targetId }) {
            errors += "$label: 場所が存在しません（${change.targetId}）"
            return
        }
        if (snapshot.currentLocationId == change.targetId) {
            errors += "$label: 既にその場所にいます"
        }
    }

    private fun validateFlag(change: StateChange, label: String, errors: MutableList<String>) {
        if (!flagKey.matches(change.targetId)) {
            errors += "$label: フラグ名が不正です（英数字と _ . : - のみ、最大${StateChangeLimits.MAX_FLAG_KEY_LENGTH}文字）"
        }
        if (change.value.length > StateChangeLimits.MAX_FLAG_VALUE_LENGTH) {
            errors += "$label: フラグ値が長すぎます（最大${StateChangeLimits.MAX_FLAG_VALUE_LENGTH}文字）"
        }
    }

    private fun validateCombatStart(
        snapshot: GameSnapshot,
        change: StateChange,
        label: String,
        errors: MutableList<String>,
    ) {
        if (snapshot.activeCombat != null) {
            errors += "$label: 既に戦闘中です"
            return
        }
        val npc = snapshot.npcs.firstOrNull { it.id == change.targetId }
        if (npc == null) {
            errors += "$label: NPCが存在しません（${change.targetId}）"
            return
        }
        if (!npc.isAlive) {
            errors += "$label: 倒れているNPCとは戦闘できません"
            return
        }
        val stats = npc.combat
        if (stats == null) {
            errors += "$label: 戦闘値のないNPCです"
            return
        }
        if (stats.normalized().hp <= 0) {
            errors += "$label: 敵のHPが0です"
        }
    }
}
