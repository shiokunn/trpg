package io.github.shiokunn.trpg.game

import io.github.shiokunn.trpg.ai.engine.EngineStatus
import io.github.shiokunn.trpg.ai.engine.GenerationOutcome
import io.github.shiokunn.trpg.ai.engine.GenerationProgress
import io.github.shiokunn.trpg.ai.engine.GenerationRequest
import io.github.shiokunn.trpg.ai.engine.InferenceEngine
import io.github.shiokunn.trpg.ai.engine.PromptMessage
import io.github.shiokunn.trpg.ai.engine.SamplingParams
import android.util.Log
import io.github.shiokunn.trpg.core.ai.AiParseResult
import io.github.shiokunn.trpg.core.ai.CheckRequest
import io.github.shiokunn.trpg.core.ai.GmErrorCategory
import io.github.shiokunn.trpg.core.ai.GmRepair
import io.github.shiokunn.trpg.core.ai.GmRepairIssue
import io.github.shiokunn.trpg.core.ai.GmResponse
import io.github.shiokunn.trpg.core.ai.GmResponseParser
import io.github.shiokunn.trpg.core.ai.GmResponseRules
import io.github.shiokunn.trpg.core.ai.TurnChoices
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.GameEvent
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.core.domain.NpcMemory
import io.github.shiokunn.trpg.core.domain.StoryMemory
import io.github.shiokunn.trpg.core.domain.World
import io.github.shiokunn.trpg.core.memory.MemorySelector
import io.github.shiokunn.trpg.core.prompt.PromptBudget
import io.github.shiokunn.trpg.core.prompt.PromptBuilder
import io.github.shiokunn.trpg.core.prompt.PromptBundle
import io.github.shiokunn.trpg.core.prompt.PromptContext
import io.github.shiokunn.trpg.core.prompt.PromptTokenCounter
import io.github.shiokunn.trpg.core.rules.DiceEngine
import io.github.shiokunn.trpg.core.state.GameSnapshot
import io.github.shiokunn.trpg.core.state.MutationResult
import io.github.shiokunn.trpg.core.state.StateChangeEngine
import io.github.shiokunn.trpg.data.repository.MemoryRepository
import io.github.shiokunn.trpg.data.repository.ModelRepository
import io.github.shiokunn.trpg.data.repository.SessionBundle
import io.github.shiokunn.trpg.data.repository.SessionRepository
import io.github.shiokunn.trpg.data.repository.TurnCommit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.UUID

/** Phases reported to the UI while one turn is being produced. */
sealed interface GmTurnPhase {
    data object Preparing : GmTurnPhase
    data class Generating(val attempt: Int) : GmTurnPhase
    data class Repairing(val errors: List<String>) : GmTurnPhase
    data object Committing : GmTurnPhase
}

/** Streaming hooks. Implementations must not throw; the service treats them as best effort. */
interface GmTurnListener {
    suspend fun onPhase(phase: GmTurnPhase) {}

    suspend fun onToken(piece: String) {}

    /** Live token accounting reported by llama.cpp (never derived from text length). */
    fun onProgress(progress: GenerationProgress) {}
}

/** What the player chose to do this turn. A check is already resolved by the app. */
data class PlayerTurnAction(
    val text: String,
    val check: CheckRequest? = null,
    /** Already resolved by the app on an earlier attempt; retry must reuse it. */
    val resolvedCheck: DiceResult? = null,
)

sealed interface GmTurnResult {
    data class Success(
        val response: GmResponse,
        val dice: DiceResult?,
    ) : GmTurnResult

    data object Cancelled : GmTurnResult

    data class Failed(
        val message: String,
        val errors: List<String> = emptyList(),
        val errorKind: GmErrorCategory? = null,
        val dice: DiceResult? = null,
    ) : GmTurnResult
}

/**
 * Runs one complete GM turn:
 *
 * 1. resolve an optional check with [DiceEngine] (the AI never rolls),
 * 2. assemble a bounded prompt from the four memory layers,
 * 3. stream generated text to the UI,
 * 4. strictly parse the JSON contract, repairing once on failure,
 * 5. validate every state change against the live snapshot,
 * 6. commit everything in a single Room transaction.
 *
 * Cancellation or any failure leaves the save untouched: nothing is written before step 6.
 */
class GmTurnService(
    private val sessions: SessionRepository,
    private val memories: MemoryRepository,
    private val models: ModelRepository,
    private val engine: InferenceEngine,
) {
    private val diceEngine = DiceEngine()
    private val json = Json { encodeDefaults = true }

    suspend fun playTurn(
        sessionId: String,
        action: PlayerTurnAction,
        listener: GmTurnListener = object : GmTurnListener {},
    ): GmTurnResult {
        if (action.text.isBlank()) {
            return GmTurnResult.Failed("行動を入力してください。")
        }
        val bundle = sessions.loadBundle(sessionId)
            ?: return GmTurnResult.Failed("セーブデータを読み込めませんでした。")

        val status = engine.status.value
        if (status !is EngineStatus.Ready) {
            return GmTurnResult.Failed(
                "モデルが読み込まれていません。設定 → モデル管理からGGUFモデルを読み込んでください。",
            )
        }

        listener.onPhase(GmTurnPhase.Preparing)

        val settings = (models.settings() ?: ModelSettings()).normalized()
        val newTurn = bundle.session.turnCount + 1
        val now = System.currentTimeMillis()

        val checkResult = action.resolvedCheck ?: action.check?.let { request ->
            resolveCheck(bundle, request, newTurn, now)
                ?: return GmTurnResult.Failed(
                    "選択肢の技能ID「${request.skillId}」がキャラクターに存在しません。",
                )
        }

        fun failure(
            message: String,
            errors: List<String> = emptyList(),
            kind: GmErrorCategory? = null,
        ) = GmTurnResult.Failed(message, errors, kind, checkResult)

        val recentChat = memories.recentChat(sessionId, MAX_HISTORY_MESSAGES)
        val longTerm = MemorySelector.select(
            memories = memories.allMemories(sessionId),
            query = action.text,
            currentTurn = bundle.session.turnCount,
            limit = MAX_LONG_TERM_MEMORIES,
        )
        // NPC memories are selected per NPC so one character's memories never leak into
        // another character's section, and undiscovered NPCs do not consume the budget.
        val npcMemories = MemorySelector.selectNpcMemories(
            memories = memories.npcMemories(sessionId),
            npcs = bundle.npcs,
            query = action.text,
            currentTurn = bundle.session.turnCount,
        )

        val context = PromptContext(
            world = bundle.world,
            session = bundle.session,
            character = bundle.character,
            npcs = bundle.npcs,
            quests = bundle.quests,
            locations = bundle.locations,
            flags = bundle.flags,
            memories = longTerm,
            npcMemories = npcMemories,
            recentChat = recentChat,
            activeCombat = bundle.session.activeCombat,
            playerAction = action.text,
            checkResult = checkResult,
        )

        val contextTokens = status.info.nCtx.takeIf { it > 0 } ?: settings.nCtx
        val budget = PromptBudget(
            contextTokens = contextTokens,
            maxOutputTokens = settings.maxTokens,
        )
        val prompt = runCatching {
            PromptBuilder(MemoTokenCounter(EngineTokenCounter(engine))).build(context, budget)
        }.getOrElse { error ->
            return failure(error.message ?: "プロンプトの組み立てに失敗しました。")
        }

        val messages = prompt.toMessages()

        listener.onPhase(GmTurnPhase.Generating(attempt = 1))
        val first = generate(messages, settings, listener)
        val firstText = when (first) {
            is GenerationOutcome.Cancelled -> return GmTurnResult.Cancelled
            is GenerationOutcome.Failure -> return failure(first.message)
            is GenerationOutcome.Success -> first.text
        }

        val snapshot = bundle.toSnapshot()
        val firstEvaluation = evaluateTurn(firstText, snapshot, bundle, allowLocalRepair = true)
        var attempt = firstEvaluation.attempt
        var localRepairApplied = firstEvaluation.localRepairApplied

        if (attempt is Attempt.Rejected) {
            listener.onPhase(GmTurnPhase.Repairing(attempt.errors))
            val repairStarted = System.currentTimeMillis()
            val mode = if (attempt.issues.any { it.category == GmErrorCategory.GAME_RULE }) {
                RepairMode.CONTENT
            } else {
                RepairMode.STRUCTURE
            }
            val repairMessages = buildRepairMessages(mode, attempt, bundle, recentChat, snapshot)
            when (val second = generate(repairMessages, settings, listener)) {
                is GenerationOutcome.Cancelled -> return GmTurnResult.Cancelled
                is GenerationOutcome.Failure -> return failure(second.message)
                is GenerationOutcome.Success -> {
                    val secondEvaluation = evaluateTurn(second.text, snapshot, bundle, allowLocalRepair = true)
                    attempt = secondEvaluation.attempt
                    localRepairApplied = localRepairApplied || secondEvaluation.localRepairApplied
                    if (attempt is Attempt.Rejected) {
                        logRepair(
                            mode = mode,
                            issues = attempt.issues,
                            localRepairApplied = localRepairApplied,
                            success = false,
                            elapsedMs = System.currentTimeMillis() - repairStarted,
                            modelFileName = status.modelFileName,
                        )
                        return failure(
                            "ゲームマスターの応答が規定のJSON契約を満たしませんでした。" +
                                "ターンは保存されていません。",
                            errors = attempt.errors,
                            kind = primaryKind(attempt.issues),
                        )
                    }
                }
            }
            logRepair(
                mode = mode,
                issues = attempt.issues,
                localRepairApplied = localRepairApplied,
                success = true,
                elapsedMs = System.currentTimeMillis() - repairStarted,
                modelFileName = status.modelFileName,
            )
        }

        val accepted = attempt as Attempt.Accepted
        listener.onPhase(GmTurnPhase.Committing)

        val commit = buildCommit(
            bundle = bundle,
            response = accepted.response,
            mutated = accepted.snapshot,
            dice = checkResult,
            playerAction = action.text,
            newTurn = newTurn,
            now = now,
        )
        return runCatching {
            sessions.commitTurn(sessionId, commit)
            GmTurnResult.Success(accepted.response, checkResult)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            failure(error.message ?: "ターンの保存に失敗しました。")
        }
    }

    // ---------------------------------------------------------------- generation

    private suspend fun generate(
        messages: List<PromptMessage>,
        settings: ModelSettings,
        listener: GmTurnListener,
    ): GenerationOutcome = try {
        engine.generate(
            GenerationRequest(
                messages = messages,
                params = SamplingParams.from(settings),
            ),
            onToken = { piece -> listener.onToken(piece) },
            onProgress = { progress -> listener.onProgress(progress) },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        GenerationOutcome.Failure(error.message ?: "推論中にエラーが発生しました")
    }

    // ---------------------------------------------------------------- validation

    private enum class RepairMode { STRUCTURE, CONTENT }

    private sealed interface Attempt {
        data class Accepted(
            val response: GmResponse,
            val snapshot: GameSnapshot,
        ) : Attempt

        data class Rejected(
            val errors: List<String>,
            val issues: List<GmRepairIssue> = emptyList(),
            val raw: String = "",
        ) : Attempt
    }

    private data class Evaluation(
        val attempt: Attempt,
        val localRepairApplied: Boolean = false,
    )

    /**
     * Strict validation with an optional deterministic pre-pass. Local repair is never
     * counted as an LLM retry and is used only when the repaired JSON passes the exact
     * same validation as a normal response.
     */
    private fun evaluateTurn(
        raw: String,
        snapshot: GameSnapshot,
        bundle: SessionBundle,
        allowLocalRepair: Boolean,
    ): Evaluation {
        val parsed = GmResponseParser.parse(raw)
        if (parsed is AiParseResult.Success) {
            return Evaluation(validateSemantics(parsed.value, snapshot))
        }

        val failure = parsed as AiParseResult.Failure
        val issues = GmRepair.analyze(failure.errors)
        if (allowLocalRepair) {
            val repaired = GmRepair.repairTopLevelUnknownKeys(raw)
            if (repaired != null) {
                val reparsed = GmResponseParser.parse(repaired)
                if (reparsed is AiParseResult.Success) {
                    val result = validateSemantics(reparsed.value, snapshot)
                    if (result is Attempt.Accepted) {
                        return Evaluation(result, localRepairApplied = true)
                    }
                }
            }
        }
        return Evaluation(
            Attempt.Rejected(
                errors = failure.errors.map { it.message },
                issues = issues,
                raw = raw.take(MAX_REPAIR_JSON_CHARS),
            ),
        )
    }

    /** Game-specific validation that cannot be expressed in the JSON schema alone. */
    private fun validateSemantics(
        response: GmResponse,
        snapshot: GameSnapshot,
    ): Attempt {
        val referenceErrors = validateReferences(response, snapshot)
        if (referenceErrors.isNotEmpty()) return Attempt.Rejected(referenceErrors)

        return when (val mutation = StateChangeEngine.apply(snapshot, response.stateChanges)) {
            is MutationResult.Rejected -> Attempt.Rejected(mutation.errors)
            is MutationResult.Applied -> Attempt.Accepted(response, mutation.snapshot)
        }
    }

    /** Checks the IDs the pure state validator cannot see (skills, NPC memories). */
    private fun validateReferences(response: GmResponse, snapshot: GameSnapshot): List<String> {
        val errors = mutableListOf<String>()
        val skillIds = snapshot.character.skills.map { it.id }.toSet()
        response.choices.forEachIndexed { index, choice ->
            val skillId = choice.check?.skillId ?: return@forEachIndexed
            if (skillId !in skillIds) {
                errors += "choices[$index].check.skillId「$skillId」は所持スキルに存在しません"
            }
        }
        val npcIds = snapshot.npcs.map { it.id }.toSet()
        response.npcMemories.forEachIndexed { index, memory ->
            if (memory.npcId !in npcIds) {
                errors += "npcMemories[$index].npcId「${memory.npcId}」は存在しません"
            }
        }
        return errors
    }

    // ---------------------------------------------------------------- dice

    private fun resolveCheck(
        bundle: SessionBundle,
        request: CheckRequest,
        turn: Int,
        now: Long,
    ): DiceResult? {
        val character = bundle.character
        val skill = request.skillId?.let { id -> character.skillById(id) ?: return null }
        return diceEngine.roll(
            io.github.shiokunn.trpg.core.rules.CheckRequest(
                attributeId = request.attribute,
                attributeValue = character.attributes.valueOf(request.attribute),
                skillId = skill?.id,
                skillName = skill?.name.orEmpty(),
                skillLevel = skill?.level ?: 0,
                difficulty = request.difficulty,
                rollMode = request.rollMode,
                turn = turn,
            ),
        ).copy(createdAt = now)
    }

    // ---------------------------------------------------------------- commit

    private fun buildCommit(
        bundle: SessionBundle,
        response: GmResponse,
        mutated: GameSnapshot,
        dice: DiceResult?,
        playerAction: String,
        newTurn: Int,
        now: Long,
    ): TurnCommit {
        val session = bundle.session.copy(
            turnCount = newTurn,
            summary = mergeSummary(bundle.session.summary, response.eventSummary),
            currentLocationId = mutated.currentLocationId,
            activeCombat = mutated.activeCombat,
            updatedAt = now,
            lastPlayedAt = now,
        )

        val playerMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.PLAYER,
            content = buildString {
                if (dice != null) {
                    append("【判定: ").append(dice.attributeId.label)
                    if (dice.skillName.isNotBlank()) append(" + ").append(dice.skillName)
                    append(" / 出目 ").append(dice.dice.joinToString(", "))
                    append(" = ").append(dice.total)
                    append(" / 難易度 ").append(dice.difficulty)
                    append(" → ").append(dice.outcome.label).append("】\n")
                }
                append(playerAction.trim())
            },
            turn = newTurn,
            createdAt = now,
        )
        val gmMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.GM,
            content = response.narrative,
            turn = newTurn,
            createdAt = now + 1,
        )

        val choicesEvent = GameEvent(
            id = UUID.randomUUID().toString(),
            turn = newTurn,
            type = GameEventTypes.CHOICES,
            summary = response.sceneTitle,
            payloadJson = json.encodeToString(
                TurnChoices.serializer(),
                TurnChoices(sceneTitle = response.sceneTitle, choices = response.choices),
            ),
            createdAt = now + 2,
        )
        val turnEvent = GameEvent(
            id = UUID.randomUUID().toString(),
            turn = newTurn,
            type = GameEventTypes.TURN,
            summary = response.eventSummary.ifBlank {
                response.sceneTitle.ifBlank { response.narrative.take(120) }
            },
            createdAt = now + 3,
        )

        val storyMemories = response.memories.map { draft ->
            StoryMemory(
                id = UUID.randomUUID().toString(),
                category = draft.category,
                content = draft.content.trim(),
                importance = draft.importance.coerceIn(StoryMemory.MIN_IMPORTANCE, StoryMemory.MAX_IMPORTANCE),
                isSecret = draft.isSecret,
                isConfirmed = draft.isConfirmed,
                turn = newTurn,
                relatedIds = draft.relatedIds,
                createdAt = now,
            )
        }
        val npcMemoryRows = response.npcMemories.map { draft ->
            NpcMemory(
                id = UUID.randomUUID().toString(),
                npcId = draft.npcId,
                content = draft.content.trim(),
                importance = draft.importance.coerceIn(1, 5),
                isSecret = draft.isSecret,
                turn = newTurn,
                createdAt = now,
            )
        }

        return TurnCommit(
            session = session,
            character = mutated.character,
            npcs = mutated.npcs,
            quests = mutated.quests,
            locations = mutated.locations,
            flags = mutated.flags,
            chatMessages = listOf(playerMessage, gmMessage),
            diceRolls = listOfNotNull(dice),
            memories = storyMemories,
            npcMemories = npcMemoryRows,
            events = listOf(choicesEvent, turnEvent),
        )
    }

    private fun mergeSummary(previous: String, eventSummary: String): String {
        val addition = eventSummary.trim()
        if (addition.isBlank()) return previous.trim().takeLast(SUMMARY_CHARS)
        val combined = if (previous.isBlank()) addition else previous.trim() + " " + addition
        return combined.takeLast(SUMMARY_CHARS)
    }

    private fun SessionBundle.toSnapshot(): GameSnapshot = GameSnapshot(
        character = character,
        npcs = npcs,
        quests = quests,
        locations = locations,
        flags = flags,
        currentLocationId = session.currentLocationId,
        activeCombat = session.activeCombat,
        turn = session.turnCount,
    )

    private fun PromptBundle.toMessages(): List<PromptMessage> = buildList {
        add(PromptMessage("system", system))
        history.forEach { turn -> add(PromptMessage(turn.role, turn.content)) }
        add(PromptMessage("user", user))
    }

    /** World information the AI produced when the adventure was created. */
    private fun initialScenarioFrom(world: World, summary: String): String = buildString {
        append("世界名: ").append(world.name)
        if (world.genre.isNotBlank()) append(" / ジャンル: ").append(world.genre)
        if (world.era.isNotBlank()) append(" / 時代: ").append(world.era)
        if (world.setting.isNotBlank()) append("\n設定: ").append(world.setting)
        if (world.atmosphere.isNotBlank()) append("\n雰囲気: ").append(world.atmosphere)
        if (world.history.isNotBlank()) append("\n歴史: ").append(world.history)
        if (world.objective.isNotBlank()) append("\n目的: ").append(world.objective)
        if (world.problem.isNotBlank()) append("\n問題: ").append(world.problem)
        if (world.scenarioPremise.isNotBlank()) append("\nシナリオ前提: ").append(world.scenarioPremise)
        if (summary.isNotBlank()) append("\n冒険のあらすじ: ").append(summary)
    }

    /** The exchange immediately before the current turn. */
    private fun previousChatFrom(chat: List<ChatMessage>): String = chat.takeLast(2)
        .joinToString("\n") { message ->
            val label = when (message.role) {
                ChatRole.PLAYER -> "プレイヤー"
                ChatRole.GM -> "GM"
                ChatRole.SYSTEM -> "システム"
            }
            "$label: ${message.content}"
        }

    /**
     * Structure repairs only need the broken JSON and the error list. Content repairs
     * additionally need the scenario, the previous exchange and the current game state.
     */
    private fun buildRepairMessages(
        mode: RepairMode,
        rejected: Attempt.Rejected,
        bundle: SessionBundle,
        recentChat: List<ChatMessage>,
        snapshot: GameSnapshot,
    ): List<PromptMessage> {
        val userMessage = buildString {
            if (mode == RepairMode.STRUCTURE) {
                append("失敗したJSON:\n").append(rejected.raw)
                append("\n\n")
            } else {
                append("最初のシナリオ（冒険開始時に生成された世界情報）:\n")
                append(initialScenarioFrom(bundle.world, bundle.session.summary))
                append("\n\n")
                val previousChat = previousChatFrom(recentChat)
                if (previousChat.isNotBlank()) {
                    append("一つ前の会話履歴:\n").append(previousChat).append("\n\n")
                }
                append("現在のゲーム状態:\n").append(currentStateFrom(snapshot)).append("\n\n")
            }

            val narrative = validNarrativeFrom(rejected.raw)
                .take(GmResponseRules.MAX_NARRATIVE_CHARS)
            if (narrative.isNotBlank()) {
                append("契約違反がなかった情景描写（変更しないでください）:\n").append(narrative).append("\n\n")
            }

            append(
                if (mode == RepairMode.STRUCTURE) {
                    GmRepair.structuralInstruction(rejected.issues)
                } else {
                    GmRepair.contentInstruction(rejected.issues)
                },
            )
        }
        return listOf(
            PromptMessage("system", PromptBuilder.repairSystemPrompt()),
            PromptMessage("user", userMessage),
        )
    }

    /** Compact snapshot summary for content repairs; no private conversation is logged. */
    private fun currentStateFrom(snapshot: GameSnapshot): String = buildString {
        append("ターン: ").append(snapshot.turn + 1)
        val location = snapshot.locations.firstOrNull { it.id == snapshot.currentLocationId }
        if (location != null) append("\n現在地: ").append(location.name)
        append("\nHP: ").append(snapshot.character.hp).append('/').append(snapshot.character.maxHp)
        append("\nレベル: ").append(io.github.shiokunn.trpg.core.rules.CharacterRules.levelForXp(snapshot.character.experience))
        if (snapshot.activeCombat != null) append("\n戦闘中: はい")
    }

    private fun primaryKind(issues: List<GmRepairIssue>): GmErrorCategory =
        issues.firstOrNull()?.category ?: GmErrorCategory.OTHER

    /** Structured repair telemetry without narrative text or personal data. */
    private fun logRepair(
        mode: RepairMode,
        issues: List<GmRepairIssue>,
        localRepairApplied: Boolean,
        success: Boolean,
        elapsedMs: Long,
        modelFileName: String,
    ) {
        val first = issues.firstOrNull()
        Log.i(
            TAG,
            "repair mode=${mode.name} kind=${first?.category?.name ?: "NONE"} " +
                "key=${first?.key ?: "-"} path=${first?.path ?: "-"} " +
                "localRepair=$localRepairApplied success=$success elapsedMs=$elapsedMs " +
                "model=$modelFileName issues=${issues.size}",
        )
    }

    /** Keeps only the narrative text; drops every other (possibly invalid) JSON field. */
    private fun validNarrativeFrom(raw: String): String {
        val parsed = GmResponseParser.parse(raw)
        if (parsed is AiParseResult.Success) return parsed.value.narrative
        val match = NARRATIVE_REGEX.find(raw) ?: return ""
        val escaped = match.groupValues[1]
        return runCatching { Json.decodeFromString<String>("\"$escaped\"") }
            .getOrDefault(escaped)
    }

    /** Per-turn memoisation: tokenizer calls are expensive and repeat during truncation. */
    private class MemoTokenCounter(private val delegate: PromptTokenCounter) : PromptTokenCounter {
        private val cache = HashMap<String, Int>(128)

        override suspend fun count(text: String): Int = cache.getOrPut(text) { delegate.count(text) }
    }

    private inner class EngineTokenCounter(private val engine: InferenceEngine) : PromptTokenCounter {
        override suspend fun count(text: String): Int =
            runCatching { engine.countTokens(text) }.getOrElse { text.length.coerceAtLeast(1) }
    }

    companion object {
        const val MAX_HISTORY_MESSAGES = 40
        const val MAX_LONG_TERM_MEMORIES = MemorySelector.DEFAULT_LIMIT
        const val SUMMARY_CHARS = 600
        const val MAX_REPAIR_JSON_CHARS = 4_000
        private const val TAG = "TrpgRepair"

        private val NARRATIVE_REGEX = Regex("\"narrative\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    }
}

/** Event types written by the app (never invented by the AI). */
object GameEventTypes {
    const val TURN = "TURN"
    const val CHOICES = "CHOICES"
    const val COMBAT = "COMBAT"
}
