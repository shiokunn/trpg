package io.github.shiokunn.trpg.core.prompt

import io.github.shiokunn.trpg.core.domain.AttributeId
import io.github.shiokunn.trpg.core.domain.Attributes
import io.github.shiokunn.trpg.core.domain.Character
import io.github.shiokunn.trpg.core.domain.ChatMessage
import io.github.shiokunn.trpg.core.domain.ChatRole
import io.github.shiokunn.trpg.core.domain.CheckOutcome
import io.github.shiokunn.trpg.core.domain.DiceResult
import io.github.shiokunn.trpg.core.domain.GameSession
import io.github.shiokunn.trpg.core.domain.Item
import io.github.shiokunn.trpg.core.domain.Location
import io.github.shiokunn.trpg.core.domain.MemoryCategory
import io.github.shiokunn.trpg.core.domain.Npc
import io.github.shiokunn.trpg.core.domain.Quest
import io.github.shiokunn.trpg.core.domain.QuestObjective
import io.github.shiokunn.trpg.core.domain.RollMode
import io.github.shiokunn.trpg.core.domain.Skill
import io.github.shiokunn.trpg.core.domain.StoryMemory
import io.github.shiokunn.trpg.core.domain.World
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private fun world() = World(
        id = "w1",
        name = "霧の港",
        genre = "ファンタジー",
        era = "中世",
        setting = "海霧に覆われた港町",
        atmosphere = "物憂げ",
        history = "百年前に灯台が建てられた",
        geography = "入り江と断崖",
        socialStructure = "灯台守の会が実権を持つ",
        factions = listOf("灯台守の会"),
        techMagic = "潮の魔法",
        objective = "灯台守を探す",
        problem = "港の灯りが消えた",
        secrets = listOf("灯台守は密輸の証拠を隠している"),
        scenarioTitle = "消えた灯台守",
        scenarioPremise = "灯台守が姿を消した",
    )

    private fun character() = Character(
        id = "pc",
        name = "リナ",
        species = "人間",
        occupation = "斥候",
        attributes = Attributes(2, 4, 3, 2, 4, 3),
        skills = listOf(Skill("s1", "探索", 2), Skill("s2", "弓術", 2)),
        hp = 7,
        maxHp = 10,
        inventory = listOf(
            Item("i1", "短弓", quantity = 1),
            Item("i2", "ポーション", quantity = 2),
        ),
        equipment = listOf("i1"),
        experience = 12,
    )

    private fun session() = GameSession(
        id = "s1",
        title = "消えた灯台守",
        worldId = "w1",
        characterId = "pc",
        currentLocationId = "l1",
        turnCount = 4,
        summary = "酒場でマリナに会った",
    )

    private fun context(chat: List<ChatMessage> = emptyList()) = PromptContext(
        world = world(),
        session = session(),
        character = character(),
        npcs = listOf(
            Npc(
                id = "n1",
                name = "マリナ",
                description = "酒場の主人",
                occupation = "酒場の主人",
                relationship = 35,
                isDiscovered = true,
                secrets = listOf("密輸に関与している"),
            ),
            Npc(id = "n2", name = "謎の男", isDiscovered = false),
        ),
        quests = listOf(
            Quest(
                id = "q1",
                title = "灯台守を探せ",
                objectives = listOf(QuestObjective("酒場で聞き込み", completed = true), QuestObjective("灯台へ向かう")),
            ),
        ),
        locations = listOf(
            Location("l1", "港の酒場", description = "潮と酒の匂い", isDiscovered = true),
            Location("l2", "灯台", isDiscovered = false),
        ),
        flags = mapOf("quest.cat_found" to "true"),
        memories = listOf(
            StoryMemory("m1", MemoryCategory.EVENT, "マリナに灯台の噂を聞いた", importance = 4),
            StoryMemory("m2", MemoryCategory.DISCOVERY, "密輸の噂", importance = 2, isSecret = true, isConfirmed = false),
        ),
        recentChat = chat,
        playerAction = "酒場で聞き込みをする",
        checkResult = DiceResult(
            attributeId = AttributeId.PER,
            skillId = "s1",
            skillName = "探索",
            dice = listOf(5),
            rollMode = RollMode.NORMAL,
            attributeValue = 4,
            skillLevel = 2,
            modifiers = 0,
            total = 11,
            difficulty = 8,
            outcome = CheckOutcome.SUCCESS,
        ),
    )

    private val largeBudget = PromptBudget(contextTokens = 12000, maxOutputTokens = 1000, marginTokens = 256)

    @Test
    fun includesRulesContractStateAndCheck() = runBlocking {
        val bundle = PromptBuilder().build(context(), largeBudget)
        assertTrue(bundle.system.contains("stateChanges"))
        assertTrue(bundle.system.contains("HP: 7/10"))
        assertTrue(bundle.system.contains("現在の状態"))
        assertTrue(bundle.system.contains("レベル: 2"))
        assertTrue(bundle.user.contains("酒場で聞き込みをする"))
        assertTrue(bundle.user.contains("成功"))
        assertTrue(bundle.user.contains("難易度: 8"))
        assertTrue(bundle.usedTokens <= largeBudget.inputBudget)
    }

    @Test
    fun secretInformationIsClearlyMarkedGmOnly() = runBlocking {
        val bundle = PromptBuilder().build(context(), largeBudget)
        assertTrue(bundle.system.contains("GM専用"))
        assertTrue(bundle.system.contains("灯台守は密輸の証拠を隠している"))
        assertTrue(bundle.system.contains("密輸に関与している"))
        // Undiscovered NPCs/locations stay out of the prompt.
        assertTrue(!bundle.system.contains("謎の男"))
        assertTrue(!bundle.system.contains("- 灯台"))
    }

    @Test
    fun lowPrioritySectionsAreDroppedFirst() = runBlocking {
        val builder = PromptBuilder()
        // Measure the mandatory rules/contract cost with an otherwise empty context.
        val minimal = context().copy(
            world = World(id = "w", name = "世界", genre = "ジャンル"),
            npcs = emptyList(),
            quests = emptyList(),
            locations = emptyList(),
            memories = emptyList(),
            recentChat = emptyList(),
            checkResult = null,
        )
        val base = builder.build(minimal, PromptBudget(contextTokens = 100_000, maxOutputTokens = 0, marginTokens = 0))
        val tightBudget = PromptBudget(
            contextTokens = base.usedTokens + 40,
            maxOutputTokens = 0,
            marginTokens = 0,
        )
        val bundle = builder.build(context(), tightBudget)
        assertTrue(bundle.system.contains("【現在の状態】"))
        assertTrue(bundle.droppedSections.contains("世界設定"))
    }

    @Test
    fun memoriesAreOrderedByImportance() = runBlocking {
        val bundle = PromptBuilder().build(context(), largeBudget)
        val high = bundle.system.indexOf("マリナに灯台の噂を聞いた")
        val low = bundle.system.indexOf("密輸の噂")
        assertTrue(high >= 0 && low >= 0)
        assertTrue(high < low)
        assertTrue(bundle.system.contains("推測"))
        assertTrue(bundle.system.contains("GM専用"))
    }

    @Test
    fun historyKeepsOnlyRecentMessagesInOrder() = runBlocking {
        val chat = (1..10).map { index ->
            ChatMessage(
                id = "c$index",
                role = if (index % 2 == 0) ChatRole.GM else ChatRole.PLAYER,
                content = "メッセージ$index",
            )
        }
        val bundle = PromptBuilder().build(context(chat), largeBudget)
        assertTrue(bundle.history.size == 8)
        assertTrue(bundle.history.first().content == "メッセージ3")
        assertTrue(bundle.history.last().content == "メッセージ10")
        assertTrue(bundle.history.first().role == "user")
        assertTrue(bundle.history[1].role == "assistant")
    }

    @Test
    fun tinyBudgetProducesNoHistoryButStillRules() = runBlocking {
        val chat = (1..6).map {
            ChatMessage(id = "c$it", role = ChatRole.GM, content = "長いメッセージ".repeat(20))
        }
        val builder = PromptBuilder()
        val tight = PromptBudget(contextTokens = 1, maxOutputTokens = 0, marginTokens = 0)
        val bundle = builder.build(context(chat), tight)
        assertTrue(bundle.history.isEmpty())
        assertTrue(bundle.system.contains("出力JSON契約"))
    }
}
