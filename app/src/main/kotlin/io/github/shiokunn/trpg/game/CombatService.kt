package io.github.shiokunn.trpg.game

import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.GameEvent
import io.github.shiokunn.trpg.core.rules.CombatRoundEngine
import io.github.shiokunn.trpg.core.rules.CombatRoundOutcome
import io.github.shiokunn.trpg.data.repository.SessionRepository
import io.github.shiokunn.trpg.data.repository.TurnCommit
import java.util.UUID

sealed interface CombatActionResult {
    data class Success(val outcome: CombatRoundOutcome) : CombatActionResult
    data class Failed(val message: String) : CombatActionResult
}

/**
 * Resolves one combat round with the app's D6 rules and commits it atomically.
 * The AI is not called here: it must never decide hits, damage or enemy HP.
 */
class CombatService(private val sessions: SessionRepository) {

    private val engine = CombatRoundEngine()

    suspend fun attack(sessionId: String, useDexterity: Boolean): CombatActionResult {
        val bundle = sessions.loadBundle(sessionId)
            ?: return CombatActionResult.Failed("セーブデータを読み込めませんでした。")
        val combat = bundle.session.activeCombat
            ?: return CombatActionResult.Failed("現在は戦闘中ではありません。")
        if (bundle.character.hpClamped() <= 0) {
            return CombatActionResult.Failed("HPが0のため行動できません。")
        }

        val outcome = engine.resolve(bundle.character, combat, useDexterity)
        val now = System.currentTimeMillis()
        val newTurn = bundle.session.turnCount + 1
        val defeated = outcome.enemyDefeated

        val session = bundle.session.copy(
            turnCount = newTurn,
            activeCombat = if (defeated) null else combat.copy(
                enemyHp = outcome.enemyHpAfter,
                round = combat.round + 1,
            ),
            updatedAt = now,
            lastPlayedAt = now,
        )
        val character = bundle.character.copy(hp = outcome.playerHpAfter)
        val npcs = if (defeated) {
            bundle.npcs.map { npc ->
                if (npc.id == combat.enemyNpcId) {
                    npc.copy(
                        isAlive = false,
                        combat = npc.combat?.copy(hp = 0),
                    )
                } else {
                    npc
                }
            }
        } else {
            bundle.npcs
        }

        val playerMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.PLAYER,
            content = if (useDexterity) "敏捷で攻撃する" else "筋力で攻撃する",
            turn = newTurn,
            createdAt = now,
        )
        val resultMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.GM,
            content = "【戦闘ラウンド${combat.round}】\n" + outcome.description,
            turn = newTurn,
            createdAt = now + 1,
        )
        val event = GameEvent(
            id = UUID.randomUUID().toString(),
            turn = newTurn,
            type = GameEventTypes.COMBAT,
            summary = outcome.description.lineSequence().first().take(300),
            createdAt = now + 2,
        )

        return try {
            sessions.commitTurn(
                sessionId,
                TurnCommit(
                    session = session,
                    character = character,
                    npcs = npcs,
                    chatMessages = listOf(playerMessage, resultMessage),
                    events = listOf(event),
                ),
            )
            CombatActionResult.Success(outcome)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            CombatActionResult.Failed(error.message ?: "戦闘結果の保存に失敗しました。")
        }
    }

    companion object {
        const val EVENT_TYPE = "COMBAT"
    }
}
