package com.irondroid.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * The provider-neutral conversation (IRON-46). Every adapter translates these types to its own
 * API, so the rest of the app talks to any AI the same way (RNF-MAN-04).
 */

/** One message of the conversation sent to the model. */
sealed interface ChatMessage {
    /** Instructions for the model: the narrator prompt, the rules, the context of the turn. */
    data class System(val text: String) : ChatMessage {
        init {
            require(text.isNotBlank()) { "A system message needs text" }
        }
    }

    /** What the player said or wrote. */
    data class User(val text: String) : ChatMessage {
        init {
            require(text.isNotBlank()) { "A user message needs text" }
        }
    }

    /** What the model answered: text, tool calls or both. */
    data class Assistant(val text: String? = null, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage {
        init {
            require(!text.isNullOrBlank() || toolCalls.isNotEmpty()) { "An assistant message needs text or tool calls" }
            require(toolCalls.map { it.id }.toSet().size == toolCalls.size) { "Tool call ids must be unique" }
        }
    }

    /** The result of running the tool call [callId], as the text the model reads (usually JSON). */
    data class ToolResult(val callId: String, val content: String) : ChatMessage {
        init {
            require(callId.isNotBlank()) { "A tool result needs the id of its call" }
        }
    }
}

/**
 * A tool the model may call (RF-API-12): [name], what it does in [description] and the JSON Schema
 * of its arguments in [parameters]. IRON-48 builds these from the action catalog.
 */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    init {
        require(NAME_PATTERN.matches(name)) { "Tool name $name must be 1-64 letters, digits, _ or -" }
        require(description.isNotBlank()) { "Tool $name needs a description" }
    }

    companion object {
        /** The names every function calling API accepts. */
        val NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/**
 * A call to a tool requested by the model. [arguments] is the JSON text exactly as the model wrote
 * it: it may be invalid, and whoever runs the tool explains the error back to the model.
 */
data class ToolCall(val id: String, val name: String, val arguments: String) {
    init {
        require(id.isNotBlank()) { "A tool call needs an id" }
        require(name.isNotBlank()) { "A tool call needs a name" }
    }

    /** The [arguments] as a JSON object, or null when the model wrote something else. */
    fun parsedArguments(): JsonObject? = try {
        Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** How the model may use the tools of a request. */
enum class ToolChoice {
    /** The model decides whether to call a tool or answer with text. */
    AUTO,

    /** The model must call at least one tool. */
    REQUIRED,

    /** The model must answer with text only. */
    NONE,
}

/**
 * One request to a model: the [model] id, the [messages] so far and the [tools] it may call.
 * [temperature] and [maxTokens] are left to the provider's defaults when null.
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.AUTO,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) {
    init {
        require(model.isNotBlank()) { "A request needs a model" }
        require(messages.isNotEmpty()) { "A request needs at least one message" }
        require(tools.map { it.name }.toSet().size == tools.size) { "Tool names must be unique" }
        require(toolChoice != ToolChoice.REQUIRED || tools.isNotEmpty()) { "Requiring a tool call needs tools" }
        require(temperature == null || temperature in 0.0..2.0) { "Temperature must be between 0 and 2" }
        require(maxTokens == null || maxTokens > 0) { "maxTokens must be positive" }
        val asked = messages.filterIsInstance<ChatMessage.Assistant>().flatMap { m -> m.toolCalls.map { it.id } }.toSet()
        messages.filterIsInstance<ChatMessage.ToolResult>().forEach {
            require(it.callId in asked) { "Tool result ${it.callId} answers no tool call of the conversation" }
        }
    }
}
