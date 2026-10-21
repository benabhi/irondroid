package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * The arguments of a call, already checked against the action's parameters, so the typed getters
 * only fail when a handler asks for something its schema does not declare. Null values are absent.
 */
class ActionArguments(json: JsonObject) {
    val json: JsonObject = JsonObject(json.filterValues { it != JsonNull })

    fun has(name: String): Boolean = name in json

    fun string(name: String): String = require(name).jsonPrimitive.content

    fun stringOrNull(name: String): String? = json[name]?.jsonPrimitive?.content

    fun int(name: String): Int = require(name).jsonPrimitive.int

    fun intOrNull(name: String): Int? = json[name]?.jsonPrimitive?.int

    fun double(name: String): Double = require(name).jsonPrimitive.double

    fun boolean(name: String): Boolean = require(name).jsonPrimitive.boolean

    fun booleanOrNull(name: String): Boolean? = json[name]?.jsonPrimitive?.boolean

    fun array(name: String): JsonArray = require(name) as JsonArray

    fun obj(name: String): ActionArguments = ActionArguments(require(name) as JsonObject)

    private fun require(name: String): JsonElement = json[name] ?: throw IllegalArgumentException("Missing argument $name")

    override fun equals(other: Any?) = other is ActionArguments && other.json == json

    override fun hashCode() = json.hashCode()

    override fun toString() = json.toString()

    companion object {
        val NONE = ActionArguments(JsonObject(emptyMap()))
    }
}
