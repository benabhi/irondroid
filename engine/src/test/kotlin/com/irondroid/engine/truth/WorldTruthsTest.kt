package com.irondroid.engine.truth

import com.irondroid.engine.dice.FixedDice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The world truths of SRD chapter 4 (IRON-76). */
class WorldTruthsTest {
    private val catalog = TruthLoader.fromResources()

    @Test
    fun `the book's eleven categories load in book order with three options each`() {
        assertEquals(BOOK_ORDER, catalog.categories.map { it.id })
        assertEquals(setOf("en", "es"), catalog.languages)
        for (category in catalog.categories) {
            assertEquals(3, category.options.size, category.id)
            for (option in category.options) {
                for (language in catalog.languages) {
                    val text = catalog.optionText(category.id, option.id, language)!!
                    assertTrue(text.text.isNotBlank() && text.quest.isNotBlank(), "${option.id} $language")
                }
            }
        }
    }

    @Test
    fun `texts match the book and its translation`() {
        assertEquals("The Old World", catalog.text("old_world", "en")!!.name)
        assertEquals("El Viejo Mundo", catalog.text("old_world", "es")!!.name)
        assertEquals("Leaders", catalog.text("leaders", "en")!!.name)
        assertEquals("Nothing but stories to frighten children.", catalog.optionText("horrors", "horrors_1", "en")!!.text)
        assertEquals("No son más que cuentos para asustar a los niños.", catalog.optionText("horrors", "horrors_1", "es")!!.text)
        assertTrue(catalog.optionText("iron", "iron_1", "en")!!.text.contains("star-forged black iron"))
        assertTrue(catalog.optionText("old_world", "old_world_1", "en")!!.quest.startsWith("You are a descendant of the Skulde."))
        assertNull(catalog.optionText("iron", "old_world_1", "en"))
        assertNull(catalog.text("iron", "fr"))
        assertNull(catalog["unknown"])
    }

    @Test
    fun `a d100 roll picks an option by thirds`() {
        val iron = catalog["iron"]!!
        assertEquals("iron_1", iron.optionFor(1).id)
        assertEquals("iron_1", iron.optionFor(33).id)
        assertEquals("iron_2", iron.optionFor(34).id)
        assertEquals("iron_2", iron.optionFor(67).id)
        assertEquals("iron_3", iron.optionFor(68).id)
        assertEquals("iron_3", iron.roll(FixedDice(listOf(100))).id)
        assertFailsWith<IllegalArgumentException> { iron.optionFor(0) }
        assertFailsWith<IllegalArgumentException> { iron.optionFor(101) }
    }

    private val validCategory = """{ "id": "coin", "options": [ { "id": "heads", "min": 1, "max": 50 }, { "id": "tails", "min": 51, "max": 100 } ] }"""
    private val validText = """"coin": { "name": "Coin", "options": { "heads": { "text": "H", "quest": "Q" }, "tails": { "text": "T", "quest": "Q" } } }"""

    private fun structure(vararg categories: String, version: Int = 1) =
        """{ "version": $version, "categories": [${categories.joinToString(",")}] }"""

    private fun texts(vararg entries: String, version: Int = 1) = """{ "version": $version, "categories": { ${entries.joinToString(",")} } }"""

    private fun bothLanguages(vararg entries: String) = mapOf("en" to texts(*entries), "es" to texts(*entries))

    private fun errors(structure: String, texts: Map<String, String> = bothLanguages(validText)): List<String> =
        assertFailsWith<TruthDataException> { TruthLoader.load(structure, texts) }.errors

    @Test
    fun `valid data loads`() {
        val loaded = TruthLoader.load(structure(validCategory), bothLanguages(validText))
        assertEquals(listOf("heads", "tails"), loaded["coin"]!!.options.map { it.id })
        assertEquals("T", loaded.optionText("coin", "tails", "es")!!.text)
    }

    @Test
    fun `bad structure is reported together`() {
        val bad = """{ "id": "Coin", "options": [ { "id": "heads", "min": 0, "max": 40 }, { "id": "Tails", "min": 60, "max": 50 },
            { "id": "edge", "min": 40, "max": 101 } ] }"""
        val empty = """{ "id": "empty", "options": [] }"""
        val errors = errors(structure(bad, bad, empty, version = 2))
        val expected = listOf(
            "truths.json: version 2, expected 1",
            "Category Coin is defined more than once",
            "Option heads is defined more than once",
            "Category Coin: id must be snake_case",
            "Category Coin: option id Tails must be snake_case",
            "Category Coin: option Tails goes from 60 down to 50",
            "Category Coin: option heads goes outside 1..100",
            "Category Coin: option edge goes outside 1..100",
            "Category Coin: 40 is in options heads, edge",
            "Category empty: no options",
            "Category empty: no option for 1..100",
        )
        for (error in expected) assertTrue(error in errors, errors.toString())
    }

    @Test
    fun `text problems are reported for each language`() {
        val incomplete = """"coin": { "name": " ", "options": { "heads": { "text": "", "quest": " " }, "extra": { "text": "E", "quest": "Q" } } },
            "dice": { "name": "Dice", "options": {} }"""
        val errors = errors(structure(validCategory), mapOf("en" to texts(incomplete, version = 3)))
        val expected = listOf(
            "truths.es.json: missing",
            "truths.en.json: version 3, expected 1",
            "truths.en.json: text for unknown category dice",
            "truths.en.json, category coin: empty name",
            "truths.en.json, category coin: missing text for option tails",
            "truths.en.json, category coin: text for unknown option extra",
            "truths.en.json, category coin: empty text for option heads",
            "truths.en.json, category coin: empty quest starter for option heads",
        )
        for (error in expected) assertTrue(error in errors, errors.toString())
        assertTrue("truths.en.json: no text for category coin" in errors(structure(validCategory), bothLanguages()))
    }

    @Test
    fun `unreadable files are reported`() {
        assertTrue(errors("not json").single().startsWith("truths.json:"))
        assertTrue(errors(structure(validCategory), mapOf("en" to "{", "es" to "{")).all { it.startsWith("truths.") })
        val missing = assertFailsWith<TruthDataException> { TruthLoader.fromResources("nowhere") }.errors
        assertEquals(listOf("nowhere/truths.json: file not found"), missing)
    }

    private companion object {
        val BOOK_ORDER = listOf(
            "old_world", "iron", "legacies", "communities", "leaders", "defense",
            "mysticism", "religion", "firstborn", "beasts", "horrors",
        )
    }
}
