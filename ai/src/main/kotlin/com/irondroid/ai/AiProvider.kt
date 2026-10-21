package com.irondroid.ai

import kotlinx.coroutines.flow.Flow

/**
 * An AI the app can talk to (RF-IA-01, RNF-MAN-04). Each provider (DeepSeek, a local model, ...)
 * implements this one interface, so the narrator never knows which one it uses.
 */
interface AiProvider {
    val info: ProviderInfo

    /**
     * Sends [request] and streams the answer as it arrives. The flow is cold: nothing is sent until
     * it is collected, and cancelling the collection cancels the request. Failures are thrown as
     * [AiException].
     */
    fun chat(request: ChatRequest): Flow<ChatEvent>
}

/** A model a provider offers; [supportsTools] says whether it can call tools (RF-IA-06). */
data class ModelInfo(val id: String, val supportsTools: Boolean = true) {
    init {
        require(id.isNotBlank()) { "A model needs an id" }
    }
}

/**
 * What the app shows and stores about a provider: its stable [id] (saved with the player's
 * settings), its [name] and the [models] it offers, the first one being the default.
 */
data class ProviderInfo(
    val id: String,
    val name: String,
    val models: List<ModelInfo>,
    val needsApiKey: Boolean = true,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Provider id $id must be lowercase letters, digits or -" }
        require(name.isNotBlank()) { "Provider $id needs a name" }
        require(models.isNotEmpty()) { "Provider $id needs at least one model" }
        require(models.map { it.id }.toSet().size == models.size) { "Provider $id repeats a model" }
    }

    val defaultModel: ModelInfo get() = models.first()

    fun model(id: String): ModelInfo? = models.firstOrNull { it.id == id }

    private companion object {
        val ID_PATTERN = Regex("^[a-z0-9-]+$")
    }
}

/**
 * What a provider needs to connect: the player's [apiKey] and, for custom OpenAI-compatible
 * endpoints, a [baseUrl] (RF-IA-04). [toString] never shows the key, so it cannot leak to logs.
 */
class ProviderSettings(val apiKey: String? = null, val baseUrl: String? = null) {
    override fun toString() = "ProviderSettings(apiKey=${if (apiKey.isNullOrEmpty()) "none" else "***"}, baseUrl=$baseUrl)"
}

/** A provider the app knows: its [info] and how to [create] it with the player's settings. */
class ProviderRegistration(val info: ProviderInfo, val create: (ProviderSettings) -> AiProvider)

/**
 * The providers the app offers. Adding a provider means registering it here; nothing else changes.
 */
class ProviderRegistry(registrations: List<ProviderRegistration>) {
    private val byId = registrations.associateBy { it.info.id }

    init {
        require(byId.size == registrations.size) { "Provider ids must be unique" }
    }

    /** The registered providers, in the order they were given. */
    val providers: List<ProviderInfo> = registrations.map { it.info }

    fun find(id: String): ProviderInfo? = byId[id]?.info

    /** Creates the provider [id] with [settings]. Fails if [id] is not registered or needs a key it did not get. */
    fun create(id: String, settings: ProviderSettings): AiProvider {
        val registration = requireNotNull(byId[id]) { "Unknown provider $id" }
        if (registration.info.needsApiKey && settings.apiKey.isNullOrBlank()) {
            throw AiException(AiErrorKind.AUTHENTICATION, "Provider $id needs an API key")
        }
        return registration.create(settings)
    }
}
