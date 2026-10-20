package com.irondroid.engine.oracle

import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.move.MoveLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OracleTableTest {
    private val catalog = OracleTableLoader.fromResources()
    private val payThePrice = catalog["pay_the_price"]!!

    private fun ids(roll: TableRoll) = roll.results.map { it.id }

    @Test
    fun `pay the price follows the SRD ranges`() {
        val expected = mapOf(
            1 to "roll_again_worse", 2 to "roll_again_worse", 3 to "trust_lost", 9 to "loved_ones_in_danger",
            10 to "separated", 16 to "separated", 17 to "unintended_effect", 32 to "value_lost", 33 to "situation_worsens",
            50 to "new_danger", 51 to "delay_or_disadvantage", 60 to "harmful", 77 to "stressful", 78 to "surprising_development",
            90 to "wastes_resources", 91 to "against_intentions", 94 to "against_intentions", 95 to "ally_in_harms_way",
            98 to "ally_in_harms_way", 99 to "roll_twice", 100 to "roll_twice",
        )
        assertEquals(expected, expected.mapValues { payThePrice.rowFor(it.key).id })
        assertEquals(16, payThePrice.rows.size)
    }

    @Test
    fun `every row has its text in english and spanish`() {
        assertEquals(setOf("en", "es"), catalog.languages)
        assertEquals("Pay the Price", catalog.text("pay_the_price", "en")!!.name)
        assertEquals("Pagar el precio", catalog.text("pay_the_price", "es")!!.name)
        assertEquals("It is harmful.", catalog.text("pay_the_price", "en")!!.rows["harmful"])
        assertEquals("Es dañino.", catalog.text("pay_the_price", "es")!!.rows["harmful"])
    }

    @Test
    fun `a plain row is the result`() {
        val dice = FixedDice(60)
        val roll = catalog.roll("pay_the_price", dice)
        assertEquals(TableRoll("pay_the_price", 60, payThePrice.rowFor(60)), roll)
        assertEquals(listOf("harmful"), ids(roll))
        assertEquals(0, dice.remaining)
    }

    @Test
    fun `1-2 rolls again and keeps the new result`() {
        val roll = payThePrice.roll(FixedDice(2, 45))
        assertEquals(listOf(45), roll.extra.map { it.roll })
        assertEquals(listOf("new_danger"), ids(roll))
    }

    @Test
    fun `1-2 twice in a row stops so the player invents something dreadful`() {
        val dice = FixedDice(1, 2, 50)
        val roll = payThePrice.roll(dice)
        assertEquals(listOf("roll_again_worse"), ids(roll))
        assertTrue(roll.extra.single().extra.isEmpty())
        assertEquals(1, dice.remaining)
    }

    @Test
    fun `99-100 rolls twice more and both results happen`() {
        val roll = payThePrice.roll(FixedDice(99, 10, 70))
        assertEquals(listOf("separated", "stressful"), ids(roll))
        assertFalse(roll.isRepeated)
    }

    @Test
    fun `the same result twice is marked to make it worse`() {
        val roll = payThePrice.roll(FixedDice(100, 60, 65))
        assertEquals(listOf("harmful", "harmful"), ids(roll))
        assertTrue(roll.isRepeated)
    }

    @Test
    fun `extra rolls nest, as 99-100 after 1-2 or 1-2 after 99-100`() {
        assertEquals(listOf("trust_lost", "value_lost"), ids(payThePrice.roll(FixedDice(1, 99, 3, 24))))
        assertEquals(listOf("harmful", "situation_worsens"), ids(payThePrice.roll(FixedDice(99, 2, 60, 33))))
    }

    @Test
    fun `a chain of 99-100 stops at the depth limit`() {
        val dice = FixedDice(List(31) { 100 })
        val roll = payThePrice.roll(dice)
        assertEquals(16, roll.results.size) // Two extra rolls per level, four levels deep.
        assertTrue(roll.results.all { it.id == "roll_twice" })
        assertEquals(0, dice.remaining)
    }

    @Test
    fun `same seed gives the same table rolls`() {
        val first = SeededDice.fromSeed(31)
        val second = SeededDice.fromSeed(31)
        repeat(200) { assertEquals(payThePrice.roll(first), payThePrice.roll(second)) }
    }

    @Test
    fun `unknown table and rolls outside the d100 are rejected`() {
        assertFailsWith<IllegalArgumentException> { catalog.roll("no_such_table", FixedDice(5)) }
        assertFailsWith<IllegalArgumentException> { payThePrice.rowFor(0) }
        assertFailsWith<IllegalArgumentException> { payThePrice.rowFor(101) }
    }

    @Test
    fun `only the consequence tables of IRON-98 are still missing for the moves`() {
        val moves = MoveLoader.fromResources()
        val missing = moves.referencedTables() - catalog.tables.map { it.id }.toSet()
        assertEquals(setOf("endure_harm", "endure_stress"), missing)
    }
}
