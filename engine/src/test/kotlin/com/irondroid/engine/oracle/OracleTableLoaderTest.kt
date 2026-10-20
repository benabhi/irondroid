package com.irondroid.engine.oracle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OracleTableLoaderTest {
    private val validTable = """{ "id": "coin", "rows": [ { "id": "heads", "min": 1, "max": 50 }, { "id": "tails", "min": 51, "max": 100 } ] }"""
    private val validText = """"coin": { "name": "Coin", "rows": { "heads": "Heads", "tails": "Tails" } }"""

    private fun structure(vararg tables: String, version: Int = 1) = """{ "version": $version, "tables": [${tables.joinToString(",")}] }"""

    private fun texts(vararg entries: String, version: Int = 1) = """{ "version": $version, "tables": { ${entries.joinToString(",")} } }"""

    private fun bothLanguages(vararg entries: String) = mapOf("en" to texts(*entries), "es" to texts(*entries))

    private fun errors(structure: String, texts: Map<String, String> = bothLanguages(validText)): List<String> =
        assertFailsWith<OracleTableDataException> { OracleTableLoader.load(structure, texts) }.errors

    @Test
    fun `valid data loads`() {
        val catalog = OracleTableLoader.load(structure(validTable), bothLanguages(validText))
        assertEquals(listOf("coin"), catalog.tables.map { it.id })
        assertEquals("Tails", catalog.text("coin", "es")!!.rows["tails"])
    }

    @Test
    fun `gaps and overlaps in the d100 are reported`() {
        val table = """{ "id": "coin", "rows": [ { "id": "heads", "min": 1, "max": 40 }, { "id": "tails", "min": 45, "max": 99 },
            { "id": "edge", "min": 99, "max": 100 } ] }"""
        val text = """"coin": { "name": "Coin", "rows": { "heads": "H", "tails": "T", "edge": "E" } }"""
        val errors = errors(structure(table), bothLanguages(text))
        assertTrue("Table coin: no row for 41-44" in errors, errors.toString())
        assertTrue("Table coin: 99 is in rows tails, edge" in errors, errors.toString())
    }

    @Test
    fun `bad rows are reported together`() {
        val table = """{ "id": "Coin", "rows": [ { "id": "heads", "min": 0, "max": 50 }, { "id": "heads", "min": 51, "max": 50 },
            { "id": "Tails", "min": 51, "max": 101 } ] }"""
        val errors = errors(structure(table, table), bothLanguages())
        assertTrue("Table Coin is defined more than once" in errors, errors.toString())
        assertTrue("Table Coin: id must be snake_case" in errors, errors.toString())
        assertTrue("Table Coin: row heads is repeated" in errors, errors.toString())
        assertTrue("Table Coin: row id Tails must be snake_case" in errors, errors.toString())
        assertTrue("Table Coin: row heads goes from 51 down to 50" in errors, errors.toString())
        assertTrue("Table Coin: row heads goes outside 1..100" in errors, errors.toString())
        assertTrue("Table Coin: row Tails goes outside 1..100" in errors, errors.toString())
    }

    @Test
    fun `a single uncovered roll is reported without a range`() {
        val table = """{ "id": "coin", "rows": [ { "id": "heads", "min": 2, "max": 50 }, { "id": "tails", "min": 51, "max": 100 } ] }"""
        assertTrue("Table coin: no row for 1" in errors(structure(table)))
    }

    @Test
    fun `text problems are reported for each language`() {
        val incomplete = """"coin": { "name": " ", "rows": { "heads": "", "extra": "?" } }, "dice": { "name": "Dice", "rows": {} }"""
        val errors = errors(structure(validTable), mapOf("en" to texts(incomplete), "es" to texts(version = 2)))
        assertTrue("tables.en.json: text for unknown table dice" in errors, errors.toString())
        assertTrue("tables.en.json, table coin: empty name" in errors, errors.toString())
        assertTrue("tables.en.json, table coin: missing text for row tails" in errors, errors.toString())
        assertTrue("tables.en.json, table coin: text for unknown row extra" in errors, errors.toString())
        assertTrue("tables.en.json, table coin: empty text for row heads" in errors, errors.toString())
        assertTrue("tables.es.json: version 2, expected 1" in errors, errors.toString())
        assertTrue("tables.es.json: no text for table coin" in errors, errors.toString())
    }

    @Test
    fun `missing language, wrong version and broken json are reported`() {
        val missing = errors(structure(validTable, version = 3), mapOf("en" to texts(validText)))
        assertTrue("tables.json: version 3, expected 1" in missing, missing.toString())
        assertTrue("tables.es.json: missing" in missing, missing.toString())

        val broken = errors("{ not json", mapOf("en" to "[]", "es" to texts(validText)))
        assertTrue(broken.any { it.startsWith("tables.json: ") }, broken.toString())
        assertTrue(broken.any { it.startsWith("tables.en.json: ") }, broken.toString())
    }

    @Test
    fun `move of a row must be a snake_case id`() {
        val table = """{ "id": "coin", "rows": [ { "id": "heads", "min": 1, "max": 50, "move": "Face Death" },
            { "id": "tails", "min": 51, "max": 100, "move": "face_death" } ] }"""
        assertEquals(listOf("Table coin: move id Face Death must be snake_case"), errors(structure(table)))
    }

    @Test
    fun `unknown row action is an error`() {
        val table = """{ "id": "coin", "rows": [ { "id": "heads", "min": 1, "max": 100, "then": "roll_thrice" } ] }"""
        assertTrue(errors(structure(table)).single().startsWith("tables.json: "))
    }

    @Test
    fun `missing resource file is reported`() {
        val errors = assertFailsWith<OracleTableDataException> { OracleTableLoader.fromResources("no_such_dir") }.errors
        assertEquals(listOf("no_such_dir/tables.json: file not found"), errors)
    }
}
