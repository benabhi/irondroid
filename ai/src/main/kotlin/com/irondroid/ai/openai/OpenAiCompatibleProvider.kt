package com.irondroid.ai.openai

import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.AiException
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ChatEvent
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.FinishReason
import com.irondroid.ai.ProviderInfo
import com.irondroid.ai.TokenUsage
import com.irondroid.ai.ToolCallAssembler
import com.irondroid.ai.ToolChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A provider for any API with OpenAI's chat completions format (IRON-47): DeepSeek, OpenRouter,
 * OpenAI itself or a custom endpoint (RF-IA-04). Answers are always streamed (server-sent events).
 *
 * [returnsReasoning] is for APIs whose reasoning models must get their `reasoning_content` back in
 * the assistant messages of a tool round, like DeepSeek; other APIs would reject that field.
 * [client] is shared by default; tests pass their own.
 */
class OpenAiCompatibleProvider(
    override val info: ProviderInfo,
    baseUrl: String,
    private val apiKey: String?,
    client: OkHttpClient? = null,
    private val returnsReasoning: Boolean = false,
) : AiProvider {
    private val client = client ?: DEFAULT_CLIENT
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

    override fun chat(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val call = client.newCall(httpRequest(request))
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw httpError(response)
                    val body = response.body ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "The answer came empty")
                    readStream(body.source()) { send(it) }
                }
                close()
            } catch (e: AiException) {
                close(e)
            } catch (e: IOException) {
                close(AiException(AiErrorKind.NETWORK, "The connection failed: ${e.message ?: e.javaClass.simpleName}", e))
            } catch (e: SerializationException) {
                close(AiException(AiErrorKind.MALFORMED_RESPONSE, "The answer is not valid JSON", e))
            } catch (e: IllegalArgumentException) {
                close(AiException(AiErrorKind.MALFORMED_RESPONSE, "The answer has an unexpected shape: ${e.message}", e))
            }
        }
        // Stopping the collection (the player leaves, a new turn starts) cancels the HTTP call.
        awaitClose { call.cancel() }
    }

    private fun httpRequest(request: ChatRequest): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
            .post(requestBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer ${apiKey.trim()}")
        return builder.build()
    }

    /** The request in the API's JSON format. */
    internal fun requestBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("messages", JsonArray(request.messages.map(::message)))
        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", tool.parameters)
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("tool_choice", request.toolChoice.name.lowercase())
        } else if (request.toolChoice == ToolChoice.NONE) {
            put("tool_choice", "none")
        }
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        request.reasoningEffort?.let { put("reasoning_effort", it.name.lowercase()) }
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })
    }

    private fun message(message: ChatMessage): JsonObject = when (message) {
        is ChatMessage.System -> buildJsonObject {
            put("role", "system")
            put("content", message.text)
        }
        is ChatMessage.User -> buildJsonObject {
            put("role", "user")
            put("content", message.text)
        }
        is ChatMessage.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", message.text ?: "")
            if (message.toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        message.toolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.arguments)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
            if (returnsReasoning && message.reasoning != null) put("reasoning_content", message.reasoning)
        }
        is ChatMessage.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", message.callId)
            put("content", message.content)
        }
    }

    /**
     * Reads the server-sent events of one answer and sends its [ChatEvent]s: text and reasoning as
     * they arrive, then the tool calls once complete, then [ChatEvent.Finished] with the usage, which
     * comes in the last chunk.
     */
    private suspend fun readStream(source: BufferedSource, send: suspend (ChatEvent) -> Unit) {
        val assembler = ToolCallAssembler()
        var finishReason: FinishReason? = null
        var usage: TokenUsage? = null
        while (true) {
            val line = source.readUtf8Line() ?: break
            // Blank lines separate events and lines starting with ':' are keep-alive comments.
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val chunk = Json.parseToJsonElement(data).jsonObject
            chunk["error"]?.let { throw streamError(it) }
            chunk.objectOrNull("usage")?.let { usage = tokenUsage(it) }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
            choice.objectOrNull("delta")?.let { delta ->
                delta.stringOrNull("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { send(ChatEvent.ReasoningDelta(it)) }
                delta.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let { send(ChatEvent.TextDelta(it)) }
                (delta["tool_calls"] as? JsonArray)?.forEach { piece ->
                    val call = piece.jsonObject
                    val function = call.objectOrNull("function")
                    assembler.add(
                        index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0,
                        id = call.stringOrNull("id"),
                        name = function?.stringOrNull("name"),
                        arguments = function?.stringOrNull("arguments"),
                    )
                }
            }
            choice.stringOrNull("finish_reason")?.let { finishReason = finishReason(it) }
        }
        val reason = finishReason ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "The answer ended without finishing")
        assembler.complete().forEach { send(ChatEvent.ToolCallRequested(it)) }
        send(ChatEvent.Finished(reason, usage))
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** The longest piece of an error body kept in a message. */
        const val MAX_ERROR_TEXT = 300

        val DEFAULT_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Time between two pieces of the stream: reasoning models may think a while before writing.
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        fun finishReason(value: String) = when (value) {
            "stop" -> FinishReason.STOP
            "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
            "length" -> FinishReason.LENGTH
            "content_filter" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.OTHER
        }

        /** DeepSeek reports cached prompt tokens as `prompt_cache_hit_tokens`, OpenAI inside `prompt_tokens_details`. */
        fun tokenUsage(usage: JsonObject): TokenUsage {
            val prompt = usage.intOrZero("prompt_tokens")
            val cached = usage.intOrNull("prompt_cache_hit_tokens")
                ?: usage.objectOrNull("prompt_tokens_details")?.intOrNull("cached_tokens")
                ?: 0
            return TokenUsage(prompt, usage.intOrZero("completion_tokens"), cached.coerceAtMost(prompt))
        }

        fun httpError(response: Response): AiException {
            val text = response.body?.string().orEmpty()
            val message = errorMessage(text) ?: text.take(MAX_ERROR_TEXT).ifBlank { "HTTP ${response.code}" }
            val kind = when (response.code) {
                401, 403 -> AiErrorKind.AUTHENTICATION
                402 -> AiErrorKind.INSUFFICIENT_BALANCE
                404 -> AiErrorKind.MODEL_NOT_FOUND
                408 -> AiErrorKind.NETWORK
                429 -> AiErrorKind.RATE_LIMITED
                in 500..599 -> AiErrorKind.SERVER
                else -> if (mentionsMissingModel(message)) AiErrorKind.MODEL_NOT_FOUND else AiErrorKind.INVALID_REQUEST
            }
            val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
            return AiException(kind, "HTTP ${response.code}: $message", retryAfterSeconds = retryAfter)
        }

        /** An error sent inside the stream, after the answer had started. */
        fun streamError(error: JsonElement): AiException {
            val message = (error as? JsonObject)?.stringOrNull("message") ?: error.toString().take(MAX_ERROR_TEXT)
            return AiException(AiErrorKind.SERVER, "The provider stopped the answer: $message")
        }

        fun errorMessage(body: String): String? = try {
            val error = Json.parseToJsonElement(body).jsonObject["error"]
            when (error) {
                is JsonObject -> error.stringOrNull("message")
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }?.take(MAX_ERROR_TEXT)
        } catch (_: IllegalArgumentException) {
            null
        }

        fun mentionsMissingModel(message: String): Boolean {
            val lower = message.lowercase()
            return "model" in lower && ("not exist" in lower || "not found" in lower || "does not exist" in lower)
        }

        fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject

        fun JsonObject.stringOrNull(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

        fun JsonObject.intOrNull(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

        fun JsonObject.intOrZero(key: String) = intOrNull(key) ?: 0
    }
}
