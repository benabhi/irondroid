package com.irondroid.engine.character

import com.irondroid.engine.debility.Debilities
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.momentum.Momentum
import com.irondroid.engine.move.RollSource
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CharacterTest {
    private val hero = Character.create(edge = 3, heart = 2, iron = 2, shadow = 1, wits = 1)

    @Test
    fun `a new character starts with full meters and +2 momentum`() {
        assertEquals(listOf(5, 5, 5), MeterType.entries.map { hero.meters[it].value })
        assertEquals(Momentum(), hero.momentum)
        assertEquals(0, hero.experience)
    }

    @Test
    fun `roll sources read stats and meters`() {
        assertEquals(3, hero.value(RollSource.EDGE))
        assertEquals(1, hero.value(RollSource.WITS))
        assertEquals(5, hero.value(RollSource.SUPPLY))
    }

    @Test
    fun `tracks get numbered ids by type`() {
        val (first, one) = hero.startTrack(ProgressTrackType.VOW, "Avenge my kin", ChallengeRank.EPIC)
        val (second, two) = one.startTrack(ProgressTrackType.VOW, "Find the healer", ChallengeRank.DANGEROUS)
        val (journey, three) = two.startTrack(ProgressTrackType.JOURNEY, "To the Hinterlands", ChallengeRank.TROUBLESOME)
        assertEquals(listOf("vow_1", "vow_2", "journey_1"), listOf(first, second, journey))
        assertEquals(3, three.tracks.size)
    }

    @Test
    fun `marking debilities keeps momentum in step`() {
        val shaken = hero.copy(momentum = Momentum(10)).withDebilities(Debilities(setOf(Debility.SHAKEN)))
        assertEquals(Momentum(9, debilities = 1), shaken.momentum)
    }

    @Test
    fun `an inconsistent sheet is rejected`() {
        assertFailsWith<IllegalArgumentException> { Character(mapOf(Stat.EDGE to 1)) }
        assertFailsWith<IllegalArgumentException> { Character.create(-1, 1, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { hero.copy(debilities = Debilities(setOf(Debility.WOUNDED))) }
        assertFailsWith<IllegalArgumentException> { hero.copy(experience = -1) }
    }
}
