package com.irondroid.engine.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaValidationTest {
    private val schema = JsonSchema.obj {
        string("odds", "Odds", enum = listOf("likely", "unlikely"))
        integer("amount", "Amount", required = false, minimum = 1, maximum = 5)
        boolean("burn", "Burn", required = false)
        property("weight", JsonSchema.NumberValue("Weight"), required = false)
        property("name", JsonSchema.StringValue("Name", minLength = 2, maxLength = 4), required = false)
        property("tags", JsonSchema.ArrayOf(JsonSchema.IntegerValue("Tag", minimum = 0), "Tags", minItems = 1, maxItems = 2), required = false)
        obj("target", "Target", required = false) { string("who", "Who") }
    }

    private fun check(json: String) = schema.validate(Json.parseToJsonElement(json))

    @Test
    fun `valid arguments have no problems`() {
        assertEquals(emptyList(), check("""{ "odds": "likely" }"""))
        assertEquals(
            emptyList(),
            check("""{ "odds": "unlikely", "amount": 5, "burn": false, "weight": 1.5, "name": "Kai", "tags": [0, 3], "target": { "who": "Ash" } }"""),
        )
    }

    @Test
    fun `null optional arguments count as absent but a null required one is missing`() {
        assertEquals(emptyList(), check("""{ "odds": "likely", "amount": null, "target": null }"""))
        assertEquals(listOf("odds: required"), check("""{ "odds": null }"""))
    }

    @Test
    fun `every problem is reported with its path`() {
        assertEquals(
            listOf(
                "extra: unknown parameter",
                "odds: must be one of likely, unlikely, was \"maybe\"",
                "amount: must be at most 5, was 9",
                "burn: must be true or false",
                "weight: must be a number",
                "name: must have at most 4 characters",
                "tags: allows at most 2 items",
                "tags[1]: must be at least 0, was -1",
                "tags[2]: must be an integer",
                "target.who: required",
            ),
            check(
                """{ "odds": "maybe", "amount": 9, "burn": "yes", "weight": "heavy", "name": "Kaiden",
                     "tags": [1, -1, "x"], "target": {}, "extra": 1 }""",
            ),
        )
    }

    @Test
    fun `wrong types are reported once per value`() {
        assertEquals(
            listOf(
                "odds: must be a string",
                "amount: must be an integer",
                "name: must have at least 2 characters",
                "tags: must be a list",
                "target: must be an object",
            ),
            check("""{ "odds": 3, "amount": 1.5, "name": "K", "tags": {}, "target": [] }"""),
        )
        assertEquals(listOf("amount: must be at least 1, was 0"), check("""{ "odds": "likely", "amount": 0 }"""))
        assertEquals(listOf("amount: must be an integer"), check("""{ "odds": "likely", "amount": "2" }"""))
        assertEquals(listOf("tags: needs at least 1 items"), check("""{ "odds": "likely", "tags": [] }"""))
    }

    @Test
    fun `a value that is not an object is named as the arguments`() {
        assertEquals(listOf("arguments: must be an object"), schema.validate(Json.parseToJsonElement("[]")))
        assertEquals(emptyList(), JsonSchema.ObjectOf().validate(JsonObject(emptyMap())))
    }
}
