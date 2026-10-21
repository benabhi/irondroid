package com.irondroid.engine.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSchemaTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `the builder makes a standard object schema with required properties`() {
        val schema = JsonSchema.obj("Arguments") {
            string("odds", "How likely a yes is", enum = listOf("likely", "unlikely"))
            integer("amount", "How much", required = false, minimum = 1, maximum = 5)
            boolean("burn", "Whether to burn momentum", required = false)
            array("tags", "Free tags", JsonSchema.StringValue("A tag"), required = false)
            obj("target", "Who") { string("name", "Their name") }
        }
        val expected = json(
            """
            { "type": "object", "description": "Arguments",
              "properties": {
                "odds": { "type": "string", "description": "How likely a yes is", "enum": ["likely", "unlikely"] },
                "amount": { "type": "integer", "description": "How much", "minimum": 1, "maximum": 5 },
                "burn": { "type": "boolean", "description": "Whether to burn momentum" },
                "tags": { "type": "array", "description": "Free tags", "items": { "type": "string", "description": "A tag" } },
                "target": { "type": "object", "description": "Who",
                  "properties": { "name": { "type": "string", "description": "Their name" } },
                  "required": ["name"], "additionalProperties": false }
              },
              "required": ["odds", "target"],
              "additionalProperties": false }
            """,
        )
        assertEquals(expected, schema.toJson())
        assertEquals(emptyList(), schema.problems("args"))
    }

    @Test
    fun `a property declared again keeps its last required flag`() {
        val schema = JsonSchema.obj {
            boolean("burn", "Burn")
            boolean("burn", "Burn", required = false)
        }
        assertEquals(emptySet(), schema.required)
    }

    @Test
    fun `optional limits and descriptions are left out of the json`() {
        assertEquals(json("""{ "type": "string" }"""), JsonSchema.StringValue().toJson())
        assertEquals(json("""{ "type": "string", "minLength": 1, "maxLength": 40 }"""), JsonSchema.StringValue(minLength = 1, maxLength = 40).toJson())
        assertEquals(json("""{ "type": "number" }"""), JsonSchema.NumberValue().toJson())
        assertEquals(
            json("""{ "type": "array", "items": { "type": "integer" }, "minItems": 1, "maxItems": 3 }"""),
            JsonSchema.ArrayOf(JsonSchema.IntegerValue(), minItems = 1, maxItems = 3).toJson(),
        )
        assertEquals(
            json("""{ "type": "object", "properties": {}, "required": [], "additionalProperties": false }"""),
            JsonSchema.ObjectOf().toJson(),
        )
    }

    @Test
    fun `valid leaf schemas have no problems`() {
        listOf(
            JsonSchema.StringValue(minLength = 0, maxLength = 0),
            JsonSchema.IntegerValue(minimum = 2, maximum = 2),
            JsonSchema.IntegerValue(minimum = 2),
            JsonSchema.NumberValue(),
            JsonSchema.BooleanValue(),
            JsonSchema.ArrayOf(JsonSchema.BooleanValue(), minItems = 0, maxItems = 1),
        ).forEach { assertEquals(emptyList(), it.problems("x"), "$it") }
    }

    @Test
    fun `broken limits and enums are reported with their path`() {
        assertEquals(listOf("x: enum without values"), JsonSchema.StringValue(enum = emptyList()).problems("x"))
        assertEquals(listOf("x: enum with repeated values"), JsonSchema.StringValue(enum = listOf("a", "a")).problems("x"))
        assertEquals(
            listOf("x: minLength below 0"),
            JsonSchema.StringValue(minLength = -1).problems("x"),
        )
        assertEquals(listOf("x: minLength above maxLength"), JsonSchema.StringValue(minLength = 3, maxLength = 2).problems("x"))
        assertEquals(listOf("x: minimum above maximum"), JsonSchema.IntegerValue(minimum = 3, maximum = 2).problems("x"))
        assertEquals(
            listOf("x: minItems below 0", "x[]: minimum above maximum"),
            JsonSchema.ArrayOf(JsonSchema.IntegerValue(minimum = 1, maximum = 0), minItems = -1).problems("x"),
        )
        assertEquals(listOf("x: minItems above maxItems"), JsonSchema.ArrayOf(JsonSchema.BooleanValue(), minItems = 2, maxItems = 1).problems("x"))
    }

    @Test
    fun `objects need snake_case described properties and defined required ones`() {
        val schema = JsonSchema.ObjectOf(
            properties = mapOf(
                "Bad-Name" to JsonSchema.BooleanValue("ok"),
                "silent" to JsonSchema.BooleanValue(),
                "blank" to JsonSchema.BooleanValue(" "),
                "nested" to JsonSchema.obj("Nested") { property("inner", JsonSchema.IntegerValue("Inner", minimum = 2, maximum = 1)) },
            ),
            required = setOf("ghost"),
        )
        assertEquals(
            listOf(
                "args: required property ghost is not defined",
                "args.Bad-Name: property names must be snake_case",
                "args.silent: needs a description",
                "args.blank: needs a description",
                "args.nested.inner: minimum above maximum",
            ),
            schema.problems("args"),
        )
        assertTrue(JsonSchema.NAME_PATTERN.matches("roll_move2"))
    }
}
