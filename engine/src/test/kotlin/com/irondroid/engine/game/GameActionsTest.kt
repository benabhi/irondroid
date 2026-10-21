package com.irondroid.engine.game

import com.irondroid.engine.action.ActionErrorCode
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.action.StateChange
import com.irondroid.engine.character.Character
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import com.irondroid.engine.dice.DieRoll
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameActionsTest {
    /** Dice that give the values a test queues, in order. */
    private class QueuedDice : Dice {
        val queue = ArrayDeque<Int>()

        override fun roll(die: Die) = DieRoll(die, checkNotNull(queue.removeFirstOrNull()) { "No value queued for $die" })
    }

    /** One open campaign in memory, or none. */
    private class FakeGame(var current: Character?) : GameAccess {
        var saves = 0
        override var history: ActionHistory<Character>? = null

        override val sheet: Character get() = current ?: throw GameAccess.noGame()
        override val characterName: String get() = if (current == null) throw GameAccess.noGame() else "Kira"

        override fun update(change: (Character) -> Character) {
            current = change(sheet)
        }

        override fun save() {
            saves++
        }
    }

    private val dice = QueuedDice()
    private val game = FakeGame(PENDING)
    private val actions = GameActions(game, MoveLoader.fromResources(), OracleTableLoader.fromResources(), dice, "es")
    private val dispatcher = actions.dispatcher()

    init {
        val history = ActionHistory(actions.choices, snapshot = { game.sheet }, restore = { sheet -> game.current = sheet })
        game.history = history
        dispatcher.addListener(history)
    }

    private fun call(name: String, json: String = "", caller: Caller = Caller.AI, confirmed: Boolean = false) =
        dispatcher.dispatch(name, json, caller, confirmed)

    private fun ok(name: String, json: String = "", caller: Caller = Caller.AI, confirmed: Boolean = false) =
        assertIs<DispatchResult.Success>(call(name, json, caller, confirmed), "$name $json").result

    private fun error(name: String, json: String = "", caller: Caller = Caller.AI, confirmed: Boolean = false) =
        assertIs<DispatchResult.Failure>(call(name, json, caller, confirmed), "$name $json").error

    private fun roll(vararg values: Int) {
        dice.queue += values.toList()
    }

    private fun created() {
        ok("create_character", """{"edge":3,"heart":2,"iron":2,"shadow":1,"wits":1}""")
    }

    private val sheet get() = game.sheet

    @Test
    fun `the catalog has the MVP actions and leaves out what was decided to wait`() {
        assertEquals(GameActions.VERSION, actions.catalog.version)
        assertEquals(
            listOf(
                "get_character", "get_tracks", "get_scene", "search_journal", "get_move", "get_rules",
                "create_character", "set_stats", "edit_character",
                "list_moves", "suggest_move", "roll_move", "suggest_option", "choose_option", "burn_momentum",
                "adjust_meter", "mark_debility", "clear_debility",
                "create_progress_track", "mark_progress", "roll_progress", "close_progress_track",
                "ask_oracle", "roll_oracle_table", "list_oracle_tables",
                "save", "undo",
            ),
            actions.catalog.actions.map { it.name },
        )
        val described = actions.actionInterface(dispatcher).describe()
        assertEquals(27, described.getValue("actions").jsonArray.size)
        assertEquals(6, described.getValue("resources").jsonArray.size)
        assertEquals(6, actions.resources.resources.size)
    }

    @Test
    fun `nothing works without an open game`() {
        game.current = null
        for (name in listOf("get_character", "get_tracks", "save")) {
            assertEquals(ActionErrorCode.INVALID_STATE, error(name).code)
        }
        assertEquals("No game is open", error("adjust_meter", """{"meter":"health","amount":-1}""").message)
        assertEquals("No game is open", error("create_character", """{"edge":3,"heart":2,"iron":2,"shadow":1,"wits":1}""").message)
        game.history = null
        assertEquals("No game is open", error("undo").message)
        assertEquals("[]", ok("search_journal").data.getValue("entries").toString())
    }

    @Test
    fun `a pending sheet gets its starting stats once`() {
        assertTrue(GameActions.isPending(sheet))
        assertEquals(
            "The stats must be 3, 2, 2, 1 and 1, in any order, were 3, 3, 2, 1, 1",
            error("create_character", """{"edge":3,"heart":3,"iron":2,"shadow":1,"wits":1}""").message,
        )
        val result = ok("create_character", """{"edge":1,"heart":3,"iron":2,"shadow":2,"wits":1}""")
        assertEquals("Created Kira", result.summary)
        assertTrue(StateChange("stats.heart", 0, 3) in result.changes)
        assertEquals(3, sheet.stats.values.max())
        assertFalse(GameActions.isPending(sheet))

        val again = error("create_character", """{"edge":3,"heart":2,"iron":2,"shadow":1,"wits":1}""")
        assertEquals(ActionErrorCode.INVALID_STATE, again.code)
        assertEquals("Use set_stats to change them by hand", again.suggestion)
    }

    @Test
    fun `hand edits need the player's confirmation`() {
        created()
        assertIs<DispatchResult.NeedsConfirmation>(call("set_stats", """{"edge":4,"heart":2,"iron":2,"shadow":1,"wits":1}"""))
        assertEquals(listOf(StateChange("stats.edge", 3, 4)), ok("set_stats", """{"edge":4,"heart":2,"iron":2,"shadow":1,"wits":1}""", Caller.PLAYER, confirmed = true).changes)

        assertEquals("Give at least one value to change", error("edit_character", "{}", confirmed = true).message)
        ok("mark_debility", """{"debility":"wounded"}""")
        assertEquals(ActionErrorCode.RULE_VIOLATION, error("edit_character", """{"momentum":10}""", confirmed = true).code)
        val edited = ok("edit_character", """{"health":2,"supply":1,"momentum":5,"experience":3}""", Caller.PLAYER, confirmed = true)
        assertEquals(
            listOf(StateChange("health", 5, 2), StateChange("supply", 5, 1), StateChange("momentum", 2, 5), StateChange("experience", 0, 3)),
            edited.changes,
        )
        assertEquals(5, sheet.spirit())
    }

    private fun Character.spirit() = meters[com.irondroid.engine.meters.MeterType.SPIRIT].value

    @Test
    fun `a strong hit applies its outcome at once`() {
        created()
        roll(6, 1, 2)
        val result = ok("roll_move", """{"move":"face_danger","source":"edge"}""")
        assertEquals("Enfrentar el peligro: strong hit (action 9 vs 1 and 2)", result.summary)
        assertEquals(listOf(StateChange("momentum", 2, 3)), result.changes)
        val data = result.data
        assertEquals("strong_hit", data.getValue("outcome").jsonPrimitive.content)
        val diceData = data.getValue("roll").jsonObject
        assertEquals(6, diceData.getValue("action_die").jsonPrimitive.int)
        assertEquals("edge", diceData.getValue("source").jsonPrimitive.content)
        assertEquals("[1,2]", diceData.getValue("challenge_dice").toString())
        assertEquals(JsonNull, diceData.getValue("burned_momentum"))
        assertFalse(data.getValue("match").jsonPrimitive.boolean)
        assertNull(result.pendingChoice)
    }

    @Test
    fun `a weak hit waits for the player's pick, which the AI may only suggest`() {
        created()
        roll(2, 4, 8)
        val rolled = ok("roll_move", """{"move":"face_danger","source":"edge"}""")
        val choice = assertNotNull(rolled.pendingChoice)
        assertEquals("Enfrentar el peligro: pick 1", choice.prompt)
        assertEquals(listOf("lose_momentum", "endure_harm", "endure_stress", "lose_supply"), choice.options.map { it.id })
        assertTrue(choice.options.all { it.text.isNotBlank() && it.text != it.id })
        assertTrue(rolled.summary.endsWith("the player picks 1"))

        assertEquals(ActionErrorCode.INVALID_STATE, error("roll_move", """{"move":"face_danger","source":"iron"}""").code)
        assertEquals("Suggested lose_momentum", ok("suggest_option", """{"choice":"${choice.id}","options":["lose_momentum"],"why":"Health matters more"}""").summary)
        assertEquals(ActionErrorCode.RULE_VIOLATION, error("choose_option", """{"choice":"${choice.id}","options":["lose_momentum"]}""").code)

        val chosen = ok("choose_option", """{"choice":"${choice.id}","options":["lose_momentum"]}""", Caller.PLAYER)
        assertEquals(listOf(StateChange("momentum", 2, 1)), chosen.changes)
        assertEquals("[\"lose_momentum\"]", chosen.data.getValue("chosen").toString())
        assertTrue(chosen.summary.contains("picked lose_momentum"))
        assertNull(actions.choices.current)
    }

    @Test
    fun `a roll that momentum would improve offers burning it`() {
        created()
        ok("adjust_meter", """{"meter":"momentum","amount":5}""")
        roll(1, 5, 6)
        val rolled = ok("roll_move", """{"move":"face_danger","source":"edge"}""")
        val choice = assertNotNull(rolled.pendingChoice)
        assertEquals(listOf(GameActions.BURN, GameActions.KEEP), choice.options.map { it.id })
        assertTrue(rolled.summary.contains("burning momentum would make it a strong hit"))

        val burned = ok("burn_momentum", """{"choice":"${choice.id}"}""", Caller.PLAYER)
        assertTrue(burned.summary.contains("strong hit"), burned.summary)
        assertTrue(burned.summary.contains("momentum 7 burned"), burned.summary)
        assertEquals(listOf(StateChange("momentum", 7, 3)), burned.changes)
    }

    @Test
    fun `keeping the roll applies the original outcome`() {
        created()
        ok("adjust_meter", """{"meter":"momentum","amount":5}""")
        roll(1, 5, 6)
        val choice = assertNotNull(ok("roll_move", """{"move":"face_danger","source":"edge"}""").pendingChoice)
        val kept = ok("choose_option", """{"choice":"${choice.id}","options":["keep"]}""", Caller.PLAYER)
        assertTrue(kept.summary.contains("miss"))
        val followUps = kept.data.getValue("follow_ups").jsonArray
        assertEquals("make_move", followUps.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("pay_the_price", followUps.single().jsonObject.getValue("move").jsonPrimitive.content)
        assertTrue(kept.summary.contains("next: Pagar el precio"), kept.summary)
    }

    @Test
    fun `a miss with a match tells the AI what must follow`() {
        created()
        roll(1, 9, 9)
        val result = ok("roll_move", """{"move":"face_danger","source":"wits"}""")
        assertTrue(result.summary.contains("miss"))
        assertTrue(result.summary.contains("a match"))
        assertTrue(result.data.getValue("match").jsonPrimitive.boolean)
    }

    @Test
    fun `rule mistakes from the engine come back as explained rule errors`() {
        created()
        val noSource = error("roll_move", """{"move":"face_danger"}""")
        assertEquals(ActionErrorCode.RULE_VIOLATION, noSource.code)
        assertTrue(noSource.message.contains("pick a source"), noSource.message)
        assertEquals(
            "A new track needs new_track_rank as well as new_track_name",
            error("roll_move", """{"move":"swear_an_iron_vow","new_track_name":"Avenge Kai"}""").message,
        )
        assertEquals(ActionErrorCode.INVALID_ARGUMENTS, error("roll_move", """{"move":"fly"}""").code)
    }

    @Test
    fun `vows are sworn, advanced, rolled and closed`() {
        created()
        roll(6, 1, 1)
        val sworn = ok("roll_move", """{"move":"swear_an_iron_vow","new_track_name":"Avenge Kai","new_track_rank":"dangerous"}""")
        assertEquals("vow_1", sworn.data.getValue("track").jsonPrimitive.content)
        assertTrue(sworn.changes.any { it.field == "tracks.vow_1" && it.before == JsonNull })

        assertEquals(listOf(StateChange("tracks.vow_1.ticks", 0, 16)), ok("mark_progress", """{"track":"vow_1","times":2}""").changes)
        roll(1, 5)
        val progress = ok("roll_progress", """{"track":"vow_1"}""")
        assertEquals("weak hit (progress 4 vs 1 and 5)", progress.summary)
        assertTrue(progress.changes.isEmpty())

        roll(9, 9)
        assertTrue(ok("roll_progress", """{"track":"vow_1"}""").summary.endsWith("a match"))

        val missing = error("mark_progress", """{"track":"vow_9"}""")
        assertEquals("There is no track vow_9", missing.message)
        assertEquals("The tracks are vow_1", missing.suggestion)
        assertEquals("Closed Avenge Kai", ok("close_progress_track", """{"track":"vow_1"}""").summary)
        assertEquals("There are no tracks yet", error("close_progress_track", """{"track":"vow_1"}""").suggestion)
    }

    @Test
    fun `tracks can start outside a move`() {
        created()
        val bonds = ok("create_progress_track", """{"type":"bonds","name":"Bonds"}""")
        assertEquals("bonds_1", bonds.data.getValue("track").jsonPrimitive.content)
        assertEquals(ActionErrorCode.RULE_VIOLATION, error("create_progress_track", """{"type":"journey","name":"North"}""").code)
        ok("create_progress_track", """{"type":"journey","name":"North","rank":"troublesome"}""")
        assertEquals(setOf("bonds_1", "journey_1"), sheet.tracks.keys)
        val tracks = ok("get_tracks").data.getValue("tracks").jsonArray
        assertEquals(2, tracks.size)
    }

    @Test
    fun `meters move within their limits and say what must follow`() {
        created()
        assertEquals("The amount cannot be 0", error("adjust_meter", """{"meter":"health","amount":0}""").message)
        val out = ok("adjust_meter", """{"meter":"supply","amount":-6}""")
        assertEquals("-6 supply; next: Sin suministros", out.summary)
        assertEquals("[\"out_of_supply\"]", out.data.getValue("follow_ups").toString())

        ok("mark_debility", """{"debility":"unprepared"}""")
        val unprepared = error("adjust_meter", """{"meter":"supply","amount":1}""")
        assertEquals("Supply cannot increase while unprepared", unprepared.message)

        assertTrue(ok("adjust_meter", """{"meter":"health","amount":1}""").changes.isEmpty())
        assertEquals(listOf(StateChange("health", 5, 3)), ok("adjust_meter", """{"meter":"health","amount":-2}""").changes)
        assertEquals("+2 momentum", ok("adjust_meter", """{"meter":"momentum","amount":2}""").summary)
        assertEquals("-10 momentum", ok("adjust_meter", """{"meter":"momentum","amount":-10}""").summary)
        val setback = ok("adjust_meter", """{"meter":"momentum","amount":-1}""")
        assertTrue(setback.summary.endsWith("next: Enfrentar un revés"), setback.summary)
        assertEquals(-6, sheet.momentum.value)
    }

    @Test
    fun `debilities are marked and cleared, banes stay`() {
        created()
        val marked = ok("mark_debility", """{"debility":"wounded"}""")
        assertTrue(marked.changes.any { it.field == "debilities" })
        assertEquals("wounded is already marked", error("mark_debility", """{"debility":"wounded"}""").message)
        ok("clear_debility", """{"debility":"wounded"}""")
        assertEquals("wounded is not marked", error("clear_debility", """{"debility":"wounded"}""").message)
        ok("mark_debility", """{"debility":"maimed"}""")
        assertEquals(ActionErrorCode.RULE_VIOLATION, error("clear_debility", """{"debility":"maimed"}""").code)
        assertTrue(Debility.MAIMED in sheet.debilities)
    }

    @Test
    fun `the oracles answer in the player's language`() {
        created()
        roll(33)
        val answer = ok("ask_oracle", """{"question":"Is the bridge guarded?","odds":"likely"}""")
        assertEquals("Yes (33 against likely), extreme", answer.summary)
        assertEquals("yes", answer.data.getValue("answer").jsonPrimitive.content)
        roll(10)
        assertEquals("No (10 against fifty_fifty)", ok("ask_oracle", """{"question":"Rain?","odds":"fifty_fifty"}""").summary)

        roll(1, 50)
        val price = ok("roll_oracle_table", """{"table":"pay_the_price"}""")
        assertTrue(price.summary.startsWith("Tirá de nuevo"), price.summary)
        assertTrue(price.summary.contains(" / "))
        val extra = price.data.getValue("extra").jsonArray.single().jsonObject
        assertEquals(50, extra.getValue("roll").jsonPrimitive.int)
        assertEquals(16, ok("list_oracle_tables").data.getValue("tables").jsonArray.size)
        assertEquals("Pagar el precio", ok("list_oracle_tables").data.getValue("tables").jsonArray.first().jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `queries read the game without changing it`() {
        created()
        assertEquals("Kira", ok("get_character").data.getValue("name").jsonPrimitive.content)
        assertEquals(JsonNull, ok("get_scene").data.getValue("scene"))
        assertEquals("Rules of Enfrentar el peligro", ok("get_move", """{"move":"face_danger"}""").summary)
        assertTrue(ok("get_rules", """{"section":"moves"}""").summary.endsWith("moves"))
        assertEquals("16 oracle tables", ok("get_rules", """{"section":"oracles"}""").summary)
        val quest = ok("list_moves", """{"category":"quest"}""").data.getValue("moves").jsonArray
        assertTrue(quest.all { it.jsonObject.getValue("category").jsonPrimitive.content == "quest" })
        assertEquals(21, ok("list_moves").data.getValue("moves").jsonArray.size)
        val suggestion = ok("suggest_move", """{"move":"gather_information","why":"They need answers"}""")
        assertEquals("Suggested Reunir información", suggestion.summary)
        assertEquals("They need answers", suggestion.data.getValue("why").jsonPrimitive.content)
        assertEquals(0, game.saves)
        assertEquals("Saved", ok("save").summary)
        assertEquals(1, game.saves)
    }

    @Test
    fun `the journal finds what happened`() {
        created()
        roll(40)
        ok("ask_oracle", """{"question":"Is it raining?","odds":"likely"}""")
        ok("adjust_meter", """{"meter":"health","amount":-1}""")
        error("adjust_meter", """{"meter":"health","amount":0}""")
        val all = ok("search_journal").data.getValue("entries").jsonArray
        assertEquals(listOf("create_character", "ask_oracle", "adjust_meter", "search_journal").dropLast(1), all.map { it.jsonObject.getValue("action").jsonPrimitive.content })
        val found = ok("search_journal", """{"query":"YES"}""").data.getValue("entries").jsonArray
        assertEquals("ask_oracle", found.single().jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals(1, ok("search_journal", """{"limit":1}""").data.getValue("entries").jsonArray.size)
    }

    @Test
    fun `undo takes back a move and its pick together`() {
        created()
        roll(2, 4, 8)
        val choice = assertNotNull(ok("roll_move", """{"move":"face_danger","source":"edge"}""").pendingChoice)
        ok("choose_option", """{"choice":"${choice.id}","options":["lose_supply"]}""", Caller.PLAYER)
        assertEquals(4, sheet.meters[com.irondroid.engine.meters.MeterType.SUPPLY].value)

        val undone = ok("undo", caller = Caller.PLAYER)
        assertEquals("Undid roll_move, choose_option", undone.summary)
        assertEquals(listOf(StateChange("supply", 4, 5)), undone.changes)
        assertEquals("[2,3]", undone.data.getValue("undone").toString())

        ok("undo")
        assertTrue(GameActions.isPending(sheet))
        assertEquals("There is nothing to undo", error("undo").message)
    }

    @Test
    fun `undo works with a choice still open`() {
        created()
        roll(2, 4, 8)
        ok("roll_move", """{"move":"face_danger","source":"edge"}""")
        assertNotNull(actions.choices.current)
        ok("undo")
        assertNull(actions.choices.current)
    }

    @Test
    fun `sheet changes cover every part of the sheet`() {
        val before = Character.create(3, 2, 2, 1, 1).startTrack(com.irondroid.engine.progress.ProgressTrackType.VOW, "Old", com.irondroid.engine.progress.ChallengeRank.TROUBLESOME).second
        val after = before
            .copy(tracks = before.tracks.mapValues { it.value.raiseRank() }, pendingBonuses = listOf(com.irondroid.engine.character.PendingBonus(1)), isEnded = true)
            .startTrack(com.irondroid.engine.progress.ProgressTrackType.BONDS, "Bonds", null).second
        val changes = SheetChanges.between(before, after)
        assertEquals(
            listOf("pending_bonuses", "tracks.bonds_1", "tracks.vow_1.rank", "is_ended"),
            changes.map { it.field },
        )
        assertEquals(JsonPrimitive("dangerous"), changes[2].after)
        assertIs<JsonObject>(changes[1].after)
        assertEquals(JsonPrimitive(0), changes[0].before)
        assertTrue(SheetChanges.between(after, after.copy(tracks = emptyMap())).all { it.after is JsonNull })
        assertIs<JsonArray>(SheetChanges.between(before, before.withDebilities(before.debilities.mark(Debility.SHAKEN))).single { it.field == "debilities" }.after)
    }

    @Test
    fun `progress moves and moves that end the story report it`() {
        created()
        roll(6, 1, 1)
        ok("roll_move", """{"move":"swear_an_iron_vow","new_track_name":"Avenge Kai","new_track_rank":"epic"}""")
        ok("mark_progress", """{"track":"vow_1","times":10}""")
        roll(1, 2)
        val fulfilled = ok("roll_move", """{"move":"fulfill_your_vow","track":"vow_1"}""")
        assertEquals("progress", fulfilled.data.getValue("roll").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(fulfilled.summary.contains("weak hit (progress 2 vs 1 and 2)"), fulfilled.summary)
        assertTrue(fulfilled.changes.any { it.field == "experience" })

        roll(1, 9, 10)
        val death = ok("roll_move", """{"move":"face_death"}""")
        assertTrue(death.summary.contains("the character's story ends"), death.summary)
        assertTrue(StateChange("is_ended", false, true) in death.changes)
        assertEquals("character_ended", death.data.getValue("follow_ups").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(error("roll_move", """{"move":"face_danger","source":"edge"}""").message.contains("over"))
    }

    @Test
    fun `moves without a roll apply at once`() {
        created()
        val camp = ok("roll_move", """{"move":"out_of_supply"}""")
        assertTrue(camp.summary.endsWith("done"), camp.summary)
        assertEquals(JsonNull, camp.data.getValue("roll"))
        assertTrue(Debility.UNPREPARED in sheet.debilities)
    }

    private companion object {
        val PENDING = Character.create(0, 0, 0, 0, 0)
    }
}
