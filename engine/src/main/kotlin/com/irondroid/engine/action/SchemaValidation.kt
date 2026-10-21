package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Checks [value] against this schema (RF-API-05) and returns every problem, each starting with its
 * path ("odds", "target.name", "tags[1]"), so the AI can fix all of them in one retry.
 * Empty when the value fits. A null optional property counts as absent: some models send them so.
 */
fun JsonSchema.validate(value: JsonElement, path: String = ""): List<String> {
    val where = path.ifEmpty { "arguments" }
    return when (this) {
        is JsonSchema.StringValue -> {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return listOf("$where: must be a string")
            buildList {
                if (enum != null && text !in enum) add("$where: must be one of ${enum.joinToString(", ")}, was \"$text\"")
                if (minLength != null && text.length < minLength) add("$where: must have at least $minLength characters")
                if (maxLength != null && text.length > maxLength) add("$where: must have at most $maxLength characters")
            }
        }
        is JsonSchema.IntegerValue -> {
            val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: return listOf("$where: must be an integer")
            listOfNotNull(
                "$where: must be at least $minimum, was $number".takeIf { minimum != null && number < minimum },
                "$where: must be at most $maximum, was $number".takeIf { maximum != null && number > maximum },
            )
        }
        is JsonSchema.NumberValue ->
            listOfNotNull("$where: must be a number".takeIf { (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull == null })
        is JsonSchema.BooleanValue ->
            listOfNotNull("$where: must be true or false".takeIf { (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null })
        is JsonSchema.ArrayOf -> {
            val array = value as? JsonArray ?: return listOf("$where: must be a list")
            buildList {
                if (minItems != null && array.size < minItems) add("$where: needs at least $minItems items")
                if (maxItems != null && array.size > maxItems) add("$where: allows at most $maxItems items")
                array.forEachIndexed { index, item -> addAll(items.validate(item, "$where[$index]")) }
            }
        }
        is JsonSchema.ObjectOf -> {
            val obj = value as? JsonObject ?: return listOf("$where: must be an object")
            fun child(name: String) = if (path.isEmpty()) name else "$path.$name"
            buildList {
                (obj.keys - properties.keys).forEach { add("${child(it)}: unknown parameter") }
                for ((name, schema) in properties) {
                    val item = obj[name]
                    when {
                        item == null || item == JsonNull -> if (name in required) add("${child(name)}: required")
                        else -> addAll(schema.validate(item, child(name)))
                    }
                }
            }
        }
    }
}
