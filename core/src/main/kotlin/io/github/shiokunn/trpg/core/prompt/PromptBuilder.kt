package io.github.shiokunn.trpg.core.prompt

import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.CombatState
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.GameSession
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.NpcMemory
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.QuestStatus
import io.github.shiokunn.trpg.core.domain.StoryMemory
import io.github.shiokunn.trpg.core.domain.World
import io.github.shiokunn.trpg.core.rules.CharacterRules

/** One chat turn in the final prompt. */
data class PromptTurn(val role: String, val content: String)

data class PromptBundle(
    val system: String,
    val history: List<PromptTurn>,
    val user: String,
    val usedTokens: Int,
    val droppedSections: List<String>,
)

data class PromptBudget(
    val contextTokens: Int = ModelSettings.DEFAULT_N_CTX,
    val maxOutputTokens: Int = ModelSettings.DEFAULT_MAX_TOKENS,
    val marginTokens: Int = 256,
) {
    val inputBudget: Int
        get() = (contextTokens - maxOutputTokens - marginTokens).coerceAtLeast(1)
}

interface PromptTokenCounter {
    suspend fun count(text: String): Int
}

/** Conservative fallback: one token per character (Japanese-heavy prompts). */
object HeuristicPromptTokenCounter : PromptTokenCounter {
    override suspend fun count(text: String): Int = text.length.coerceAtLeast(1)
}

data class PromptContext(
    val world: World,
    val session: GameSession,
    val character: Character,
    val npcs: List<Npc> = emptyList(),
    val quests: List<Quest> = emptyList(),
    val locations: List<Location> = emptyList(),
    val flags: Map<String, String> = emptyMap(),
    val memories: List<StoryMemory> = emptyList(),
    val npcMemories: List<NpcMemory> = emptyList(),
    val recentChat: List<ChatMessage> = emptyList(),
    val activeCombat: CombatState? = null,
    val playerAction: String,
    val checkResult: DiceResult? = null,
)

/**
 * Builds a bounded prompt in priority order:
 * action/check > current state > quests > NPCs > long-term memory > world > recent chat.
 *
 * When the budget is exhausted, lower-priority sections are truncated or dropped,
 * never the mandatory rules/JSON contract.
 */
class PromptBuilder(
    private val counter: PromptTokenCounter = HeuristicPromptTokenCounter,
) {

    suspend fun build(context: PromptContext, budget: PromptBudget): PromptBundle {
        val user = buildUserMessage(context)
        val fixedSystem = SYSTEM_RULES + "\n\n" + JSON_CONTRACT
        var remaining = budget.inputBudget - counter.count(fixedSystem) - counter.count(user)
        val sections = mutableListOf<String>()
        val dropped = mutableListOf<String>()

        val candidates = buildSections(context)
        for (section in candidates) {
            if (remaining <= 0) {
                dropped += section.name
                continue
            }
            val text = section.title + "\n" + section.body
            val tokens = counter.count(text)
            if (tokens <= remaining) {
                sections += text
                remaining -= tokens
            } else {
                val truncated = truncate(text, remaining)
                if (truncated.isNotBlank()) {
                    sections += truncated
                    dropped += section.name
                } else {
                    dropped += section.name
                }
                remaining = 0
            }
        }

        val history = buildHistory(context, remaining)
        val used = budget.inputBudget - remaining + history.sumOf { counter.count(it.content) }

        return PromptBundle(
            system = buildString {
                append(fixedSystem)
                if (sections.isNotEmpty()) {
                    append("\n\n")
                    append(sections.joinToString("\n\n"))
                }
            },
            history = history,
            user = user,
            usedTokens = used.coerceAtMost(budget.inputBudget),
            droppedSections = dropped,
        )
    }

    private suspend fun buildHistory(context: PromptContext, remainingTokens: Int): List<PromptTurn> {
        if (remainingTokens <= 0) return emptyList()
        val turns = context.recentChat
            .filter { it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_MESSAGES)
            .map { message ->
                PromptTurn(
                    role = when (message.role) {
                        ChatRole.PLAYER -> "user"
                        ChatRole.GM -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    },
                    content = message.content,
                )
            }

        // Keep the newest turns when space is tight, then restore chronological order.
        val kept = mutableListOf<PromptTurn>()
        var used = 0
        for (turn in turns.asReversed()) {
            val tokens = counter.count(turn.content)
            if (used + tokens > remainingTokens) break
            kept += turn
            used += tokens
        }
        return kept.asReversed()
    }

    private suspend fun truncate(text: String, tokenLimit: Int): String {
        if (tokenLimit <= 0) return ""
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (counter.count(text.substring(0, mid)) <= tokenLimit) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        if (low < MIN_TRUNCATED_CHARS) return ""
        return text.substring(0, low).trimEnd() + "\n…（省略）"
    }

    private fun buildUserMessage(context: PromptContext): String = buildString {
        append("プレイヤーの選択: ").append(context.playerAction.trim())
        val check = context.checkResult
        if (check != null) {
            append("\n\n【アプリが確定した判定結果】\n")
            append("能力値: ").append(check.attributeId.label).append(" ").append(check.attributeValue)
            if (check.skillId != null) {
                append(" / スキル: ").append(check.skillName).append(" +").append(check.skillLevel)
            }
            append("\n出目: ").append(check.dice.joinToString(", "))
            append("（").append(check.rollMode.label).append("）")
            append("\n合計: ").append(check.total).append(" / 難易度: ").append(check.difficulty)
            append("\n結果: ").append(check.outcome.label)
            if (check.reason.isNotBlank()) {
                append("\n理由: ").append(check.reason)
            }
            append("\nこの結果に従って描写してください。判定をやり直したり、出目を変更してはいけません。")
        }
        append("\n\n次の場面を描写し、JSON契約に従って1つだけ出力してください。")
    }

    private fun buildSections(context: PromptContext): List<PromptSection> {
        val sections = mutableListOf<PromptSection>()

        sections += PromptSection("現在の状態", 1, renderState(context))
        sections += PromptSection("クエスト", 2, renderQuests(context.quests))
        sections += PromptSection("NPC", 3, renderNpcs(context.npcs, context.npcMemories))
        sections += PromptSection("長期記憶", 4, renderMemories(context.memories))
        sections += PromptSection("場所", 5, renderLocations(context.locations, context.session.currentLocationId))
        sections += PromptSection("世界設定", 6, renderWorld(context.world))

        return sections.filter { it.body.isNotBlank() }
    }

    private fun renderState(context: PromptContext): String = buildString {
        val character = context.character
        append("PC: ").append(character.name)
        if (character.species.isNotBlank()) append("（").append(character.species).append("）")
        if (character.occupation.isNotBlank()) append(" / ").append(character.occupation)
        append("\nHP: ").append(character.hpClamped()).append("/").append(character.maxHp)
        append(" / レベル: ").append(CharacterRules.levelForXp(character.experience))
        append("（XP ").append(character.experience).append("）")
        append("\n能力値: ")
        append(io.github.shiokunn.trpg.core.domain.AttributeId.entries.joinToString(" ") {
            "${it.label}${character.attributes.valueOf(it)}"
        })
        if (character.skills.isNotEmpty()) {
            append("\nスキル: ").append(
                character.skills.joinToString("、") { skill ->
                    "${skill.name} Lv${skill.level}(id=${skill.id})"
                },
            )
        }
        if (character.inventory.isNotEmpty()) {
            append("\n所持品: ").append(
                character.inventory.joinToString("、") { item ->
                    val equipped = if (item.id in character.equipment) "（装備）" else ""
                    "${item.name}×${item.quantity}(id=${item.id})$equipped"
                },
            )
        }
        context.activeCombat?.let { combat ->
            append("\n戦闘中: ラウンド").append(combat.round)
            append(" / 敵 ").append(combat.enemyName)
            append(" HP ").append(combat.enemyHp).append("/").append(combat.enemyMaxHp)
            append("（攻撃").append(combat.enemyAttackPower)
            append(" 防御").append(combat.enemyDefense).append("）")
            append("\n戦闘中は移動できません。")
        }
        if (context.flags.isNotEmpty()) {
            append("\nフラグ: ").append(context.flags.entries.joinToString("、") { "${it.key}=${it.value}" })
        }
        val location = context.locations.firstOrNull { it.id == context.session.currentLocationId }
        if (location != null) {
            append("\n現在地: ").append(location.name)
        }
        append("\nターン: ").append(context.session.turnCount + 1)
        if (context.session.summary.isNotBlank()) {
            append("\nこれまでのあらすじ: ").append(context.session.summary)
        }
    }

    private fun renderQuests(quests: List<Quest>): String {
        if (quests.isEmpty()) return ""
        return quests.sortedBy { if (it.status == QuestStatus.ACTIVE) 0 else 1 }
            .joinToString("\n") { quest ->
                buildString {
                    append("- [").append(quest.status.label).append("] ").append(quest.title)
                    append("(id=").append(quest.id).append(")")
                    if (quest.description.isNotBlank()) append(": ").append(quest.description)
                    if (quest.objectives.isNotEmpty()) {
                        append("（").append(
                            quest.objectives.withIndex().joinToString(" / ") { (index, objective) ->
                                "$index: " + (if (objective.completed) "達成: " else "未達: ") + objective.description
                            },
                        ).append("）")
                    }
                }
            }
    }

    private fun renderNpcs(npcs: List<Npc>, npcMemories: List<NpcMemory>): String {
        val visible = npcs.filter { it.isDiscovered }
        if (visible.isEmpty()) return ""
        return visible.joinToString("\n\n") { npc ->
            buildString {
                append("- ").append(npc.name).append("(id=").append(npc.id).append(")")
                if (npc.occupation.isNotBlank()) append("（").append(npc.occupation).append("）")
                if (!npc.isAlive) append(" ※倒れている")
                append("\n  関係: ").append(relationshipLabel(npc.relationship))
                append(" (").append(npc.relationship).append(")")
                if (npc.description.isNotBlank()) append("\n  説明: ").append(npc.description)
                if (npc.personality.isNotBlank()) append("\n  性格: ").append(npc.personality)
                val memories = npcMemories.filter { it.npcId == npc.id }.sortedByDescending { it.importance }.take(3)
                if (memories.isNotEmpty()) {
                    append("\n  記憶: ").append(memories.joinToString(" / ") { it.content })
                }
                if (npc.secrets.isNotEmpty()) {
                    append("\n  【GM専用・プレイヤーに直接開示しない】")
                    append(npc.secrets.joinToString(" / "))
                }
            }
        }
    }

    private fun renderMemories(memories: List<StoryMemory>): String {
        if (memories.isEmpty()) return ""
        return memories.sortedByDescending { it.importance }
            .take(MAX_MEMORIES)
            .joinToString("\n") { memory ->
                buildString {
                    append("- [").append(memory.category.label)
                    append("/重要度").append(memory.importance)
                    if (!memory.isConfirmed) append("/推測")
                    if (memory.isSecret) append("/GM専用")
                    append("] ").append(memory.content)
                }
            }
    }

    private fun renderLocations(locations: List<Location>, currentId: String?): String {
        val discovered = locations.filter { it.isDiscovered }
        if (discovered.isEmpty()) return ""
        return discovered.joinToString("\n") { location ->
            buildString {
                append("- ").append(location.name).append("(id=").append(location.id).append(")")
                if (location.id == currentId) append("（現在地）")
                if (location.description.isNotBlank()) append(": ").append(location.description)
                if (location.secrets.isNotEmpty()) {
                    append(" 【GM専用: ").append(location.secrets.joinToString(" / ")).append("】")
                }
            }
        }
    }

    private fun renderWorld(world: World): String = buildString {
        append("名前: ").append(world.name).append(" / ジャンル: ").append(world.genre)
        if (world.era.isNotBlank()) append(" / 時代: ").append(world.era)
        if (world.setting.isNotBlank()) append("\n設定: ").append(world.setting)
        if (world.atmosphere.isNotBlank()) append("\n雰囲気: ").append(world.atmosphere)
        if (world.history.isNotBlank()) append("\n歴史: ").append(world.history)
        if (world.geography.isNotBlank()) append("\n地理: ").append(world.geography)
        if (world.socialStructure.isNotBlank()) append("\n社会構造: ").append(world.socialStructure)
        if (world.factions.isNotEmpty()) append("\n勢力: ").append(world.factions.joinToString("、"))
        if (world.techMagic.isNotBlank()) append("\n技術・魔法: ").append(world.techMagic)
        if (world.specialSettings.isNotEmpty()) {
            append("\n特殊設定: ").append(world.specialSettings.joinToString("、"))
        }
        if (world.objective.isNotBlank()) append("\n目的: ").append(world.objective)
        if (world.problem.isNotBlank()) append("\n問題: ").append(world.problem)
        if (world.scenarioPremise.isNotBlank()) append("\nシナリオ前提: ").append(world.scenarioPremise)
        if (world.secrets.isNotEmpty()) {
            append("\n【GM専用・プレイヤーに直接開示しない秘密】")
            append(world.secrets.joinToString(" / "))
        }
    }

    private fun relationshipLabel(value: Int): String = when {
        value <= -60 -> "敵対"
        value <= -20 -> "不信"
        value < 20 -> "中立"
        value < 60 -> "友好"
        else -> "信頼"
    }

    private data class PromptSection(val name: String, val priority: Int, val body: String) {
        val title: String get() = "【$name】"
    }

    private companion object {
        const val MAX_MEMORIES = 12
        const val MAX_HISTORY_MESSAGES = 8
        const val MIN_TRUNCATED_CHARS = 40

        val SYSTEM_RULES = """
            あなたは完全オフラインのAndroid TRPGアプリで動作するゲームマスター（GM）です。
            日本語で、プレイヤーの選択に基づく情景描写と次の選択肢を生成してください。

            【厳守するルール】
            - サイコロを振らない。出目・合計・判定結果を創作しない。アプリが確定した判定結果だけを物語に反映する。
            - ゲーム状態（HP・経験値・所持品・NPC関係・クエスト・場所・フラグ）を直接変更しない。
              変更は必ず stateChanges に宣言する。アプリが検証し、不正なら全体を破棄する。
            - 【GM専用】と書かれた情報は秘密である。プレイヤーに直接開示せず、必要なら伏線として扱う。
            - 世界設定と既存の記憶に矛盾する展開を避ける。不明点は既存設定から自然に補完する。
            - 選択肢は3〜4個。現在の状況から実行可能なものにし、idは一意にする。
            - 判定が必要な選択肢だけ check を持たせる。difficulty は 4/6/8/10/12/14 のいずれか。
            - 出力はJSONオブジェクト1つだけ。前後に説明文やコードフェンスを付けない。
        """.trimIndent()

        val JSON_CONTRACT = """
            【出力JSON契約】
            {
              "narrative": "情景描写（必須・日本語）",
              "sceneTitle": "短い場面名",
              "choices": [
                {
                  "id": "一意なID",
                  "text": "選択肢の文",
                  "check": {
                    "attribute": "STR|DEX|INT|WIL|PER|CHA",
                    "skillId": "所持スキルのID または null",
                    "difficulty": 4,
                    "rollMode": "NORMAL|ADVANTAGE|DISADVANTAGE"
                  }
                }
              ],
              "stateChanges": [
                {"type": "HP_DELTA|XP_DELTA|ITEM_ADD|ITEM_REMOVE|NPC_RELATION_DELTA|QUEST_OBJECTIVE_PROGRESS|QUEST_STATUS|LOCATION_CHANGE|FLAG_SET|COMBAT_START|COMBAT_END",
                 "targetId": "対象ID", "amount": 0, "value": "", "questStatus": null,
                 "objectiveIndex": -1, "item": null, "reason": "短い理由"}
              ],
              "memories": [
                {"category": "EVENT|NPC_RELATION|DISCOVERY|UNRESOLVED|FORESHADOWING|WORLD",
                 "content": "記憶する出来事", "importance": 1, "isSecret": false,
                 "isConfirmed": true, "relatedIds": []}
              ],
              "npcMemories": [
                {"npcId": "NPCのID", "content": "NPCが覚えたこと", "importance": 1, "isSecret": false}
              ],
              "eventSummary": "このターンの短い要約"
            }
            stateChanges が不要なら空配列にする。存在しないIDを創作しない。
        """.trimIndent()
    }
}
