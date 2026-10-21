package com.irondroid.data

import com.irondroid.engine.debility.Debility
import org.junit.Test
import kotlin.test.assertEquals

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `no debilities are stored as empty text`() {
        assertEquals("", converters.debilitiesToText(emptySet()))
        assertEquals(emptySet(), converters.textToDebilities(""))
    }

    @Test
    fun `debilities are stored in declaration order and read back`() {
        val marked = setOf(Debility.TORMENTED, Debility.WOUNDED, Debility.MAIMED)

        val text = converters.debilitiesToText(marked)

        assertEquals("WOUNDED,MAIMED,TORMENTED", text)
        assertEquals(marked, converters.textToDebilities(text))
    }

    @Test
    fun `every debility survives the round trip`() {
        val all = Debility.entries.toSet()

        assertEquals(all, converters.textToDebilities(converters.debilitiesToText(all)))
    }
}
