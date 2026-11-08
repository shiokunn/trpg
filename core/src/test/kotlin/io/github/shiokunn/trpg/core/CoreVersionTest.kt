package io.github.shiokunn.trpg.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreVersionTest {
    @Test
    fun versionIsPresent() {
        assertEquals("0.1.0", CoreVersion.NAME)
    }
}
