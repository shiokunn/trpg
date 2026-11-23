package io.github.shiokunn.trpg.core.card

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * Character Card V2/V3 import. Cards may be plain JSON or PNG files with the card
 * stored in a tEXt / zTXt / iTXt chunk (`chara` or `ccv3` keyword).
 *
 * Import is side-effect free: nothing is written until the caller accepts the card.
 */
object CharacterCard {

    @Serializable
    data class Data(
        val name: String = "",
        val description: String = "",
        val personality: String = "",
        val scenario: String = "",
        @SerialName("first_mes") val firstMessage: String = "",
        @SerialName("avatar") val avatar: String? = null,
    )

    @Serializable
    private data class Envelope(val data: Data? = null)

    data class Parsed(
        val name: String,
        val description: String,
        val personality: String,
        val scenario: String,
        val firstMessage: String,
        val avatarBytes: ByteArray? = null,
    ) {
        /** Persona sent to the model; the chat screen owns the conversation format. */
        val systemPrompt: String
            get() = buildString {
                append("あなたはキャラクターとしてロールプレイします。キャラクター以外の説明・メタ発言はしないでください。\n")
                if (name.isNotBlank()) append("名前: ").append(name).append('\n')
                if (description.isNotBlank()) append("設定: ").append(description).append('\n')
                if (personality.isNotBlank()) append("性格: ").append(personality).append('\n')
                if (scenario.isNotBlank()) append("状況: ").append(scenario).append('\n')
                append("日本語で、自然な会話文だけを出力してください。")
            }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val MAX_CHUNK_BYTES = 8 * 1024 * 1024
    private const val MAX_AVATAR_BYTES = 4 * 1024 * 1024

    /** Accepts both `{ "data": {...} }` and a flat card object. */
    fun parseJson(text: String): Parsed? {
        if (text.length > MAX_CHUNK_BYTES) return null
        val data = runCatching { json.decodeFromString<Envelope>(text).data }.getOrNull()
            ?: runCatching { json.decodeFromString<Data>(text) }.getOrNull()
            ?: return null
        return data.toParsed()
    }

    fun parsePng(bytes: ByteArray): Parsed? {
        if (bytes.size <= signature.size || !bytes.copyOfRange(0, signature.size).contentEquals(signature)) {
            return null
        }
        var offset = signature.size
        while (offset + 12 <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || length > MAX_CHUNK_BYTES || offset + 12L + length > bytes.size) return null
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val chunk = bytes.copyOfRange(offset + 8, offset + 8 + length)
            if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
                val (keyword, value) = textChunk(type, chunk) ?: return null
                if (keyword.equals("chara", ignoreCase = true) ||
                    keyword.equals("ccv3", ignoreCase = true) ||
                    keyword.equals("character", ignoreCase = true)
                ) {
                    return parseJson(value)
                }
            }
            if (type == "IEND") break
            offset += 12 + length
        }
        return null
    }

    private fun Data.toParsed(): Parsed? {
        if (name.isBlank() && description.isBlank()) return null
        val avatar = avatar?.takeIf { it.isNotBlank() }?.let { value ->
            runCatching { Base64.getDecoder().decode(value) }
                .getOrNull()
                ?.takeIf { it.size <= MAX_AVATAR_BYTES }
        }
        return Parsed(
            name = name.take(200),
            description = description.take(20_000),
            personality = personality.take(20_000),
            scenario = scenario.take(20_000),
            firstMessage = firstMessage.take(20_000),
            avatarBytes = avatar,
        )
    }

    private fun indexOfFrom(bytes: ByteArray, from: Int): Int {
        for (index in from until bytes.size) {
            if (bytes[index] == 0.toByte()) return index
        }
        return -1
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Returns the keyword and decoded UTF-8 text of a PNG text chunk. */
    private fun textChunk(type: String, data: ByteArray): Pair<String, String>? {
        val zero = data.indexOf(0.toByte())
        if (zero < 0) return null
        val keyword = String(data, 0, zero, Charsets.US_ASCII)
        return when (type) {
            // Character cards store UTF-8 JSON here even though the PNG spec says Latin-1.
            "tEXt" -> keyword to String(data, zero + 1, data.size - zero - 1, Charsets.UTF_8)
            "zTXt" -> {
                if (zero + 1 >= data.size || data[zero + 1].toInt() != 0) return null
                val inflated = runCatching {
                    InflaterInputStream(java.io.ByteArrayInputStream(data, zero + 2, data.size - zero - 2))
                        .use { it.readBytes() }
                }.getOrNull() ?: return null
                keyword to String(inflated, Charsets.UTF_8)
            }
            // iTXt: keyword\0 flags compression lang\0 translated\0 text
            else -> {
                if (zero + 3 >= data.size) return null
                val compressed = data[zero + 2].toInt() and 1 != 0
                var cursor = zero + 3
                val langEnd = indexOfFrom(data, cursor).takeIf { it >= 0 } ?: return null
                cursor = langEnd + 1
                val translatedEnd = indexOfFrom(data, cursor).takeIf { it >= 0 } ?: return null
                cursor = translatedEnd + 1
                val textBytes = if (compressed) {
                    runCatching {
                        InflaterInputStream(java.io.ByteArrayInputStream(data, cursor, data.size - cursor))
                            .use { it.readBytes() }
                    }.getOrNull() ?: return null
                } else {
                    data.copyOfRange(cursor, data.size)
                }
                keyword to String(textBytes, Charsets.UTF_8)
            }
        }
    }
}
