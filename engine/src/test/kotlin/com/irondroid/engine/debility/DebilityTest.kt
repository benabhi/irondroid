package com.irondroid.engine.debility

import com.irondroid.engine.momentum.Momentum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebilityTest {
    @Test
    fun `there are four conditions, two banes and two burdens`() {
        val byKind = Debility.entries.groupBy { it.kind }
        assertEquals(
            listOf(Debility.WOUNDED, Debility.SHAKEN, Debility.UNPREPARED, Debility.ENCUMBERED),
            byKind[DebilityKind.CONDITION],
        )
        assertEquals(listOf(Debility.MAIMED, Debility.CORRUPTED), byKind[DebilityKind.BANE])
        assertEquals(listOf(Debility.CURSED, Debility.TORMENTED), byKind[DebilityKind.BURDEN])
    }

    @Test
    fun `marking adds a debility once`() {
        val debilities = Debilities().mark(Debility.WOUNDED).mark(Debility.WOUNDED).mark(Debility.CURSED)
        assertEquals(2, debilities.count)
        assertTrue(Debility.WOUNDED in debilities)
        assertFalse(Debility.SHAKEN in debilities)
    }

    @Test
    fun `conditions and burdens can be cleared`() {
        val debilities = Debilities(setOf(Debility.SHAKEN, Debility.TORMENTED))
        assertEquals(Debilities(setOf(Debility.TORMENTED)), debilities.clear(Debility.SHAKEN))
        assertEquals(Debilities(setOf(Debility.SHAKEN)), debilities.clear(Debility.TORMENTED))
        assertEquals(debilities, debilities.clear(Debility.WOUNDED)) // Not marked: nothing changes.
    }

    @Test
    fun `banes are permanent`() {
        val maimed = Debilities().mark(Debility.MAIMED)
        assertFailsWith<IllegalArgumentException> { maimed.clear(Debility.MAIMED) }
    }

    @Test
    fun `each debility lowers the momentum maximum and reset`() {
        var debilities = Debilities()
        val momentum = Momentum(10)
        assertEquals(Momentum(10, debilities = 0), debilities.applyTo(momentum))

        debilities = debilities.mark(Debility.WOUNDED)
        val oneDebility = debilities.applyTo(momentum)
        assertEquals(9, oneDebility.value) // Above the new maximum of 9.
        assertEquals(9, oneDebility.max)
        assertEquals(1, oneDebility.reset)

        debilities = debilities.mark(Debility.CORRUPTED).mark(Debility.CURSED)
        val three = debilities.applyTo(oneDebility)
        assertEquals(7, three.max)
        assertEquals(0, three.reset)
    }

    @Test
    fun `clearing a debility raises the maximum again without raising momentum`() {
        val debilities = Debilities(setOf(Debility.UNPREPARED))
        val momentum = debilities.applyTo(Momentum(9, debilities = 1))
        val cleared = debilities.clear(Debility.UNPREPARED).applyTo(momentum)
        assertEquals(10, cleared.max)
        assertEquals(2, cleared.reset)
        assertEquals(9, cleared.value)
    }

    @Test
    fun `all eight debilities leave a maximum of +2 and reset 0`() {
        val all = Debilities(Debility.entries.toSet()).applyTo(Momentum())
        assertEquals(2, all.max)
        assertEquals(0, all.reset)
    }
}
