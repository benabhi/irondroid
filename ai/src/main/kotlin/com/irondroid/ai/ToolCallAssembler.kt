package com.irondroid.ai

/**
 * Rebuilds the tool calls that streaming APIs send in pieces (IRON-46).
 *
 * OpenAI-compatible providers stream each call by its position: the first piece brings the id and
 * the name, the next ones bring more of the arguments' JSON text. Adapters feed every piece to
 * [add] and take the finished calls with [complete] when the stream says the answer ended.
 */
class ToolCallAssembler {
    private class Partial(var id: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())

    private val partials = sortedMapOf<Int, Partial>()

    /** Whether any piece arrived. */
    val isEmpty: Boolean get() = partials.isEmpty()

    /** Adds a piece of the call at [index]: any of its [id], [name] or the next part of its [arguments]. */
    fun add(index: Int, id: String? = null, name: String? = null, arguments: String? = null) {
        require(index >= 0) { "A tool call index cannot be negative" }
        val partial = partials.getOrPut(index) { Partial() }
        if (!id.isNullOrEmpty()) partial.id = id
        // Some providers repeat the name in every piece and others send it once, so it is set, not appended.
        if (!name.isNullOrEmpty()) partial.name = name
        if (arguments != null) partial.arguments.append(arguments)
    }

    /**
     * The finished calls in order, and the assembler starts empty again. Arguments that never arrived
     * become `{}`. Fails with [AiErrorKind.MALFORMED_RESPONSE] if a call never got its id or name.
     */
    fun complete(): List<ToolCall> {
        val calls = partials.map { (index, partial) ->
            val id = partial.id ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Tool call $index came without an id")
            val name = partial.name ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Tool call $index came without a name")
            ToolCall(id, name, partial.arguments.toString().ifBlank { "{}" })
        }
        partials.clear()
        return calls
    }
}
