package com.irondroid.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldNamesTest {
    @Test
    fun `a free name is kept`() {
        assertEquals("The Ironlands", WorldNames.suggest(" The Ironlands ", listOf("Other")))
    }

    @Test
    fun `a taken name gets the first free number`() {
        val existing = listOf("The Ironlands", "the ironlands (2)", "The Ironlands (4)")

        assertEquals("The Ironlands (3)", WorldNames.suggest("The Ironlands", existing))
    }

    @Test
    fun `names compare ignoring case and spaces`() {
        assertTrue(WorldNames.isTaken("  THE IRONLANDS", listOf("The Ironlands ")))
        assertFalse(WorldNames.isTaken("The Ironlands 2", listOf("The Ironlands")))
    }
}
