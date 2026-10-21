package com.irondroid.ai.narrator

import com.irondroid.ai.tools.ActionTools
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.game.GameAccess
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NarratorPromptTest {
    private val spanish = NarratorPrompt.build("Kira")

    @Test
    fun `the prompt joins the common rules, the character, the language and the style`() {
        assertTrue(spanish.startsWith("You are the narrator of a solo Ironsworn game"))
        assertTrue("## What you may and may not do" in spanish)
        assertTrue("## Inspiration before invention" in spanish)
        assertTrue("## The player's character" in spanish)
        assertTrue("neutral Latin American Spanish" in spanish)
        assertTrue("## Style: performed" in spanish)
        assertTrue("2 to 4 short sentences" in spanish)
    }

    @Test
    fun `the character's name fills every placeholder`() {
        assertFalse("{" in spanish || "}" in spanish, spanish)
        assertTrue("the player decides what Kira does" in spanish)
        assertTrue("Never decide for Kira" in spanish)
    }

    @Test
    fun `the style block changes and the common rules stay`() {
        val sober = NarratorPrompt.build("Kira", style = NarratorStyle.SOBER)
        assertTrue("## Style: sober" in sober)
        assertFalse("## Style: performed" in sober)
        assertEquals(spanish.substringBefore("## Style"), sober.substringBefore("## Style"))
    }

    @Test
    fun `the gender line follows what the player said`() {
        assertTrue("avoid gendered words about Kira" in spanish)
        assertTrue("Kira is a woman" in NarratorPrompt.build("Kira", gender = CharacterGender.FEMININE))
        assertTrue("Bran is a man" in NarratorPrompt.build("Bran", gender = CharacterGender.MASCULINE))
    }

    @Test
    fun `english narration has its own language block`() {
        val english = NarratorPrompt.build("Kira", language = "en")
        assertTrue("Write ONLY in English" in english)
        assertFalse("Latin American" in english)
    }

    @Test
    fun `unknown languages and blank names are rejected`() {
        assertFailsWith<IllegalArgumentException> { NarratorPrompt.build("Kira", language = "fr") }
        assertFailsWith<IllegalArgumentException> { NarratorPrompt.build(" ") }
    }

    @Test
    fun `every tool and oracle table the prompt names exists`() {
        val oracles = OracleTableLoader.fromResources()
        val game = object : GameAccess {
            override val sheet = Character.create(edge = 1, heart = 1, iron = 1, shadow = 1, wits = 1)
            override val characterName = "Kira"
            override val history: ActionHistory<Character>? = null
            override fun update(change: (Character) -> Character) {}
            override fun save() {}
        }
        val tools = ActionTools.forNarrator(GameActions(game, MoveLoader.fromResources(), oracles, SeededDice.fromSeed(1), "es").dispatcher())
        val known = tools.specs.map { it.name }.toSet() + oracles.tables.map { it.id }
        val named = Regex("`([a-z_0-9]+)`").findAll(spanish + NarratorPrompt.build("Kira", style = NarratorStyle.SOBER)).map { it.groupValues[1] }.toSet()
        assertTrue(named.isNotEmpty())
        assertEquals(emptySet(), named - known)
    }
}
