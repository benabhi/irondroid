package com.irondroid.ai

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * What a model costs, in US dollars per million tokens (IRON-111): input served from the
 * provider's cache, the rest of the input, and output. Always dollars, the currency providers
 * charge in. A provider whose prices go up at busy hours gives [peakHours] and [peakFactor].
 * Prices are copied from the provider's pricing page and change when it does.
 */
data class ModelPricing(
    val cachedInput: Double,
    val input: Double,
    val output: Double,
    val peakHours: PeakHours? = null,
    val peakFactor: Double = 1.0,
) {
    init {
        require(cachedInput >= 0 && input >= 0 && output >= 0) { "Prices cannot be negative" }
        require(peakFactor >= 1.0) { "Peak prices cannot be lower" }
    }

    /** Estimated cost in US dollars of [usage], for a request made [at]. */
    fun cost(usage: TokenUsage, at: Instant): Double {
        val fresh = usage.promptTokens - usage.cachedPromptTokens
        val base = (usage.cachedPromptTokens * cachedInput + fresh * input + usage.completionTokens * output) / MILLION
        return if (peakHours?.contains(at) == true) base * peakFactor else base
    }

    private companion object {
        const val MILLION = 1_000_000.0
    }
}

/** Busy hours in UTC, on [days]: from the start hour included to the end hour excluded. */
data class PeakHours(val ranges: List<IntRange>, val days: Set<DayOfWeek> = WEEKDAYS) {
    operator fun contains(at: Instant): Boolean {
        val time = at.atZone(ZoneOffset.UTC)
        return time.dayOfWeek in days && ranges.any { time.hour in it }
    }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}
