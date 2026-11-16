package io.github.shiokunn.trpg.core.debug

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawOutputJournalTest {

    private val root = File(
        System.getProperty("java.io.tmpdir"),
        "raw-output-journal-${System.nanoTime()}",
    )

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `disabled mode creates nothing`() {
        val journal = RawOutputJournal.begin(root, enabled = false, label = "world")
        assertNull(journal)
        assertFalse(root.exists())
    }

    @Test
    fun `enabled mode separates initial and repair replies`() {
        val journal = RawOutputJournal.begin(
            root = root,
            enabled = true,
            label = "world",
            now = 1_700_000_000_000L,
            sessionId = "session-a",
        )
        requireNotNull(journal)

        journal.writeInitial("{\"initial\":true}", "length", 64, grammarApplied = true)
        journal.writeRepair("{\"repair\":true}", "stop", 12, grammarApplied = false)

        val sessionDir = File(root, "world-1700000000000-session-a")
        assertEquals("{\"initial\":true}", File(sessionDir, "initial.txt").readText())
        assertEquals("{\"repair\":true}", File(sessionDir, "repair.txt").readText())

        val initialMeta = File(sessionDir, "initial.meta.txt").readText()
        assertTrue(initialMeta.contains("stage=initial"))
        assertTrue(initialMeta.contains("finishReason=length"))
        assertTrue(initialMeta.contains("generatedTokens=64"))
        assertTrue(initialMeta.contains("grammarApplied=true"))

        val repairMeta = File(sessionDir, "repair.meta.txt").readText()
        assertTrue(repairMeta.contains("stage=repair"))
        assertTrue(repairMeta.contains("finishReason=stop"))
        assertTrue(repairMeta.contains("grammarApplied=false"))
    }

    @Test
    fun `each generation session gets its own directory`() {
        val first = RawOutputJournal.begin(root, true, "world", now = 1L, sessionId = "one")
        val second = RawOutputJournal.begin(root, true, "world", now = 2L, sessionId = "two")
        requireNotNull(first)
        requireNotNull(second)

        first.writeInitial("first", "stop", 1, true)
        second.writeInitial("second", "stop", 2, true)

        assertEquals("first", File(root, "world-1-one/initial.txt").readText())
        assertEquals("second", File(root, "world-2-two/initial.txt").readText())
    }

    @Test
    fun `a missing root keeps the journal disabled`() {
        assertNull(RawOutputJournal.begin(root = null, enabled = true, label = "world"))
    }
}
