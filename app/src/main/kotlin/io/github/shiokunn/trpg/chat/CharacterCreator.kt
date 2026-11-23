package io.github.shiokunn.trpg.chat

import io.github.shiokunn.trpg.ai.engine.GenerationOutcome
import io.github.shiokunn.trpg.ai.engine.GenerationRequest
import io.github.shiokunn.trpg.ai.engine.InferenceEngine
import io.github.shiokunn.trpg.ai.engine.PromptMessage
import io.github.shiokunn.trpg.ai.engine.SamplingParams
import io.github.shiokunn.trpg.core.card.CharacterCard
import io.github.shiokunn.trpg.core.domain.ModelSettings
import io.github.shiokunn.trpg.data.repository.ModelRepository

/**
 * Creates a character card with the on-device model. Manual fields can be supplied as
 * a seed so AI generation and hand-written settings can be combined (guided mode).
 */
class CharacterCreator(
    private val engine: InferenceEngine,
    private val models: ModelRepository,
) {

    suspend fun create(
        keywords: String,
        wish: String,
        seed: CharacterCard.Parsed? = null,
    ): Result<CharacterCard.Parsed> {
        val settings = (models.settings() ?: ModelSettings()).normalized()
        val messages = listOf(
            PromptMessage("system", SYSTEM_PROMPT),
            PromptMessage("user", buildUserPrompt(keywords, wish, seed)),
        )
        val first = generate(messages, settings)
        val parsed = first?.let { CharacterCard.parseJson(it) }
        if (parsed != null) return Result.success(parsed)

        // A single repair retry, mirroring the adventure creator's behaviour.
        val repair = messages + listOfNotNull(
            first?.let { PromptMessage("assistant", it) },
            PromptMessage("user", REPAIR_PROMPT),
        )
        val second = generate(repair, settings)
        val retried = second?.let { CharacterCard.parseJson(it) }
        return if (retried != null) {
            Result.success(retried)
        } else {
            Result.failure(IllegalStateException("キャラクターカードを生成できませんでした"))
        }
    }

    private suspend fun generate(
        messages: List<PromptMessage>,
        settings: ModelSettings,
    ): String? = try {
        when (
            val outcome = engine.generate(
                GenerationRequest(messages = messages, params = SamplingParams.from(settings)),
                onToken = {},
                onProgress = {},
            )
        ) {
            is GenerationOutcome.Success -> outcome.text
            is GenerationOutcome.Failure -> null
            is GenerationOutcome.Cancelled -> null
        }
    } catch (t: Throwable) {
        null
    }

    private fun buildUserPrompt(
        keywords: String,
        wish: String,
        seed: CharacterCard.Parsed?,
    ): String = buildString {
        append("次の条件で魅力的なTRPGキャラクターを作成してください。\n")
        if (keywords.isNotBlank()) append("キーワード: ").append(keywords).append('\n')
        if (wish.isNotBlank()) append("希望: ").append(wish).append('\n')
        seed?.let { card ->
            append("ユーザーが決めた項目は変更せず、足りない項目だけ補完してください。\n")
            if (card.name.isNotBlank()) append("名前: ").append(card.name).append('\n')
            if (card.description.isNotBlank()) append("設定: ").append(card.description).append('\n')
            if (card.personality.isNotBlank()) append("性格: ").append(card.personality).append('\n')
            if (card.scenario.isNotBlank()) append("状況: ").append(card.scenario).append('\n')
            if (card.firstMessage.isNotBlank()) append("最初の発言: ").append(card.firstMessage).append('\n')
        }
        append("JSONオブジェクト1つだけを出力してください。")
    }

    companion object {
        private val SYSTEM_PROMPT = """
            あなたはTRPG用のキャラクターカードを作成します。
            説明やコードフェンスを含めず、次のJSONオブジェクト1つだけを出力してください。
            {"name":"名前","description":"外見と背景の説明","personality":"性格","scenario":"登場 situation","first_mes":"最初の発言"}
            すべて日本語で、first_mes はキャラクターの声として自然な1文にしてください。
        """.trimIndent()

        private val REPAIR_PROMPT = """
            出力がキャラクターカードのJSONとして読み取れませんでした。
            name / description / personality / scenario / first_mes を含むJSONオブジェクト1つだけを、
            説明なしで再度出力してください。
        """.trimIndent()
    }
}
