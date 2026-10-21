package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The subset of JSON Schema that action parameters and results use (RF-API-02).
 *
 * It is typed so actions are declared without hand-written JSON, and [toJson] gives the standard
 * schema that providers' function calling expects (RF-API-12).
 */
sealed interface JsonSchema {
    /** What the value means, for the AI. Every property of an object needs one. */
    val description: String?

    fun toJson(): JsonObject

    /** Problems that make this schema unusable, each prefixed with [path]. Empty when it is fine. */
    fun problems(path: String): List<String>

    data class StringValue(
        override val description: String? = null,
        val enum: List<String>? = null,
        val minLength: Int? = null,
        val maxLength: Int? = null,
    ) : JsonSchema {
        override fun toJson() = schema("string", description) {
            enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
            minLength?.let { put("minLength", it) }
            maxLength?.let { put("maxLength", it) }
        }

        override fun problems(path: String) = buildList {
            if (enum != null) {
                if (enum.isEmpty()) add("$path: enum without values")
                if (enum.toSet().size != enum.size) add("$path: enum with repeated values")
            }
            if (minLength != null && minLength < 0) add("$path: minLength below 0")
            if (minLength != null && maxLength != null && minLength > maxLength) add("$path: minLength above maxLength")
        }
    }

    data class IntegerValue(
        override val description: String? = null,
        val minimum: Int? = null,
        val maximum: Int? = null,
    ) : JsonSchema {
        override fun toJson() = schema("integer", description) {
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

        override fun problems(path: String) =
            listOfNotNull("$path: minimum above maximum".takeIf { minimum != null && maximum != null && minimum > maximum })
    }

    data class NumberValue(override val description: String? = null) : JsonSchema {
        override fun toJson() = schema("number", description) {}

        override fun problems(path: String) = emptyList<String>()
    }

    data class BooleanValue(override val description: String? = null) : JsonSchema {
        override fun toJson() = schema("boolean", description) {}

        override fun problems(path: String) = emptyList<String>()
    }

    data class ArrayOf(
        val items: JsonSchema,
        override val description: String? = null,
        val minItems: Int? = null,
        val maxItems: Int? = null,
    ) : JsonSchema {
        override fun toJson() = schema("array", description) {
            put("items", items.toJson())
            minItems?.let { put("minItems", it) }
            maxItems?.let { put("maxItems", it) }
        }

        override fun problems(path: String) = buildList {
            if (minItems != null && minItems < 0) add("$path: minItems below 0")
            if (minItems != null && maxItems != null && minItems > maxItems) add("$path: minItems above maxItems")
            addAll(items.problems("$path[]"))
        }
    }

    /** An object with exactly [properties]: unknown properties are not allowed. */
    data class ObjectOf(
        val properties: Map<String, JsonSchema> = emptyMap(),
        val required: Set<String> = emptySet(),
        override val description: String? = null,
    ) : JsonSchema {
        override fun toJson() = schema("object", description) {
            put("properties", JsonObject(properties.mapValues { it.value.toJson() }))
            put("required", JsonArray(properties.keys.filter { it in required }.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }

        override fun problems(path: String) = buildList {
            (required - properties.keys).forEach { add("$path: required property $it is not defined") }
            for ((name, schema) in properties) {
                val where = "$path.$name"
                if (!NAME_PATTERN.matches(name)) add("$where: property names must be snake_case")
                if (schema.description.isNullOrBlank()) add("$where: needs a description")
                addAll(schema.problems(where))
            }
        }
    }

    companion object {
        /** Names of actions and properties: snake_case, as the providers' function calling expects. */
        val NAME_PATTERN = Regex("[a-z][a-z0-9_]*")

        /** Builds an [ObjectOf] property by property; see [ObjectSchemaBuilder]. */
        fun obj(description: String? = null, build: ObjectSchemaBuilder.() -> Unit = {}): ObjectOf =
            ObjectSchemaBuilder().apply(build).build(description)

        private inline fun schema(type: String, description: String?, extra: JsonObjectBuilder.() -> Unit) =
            buildJsonObject {
                put("type", type)
                description?.let { put("description", it) }
                extra()
            }
    }
}

/** Declares the properties of an object schema. Properties are required unless `required = false`. */
class ObjectSchemaBuilder internal constructor() {
    private val properties = linkedMapOf<String, JsonSchema>()
    private val required = mutableSetOf<String>()

    fun property(name: String, schema: JsonSchema, required: Boolean = true) {
        properties[name] = schema
        if (required) this.required += name else this.required -= name
    }

    fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) =
        property(name, JsonSchema.StringValue(description, enum), required)

    fun integer(name: String, description: String, required: Boolean = true, minimum: Int? = null, maximum: Int? = null) =
        property(name, JsonSchema.IntegerValue(description, minimum, maximum), required)

    fun boolean(name: String, description: String, required: Boolean = true) =
        property(name, JsonSchema.BooleanValue(description), required)

    fun array(name: String, description: String, items: JsonSchema, required: Boolean = true) =
        property(name, JsonSchema.ArrayOf(items, description), required)

    fun obj(name: String, description: String, required: Boolean = true, build: ObjectSchemaBuilder.() -> Unit) =
        property(name, JsonSchema.obj(description, build), required)

    internal fun build(description: String?) = JsonSchema.ObjectOf(properties.toMap(), required.toSet(), description)
}
