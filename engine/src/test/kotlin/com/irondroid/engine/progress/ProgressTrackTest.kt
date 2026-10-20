package com.irondroid.engine.progress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressTrackTest {
    private fun vow(rank: ChallengeRank, ticks: Int = 0) = ProgressTrack("Avenge my kin", ProgressTrackType.VOW, rank, ticks)

    @Test
    fun `each rank marks its own amount of progress`() {
        val ticks = ChallengeRank.entries.associateWith { vow(it).markProgress().ticks }
        assertEquals(
            mapOf(
                ChallengeRank.TROUBLESOME to 12,
                ChallengeRank.DANGEROUS to 8,
                ChallengeRank.FORMIDABLE to 4,
                ChallengeRank.EXTREME to 2,
                ChallengeRank.EPIC to 1,
            ),
            ticks,
        )
    }

    @Test
    fun `ranks carry their harm and experience`() {
        assertEquals(listOf(1, 2, 3, 4, 5), ChallengeRank.entries.map { it.harm })
        assertEquals(listOf(1, 2, 3, 4, 5), ChallengeRank.entries.map { it.experience })
    }

    @Test
    fun `bonds have no rank and advance one tick`() {
        val bonds = ProgressTrack("Bonds", ProgressTrackType.BONDS, rank = null)
        assertEquals(1, bonds.markProgress().ticks)
        assertEquals(3, bonds.markProgress(3).ticks)
    }

    @Test
    fun `only complete boxes count`() {
        assertEquals(0, vow(ChallengeRank.EXTREME).markProgress().filledBoxes) // 2 ticks.
        assertEquals(1, vow(ChallengeRank.EXTREME).markProgress(2).filledBoxes) // 4 ticks.
        assertEquals(3, vow(ChallengeRank.DANGEROUS, ticks = 6).markProgress().filledBoxes) // 14 ticks.
    }

    @Test
    fun `progress stops when the track is full`() {
        val full = vow(ChallengeRank.TROUBLESOME).markProgress(4) // 48 ticks would overflow.
        assertEquals(40, full.ticks)
        assertEquals(10, full.filledBoxes)
        assertTrue(full.isComplete)
        assertFalse(vow(ChallengeRank.TROUBLESOME, ticks = 39).isComplete)
    }

    @Test
    fun `boxes report their ticks for drawing`() {
        val track = vow(ChallengeRank.FORMIDABLE, ticks = 10) // Two full boxes and half of the third.
        assertEquals(listOf(4, 4, 2, 0, 0, 0, 0, 0, 0, 0), (0 until 10).map { track.ticksInBox(it) })
        assertFailsWith<IllegalArgumentException> { track.ticksInBox(10) }
    }

    @Test
    fun `invalid tracks are rejected`() {
        assertFailsWith<IllegalArgumentException> { ProgressTrack("Journey", ProgressTrackType.JOURNEY, rank = null) }
        assertFailsWith<IllegalArgumentException> { ProgressTrack("Bonds", ProgressTrackType.BONDS, ChallengeRank.EPIC) }
        assertFailsWith<IllegalArgumentException> { ProgressTrack(" ", ProgressTrackType.COMBAT, ChallengeRank.EPIC) }
        assertFailsWith<IllegalArgumentException> { vow(ChallengeRank.EPIC, ticks = 41) }
        assertFailsWith<IllegalArgumentException> { vow(ChallengeRank.EPIC).markProgress(-1) }
    }

    @Test
    fun `clearing progress keeps only the boxes asked for`() {
        val track = vow(ChallengeRank.DANGEROUS, ticks = 22) // 5 boxes and 2 ticks.
        assertEquals(4, track.clearProgress(keepBoxes = 1).ticks)
        assertEquals(0, track.clearProgress().ticks)
        assertEquals(2, vow(ChallengeRank.EPIC, ticks = 2).clearProgress(keepBoxes = 1).ticks) // Never adds.
        assertFailsWith<IllegalArgumentException> { track.clearProgress(keepBoxes = 11) }
    }

    @Test
    fun `raising the rank stops at epic`() {
        assertEquals(ChallengeRank.DANGEROUS, vow(ChallengeRank.TROUBLESOME).raiseRank().rank)
        assertEquals(ChallengeRank.EPIC, vow(ChallengeRank.EXTREME).raiseRank().rank)
        assertEquals(ChallengeRank.EPIC, vow(ChallengeRank.EPIC).raiseRank().rank)
        assertFailsWith<IllegalStateException> { ProgressTrack("Bonds", ProgressTrackType.BONDS, rank = null).raiseRank() }
    }
}
