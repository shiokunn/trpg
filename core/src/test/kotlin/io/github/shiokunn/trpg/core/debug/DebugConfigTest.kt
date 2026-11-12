package io.github.shiokunn.trpg.core.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugConfigTest {
    @After
    fun reset() {
        DebugConfig.setEnabled(false)
    }

    @Test
    fun startsDisabled() {
        DebugConfig.setEnabled(false)
        assertFalse(DebugConfig.isEnabled)
        assertFalse(DebugConfig.enabled.value)
    }

    @Test
    fun canBeEnabledAndDisabled() {
        DebugConfig.setEnabled(true)
        assertTrue(DebugConfig.isEnabled)
        assertTrue(DebugConfig.enabled.value)

        DebugConfig.setEnabled(false)
        assertFalse(DebugConfig.isEnabled)
    }

    @Test
    fun toggleFlipsCurrentState() {
        DebugConfig.setEnabled(false)
        DebugConfig.toggle()
        assertTrue(DebugConfig.isEnabled)
        DebugConfig.toggle()
        assertFalse(DebugConfig.isEnabled)
    }

    @Test
    fun settingSameValueTwiceIsIdempotent() {
        DebugConfig.setEnabled(true)
        DebugConfig.setEnabled(true)
        assertTrue(DebugConfig.isEnabled)
        assertEquals(true, DebugConfig.enabled.value)
    }
}
