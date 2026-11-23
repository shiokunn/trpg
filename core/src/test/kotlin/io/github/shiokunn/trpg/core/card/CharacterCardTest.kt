package io.github.shiokunn.trpg.core.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream

class CharacterCardTest {

    private val cardJson = """
        {
          "data": {
            "name": "ミオ",
            "description": "図書館で働く静かな少女",
            "personality": "控えめだが好奇心旺盛",
            "scenario": "閉館後の書庫",
            "first_mes": "……ここ、まだ明かりがついてる。"
          }
        }
    """.trimIndent()

    @Test
    fun parsesV2Envelope() {
        val card = CharacterCard.parseJson(cardJson)!!

        assertEquals("ミオ", card.name)
        assertEquals("図書館で働く静かな少女", card.description)
        assertEquals("控えめだが好奇心旺盛", card.personality)
        assertEquals("……ここ、まだ明かりがついてる。", card.firstMessage)
        assertTrue(card.systemPrompt.contains("ミオ"))
        assertTrue(card.systemPrompt.contains("ロールプレイ"))
    }

    @Test
    fun parsesFlatCard() {
        val card = CharacterCard.parseJson(
            """{"name":"レン","description":"旅人","first_mes":"やあ"}""",
        )!!

        assertEquals("レン", card.name)
        assertEquals("やあ", card.firstMessage)
    }

    @Test
    fun rejectsCardWithoutIdentity() {
        assertNull(CharacterCard.parseJson("""{"personality":"何もない"}"""))
    }

    @Test
    fun parsesPngTextChunk() {
        val png = pngWithText("chara", cardJson)

        assertEquals("ミオ", CharacterCard.parsePng(png)?.name)
    }

    @Test
    fun parsesPngCompressedTextChunk() {
        val png = pngWithCompressedText("ccv3", cardJson)

        assertEquals("ミオ", CharacterCard.parsePng(png)?.name)
    }

    @Test
    fun rejectsNonPngBytes() {
        assertNull(CharacterCard.parsePng("not a png".toByteArray()))
    }

    private fun pngWithText(keyword: String, json: String): ByteArray {
        val value = keyword.toByteArray(Charsets.US_ASCII) + 0.toByte() +
            json.toByteArray(Charsets.UTF_8)
        return signature() + chunk("tEXt", value) + chunk("IEND", byteArrayOf())
    }

    private fun pngWithCompressedText(keyword: String, json: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        DeflaterOutputStream(buffer).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val value = keyword.toByteArray(Charsets.US_ASCII) + 0.toByte() + 0.toByte() + buffer.toByteArray()
        return signature() + chunk("zTXt", value) + chunk("IEND", byteArrayOf())
    }

    private fun signature() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(type: String, data: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(data.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(data)
            .putInt(0) // CRC is not validated by the importer
            .array()
}
