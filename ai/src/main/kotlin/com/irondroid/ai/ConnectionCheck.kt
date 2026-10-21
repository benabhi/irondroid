package com.irondroid.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What a connection check found (RF-IA-05). */
sealed interface ConnectionResult {
    /** How long the provider took to answer, in milliseconds. */
    val millis: Long

    /** The key works and the model called the test tool: it can play with the actions. */
    data class Ready(override val millis: Long) : ConnectionResult

    /** The key works but the model answered with text instead of calling the tool (RF-IA-06). */
    data class NoTools(override val millis: Long) : ConnectionResult

    /** The provider refused or could not be reached; [error] says why. */
    data class Failed(val error: AiException, override val millis: Long) : ConnectionResult
}

/**
 * Checks a provider with the player's settings before playing (RF-IA-05): one small request that
 * asks the model to call a test tool, so it proves both the API key and tool calling.
 */
object ConnectionCheck {
    /** The tool the model is asked to call. */
    val PING = ToolSpec(
        name = "connection_check",
        description = "Confirms that the connection works. Call it once with ok set to true.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("ok") {
                    put("type", "boolean")
                    put("description", "Always true")
                }
            }
            put("additionalProperties", false)
        },
    )

    private val INSTRUCTIONS = listOf(
        ChatMessage.System("You are checking a connection. Do not write text: call the connection_check tool once."),
        ChatMessage.User("Call connection_check now."),
    )

    /** Runs the check on [model] of [provider]; [clock] gives the time in milliseconds. */
    suspend fun run(provider: AiProvider, model: String, clock: () -> Long = System::currentTimeMillis): ConnectionResult {
        val started = clock()
        return try {
            val reply = provider.chat(
                ChatRequest(model, INSTRUCTIONS, listOf(PING), reasoningEffort = ReasoningEffort.LOW),
            ).collectReply()
            val millis = clock() - started
            if (reply.toolCalls.any { it.name == PING.name }) ConnectionResult.Ready(millis) else ConnectionResult.NoTools(millis)
        } catch (e: AiException) {
            ConnectionResult.Failed(e, clock() - started)
        }
    }
}
