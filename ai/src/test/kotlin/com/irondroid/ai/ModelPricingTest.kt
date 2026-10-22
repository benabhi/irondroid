package com.irondroid.ai

import com.irondroid.ai.openai.DeepSeek
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelPricingTest {
    private val flash = DeepSeek.INFO.model("deepseek-flash")!!.pricing!!
    private val usage = TokenUsage(promptTokens = 10_000, completionTokens = 1_000, cachedPromptTokens = 8_000)

    // Tuesday 21/10/2026.
    private val offPeak = Instant.parse("2026-10-21T12:00:00Z")
    private val peak = Instant.parse("2026-10-21T07:30:00Z")

    @Test
    fun `the cost adds cached input, fresh input and output`() {
        // 8 000 x 0.003 + 2 000 x 0.15 + 1 000 x 0.6 = 24 + 300 + 600 = 924 per million.
        assertEquals(0.000924, flash.cost(usage, offPeak), 1e-12)
    }

    @Test
    fun `peak hours cost double on weekdays only`() {
        assertEquals(0.001848, flash.cost(usage, peak), 1e-12)
        assertEquals(0.000924, flash.cost(usage, Instant.parse("2026-10-24T07:30:00Z")), 1e-12) // Saturday.
        val hours = PeakHours(listOf(1 until 4, 6 until 10))
        assertTrue(Instant.parse("2026-10-21T01:00:00Z") in hours)
        assertFalse(Instant.parse("2026-10-21T04:00:00Z") in hours)
        assertTrue(Instant.parse("2026-10-21T09:59:00Z") in hours)
        assertFalse(Instant.parse("2026-10-21T10:00:00Z") in hours)
    }

    @Test
    fun `every DeepSeek model has its prices`() {
        assertTrue(DeepSeek.INFO.models.all { it.pricing != null })
    }

    @Test
    fun `prices cannot be negative nor cheaper at peak`() {
        assertFailsWith<IllegalArgumentException> { ModelPricing(-1.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { ModelPricing(0.0, 0.0, 0.0, peakFactor = 0.5) }
    }
}
