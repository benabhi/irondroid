package com.irondroid.engine.momentum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomentumTest {
    @Test
    fun `a new character starts at +2 with maximum 10 and reset 2`() {
        val momentum = Momentum()
        assertEquals(2, momentum.value)
        assertEquals(10, momentum.max)
        assertEquals(2, momentum.reset)
    }

    @Test
    fun `debilities lower the maximum and the reset`() {
        assertEquals(listOf(10, 9, 8, 7, 2), listOf(0, 1, 2, 3, 8).map { Momentum.maxFor(it) })
        assertEquals(listOf(2, 1, 0, 0, 0), listOf(0, 1, 2, 3, 8).map { Momentum.resetFor(it) })
    }

    @Test
    fun `gaining stops at the maximum`() {
        assertEquals(10, Momentum(8).gain(5).value)
        assertEquals(8, Momentum(6, debilities = 2).gain(5).value)
    }

    @Test
    fun `losing within range does not require a setback`() {
        val loss = Momentum(2).lose(3)
        assertEquals(-1, loss.momentum.value)
        assertFalse(loss.requiresSetback)
    }

    @Test
    fun `losing past the minimum stops at -6 and requires a setback`() {
        val atMinimum = Momentum(-6).lose(2)
        assertEquals(-6, atMinimum.momentum.value)
        assertEquals(2, atMinimum.overflow)
        assertTrue(atMinimum.requiresSetback)

        val crossing = Momentum(-5).lose(3)
        assertEquals(-6, crossing.momentum.value)
        assertEquals(2, crossing.overflow)

        val landing = Momentum(-4).lose(2)
        assertEquals(-6, landing.momentum.value)
        assertFalse(landing.requiresSetback)
    }

    @Test
    fun `resetting uses the value for the current debilities`() {
        assertEquals(2, Momentum(9).resetValue().value)
        assertEquals(1, Momentum(7, debilities = 1).resetValue().value)
        assertEquals(0, Momentum(-3, debilities = 2).resetValue().value)
    }

    @Test
    fun `a new debility pulls momentum down to the new maximum`() {
        assertEquals(Momentum(9, debilities = 1), Momentum(10).withDebilities(1))
        assertEquals(Momentum(4, debilities = 1), Momentum(4).withDebilities(1))
        assertEquals(Momentum(4, debilities = 0), Momentum(4, debilities = 1).withDebilities(0))
    }

    @Test
    fun `invalid values are rejected`() {
        assertFailsWith<IllegalArgumentException> { Momentum(11) }
        assertFailsWith<IllegalArgumentException> { Momentum(-7) }
        assertFailsWith<IllegalArgumentException> { Momentum(10, debilities = 1) }
        assertFailsWith<IllegalArgumentException> { Momentum(0, debilities = 9) }
        assertFailsWith<IllegalArgumentException> { Momentum().gain(-1) }
        assertFailsWith<IllegalArgumentException> { Momentum().lose(-1) }
    }
}
