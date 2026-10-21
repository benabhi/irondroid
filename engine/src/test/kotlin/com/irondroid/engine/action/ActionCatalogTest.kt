package com.irondroid.engine.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActionCatalogTest {
    private val getCharacter = ActionDefinition(
        name = "get_character",
        area = ActionArea.QUERY,
        description = "Reads the character sheet",
        whenToUse = "You need the current stats, meters or debilities",
        permission = ActionPermission.READ,
        returns = ActionReturn("The character sheet", JsonSchema.obj { integer("health", "Health, 0 to 5", minimum = 0, maximum = 5) }),
    )

    private val askOracle = ActionDefinition(
        name = "ask_oracle",
        area = ActionArea.ORACLES,
        description = "Asks the yes/no oracle.",
        whenToUse = "A question about the world has no clear answer in the fiction.",
        permission = ActionPermission.PLAY,
        returns = ActionReturn("The roll, the answer and whether it is extreme."),
        parameters = JsonSchema.obj {
            string("question", "The question, as the player asked it")
            string("odds", "How likely a yes is", enum = listOf("almost_certain", "likely", "fifty_fifty", "unlikely", "small_chance"))
        },
        effects = setOf(ActionEffect.ROLLS_DICE),
    )

    private val markProgress = ActionDefinition(
        name = "mark_progress",
        area = ActionArea.PROGRESS,
        description = "Marks progress on a track",
        whenToUse = "The fiction moves a vow, journey or fight forward",
        permission = ActionPermission.PLAY,
        returns = ActionReturn("The track with its new progress"),
        parameters = JsonSchema.obj { integer("times", "How many times to mark", required = false, minimum = 1) },
        effects = setOf(ActionEffect.CHANGES_TRACKS, ActionEffect.CHANGES_JOURNAL),
    )

    private val deleteCampaign = ActionDefinition(
        name = "delete_campaign",
        area = ActionArea.SYSTEM,
        description = "Deletes a campaign and its history",
        whenToUse = "The player asks to delete a campaign!",
        permission = ActionPermission.SENSITIVE,
        returns = ActionReturn("Nothing?"),
        effects = setOf(ActionEffect.DELETES_DATA),
    )

    private val v1 = CatalogVersion(1, 0)

    private fun errorsOf(vararg actions: ActionDefinition) =
        assertFailsWith<ActionCatalogException> { ActionCatalog.of(v1, actions.toList()) }.errors

    @Test
    fun `a catalog finds its actions by name and by area`() {
        val catalog = ActionCatalog.of(v1, listOf(getCharacter, askOracle, markProgress, deleteCampaign))
        assertSame(askOracle, catalog["ask_oracle"])
        assertNull(catalog["roll_dice"])
        assertEquals(listOf(markProgress), catalog.inArea(ActionArea.PROGRESS))
        assertEquals(emptyList(), catalog.inArea(ActionArea.COMBAT))
        assertEquals(4, catalog.actions.size)
    }

    @Test
    fun `permissions say what needs confirmation and what can be undone`() {
        assertFalse(ActionPermission.READ.requiresConfirmation)
        assertFalse(ActionPermission.READ.undoable)
        assertFalse(ActionPermission.PLAY.requiresConfirmation)
        assertTrue(ActionPermission.PLAY.undoable)
        assertTrue(ActionPermission.SENSITIVE.requiresConfirmation)
        assertFalse(ActionPermission.SENSITIVE.undoable)
    }

    @Test
    fun `only effects on saved data make an action change state`() {
        assertFalse(getCharacter.changesState)
        assertFalse(askOracle.changesState)
        assertTrue(markProgress.changesState)
        assertTrue(deleteCampaign.changesState)
    }

    @Test
    fun `the full description joins everything the AI needs`() {
        assertEquals(
            "Reads the character sheet. Use it when you need the current stats, meters or debilities. " +
                "It changes nothing. Returns: the character sheet.",
            getCharacter.fullDescription,
        )
        assertEquals(
            "Marks progress on a track. Use it when the fiction moves a vow, journey or fight forward. " +
                "Effects: changes progress tracks, writes to the campaign journal. Returns: the track with its new progress.",
            markProgress.fullDescription,
        )
        assertEquals(
            "Deletes a campaign and its history. Use it when the player asks to delete a campaign! " +
                "Effects: deletes data. The player must confirm it before it runs. Returns: nothing?",
            deleteCampaign.fullDescription,
        )
    }

    @Test
    fun `the catalog json lists every action with its schema and metadata`() {
        val json = ActionCatalog.of(CatalogVersion(1, 2), listOf(askOracle, deleteCampaign, getCharacter)).toJson()
        assertEquals("1.2", json["version"]?.jsonPrimitive?.content)
        val actions = json.getValue("actions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("ask_oracle", "delete_campaign", "get_character"), actions.map { it.getValue("name").jsonPrimitive.content })

        val expected = Json.parseToJsonElement(
            """
            { "name": "ask_oracle", "area": "oracles",
              "description": "Asks the yes/no oracle.",
              "when_to_use": "A question about the world has no clear answer in the fiction.",
              "permission": "play", "requires_confirmation": false, "undoable": true,
              "effects": ["rolls_dice"],
              "parameters": { "type": "object",
                "properties": {
                  "question": { "type": "string", "description": "The question, as the player asked it" },
                  "odds": { "type": "string", "description": "How likely a yes is",
                            "enum": ["almost_certain", "likely", "fifty_fifty", "unlikely", "small_chance"] } },
                "required": ["question", "odds"], "additionalProperties": false },
              "returns": { "description": "The roll, the answer and whether it is extreme." } }
            """,
        ) as JsonObject
        assertEquals(expected, actions[0])
        assertEquals("true", actions[1].getValue("requires_confirmation").jsonPrimitive.content)
        assertEquals("integer", actions[2].getValue("returns").jsonObject.getValue("schema").jsonObject
            .getValue("properties").jsonObject.getValue("health").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `every problem of every action is reported at once`() {
        val errors = errorsOf(
            askOracle,
            askOracle.copy(description = "Again"),
            getCharacter.copy(name = "GetCharacter", description = " ", whenToUse = "", returns = ActionReturn("")),
            getCharacter.copy(name = "peek", effects = setOf(ActionEffect.ROLLS_DICE)),
            markProgress.copy(name = "do_nothing", effects = emptySet()),
            deleteCampaign.copy(name = "silent_delete", effects = emptySet()),
            markProgress.copy(name = "wipe", effects = setOf(ActionEffect.DELETES_DATA, ActionEffect.CHANGES_SETTINGS)),
            askOracle.copy(name = "bad_args", parameters = JsonSchema.obj { property("odds", JsonSchema.StringValue("Odds", enum = emptyList())) }),
            getCharacter.copy(name = "bad_result", returns = ActionReturn("Stats", JsonSchema.obj { property("x", JsonSchema.BooleanValue()) })),
        )
        assertEquals(
            listOf(
                "Action ask_oracle is defined more than once",
                "Action GetCharacter: name must be snake_case",
                "Action GetCharacter: needs a description",
                "Action GetCharacter: needs to say when to use it",
                "Action GetCharacter: needs to say what it returns",
                "Action peek: a read action cannot have effects",
                "Action do_nothing: a play action needs its effects",
                "Action silent_delete: a sensitive action needs its effects",
                "Action wipe: deletes data only in a sensitive action",
                "Action wipe: changes app settings only in a sensitive action",
                "Action bad_args, parameters.odds: enum without values",
                "Action bad_result, returns.x: needs a description",
            ),
            errors,
        )
    }

    @Test
    fun `the exception message lists the errors`() {
        val message = assertFailsWith<ActionCatalogException> { ActionCatalog.of(v1, listOf(askOracle, askOracle)) }.message
        assertEquals("Invalid action catalog:\n- Action ask_oracle is defined more than once", message)
    }

    @Test
    fun `an empty catalog is valid`() {
        assertEquals(emptyList(), ActionCatalog.of(v1, emptyList()).actions)
    }

    @Test
    fun `versions compare, parse and tell compatible callers apart`() {
        assertEquals(CatalogVersion(2, 10), CatalogVersion.parse(" 2.10 "))
        assertEquals("2.10", CatalogVersion(2, 10).toString())
        assertTrue(CatalogVersion(1, 2) > CatalogVersion(1, 1))
        assertTrue(CatalogVersion(2, 0) > CatalogVersion(1, 9))
        assertTrue(CatalogVersion(1, 3).isCompatibleWith(CatalogVersion(1, 1)))
        assertTrue(CatalogVersion(1, 1).isCompatibleWith(CatalogVersion(1, 1)))
        assertFalse(CatalogVersion(1, 1).isCompatibleWith(CatalogVersion(1, 3)))
        assertFalse(CatalogVersion(2, 0).isCompatibleWith(CatalogVersion(1, 0)))
        assertFailsWith<IllegalArgumentException> { CatalogVersion.parse("1") }
        assertFailsWith<IllegalArgumentException> { CatalogVersion.parse("v1.0") }
        assertFailsWith<IllegalArgumentException> { CatalogVersion(0, 1) }
        assertFailsWith<IllegalArgumentException> { CatalogVersion(1, -1) }
    }
}
