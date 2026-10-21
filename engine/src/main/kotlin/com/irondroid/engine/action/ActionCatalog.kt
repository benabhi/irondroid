package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Version of the action catalog (RF-API-11). Breaking changes (an action removed or renamed, a new
 * required parameter, a different result) raise [major]; additions raise [minor].
 */
data class CatalogVersion(val major: Int, val minor: Int) : Comparable<CatalogVersion> {
    init {
        require(major >= 1 && minor >= 0) { "Catalog version must be 1.0 or above, was $major.$minor" }
    }

    /** Whether a caller built for [other] can use this catalog: same major version, and this one is not older. */
    fun isCompatibleWith(other: CatalogVersion): Boolean = major == other.major && this >= other

    override fun compareTo(other: CatalogVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString() = "$major.$minor"

    companion object {
        private val pattern = Regex("(\\d+)\\.(\\d+)")

        /** Reads "major.minor", such as "1.2". */
        fun parse(text: String): CatalogVersion {
            val match = requireNotNull(pattern.matchEntire(text.trim())) { "Catalog version must look like 1.0, was $text" }
            return CatalogVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
    }
}

/** A catalog that cannot be used. Lists every problem found, not only the first. */
class ActionCatalogException(val errors: List<String>) :
    IllegalArgumentException("Invalid action catalog:\n" + errors.joinToString("\n") { "- $it" })

/** Every action of the interface, as the AI discovers it when it connects (RF-API-03). */
class ActionCatalog private constructor(val version: CatalogVersion, val actions: List<ActionDefinition>) {
    private val byName = actions.associateBy { it.name }

    operator fun get(name: String): ActionDefinition? = byName[name]

    /** Actions of [area], in catalog order. */
    fun inArea(area: ActionArea): List<ActionDefinition> = actions.filter { it.area == area }

    /** The whole catalog as JSON, the equivalent of MCP's `tools/list`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("version", version.toString())
        put("actions", JsonArray(actions.map { it.toJson() }))
    }

    companion object {
        /** Builds a catalog after checking every action; throws [ActionCatalogException] with all the problems. */
        fun of(version: CatalogVersion, actions: List<ActionDefinition>): ActionCatalog {
            val errors = mutableListOf<String>()
            actions.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { errors += "Action $it is defined more than once" }
            actions.forEach { errors += it.problems() }
            if (errors.isNotEmpty()) throw ActionCatalogException(errors)
            return ActionCatalog(version, actions.toList())
        }
    }
}
