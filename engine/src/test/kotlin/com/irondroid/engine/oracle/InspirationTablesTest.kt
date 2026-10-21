package com.irondroid.engine.oracle

import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.dice.SeededDice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The inspiration tables of SRD chapter 6 the narrator rolls on before inventing (IRON-103). */
class InspirationTablesTest {
    private val catalog = OracleTableLoader.fromResources()

    private fun row(table: String, roll: Int) = catalog[table]!!.rowFor(roll).id

    private fun text(table: String, roll: Int, language: String) = catalog.text(table, language)!!.rows.getValue(row(table, roll))

    @Test
    fun `the inspiration tables are loaded`() {
        assertEquals(INSPIRATION, catalog.tables.map { it.id }.filter { it in INSPIRATION }.toSet())
        for (id in INSPIRATION) {
            assertTrue(catalog.text(id, "en")!!.name.isNotBlank())
            assertTrue(catalog.text(id, "es")!!.name.isNotBlank())
        }
    }

    @Test
    fun `single-number tables have one row per result and 100 is the SRD's 00`() {
        for (id in listOf("action", "theme", "character_descriptor", "ironlander_names_1", "ironlander_names_2")) {
            assertEquals(100, catalog[id]!!.rows.size, id)
        }
        assertEquals("Scheme", text("action", 1, "en"))
        assertEquals("Summon", text("action", 100, "en"))
        assertEquals("Convocar", text("action", 100, "es"))
        assertEquals("Supply", text("theme", 100, "en"))
        assertEquals("Provisiones", text("theme", 100, "es"))
        assertEquals("Ironsworn", text("character_descriptor", 100, "en"))
        assertEquals("Abella", text("ironlander_names_1", 1, "es"))
        assertEquals("Chandra", text("ironlander_names_2", 100, "en"))
    }

    @Test
    fun `range tables follow the SRD`() {
        assertEquals(
            listOf("barrier_islands", "barrier_islands", "ragged_coast", "havens", "shattered_wastes", "elsewhere"),
            listOf(1, 12, 13, 60, 99, 100).map { row("region", it) },
        )
        assertEquals("Islas Barrera", text("region", 1, "es"))
        assertEquals(listOf("hideout", "caravan", "caravan", "woods", "anomaly"), listOf(1, 17, 18, 99, 100).map { row("location", it) })
        assertEquals(listOf("high", "high", "remote", "isolated"), listOf(1, 2, 3, 100).map { row("location_descriptor", it) })
        assertEquals(listOf("farmer", "unusual_role"), listOf(99, 100).map { row("character_role", it) })
        assertEquals("Rol inusual", text("character_role", 100, "es"))
        assertEquals(listOf("it_was_all_a_diversion", "roll_twice"), listOf(5, 96).map { row("major_plot_twist", it) })
    }

    @Test
    fun `settlement names join a prefix and a suffix and keep their meaning in spanish`() {
        assertEquals(listOf("bleak", "long"), listOf(1, 100).map { row("settlement_name_prefix", it) })
        assertEquals(listOf("moor", "wick"), listOf(1, 100).map { row("settlement_name_suffix", it) })
        assertEquals("Wolf-", text("settlement_name_prefix", 9, "en"))
        assertEquals("Wolf- (lobo)", text("settlement_name_prefix", 9, "es"))
        assertEquals("-crag (risco)", text("settlement_name_suffix", 9, "es"))
    }

    @Test
    fun `roll twice rows give two results`() {
        val goal = catalog.roll("character_goal", FixedDice(97, 1, 30))
        assertEquals(listOf("obtain_an_object", "find_a_person"), goal.results.map { it.id })
        val twist = catalog.roll("major_plot_twist", FixedDice(100, 11, 12))
        assertEquals(listOf("a_trap_is_sprung", "a_trap_is_sprung"), twist.results.map { it.id })
        assertTrue(twist.isRepeated)
    }

    @Test
    fun `seeded rolls always land on a row with text`() {
        val dice = SeededDice.fromSeed(103)
        repeat(500) {
            for (id in INSPIRATION) {
                for (result in catalog.roll(id, dice).results) {
                    assertTrue(catalog.text(id, "es")!!.rows.getValue(result.id).isNotBlank())
                }
            }
        }
    }

    private companion object {
        val INSPIRATION = setOf(
            "action", "theme", "region", "location", "location_descriptor", "settlement_name_prefix", "settlement_name_suffix",
            "character_role", "character_goal", "character_descriptor", "ironlander_names_1", "ironlander_names_2", "major_plot_twist",
        )
    }
}
