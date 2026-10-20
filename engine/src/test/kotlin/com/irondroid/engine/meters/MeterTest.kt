package com.irondroid.engine.meters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeterTest {
    @Test
    fun `meters start at +5`() {
        val meters = Meters()
        assertEquals(listOf(5, 5, 5), MeterType.entries.map { meters[it].value })
    }

    @Test
    fun `gaining stops at +5`() {
        assertEquals(5, Meter(MeterType.HEALTH, 3).gain(4).value)
        assertEquals(4, Meter(MeterType.SPIRIT, 1).gain(3).value)
    }

    @Test
    fun `losing within range has no overflow`() {
        val loss = Meter(MeterType.HEALTH, 4).lose(3)
        assertEquals(1, loss.meter.value)
        assertEquals(0, loss.overflow)
    }

    @Test
    fun `losing past zero stops at 0 and reports the overflow`() {
        val loss = Meter(MeterType.SPIRIT, 2).lose(5)
        assertEquals(0, loss.meter.value)
        assertEquals(3, loss.overflow)
        assertEquals(2, Meter(MeterType.HEALTH, 0).lose(2).overflow)
    }

    @Test
    fun `supply reaching zero triggers Out of Supply`() {
        assertTrue(Meter(MeterType.SUPPLY, 1).lose(1).triggersOutOfSupply)
        assertTrue(Meter(MeterType.SUPPLY, 0).lose(1).triggersOutOfSupply)
        assertFalse(Meter(MeterType.SUPPLY, 2).lose(1).triggersOutOfSupply)
        assertFalse(Meter(MeterType.SUPPLY, 0).lose(0).triggersOutOfSupply)
        assertFalse(Meter(MeterType.HEALTH, 1).lose(1).triggersOutOfSupply)
    }

    @Test
    fun `meters adjust only the type asked for`() {
        val (meters, loss) = Meters().lose(MeterType.SUPPLY, 2)
        assertEquals(Meters(supply = Meter(MeterType.SUPPLY, 3)), meters)
        assertEquals(MeterType.SUPPLY, loss.meter.type)

        val healed = Meters(health = Meter(MeterType.HEALTH, 1)).gain(MeterType.HEALTH, 2)
        assertEquals(3, healed.health.value)
        assertEquals(5, healed.spirit.value)
    }

    @Test
    fun `invalid values are rejected`() {
        assertFailsWith<IllegalArgumentException> { Meter(MeterType.HEALTH, 6) }
        assertFailsWith<IllegalArgumentException> { Meter(MeterType.HEALTH, -1) }
        assertFailsWith<IllegalArgumentException> { Meter(MeterType.HEALTH).gain(-1) }
        assertFailsWith<IllegalArgumentException> { Meter(MeterType.HEALTH).lose(-1) }
        assertFailsWith<IllegalArgumentException> { Meters(health = Meter(MeterType.SPIRIT)) }
    }
}
