package io.github.shiokunn.trpg.core.inference

import io.github.shiokunn.trpg.core.domain.ModelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryAdvisorTest {

    private val gib = 1024L * 1024L * 1024L

    @Test
    fun largeModelOnTwelveGigabytesGetsConservativeContext() {
        val advice = MemoryAdvisor.advise(
            modelSizeBytes = 3_200_000_000L,
            totalRamBytes = 12L * gib,
            requestedNCtx = 16_384,
            nCtxTrain = 32_768,
        )

        assertEquals(8_192, advice.recommendedNCtx)
        assertNotNull(advice.warning)
    }

    @Test
    fun requestedValueBelowRecommendationHasNoWarning() {
        val advice = MemoryAdvisor.advise(
            modelSizeBytes = 1_000_000_000L,
            totalRamBytes = 12L * gib,
            requestedNCtx = 4_096,
            nCtxTrain = 32_768,
        )

        assertEquals(16_384, advice.recommendedNCtx)
        assertNull(advice.warning)
    }

    @Test
    fun trainingContextCapsTheRecommendation() {
        val advice = MemoryAdvisor.advise(
            modelSizeBytes = 1_000_000_000L,
            totalRamBytes = 12L * gib,
            requestedNCtx = 8_192,
            nCtxTrain = 4_096,
        )

        assertEquals(4_096, advice.recommendedNCtx)
        assertNotNull(advice.warning)
    }

    @Test
    fun lowRamDeviceIsCappedAtFourThousandNinetySix() {
        val advice = MemoryAdvisor.advise(
            modelSizeBytes = 3_200_000_000L,
            totalRamBytes = 4L * gib,
            requestedNCtx = 8_192,
            nCtxTrain = 32_768,
        )

        assertEquals(4_096, advice.recommendedNCtx)
    }

    @Test
    fun recommendationNeverDropsBelowMinimum() {
        val advice = MemoryAdvisor.advise(
            modelSizeBytes = 8_000_000_000L,
            totalRamBytes = 4L * gib,
            requestedNCtx = 2_048,
            nCtxTrain = 256,
        )

        assertEquals(ModelSettings.MIN_N_CTX, advice.recommendedNCtx)
    }
}
