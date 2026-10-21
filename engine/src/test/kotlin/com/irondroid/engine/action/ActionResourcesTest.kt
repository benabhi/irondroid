package com.irondroid.engine.action

import com.irondroid.engine.character.Character
import com.irondroid.engine.character.PendingBonus
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrackType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionResourcesTest {
    private val moves = MoveLoader.fromResources()
    private val oracles = OracleTableLoader.fromResources()

    private var sheet: Character = run {
        val (_, withTrack) = Character.create(3, 2, 2, 1, 1).startTrack(ProgressTrackType.VOW, "Avenge Kai", ChallengeRank.DANGEROUS)
        withTrack.copy(tracks = withTrack.tracks.mapValues { it.value.markProgress(3) }, experience = 2, pendingBonuses = listOf(PendingBonus(1, "face_danger")))
            .withDebilities(withTrack.debilities.mark(Debility.SHAKEN))
    }
    private val calls = mutableListOf<LoggedCall<*>>()

    private val resources = GameResources.create({ "Kira" }, { sheet }, { calls }, moves, oracles, "es")

    private fun content(uri: String) = assertIs<ResourceRead.Content>(resources.read(uri)).data

    private fun logged(sequence: Int, name: String, outcome: CallOutcome = CallOutcome.SUCCESS, undone: Boolean = false) =
        LoggedCall<Int>(sequence, name, Caller.AI, JsonObject(emptyMap()), outcome, buildJsonObject { put("summary", "Did $name") }, UndoRole.NONE, undone = undone)

    @Test
    fun `the standard resources are listed for the AI`() {
        assertEquals(
            listOf(GameResources.CHARACTER, GameResources.TRACKS, GameResources.SCENE, GameResources.JOURNAL, GameResources.MOVES, GameResources.ORACLES),
            resources.resources.map { it.uri },
        )
        val first = resources.toJson().first().jsonObject
        assertEquals("irondroid://character", first.getValue("uri").jsonPrimitive.content)
        assertEquals("application/json", first.getValue("mime_type").jsonPrimitive.content)
        assertEquals("Character sheet", resources[GameResources.CHARACTER]?.name)
        assertNull(resources["irondroid://nothing"])
    }

    @Test
    fun `the character resource shows the whole sheet`() {
        assertEquals(
            Json.parseToJsonElement(
                """
                { "name": "Kira",
                  "stats": { "edge": 3, "heart": 2, "iron": 2, "shadow": 1, "wits": 1 },
                  "meters": { "health": 5, "spirit": 5, "supply": 5 },
                  "momentum": { "value": 2, "max": 9, "reset": 1 },
                  "debilities": ["shaken"],
                  "experience": 2,
                  "pending_bonuses": [ { "amount": 1, "move": "face_danger" } ],
                  "is_ended": false }
                """,
            ),
            content(GameResources.CHARACTER),
        )
        sheet = sheet.copy(pendingBonuses = listOf(PendingBonus(2)))
        assertEquals(JsonNull, content(GameResources.CHARACTER).getValue("pending_bonuses").jsonArray.single().jsonObject.getValue("move"))
    }

    @Test
    fun `the tracks resource shows rank and progress, and always the current state`() {
        assertEquals(
            Json.parseToJsonElement(
                """{ "tracks": [ { "id": "vow_1", "name": "Avenge Kai", "type": "vow", "rank": "dangerous", "ticks": 24, "filled_boxes": 6, "complete": false } ] }""",
            ),
            content(GameResources.TRACKS),
        )
        sheet = sheet.startTrack(ProgressTrackType.BONDS, "Bonds", null).second
        val bonds = content(GameResources.TRACKS).getValue("tracks").jsonArray.first().jsonObject
        assertEquals("bonds_1", bonds.getValue("id").jsonPrimitive.content)
        assertEquals(JsonNull, bonds.getValue("rank"))
    }

    @Test
    fun `the scene says there is none yet`() {
        val scene = content(GameResources.SCENE)
        assertEquals(JsonNull, scene.getValue("scene"))
        assertTrue(scene.getValue("note").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `the journal shows recent successful calls that were not undone`() {
        calls += logged(1, "get_character")
        calls += logged(2, "roll_move", CallOutcome.FAILURE)
        calls += logged(3, "mark_progress", undone = true)
        calls += logged(4, "ask_oracle")
        val entries = content(GameResources.JOURNAL).getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(listOf("get_character", "ask_oracle"), entries.map { it.getValue("action").jsonPrimitive.content })
        assertEquals(
            Json.parseToJsonElement("""{ "sequence": 4, "action": "ask_oracle", "by": "ai", "summary": "Did ask_oracle" }"""),
            entries.last(),
        )

        repeat(40) { calls += logged(10 + it, "step_$it") }
        val journal = content(GameResources.JOURNAL)
        assertEquals(GameResources.JOURNAL_SIZE, journal.getValue("entries").jsonArray.size)
        assertEquals(12, journal.getValue("older_entries").jsonPrimitive.int)
        assertEquals(JsonNull, GameResources.journal(listOf(logged(1, "x").copy(details = JsonObject(emptyMap())))).getValue("entries").jsonArray.single().jsonObject.getValue("summary"))
    }

    @Test
    fun `the rules come in the player's language`() {
        val rules = content(GameResources.MOVES)
        assertEquals("es", rules.getValue("language").jsonPrimitive.content)
        val list = rules.getValue("moves").jsonArray.map { it.jsonObject }
        assertEquals(moves.moves.size, list.size)
        val faceDanger = list.first { it.getValue("id").jsonPrimitive.content == "face_danger" }
        assertEquals("Enfrentar el peligro", faceDanger.getValue("name").jsonPrimitive.content)
        assertEquals("action", faceDanger.getValue("roll").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(5, faceDanger.getValue("roll").jsonObject.getValue("sources").jsonArray.size)
        assertTrue("options" in faceDanger)
        assertTrue(list.any { it.getValue("roll").jsonObject.getValue("type").jsonPrimitive.content == "progress" && "track" in it.getValue("roll").jsonObject })
        val noRoll = list.first { it.getValue("roll").jsonObject.getValue("type").jsonPrimitive.content == "none" }
        assertNull(noRoll["strong_hit"])
        assertTrue(list.any { "bonuses" in it })

        val english = GameResources.moves(moves, "en").getValue("moves").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.content == "face_danger" }
        assertEquals("Face Danger", english.jsonObject.getValue("name").jsonPrimitive.content)
        assertFailsWith<IllegalStateException> { GameResources.moves(moves, "fr") }
    }

    @Test
    fun `the oracle tables come with their rows`() {
        val tables = content(GameResources.ORACLES).getValue("tables").jsonArray.map { it.jsonObject }
        assertEquals(oracles.tables.map { it.id }, tables.map { it.getValue("id").jsonPrimitive.content })
        val price = tables.first()
        assertEquals("Pagar el precio", price.getValue("name").jsonPrimitive.content)
        val firstRow = price.getValue("rows").jsonArray.first().jsonObject
        assertEquals(1, firstRow.getValue("min").jsonPrimitive.int)
        assertTrue(firstRow.getValue("text").jsonPrimitive.content.startsWith("Tirá de nuevo"))
        assertFailsWith<IllegalStateException> { GameResources.oracles(oracles, "fr") }
    }

    @Test
    fun `an unknown uri or a failing reader gives an explained error`() {
        val typo = assertIs<ResourceRead.Failure>(resources.read("irondroid://charactr")).error
        assertEquals(ActionError(ActionErrorCode.UNKNOWN_RESOURCE, "There is no resource irondroid://charactr", suggestion = "Did you mean irondroid://character?"), typo)
        assertNull(assertIs<ResourceRead.Failure>(resources.read("irondroid://weather/today")).error.suggestion)

        val broken = ResourceCatalog.of(
            listOf(
                ResourceDefinition("irondroid://broken", "Broken", "Fails") to ResourceReader { error("disk on fire") },
                ResourceDefinition("irondroid://silent", "Silent", "Fails quietly") to ResourceReader { throw IllegalStateException() },
            ),
        )
        assertEquals(
            ActionError(ActionErrorCode.INTERNAL, "Reading irondroid://broken failed inside the app: disk on fire"),
            assertIs<ResourceRead.Failure>(broken.read("irondroid://broken")).error,
        )
        assertEquals(
            "Reading irondroid://silent failed inside the app: IllegalStateException",
            assertIs<ResourceRead.Failure>(broken.read("irondroid://silent")).error.message,
        )
    }

    @Test
    fun `resource definitions are checked`() {
        val reader = ResourceReader { JsonObject(emptyMap()) }
        val errors = assertFailsWith<ActionCatalogException> {
            ResourceCatalog.of(
                listOf(
                    ResourceDefinition("irondroid://a", "A", "First") to reader,
                    ResourceDefinition("irondroid://a", "A again", "Second") to reader,
                    ResourceDefinition("http://b", " ", "") to reader,
                    ResourceDefinition("irondroid://Rules/", "C", "Bad path") to reader,
                ),
            )
        }.errors
        assertEquals(
            listOf(
                "Resource irondroid://a is defined more than once",
                "Resource http://b: URI must look like irondroid://name or irondroid://group/name",
                "Resource http://b: needs a name",
                "Resource http://b: needs a description",
                "Resource irondroid://Rules/: URI must look like irondroid://name or irondroid://group/name",
            ),
            errors,
        )
    }

    @Test
    fun `connecting gives the version, every action and every resource at once`() {
        val action = ActionDefinition(
            "get_character", ActionArea.QUERY, "Reads the sheet", "You need it", ActionPermission.READ, ActionReturn("The sheet"),
        )
        val dispatcher = ActionDispatcher(
            ActionCatalog.of(CatalogVersion(1, 3), listOf(action)),
            mapOf("get_character" to ActionHandler { _, _ -> ActionResult("Sheet", content(GameResources.CHARACTER)) }),
        )
        val described = ActionInterface(dispatcher, resources).describe()
        assertEquals(JsonPrimitive("1.3"), described.getValue("version"))
        assertEquals(listOf("get_character"), described.getValue("actions").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(6, described.getValue("resources").jsonArray.size)
    }
}
