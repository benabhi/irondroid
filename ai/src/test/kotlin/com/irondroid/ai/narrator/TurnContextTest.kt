package com.irondroid.ai.narrator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnContextTest {
    private val stats = mapOf("edge" to 2, "heart" to 1, "iron" to 3, "shadow" to 2, "wits" to 1)

    @Test
    fun `the character, tracks, truths and facts are all there`() {
        val text = TurnContext(
            "Kira",
            stats,
            health = 4,
            spirit = 5,
            supply = 2,
            momentum = -1,
            debilities = listOf("wounded"),
            tracks = listOf(TurnContext.Track("Avenge my family", "vow", "dangerous", 3), TurnContext.Track("Bond", "bonds", null, 1)),
            truths = listOf("The old world was lost to a plague."),
            facts = listOf("Ironlander Names (1-100): Kaivan", "Settlement Name: Prefix: Gray- (gris)"),
        ).render()
        assertTrue("Kira: edge 2, heart 1, iron 3, shadow 2, wits 1; health 4, spirit 5, supply 2, momentum -1; debilities: wounded." in text, text)
        assertTrue("- Avenge my family (vow, dangerous): 3/10 boxes" in text, text)
        assertTrue("- Bond (bonds): 1/10 boxes" in text, text)
        assertTrue("- The old world was lost to a plague." in text, text)
        assertTrue("- Ironlander Names (1-100): Kaivan" in text, text)
        assertFalse("not chosen yet" in text)
    }

    @Test
    fun `an empty game says so instead of leaving gaps`() {
        val text = TurnContext("Kira", stats.mapValues { 0 }, health = 5, spirit = 5, supply = 5, momentum = 2).render()
        assertTrue("momentum +2" in text, text)
        assertTrue("The stats are not chosen yet" in text, text)
        assertTrue("None yet." in text, text)
        assertFalse("World truths" in text || "Established by the oracles" in text, text)
    }
}
