package io.github.shiokunn.trpg.core.ai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiJsonExtractionTest {

    private fun found(raw: String): AiJsonExtraction.Found {
        val result = AiJson.extractObject(raw)
        assertTrue("expected Found, was $result", result is AiJsonExtraction.Found)
        return result as AiJsonExtraction.Found
    }

    private fun truncated(raw: String): AiJsonExtraction.Truncated {
        val result = AiJson.extractObject(raw)
        assertTrue("expected Truncated, was $result", result is AiJsonExtraction.Truncated)
        return result as AiJsonExtraction.Truncated
    }

    private fun malformed(raw: String): AiJsonExtraction.Malformed {
        val result = AiJson.extractObject(raw)
        assertTrue("expected Malformed, was $result", result is AiJsonExtraction.Malformed)
        return result as AiJsonExtraction.Malformed
    }

    private fun notFound(raw: String): AiJsonExtraction.NotFound {
        val result = AiJson.extractObject(raw)
        assertTrue("expected NotFound, was $result", result is AiJsonExtraction.NotFound)
        return result as AiJsonExtraction.NotFound
    }

    @Test
    fun `extracts an object surrounded by prose`() {
        val found = found("はい、こちらです。\n{\"name\":\"世界\"}\n以上です。")
        assertEquals("世界", found.element.jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `keeps braces and escaped quotes inside strings`() {
        val raw = """{"name":"a } b","quote":"say \"hi\" {x}","path":"c:\\tmp"}"""
        val obj = found(raw).element.jsonObject
        assertEquals("a } b", obj.getValue("name").jsonPrimitive.content)
        assertEquals("say \"hi\" {x}", obj.getValue("quote").jsonPrimitive.content)
        assertEquals("c:\\tmp", obj.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun `handles a fenced block preceded by an intro line`() {
        val raw = "Here is the JSON:\n```json\n{\"name\":\"fenced\"}\n```\nThanks"
        assertEquals("fenced", found(raw).element.jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `first valid candidate wins even with a later example`() {
        val raw = """{"name":"real"} 例: {"name":"example"}"""
        assertEquals("real", found(raw).element.jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `skips a broken candidate and uses the next valid one`() {
        val raw = """{broken} then {"name":"second"}"""
        // `{broken}` is not valid JSON, but the scan must still find the real object.
        assertEquals("second", found(raw).element.jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `reports truncated when a brace is never closed`() {
        assertTrue(truncated("""{"name":"cut off"""").message.contains("途中"))
    }

    @Test
    fun `reports not found when there is no object`() {
        notFound("JSONはありません")
    }

    @Test
    fun `reports malformed when every candidate has bad syntax`() {
        assertTrue(malformed("""{"name": }""").message.contains("構文"))
    }

    @Test
    fun `rejects a root array instead of treating it as the object`() {
        assertTrue(notFound("[1,2,3]").message.contains("オブジェクト"))
    }

    @Test
    fun `empty reply is reported as not found`() {
        notFound("   \n ")
    }
}
