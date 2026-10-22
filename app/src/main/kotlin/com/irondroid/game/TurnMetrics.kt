package com.irondroid.game

import com.irondroid.ai.ModelPricing
import com.irondroid.ai.narrator.TurnOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * What a turn cost (IRON-111): tokens in (and how many came from the provider's cache), tokens out,
 * time, the estimated cost in US dollars (null when the model has no prices) and the effort.
 * Saved with the turn as JSON, so older turns show theirs too.
 */
data class TurnMetrics(
    val promptTokens: Int,
    val cachedTokens: Int,
    val completionTokens: Int,
    val totalMs: Long,
    val costUsd: Double?,
    val effort: String?,
) {
    fun toJson(): String = buildJsonObject {
        put("prompt_tokens", promptTokens)
        put("cached_tokens", cachedTokens)
        put("completion_tokens", completionTokens)
        put("total_ms", totalMs)
        costUsd?.let { put("cost_usd", it) }
        effort?.let { put("effort", it) }
    }.toString()

    companion object {
        /** The metrics of [outcome], priced with [pricing] at [at]; null when the provider gave no token counts. */
        fun of(outcome: TurnOutcome, pricing: ModelPricing?, at: Instant): TurnMetrics? {
            val usage = outcome.usage ?: return null
            return TurnMetrics(
                usage.promptTokens,
                usage.cachedPromptTokens,
                usage.completionTokens,
                outcome.totalMs,
                pricing?.cost(usage, at),
                outcome.effort?.name?.lowercase(),
            )
        }

        fun fromJson(json: String?): TurnMetrics? = try {
            (json?.let { Json.parseToJsonElement(it) } as? JsonObject)?.let { data ->
                TurnMetrics(
                    data["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: return@let null,
                    data["cached_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    data["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    data["total_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    data["cost_usd"]?.jsonPrimitive?.doubleOrNull,
                    data["effort"]?.jsonPrimitive?.content,
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
