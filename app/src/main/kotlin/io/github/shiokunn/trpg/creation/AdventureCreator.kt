package io.github.shiokunn.trpg.creation

import io.github.shiokunn.trpg.ai.engine.EngineStatus
import io.github.shiokunn.trpg.ai.engine.GenerationOutcome
import io.github.shiokunn.trpg.ai.engine.GenerationProgress
import io.github.shiokunn.trpg.ai.engine.GenerationRequest
import io.github.shiokunn.trpg.ai.engine.InferenceEngine
import io.github.shiokunn.trpg.ai.engine.PromptMessage
import io.github.shiokunn.trpg.ai.engine.SamplingParams
import io.github.shiokunn.trpg.core.ai.AiParseResult
import io.github.shiokunn.trpg.core.ai.CharacterDraft
import io.github.shiokunn.trpg.core.ai.CharacterDraftParser
import io.github.shiokunn.trpg.core.ai.WorldDraft
import io.github.shiokunn.trpg.core.ai.WorldDraftParser
import io.github.shiokunn.trpg.core.ai.toCharacter
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.QuestObjective
import io.github.shiokunn.trpg.core.domain.World
import io.github.shiokunn.trpg.data.repository.ModelRepository
import io.github.shiokunn.trpg.data.repository.NewAdventure
import kotlinx.coroutines.CancellationException
import java.util.UUID

enum class CreationMode(val label: String, val description: String) {
    AUTO("AIにおまかせ", "キーワードだけを渡し、世界・シナリオ・導入をすべてAIが作ります。"),
    GUIDED("設定から作る", "あなたの世界観や主人公の希望を尊重し、足りない部分をAIが補完します。"),
}

data class CreationInput(
    val mode: CreationMode = CreationMode.AUTO,
    val keywords: String = "",
    val setting: String = "",
    val protagonistWish: String = "",
    val tone: String = "",
    val difficulty: String = "",
)

sealed interface CreationResult<out T> {
    data class Success<T>(val value: T) : CreationResult<T>
    data object Cancelled : CreationResult<Nothing>
    data class Failed(
        val message: String,
        val errors: List<String> = emptyList(),
    ) : CreationResult<Nothing>
}

/**
 * Generates and validates the pre-play material: a free-form world draft, then a
 * character draft that obeys the app's creation rules.
 *
 * The AI only proposes text and numbers; [WorldDraftParser] and [CharacterDraftParser]
 * enforce the contract, and a failed response is repaired at most once.
 */
class AdventureCreator(
    private val models: ModelRepository,
    private val engine: InferenceEngine,
) {

    suspend fun generateWorld(
        input: CreationInput,
        onProgress: (GenerationProgress) -> Unit = {},
    ): CreationResult<WorldDraft> {
        val readiness = readySettings() ?: return notReady()
        val (settings, _) = readiness
        val messages = listOf(
            PromptMessage("system", WORLD_SYSTEM),
            PromptMessage("user", worldRequest(input)),
        )
        return generateWithRepair(
            settings, messages, onProgress, WorldDraftParser::parse, WORLD_REPAIR_RULES,
        )
    }

    suspend fun generateCharacter(
        world: WorldDraft,
        input: CreationInput,
        onProgress: (GenerationProgress) -> Unit = {},
    ): CreationResult<CharacterDraft> {
        val readiness = readySettings() ?: return notReady()
        val (settings, _) = readiness
        val messages = listOf(
            PromptMessage("system", CHARACTER_SYSTEM),
            PromptMessage("user", characterRequest(world, input)),
        )
        return generateWithRepair(settings, messages, onProgress, CharacterDraftParser::parse)
    }

    private suspend fun readySettings(): Pair<ModelSettings, EngineStatus.Ready>? {
        val status = engine.status.value as? EngineStatus.Ready ?: return null
        val settings = (models.settings() ?: ModelSettings()).normalized()
        return settings to status
    }

    private fun notReady(): CreationResult.Failed = CreationResult.Failed(
        "モデルが読み込まれていません。設定 → モデル管理からGGUFモデルを読み込んでください。",
    )

    private suspend fun <T> generateWithRepair(
        settings: ModelSettings,
        messages: List<PromptMessage>,
        onProgress: (GenerationProgress) -> Unit,
        parser: (String) -> AiParseResult<T>,
        repairRules: String = "",
    ): CreationResult<T> {
        val first = generate(settings, messages, onProgress)
        val firstText = when (first) {
            is GenerationOutcome.Cancelled -> return CreationResult.Cancelled
            is GenerationOutcome.Failure -> return CreationResult.Failed(first.message)
            is GenerationOutcome.Success -> first.text
        }

        when (val parsed = parser(firstText)) {
            is AiParseResult.Success -> return CreationResult.Success(parsed.value)
            is AiParseResult.Failure -> {
                val repair = messages + PromptMessage("assistant", firstText) +
                    PromptMessage("user", repairInstruction(parsed.errors, repairRules))
                val second = generate(settings, repair, onProgress)
                return when (second) {
                    is GenerationOutcome.Cancelled -> CreationResult.Cancelled
                    is GenerationOutcome.Failure -> CreationResult.Failed(second.message)
                    is GenerationOutcome.Success -> when (val retried = parser(second.text)) {
                        is AiParseResult.Success -> CreationResult.Success(retried.value)
                        is AiParseResult.Failure -> CreationResult.Failed(
                            "AIの応答が規定のJSON契約を満たしませんでした。もう一度生成してください。",
                            retried.errors,
                        )
                    }
                }
            }
        }
    }

    private suspend fun generate(
        settings: ModelSettings,
        messages: List<PromptMessage>,
        onProgress: (GenerationProgress) -> Unit,
    ): GenerationOutcome = try {
        engine.generate(
            GenerationRequest(
                messages = messages,
                params = SamplingParams.from(settings).copy(temperature = (settings.temperature + 0.1f).coerceAtMost(1.2f)),
            ),
            onToken = {},
            onProgress = onProgress,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        GenerationOutcome.Failure(error.message ?: "推論中にエラーが発生しました")
    }

    private fun worldRequest(input: CreationInput): String = buildString {
        when (input.mode) {
            CreationMode.AUTO -> {
                append("次のキーワード・雰囲気から、独創的なTRPG世界と短編シナリオを作ってください。\n")
                append("キーワード: ").append(input.keywords.ifBlank { "自由に任せます" }).append('\n')
            }
            CreationMode.GUIDED -> {
                append("プレイヤーの世界設定を最優先で尊重し、矛盾しないように不足部分だけを補完してください。\n")
                append("プレイヤーの設定:\n").append(input.setting.ifBlank { "指定なし" }).append('\n')
                append("キーワード: ").append(input.keywords.ifBlank { "指定なし" }).append('\n')
            }
        }
        append("雰囲気: ").append(input.tone.ifBlank { "指定なし" }).append('\n')
        append("想定難易度: ").append(input.difficulty.ifBlank { "普通" }).append('\n')
        append("導入には開始地点1つ、NPCを2〜4人、クエストを1〜3件含めてください。")
    }

    private fun characterRequest(world: WorldDraft, input: CreationInput): String = buildString {
        append("次の世界とシナリオで遊ぶ主人公を作ってください。\n")
        append("世界: ").append(world.name).append("（").append(world.genre).append("）\n")
        append("シナリオ: ").append(world.scenario.title).append('\n')
        append("あらすじ: ").append(world.scenario.premise).append('\n')
        append("主人公への希望: ").append(input.protagonistWish.ifBlank { "指定なし" }).append('\n')
        append("能力値は各1〜5で合計18、スキルはレベル合計6（各0〜2）、所持品は2〜5個にしてください。")
    }

    private fun repairInstruction(errors: List<String>, extraRules: String = ""): String = buildString {
        append("前回の応答は次の理由で契約に違反していました。\n")
        errors.take(10).forEach { error -> append("- ").append(error).append('\n') }
        if (extraRules.isNotBlank()) {
            append(extraRules.trimEnd()).append('\n')
        }
        append("説明やコードフェンスを含めず、指定のJSONオブジェクトを1つだけ出力してください。")
    }

    private companion object {
        val WORLD_REPAIR_RULES = """
            - JSONオブジェクト1つだけを出力する。
            - 文字列の開始・終了にはASCIIの " だけを使い、閉じ括弧 」 の直後にも " を置く。スマートクォートは使わない。
            - 末尾カンマとJSONコメントを使わない。
            - 配列は要素が1つでも必ず配列にし、配列フィールドに文字列を直接入れない。任意配列は [] にする。
            - 数値は数値で出力する。
            - NPCのcombatが不要なら null にし、空の {} は使わない。
            - キー名・階層は元のJSONスキーマと一致させる。
        """.trimIndent()

        val WORLD_SYSTEM = """
            あなたは完全オフラインのAndroid TRPGアプリで、世界観とシナリオを設計するゲームデザイナーです。
            日本語で、プレイヤーが自由に探索できる具体的な世界を作ってください。

            【出力ルール】
            - JSONオブジェクト1つだけを出力する。説明文・コードフェンス・JSON以外の文字を含めない。
            - すべての文字列は日本語。空の必須項目を作らない。
            - 既存の特定作品の固有名詞をそのまま使わない。
            - secrets にはGM専用の秘密（真相・黒幕・隠された事実）を入れる。
            - JSONのキー名・階層は【JSONスキーマ】と一致させる。キーを追加・変更しない。
            - 文字列の開始・終了にはASCIIの " だけを使う。スマートクォート（“ ”）や日本語の引用符をJSONの区切りに使わない。
            - 日本語の 「」『』 は文字列の内容としてのみ使い、閉じ括弧 」 の直後にも必ずASCIIの " を置いて文字列を閉じる。
            - 末尾カンマとJSONコメント（//、/* */）は禁止。
            - 配列フィールドは要素が1つでも必ず配列にする。配列フィールドに文字列を直接入れない。任意配列が不要なら [] にする。
            - 数値フィールドは数値で出力し、文字列にしない。
            - NPCのcombatは不要なら null にする。空のcombatオブジェクト {} は出力しない。戦闘時のみ hp 1〜30、攻撃力・防御 0〜20、技能 0〜3。
            - 戦闘NPCのcombat例: {"attackPower": 3, "defense": 1, "attackSkillLevel": 1, "defenseSkillLevel": 1, "hp": 10}

            【JSONスキーマ】
            {
              "name": "世界名",
              "genre": "ジャンル",
              "era": "時代",
              "setting": "世界の概要",
              "atmosphere": "雰囲気",
              "history": "歴史",
              "geography": "地理",
              "socialStructure": "社会構造",
              "factions": ["勢力A", "勢力B"],
              "techMagic": "技術・魔法",
              "specialSettings": ["特殊設定A", "特殊設定B"],
              "objective": "プレイヤーの目的",
              "problem": "世界が抱える問題",
              "secrets": ["秘密A", "秘密B"],
              "scenario": {
                "title": "シナリオ名",
                "premise": "あらすじ",
                "length": "想定プレイ時間",
                "difficulty": "難易度",
                "openingHook": "冒険の始まり"
              },
              "opening": {
                "location": {
                  "name": "開始地点名",
                  "description": "説明",
                  "connections": ["場所A", "場所B"],
                  "secrets": ["秘密A", "秘密B"]
                },
                "npcs": [
                  {
                    "name": "NPC名",
                    "description": "説明",
                    "personality": "性格",
                    "occupation": "職業",
                    "disposition": "プレイヤーへの態度",
                    "locationName": "開始地点名",
                    "secrets": ["秘密A", "秘密B"],
                    "combat": null
                  }
                ],
                "quests": [
                  {
                    "title": "クエスト名",
                    "description": "説明",
                    "objectives": ["目的1", "目的2"],
                    "rewards": ["報酬A", "報酬B"]
                  }
                ]
              }
            }
        """.trimIndent()

        val CHARACTER_SYSTEM = """
            あなたは完全オフラインのAndroid TRPGアプリで、D6ルールの主人公を作るゲームデザイナーです。
            日本語で、世界とシナリオに合う魅力的なキャラクターを作ってください。

            【厳守する作成ルール】
            - 能力値 str/dex/int/wil/per/cha はそれぞれ1〜5の整数で、合計は必ず18。
            - スキルは3〜5個。各レベルは0〜2で、レベルの合計は必ず6。
            - 所持品は2〜5個。quantityは1〜99、power/defenseは0〜20。
            - JSONオブジェクト1つだけを出力する。説明文・コードフェンス・JSON以外の文字を含めない。

            【JSONスキーマ】
            {
              "name": "名前",
              "species": "種族",
              "occupation": "職業",
              "background": "経歴",
              "personality": "性格",
              "attributes": {"str": 3, "dex": 3, "int": 3, "wil": 3, "per": 3, "cha": 3},
              "skills": [
                {"name": "スキル名", "level": 2, "description": "説明"}
              ],
              "items": [
                {
                  "name": "アイテム名",
                  "description": "説明",
                  "quantity": 1,
                  "type": "weapon|armor|tool|consumable|misc",
                  "power": 0,
                  "defense": 0,
                  "tags": ["タグ"]
                }
              ]
            }
        """.trimIndent()
    }
}

/** Converts validated drafts into the domain objects stored by the session repository. */
object AdventureAssembler {

    fun assemble(
        worldDraft: WorldDraft,
        characterDraft: CharacterDraft,
        now: Long = System.currentTimeMillis(),
    ): NewAdventure {
        val worldId = UUID.randomUUID().toString()
        val locationId = UUID.randomUUID().toString()
        val locationName = worldDraft.opening.location.name.trim()

        val world = World(
            id = worldId,
            name = worldDraft.name.trim(),
            genre = worldDraft.genre.trim(),
            era = worldDraft.era.trim(),
            setting = worldDraft.setting.trim(),
            atmosphere = worldDraft.atmosphere.trim(),
            history = worldDraft.history.trim(),
            geography = worldDraft.geography.trim(),
            socialStructure = worldDraft.socialStructure.trim(),
            factions = worldDraft.factions,
            techMagic = worldDraft.techMagic.trim(),
            specialSettings = worldDraft.specialSettings,
            objective = worldDraft.objective.trim(),
            problem = worldDraft.problem.trim(),
            secrets = worldDraft.secrets,
            scenarioTitle = worldDraft.scenario.title.trim(),
            scenarioPremise = worldDraft.scenario.premise.trim(),
            scenarioLength = worldDraft.scenario.length.trim(),
            difficulty = worldDraft.scenario.difficulty.trim(),
            createdAt = now,
        )

        val location = Location(
            id = locationId,
            name = locationName,
            description = worldDraft.opening.location.description.trim(),
            isDiscovered = true,
            connections = worldDraft.opening.location.connections,
            secrets = worldDraft.opening.location.secrets,
        )

        val npcs = worldDraft.opening.npcs.map { draft ->
            Npc(
                id = UUID.randomUUID().toString(),
                name = draft.name.trim(),
                description = draft.description.trim(),
                personality = draft.personality.trim(),
                occupation = draft.occupation.trim(),
                relationship = 0,
                disposition = draft.disposition.trim(),
                secrets = draft.secrets,
                isAlive = true,
                isDiscovered = true,
                locationId = if (draft.locationName.isBlank() || draft.locationName.trim() == locationName) {
                    locationId
                } else {
                    null
                },
                combat = draft.combat?.normalized(),
            )
        }

        val quests = worldDraft.opening.quests.map { draft ->
            Quest(
                id = UUID.randomUUID().toString(),
                title = draft.title.trim(),
                description = draft.description.trim(),
                objectives = draft.objectives.map { QuestObjective(description = it.trim()) },
                rewards = draft.rewards,
            )
        }

        return NewAdventure(
            world = world,
            character = characterDraft.toCharacter(),
            sessionTitle = worldDraft.scenario.title.trim().ifBlank { worldDraft.name.trim() },
            openingLocationId = locationId,
            locations = listOf(location),
            npcs = npcs,
            quests = quests,
        )
    }
}
