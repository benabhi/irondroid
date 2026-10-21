package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Something the AI can read without changing the game (RF-API-03), like an MCP resource: the
 * character sheet, the progress tracks, the scene, the journal or the rules. [description] is
 * written for the AI, in English.
 */
data class ResourceDefinition(val uri: String, val name: String, val description: String) {
    fun problems(): List<String> = buildList {
        if (!URI_PATTERN.matches(uri)) add("Resource $uri: URI must look like irondroid://name or irondroid://group/name")
        if (name.isBlank()) add("Resource $uri: needs a name")
        if (description.isBlank()) add("Resource $uri: needs a description")
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("description", description)
        put("mime_type", "application/json")
    }

    companion object {
        val URI_PATTERN = Regex("irondroid://[a-z][a-z0-9_]*(/[a-z][a-z0-9_]*)*")
    }
}

/** Reads the current value of one resource. It must not change anything. */
fun interface ResourceReader {
    fun read(): JsonObject
}

/** What came of reading a resource. */
sealed interface ResourceRead {
    data class Content(val uri: String, val data: JsonObject) : ResourceRead

    data class Failure(val uri: String, val error: ActionError) : ResourceRead
}

/** Every read-only resource, each with its reader. */
class ResourceCatalog private constructor(private val readers: List<Pair<ResourceDefinition, ResourceReader>>) {
    private val byUri = readers.associate { (definition, reader) -> definition.uri to reader }

    val resources: List<ResourceDefinition> = readers.map { it.first }

    operator fun get(uri: String): ResourceDefinition? = resources.firstOrNull { it.uri == uri }

    /** Reads [uri]. An unknown URI, or a reader that fails, gives an explained error. */
    fun read(uri: String): ResourceRead {
        val reader = byUri[uri] ?: return ResourceRead.Failure(
            uri,
            ActionError(
                ActionErrorCode.UNKNOWN_RESOURCE,
                "There is no resource $uri",
                suggestion = closestName(uri, resources.map { it.uri })?.let { "Did you mean $it?" },
            ),
        )
        return try {
            ResourceRead.Content(uri, reader.read())
        } catch (e: Exception) {
            ResourceRead.Failure(uri, ActionError(ActionErrorCode.INTERNAL, "Reading $uri failed inside the app: ${e.message ?: e::class.simpleName}"))
        }
    }

    /** The list of resources, the equivalent of MCP's `resources/list`. */
    fun toJson(): JsonArray = JsonArray(resources.map { it.toJson() })

    companion object {
        /** Builds a catalog after checking every resource; throws [ActionCatalogException] with all the problems. */
        fun of(readers: List<Pair<ResourceDefinition, ResourceReader>>): ResourceCatalog {
            val definitions = readers.map { it.first }
            val errors = definitions.groupBy { it.uri }.filterValues { it.size > 1 }.keys.map { "Resource $it is defined more than once" } +
                definitions.flatMap { it.problems() }
            if (errors.isNotEmpty()) throw ActionCatalogException(errors)
            return ResourceCatalog(readers.toList())
        }
    }
}

/**
 * The whole interface an AI connects to (RF-API-03): the actions it can run, through [dispatcher],
 * and the [resources] it can read.
 */
class ActionInterface(val dispatcher: ActionDispatcher, val resources: ResourceCatalog) {
    /** Everything the AI learns when it connects: catalog version, every action and every resource. */
    fun describe(): JsonObject = buildJsonObject {
        val catalog = dispatcher.catalog.toJson()
        put("version", catalog.getValue("version"))
        put("actions", catalog.getValue("actions"))
        put("resources", resources.toJson())
    }
}
