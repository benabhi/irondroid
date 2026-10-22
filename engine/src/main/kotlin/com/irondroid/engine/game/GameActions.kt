package com.irondroid.engine.game

import com.irondroid.engine.action.ActionArea
import com.irondroid.engine.action.ActionArguments
import com.irondroid.engine.action.ActionCall
import com.irondroid.engine.action.ActionCatalog
import com.irondroid.engine.action.ActionDefinition
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.ActionEffect
import com.irondroid.engine.action.ActionHandler
import com.irondroid.engine.action.ActionInterface
import com.irondroid.engine.action.ActionPermission
import com.irondroid.engine.action.ActionRejectedException
import com.irondroid.engine.action.ActionResult
import com.irondroid.engine.action.ActionReturn
import com.irondroid.engine.action.CallOutcome
import com.irondroid.engine.action.CatalogVersion
import com.irondroid.engine.action.ChoiceOption
import com.irondroid.engine.action.GameResources
import com.irondroid.engine.action.JsonSchema
import com.irondroid.engine.action.ObjectSchemaBuilder
import com.irondroid.engine.action.PendingChoice
import com.irondroid.engine.action.PendingChoices
import com.irondroid.engine.action.ResourceCatalog
import com.irondroid.engine.character.Character
import com.irondroid.engine.character.Stat
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.dice.Dice
import com.irondroid.engine.meters.Meter
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.momentum.Momentum
import com.irondroid.engine.move.FollowUp
import com.irondroid.engine.move.MoveCatalog
import com.irondroid.engine.move.MoveCategory
import com.irondroid.engine.move.MoveRecord
import com.irondroid.engine.move.MoveRequest
import com.irondroid.engine.move.MoveResolver
import com.irondroid.engine.move.MoveStep
import com.irondroid.engine.move.NewTrack
import com.irondroid.engine.move.RollRecord
import com.irondroid.engine.move.RollSource
import com.irondroid.engine.oracle.Odds
import com.irondroid.engine.oracle.OracleTableCatalog
import com.irondroid.engine.oracle.TableRoll
import com.irondroid.engine.oracle.YesNoOracle
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrackType
import com.irondroid.engine.roll.Outcome
import com.irondroid.engine.roll.ProgressRoll
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The actions of the MVP catalog (RF-API-01, SRD 3.14), played on [game]: queries, character,
 * moves, state, progress, oracles and system. Combat and the advanced campaign actions come later,
 * and so do `choose_asset`, `advance` and `change_setting` (decided by benabhi, 2026-10-21).
 *
 * Moves and oracle tables come from [moves] and [oracles], with texts in [language]; dice from
 * [dice]. Pending choices live in [choices], which the dispatcher made by [dispatcher] shares.
 * Summaries are in English, for the AI; the screens show their own localized texts.
 */
class GameActions(
    private val game: GameAccess,
    private val moves: MoveCatalog,
    private val oracles: OracleTableCatalog,
    private val dice: Dice,
    private val language: String,
    val choices: PendingChoices = PendingChoices(),
) {
    private val resolver = MoveResolver(moves, dice)
    private val definitions = mutableListOf<ActionDefinition>()
    private val handlers = mutableMapOf<String, ActionHandler>()

    val catalog: ActionCatalog
    val resources: ResourceCatalog

    init {
        queries()
        character()
        moveActions()
        state()
        progress()
        oracleActions()
        system()
        catalog = ActionCatalog.of(VERSION, definitions)
        resources = GameResources.create(
            characterName = { game.characterName },
            sheet = { game.sheet },
            calls = { game.history?.calls.orEmpty() },
            moves = moves,
            oracles = oracles,
            language = language,
        )
    }

    /** The single door for these actions, sharing [choices]. */
    fun dispatcher(): ActionDispatcher = ActionDispatcher(catalog, handlers, choices)

    /** Actions and resources together, as the AI connects to them. */
    fun actionInterface(dispatcher: ActionDispatcher = dispatcher()): ActionInterface = ActionInterface(dispatcher, resources)

    // ---- Queries -------------------------------------------------------------------------------

    private fun queries() {
        action(
            "get_character", ActionArea.QUERY, ActionPermission.READ,
            "Reads the character sheet: stats, meters, momentum, debilities, experience and pending roll bonuses.",
            "you need the current state of the character before narrating or suggesting a move",
            "The character sheet.",
        ) { _, _ -> ActionResult("${game.characterName}'s sheet", GameResources.character(game.characterName, game.sheet)) }

        action(
            "get_tracks", ActionArea.QUERY, ActionPermission.READ,
            "Reads every progress track: vows, journeys, combat and bonds, with rank and progress.",
            "you need a track id or how far a vow or journey has gone",
            "The progress tracks.",
        ) { _, _ -> ActionResult("${game.sheet.tracks.size} progress tracks", GameResources.tracks(game.sheet)) }

        action(
            "get_scene", ActionArea.QUERY, ActionPermission.READ,
            "Reads the current scene.",
            "you need to know where the character is and what is happening",
            "The scene, or null while the app does not track scenes.",
        ) { _, _ -> ActionResult("No scene is tracked yet", GameResources.scene()) }

        action(
            "search_journal", ActionArea.QUERY, ActionPermission.READ,
            "Searches the things done in this game, newest last.",
            "you need to recall what happened earlier, such as a past roll or oracle answer",
            "The matching journal entries.",
            params = {
                string("query", "Words to look for in the action name or its summary; leave out to get the latest entries", required = false)
                integer("limit", "Most entries to return, 1 to 50; 10 when left out", required = false, minimum = 1, maximum = 50)
            },
        ) { args, _ ->
            val query = args.stringOrNull("query")?.trim().orEmpty()
            val found = game.history?.calls.orEmpty()
                .filter { it.outcome == CallOutcome.SUCCESS && !it.undone }
                .filter { call ->
                    query.isEmpty() || call.name.contains(query, ignoreCase = true) ||
                        (call.details["summary"] as? JsonPrimitive)?.content?.contains(query, ignoreCase = true) == true
                }
                .takeLast(args.intOrNull("limit") ?: 10)
            val entries = JsonArray(
                found.map { call ->
                    buildJsonObject {
                        put("sequence", call.sequence)
                        put("action", call.name)
                        put("by", call.caller.name.lowercase())
                        put("summary", call.details["summary"] ?: JsonNull)
                    }
                },
            )
            ActionResult("${found.size} journal entries", buildJsonObject { put("entries", entries) })
        }

        action(
            "get_move", ActionArea.QUERY, ActionPermission.READ,
            "Reads one move: its trigger, how it is rolled and what each outcome means, in the player's language.",
            "you are about to suggest or make a move and need its exact rules",
            "The move.",
            params = { string("move", "Move id", enum = moveIds) },
        ) { args, _ ->
            val move = GameResources.move(moves, args.string("move"), language)
            ActionResult("Rules of ${move.getValue("name").jsonPrimitive.content}", move)
        }

        action(
            "get_rules", ActionArea.QUERY, ActionPermission.READ,
            "Reads all the moves or all the oracle tables, in the player's language.",
            "you need an overview of the rules; prefer get_move for a single move",
            "The moves or the oracle tables.",
            params = { string("section", "Which rules", enum = listOf("moves", "oracles")) },
        ) { args, _ ->
            when (args.string("section")) {
                "moves" -> ActionResult("${moves.moves.size} moves", GameResources.moves(moves, language))
                else -> ActionResult("${oracles.tables.size} oracle tables", GameResources.oracles(oracles, language))
            }
        }
    }

    // ---- Character -----------------------------------------------------------------------------

    private fun character() {
        action(
            "create_character", ActionArea.CHARACTER, ActionPermission.PLAY,
            "Sets the stats of a new character: 3, 2, 2, 1 and 1, in the order the player wants. Every meter starts full and momentum at +2.",
            "the player has just created the campaign and the sheet is still pending",
            "The new sheet.",
            setOf(ActionEffect.CHANGES_CHARACTER),
            params = statParameters(1, 3),
        ) { args, _ ->
            if (!isPending(game.sheet)) {
                throw ActionRejectedException.state("The character already has stats", "Use set_stats to change them by hand")
            }
            val stats = stats(args)
            if (stats.values.sortedDescending() != STARTING_STATS) {
                throw ActionRejectedException.rule("The stats must be 3, 2, 2, 1 and 1, in any order, were ${stats.values.joinToString()}")
            }
            changing("Created ${game.characterName}") { Character.create(stats.getValue(Stat.EDGE), stats.getValue(Stat.HEART), stats.getValue(Stat.IRON), stats.getValue(Stat.SHADOW), stats.getValue(Stat.WITS)) }
        }

        action(
            "set_stats", ActionArea.CHARACTER, ActionPermission.SENSITIVE,
            "Changes the stats by hand, outside the rules.",
            "the player asks to fix a stat by hand",
            "The changes to the sheet.",
            setOf(ActionEffect.CHANGES_CHARACTER),
            params = statParameters(0, 5),
        ) { args, _ ->
            val stats = stats(args)
            changing("Stats set by hand") { it.copy(stats = stats) }
        }

        action(
            "edit_character", ActionArea.CHARACTER, ActionPermission.SENSITIVE,
            "Changes meters, momentum or experience by hand, outside the rules. Only the values given change.",
            "the player asks to fix the sheet by hand",
            "The changes to the sheet.",
            setOf(ActionEffect.CHANGES_CHARACTER),
            params = {
                for (meter in MeterType.entries) {
                    integer(meter.name.lowercase(), "New ${meter.name.lowercase()}, 0 to 5", required = false, minimum = Meter.MIN, maximum = Meter.MAX)
                }
                integer("momentum", "New momentum, -6 up to its maximum", required = false, minimum = Momentum.MIN, maximum = Momentum.MAX)
                integer("experience", "New experience", required = false, minimum = 0)
            },
        ) { args, _ ->
            if (args.json.isEmpty()) throw ActionRejectedException.rule("Give at least one value to change")
            changing("Sheet edited by hand") { sheet -> rules { edited(sheet, args) } }
        }
    }

    /** [sheet] with the values of edit_character; momentum above its maximum breaks a rule. */
    private fun edited(sheet: Character, args: ActionArguments): Character {
        var meters = sheet.meters
        for (meter in MeterType.entries) args.intOrNull(meter.name.lowercase())?.let { meters = meters.with(Meter(meter, it)) }
        return sheet.copy(
            meters = meters,
            momentum = args.intOrNull("momentum")?.let { Momentum(it, sheet.debilities.count) } ?: sheet.momentum,
            experience = args.intOrNull("experience") ?: sheet.experience,
        )
    }

    // ---- Moves ---------------------------------------------------------------------------------

    private fun moveActions() {
        action(
            "list_moves", ActionArea.MOVES, ActionPermission.READ,
            "Lists the moves with their ids and names in the player's language.",
            "you need a move id",
            "The moves.",
            params = { string("category", "Only moves of this category", required = false, enum = MoveCategory.entries.map { it.name.lowercase() }) },
        ) { args, _ ->
            val category = args.stringOrNull("category")
            val list = moves.moves.filter { category == null || it.category.name.lowercase() == category }
            ActionResult(
                "${list.size} moves",
                buildJsonObject {
                    put(
                        "moves",
                        JsonArray(
                            list.map { move ->
                                buildJsonObject {
                                    put("id", move.id)
                                    put("name", moveName(move.id))
                                    put("category", move.category.name.lowercase())
                                }
                            },
                        ),
                    )
                },
            )
        }

        action(
            "suggest_move", ActionArea.MOVES, ActionPermission.READ,
            "Suggests a move to the player, who decides whether to make it. Changes nothing.",
            "the fiction triggers a move and the player should make it",
            "The suggestion, as the player will see it.",
            params = {
                string("move", "Move id", enum = moveIds)
                string("why", "Why this move fits, in one short sentence")
            },
        ) { args, _ ->
            val id = args.string("move")
            ActionResult(
                "Suggested ${moveName(id)}",
                buildJsonObject {
                    put("move", id)
                    put("why", args.string("why"))
                },
            )
        }

        action(
            "roll_move", ActionArea.MOVES, ActionPermission.PLAY,
            "Makes a move: rolls its dice and applies its outcome. If momentum could improve the roll, or the move offers options, the player decides in a pending choice.",
            "the player makes a move, by their own words or after accepting a suggestion",
            "The roll, the outcome, the changes, the moves that must follow, and any pending choice.",
            setOf(ActionEffect.ROLLS_DICE, ActionEffect.CHANGES_CHARACTER, ActionEffect.CHANGES_TRACKS),
            params = {
                string("move", "Move id", enum = moveIds)
                string("source", "Stat or meter to roll, when the move lets the player pick", required = false, enum = RollSource.entries.map { it.name.lowercase() })
                array("bonuses", "Ids of the situational roll bonuses that apply", JsonSchema.StringValue("Bonus id"), required = false)
                string("track", "Id of the progress track the move works on", required = false)
                integer("amount", "How much the move is triggered with, such as 2 for Endure Harm 2", required = false, minimum = 1)
                string("new_track_name", "Name of the track the move starts, such as the vow", required = false)
                string("new_track_rank", "Rank of the track the move starts", required = false, enum = ChallengeRank.entries.map { it.name.lowercase() })
            },
        ) { args, _ ->
            val id = args.string("move")
            val request = MoveRequest(
                source = args.stringOrNull("source")?.let { RollSource.valueOf(it.uppercase()) },
                bonuses = args.json["bonuses"]?.let { list -> (list as JsonArray).map { it.jsonPrimitive.content }.toSet() }.orEmpty(),
                track = args.stringOrNull("track"),
                amount = args.intOrNull("amount"),
                newTrack = args.stringOrNull("new_track_name")?.let { name ->
                    val rank = args.stringOrNull("new_track_rank")
                        ?: throw ActionRejectedException.rule("A new track needs new_track_rank as well as new_track_name")
                    NewTrack(name, ChallengeRank.valueOf(rank.uppercase()))
                },
            )
            val before = game.sheet
            moveResult(before, rules { resolver.make(before, id, request) })
        }

        action(
            "suggest_option", ActionArea.MOVES, ActionPermission.PLAY,
            "Marks which options of the open choice you suggest, and why. The player still decides.",
            "the player has a choice open and you have an opinion about it",
            "Confirmation that the suggestion is shown.",
            answersChoice = true,
            params = {
                string("choice", "Id of the open choice")
                array("options", "Ids of the options you suggest", JsonSchema.StringValue("Option id"))
                string("why", "Why, in one short sentence")
                string(
                    "proposal",
                    "For an option that leaves the outcome open, such as obvious_outcome of Pay the Price: the concrete outcome you propose, in one short sentence in the player's language",
                    required = false,
                )
            },
        ) { args, _ ->
            val picked = optionIds(args)
            choices.suggest(args.string("choice"), picked, args.string("why"), args.stringOrNull("proposal"))
            ActionResult("Suggested ${picked.joinToString(", ")}")
        }

        action(
            "choose_option", ActionArea.MOVES, ActionPermission.PLAY,
            "Picks options of the open choice and goes on with the move. Only the player decides.",
            "the player has told you, or tapped, what they pick",
            "The rest of the move: outcome, changes and any next choice.",
            setOf(ActionEffect.CHANGES_CHARACTER, ActionEffect.CHANGES_TRACKS),
            answersChoice = true,
            params = {
                string("choice", "Id of the open choice")
                array("options", "Ids of the options picked", JsonSchema.StringValue("Option id"))
            },
        ) { args, call -> choices.decide(args.string("choice"), optionIds(args), call.caller) }

        action(
            "burn_momentum", ActionArea.MOVES, ActionPermission.PLAY,
            "Burns momentum on the open choice that offers it, improving the roll; momentum goes back to its reset value. Only the player decides.",
            "the player chooses to burn momentum",
            "The improved outcome, changes and any next choice.",
            setOf(ActionEffect.CHANGES_CHARACTER, ActionEffect.CHANGES_TRACKS),
            answersChoice = true,
            params = { string("choice", "Id of the open choice that offers burning momentum") },
        ) { args, call -> choices.decide(args.string("choice"), listOf(BURN), call.caller) }
    }

    /** The result of a move step; stops at a pending choice when the player has to decide. */
    private fun moveResult(before: Character, step: MoveStep): ActionResult {
        game.update { step.character }
        val name = moveName(step.record.move)
        val changes = SheetChanges.between(before, step.character)
        val data = record(step.record)
        return when (step) {
            is MoveStep.Done -> ActionResult(summary(name, step.record), data, changes)
            is MoveStep.AwaitingBurn -> {
                val outcome = step.record.roll!!.outcome
                ActionResult(
                    "${summary(name, step.record)}; burning momentum would make it a ${outcomeName(step.burnOutcome)}",
                    data,
                    changes,
                    PendingChoice(
                        "$name: burn momentum (${step.character.momentum.value}) to turn the ${outcomeName(outcome)} into a ${outcomeName(step.burnOutcome)}?",
                        listOf(
                            ChoiceOption(BURN, "Burn momentum: ${outcomeName(step.burnOutcome)}, momentum back to ${step.character.momentum.reset}"),
                            ChoiceOption(KEEP, "Keep the ${outcomeName(outcome)}"),
                        ),
                        move = step.record.move,
                    ) { picked -> moveResult(step.character, rules { resolver.decideBurn(step, picked.single() == BURN) }) },
                )
            }
            is MoveStep.AwaitingChoice -> {
                val text = moves.text(step.record.move, language)
                ActionResult(
                    "${summary(name, step.record)}; the player picks ${step.choice.count}",
                    data,
                    changes,
                    PendingChoice(
                        "$name: pick ${step.choice.count}",
                        step.choice.options.map { ChoiceOption(it.id, text?.options?.get(it.id) ?: it.id) },
                        step.choice.count,
                        move = step.record.move,
                    ) { picked -> moveResult(step.character, rules { resolver.choose(step, picked) }) },
                )
            }
            // No asset lets the player reroll yet, so the resolver never stops here.
            is MoveStep.AwaitingReroll -> error("Rerolls are not offered")
        }
    }

    private fun summary(name: String, record: MoveRecord): String = buildString {
        append(name)
        when (val roll = record.roll) {
            is RollRecord.Action -> {
                val dice = roll.roll
                append(": ").append(outcomeName(roll.outcome))
                append(" (action ${dice.actionScore} vs ${dice.challenge1} and ${dice.challenge2}")
                dice.burnedMomentum?.let { append(", momentum $it burned") }
                append(")")
                if (roll.isMatch) append(", a match")
            }
            is RollRecord.Progress -> {
                append(": ").append(outcomeName(roll.outcome))
                append(" (progress ${roll.roll.progressScore} vs ${roll.roll.challenge1} and ${roll.roll.challenge2})")
                if (roll.isMatch) append(", a match")
            }
            null -> append(": done")
        }
        if (record.chosen.isNotEmpty()) append("; picked ").append(record.chosen.joinToString(", "))
        if (record.followUps.isNotEmpty()) append("; next: ").append(record.followUps.joinToString(", ") { followUpText(it) })
    }

    private fun record(record: MoveRecord): JsonObject = buildJsonObject {
        put("move", record.move)
        when (val roll = record.roll) {
            is RollRecord.Action -> {
                val dice = roll.roll
                put(
                    "roll",
                    buildJsonObject {
                        put("type", "action")
                        put("source", roll.source.name.lowercase())
                        put("action_die", dice.actionDie)
                        put("stat", dice.stat)
                        put("adds", dice.adds)
                        put("action_score", dice.actionScore)
                        put("challenge_dice", JsonArray(listOf(JsonPrimitive(dice.challenge1), JsonPrimitive(dice.challenge2))))
                        put("burned_momentum", dice.burnedMomentum?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
                put("outcome", roll.outcome.name.lowercase())
                put("match", roll.isMatch)
            }
            is RollRecord.Progress -> {
                put(
                    "roll",
                    buildJsonObject {
                        put("type", "progress")
                        put("track", roll.track)
                        put("progress_score", roll.roll.progressScore)
                        put("challenge_dice", JsonArray(listOf(JsonPrimitive(roll.roll.challenge1), JsonPrimitive(roll.roll.challenge2))))
                    },
                )
                put("outcome", roll.outcome.name.lowercase())
                put("match", roll.isMatch)
            }
            null -> put("roll", JsonNull)
        }
        put("track", record.track?.let(::JsonPrimitive) ?: JsonNull)
        put("chosen", JsonArray(record.chosen.map(::JsonPrimitive)))
        put(
            "follow_ups",
            JsonArray(
                record.followUps.map { followUp ->
                    buildJsonObject {
                        when (followUp) {
                            is FollowUp.MakeMove -> {
                                put("type", "make_move")
                                put("move", followUp.move)
                                put("amount", followUp.amount?.let(::JsonPrimitive) ?: JsonNull)
                            }
                            is FollowUp.RollOnTable -> {
                                put("type", "roll_oracle_table")
                                put("table", followUp.table)
                            }
                            FollowUp.AskOracle -> put("type", "ask_oracle")
                            FollowUp.CharacterEnded -> put("type", "character_ended")
                        }
                    }
                },
            ),
        )
    }

    private fun followUpText(followUp: FollowUp): String = when (followUp) {
        is FollowUp.MakeMove -> moveName(followUp.move) + (followUp.amount?.let { " $it" } ?: "")
        is FollowUp.RollOnTable -> "roll on ${followUp.table}"
        FollowUp.AskOracle -> "ask the oracle"
        FollowUp.CharacterEnded -> "the character's story ends"
    }

    // ---- State ---------------------------------------------------------------------------------

    private fun state() {
        action(
            "adjust_meter", ActionArea.STATE, ActionPermission.PLAY,
            "Adds to or takes from health, spirit, supply or momentum, within their limits.",
            "the fiction changes a meter outside a move, such as finding supplies or resting",
            "The changes, and the moves that must follow.",
            setOf(ActionEffect.CHANGES_CHARACTER),
            params = {
                string("meter", "Which meter", enum = MeterType.entries.map { it.name.lowercase() } + "momentum")
                integer("amount", "How much to add (positive) or take (negative), not 0", minimum = -10, maximum = 10)
            },
        ) { args, _ ->
            val amount = args.int("amount")
            if (amount == 0) throw ActionRejectedException.rule("The amount cannot be 0")
            val meterName = args.string("meter")
            val followUps = mutableListOf<String>()
            val result = changing("${if (amount > 0) "+" else ""}$amount $meterName") { sheet ->
                if (meterName == "momentum") {
                    if (amount > 0) {
                        sheet.copy(momentum = sheet.momentum.gain(amount))
                    } else {
                        val loss = sheet.momentum.lose(-amount)
                        if (loss.requiresSetback) followUps += FACE_A_SETBACK
                        sheet.copy(momentum = loss.momentum)
                    }
                } else {
                    val meter = MeterType.valueOf(meterName.uppercase())
                    if (amount > 0) {
                        if (meter == MeterType.SUPPLY && Debility.UNPREPARED in sheet.debilities) {
                            throw ActionRejectedException.rule("Supply cannot increase while unprepared", "Clear unprepared first, as Resupply does")
                        }
                        sheet.copy(meters = sheet.meters.gain(meter, amount))
                    } else {
                        val (meters, loss) = sheet.meters.lose(meter, -amount)
                        if (loss.triggersOutOfSupply) followUps += OUT_OF_SUPPLY
                        sheet.copy(meters = meters)
                    }
                }
            }
            result.copy(
                summary = result.summary + followUps.joinToString("") { "; next: ${moveName(it)}" },
                data = buildJsonObject { put("follow_ups", JsonArray(followUps.map(::JsonPrimitive))) },
            )
        }

        action(
            "mark_debility", ActionArea.STATE, ActionPermission.PLAY,
            "Marks a debility; the maximum and reset of momentum go down.",
            "a move or the fiction gives the character a condition, bane or burden",
            "The changes.",
            setOf(ActionEffect.CHANGES_CHARACTER),
            params = { string("debility", "Which debility", enum = debilityNames) },
        ) { args, _ ->
            val debility = Debility.valueOf(args.string("debility").uppercase())
            if (debility in game.sheet.debilities) throw ActionRejectedException.state("${args.string("debility")} is already marked")
            changing("Marked ${args.string("debility")}") { it.withDebilities(it.debilities.mark(debility)) }
        }

        action(
            "clear_debility", ActionArea.STATE, ActionPermission.PLAY,
            "Clears a condition or burden. Banes (maimed, corrupted) are permanent.",
            "a move or the fiction removes a debility",
            "The changes.",
            setOf(ActionEffect.CHANGES_CHARACTER),
            params = { string("debility", "Which debility", enum = debilityNames) },
        ) { args, _ ->
            val debility = Debility.valueOf(args.string("debility").uppercase())
            if (debility !in game.sheet.debilities) throw ActionRejectedException.state("${args.string("debility")} is not marked")
            changing("Cleared ${args.string("debility")}") { sheet -> rules { sheet.withDebilities(sheet.debilities.clear(debility)) } }
        }
    }

    // ---- Progress ------------------------------------------------------------------------------

    private fun progress() {
        action(
            "create_progress_track", ActionArea.PROGRESS, ActionPermission.PLAY,
            "Starts a progress track. Vows, journeys and combat need a rank; bonds have none.",
            "a track starts outside a move; Swear an Iron Vow and Undertake a Journey start their own through roll_move",
            "The new track id and the changes.",
            setOf(ActionEffect.CHANGES_TRACKS),
            params = {
                string("type", "Kind of track", enum = ProgressTrackType.entries.map { it.name.lowercase() })
                string("name", "Name of the track, such as the vow")
                string("rank", "Rank, for every type except bonds", required = false, enum = ChallengeRank.entries.map { it.name.lowercase() })
            },
        ) { args, _ ->
            val type = ProgressTrackType.valueOf(args.string("type").uppercase())
            val rank = args.stringOrNull("rank")?.let { ChallengeRank.valueOf(it.uppercase()) }
            var id = ""
            val result = changing("Started ${args.string("type")} track \"${args.string("name")}\"") { sheet ->
                rules { sheet.startTrack(type, args.string("name"), rank) }.let { (newId, updated) ->
                    id = newId
                    updated
                }
            }
            result.copy(data = buildJsonObject { put("track", id) })
        }

        action(
            "mark_progress", ActionArea.PROGRESS, ActionPermission.PLAY,
            "Marks progress on a track, as many ticks as its rank gives per mark.",
            "the fiction moves a vow, journey, fight or bond forward outside a move",
            "The changes.",
            setOf(ActionEffect.CHANGES_TRACKS),
            params = {
                string("track", "Track id")
                integer("times", "How many marks; 1 when left out", required = false, minimum = 1, maximum = 10)
            },
        ) { args, _ ->
            val id = trackId(args)
            changing("Marked progress on ${game.sheet.tracks.getValue(id).name}") { sheet ->
                sheet.copy(tracks = sheet.tracks + (id to sheet.tracks.getValue(id).markProgress(args.intOrNull("times") ?: 1)))
            }
        }

        action(
            "roll_progress", ActionArea.PROGRESS, ActionPermission.PLAY,
            "Makes a progress roll on a track: its filled boxes against two challenge dice. Changes nothing by itself.",
            "a progress roll is needed outside the moves that make one",
            "The roll and its outcome.",
            setOf(ActionEffect.ROLLS_DICE),
            params = { string("track", "Track id") },
        ) { args, _ ->
            val id = trackId(args)
            val roll = ProgressRoll.roll(dice, game.sheet.tracks.getValue(id))
            ActionResult(
                "${outcomeName(roll.outcome)} (progress ${roll.progressScore} vs ${roll.challenge1} and ${roll.challenge2})" + if (roll.isMatch) ", a match" else "",
                buildJsonObject {
                    put("track", id)
                    put("progress_score", roll.progressScore)
                    put("challenge_dice", JsonArray(listOf(JsonPrimitive(roll.challenge1), JsonPrimitive(roll.challenge2))))
                    put("outcome", roll.outcome.name.lowercase())
                    put("match", roll.isMatch)
                },
            )
        }

        action(
            "close_progress_track", ActionArea.PROGRESS, ActionPermission.PLAY,
            "Removes a finished or abandoned track.",
            "a track ends outside a move; the vow and journey moves close their own",
            "The changes.",
            setOf(ActionEffect.CHANGES_TRACKS),
            params = { string("track", "Track id") },
        ) { args, _ ->
            val id = trackId(args)
            changing("Closed ${game.sheet.tracks.getValue(id).name}") { it.copy(tracks = it.tracks - id) }
        }
    }

    // ---- Oracles -------------------------------------------------------------------------------

    private fun oracleActions() {
        action(
            "ask_oracle", ActionArea.ORACLES, ActionPermission.PLAY,
            "Asks the yes/no oracle a question with the odds that fit the fiction. A match means an extreme answer or a twist.",
            "a question about the world has no clear answer in the fiction",
            "The roll, the answer and whether it is extreme.",
            setOf(ActionEffect.ROLLS_DICE),
            params = {
                string("question", "The question, as asked")
                string("odds", "How likely a yes is", enum = Odds.entries.map { it.name.lowercase() })
            },
        ) { args, _ ->
            val answer = YesNoOracle.ask(dice, Odds.valueOf(args.string("odds").uppercase()))
            ActionResult(
                (if (answer.isYes) "Yes" else "No") + " (${answer.roll} against ${args.string("odds")})" + if (answer.isExtreme) ", extreme" else "",
                buildJsonObject {
                    put("question", args.string("question"))
                    put("odds", args.string("odds"))
                    put("roll", answer.roll)
                    put("answer", if (answer.isYes) "yes" else "no")
                    put("extreme", answer.isExtreme)
                },
            )
        }

        action(
            "roll_oracle_table", ActionArea.ORACLES, ActionPermission.PLAY,
            "Rolls on an oracle table, following the extra rolls a row asks for. Rows are in the player's language.",
            "a move or the fiction asks for a table, such as Pay the Price",
            "The rolls, the rows and any move a row leads to.",
            setOf(ActionEffect.ROLLS_DICE),
            params = { string("table", "Table id", enum = oracles.tables.map { it.id }) },
        ) { args, _ ->
            val roll = oracles.roll(args.string("table"), dice)
            ActionResult(rowText(roll), tableRoll(roll))
        }

        action(
            "list_oracle_tables", ActionArea.ORACLES, ActionPermission.READ,
            "Lists the oracle tables with their names in the player's language.",
            "you need a table id",
            "The tables.",
        ) { _, _ ->
            ActionResult(
                "${oracles.tables.size} oracle tables",
                buildJsonObject {
                    put(
                        "tables",
                        JsonArray(
                            oracles.tables.map { table ->
                                buildJsonObject {
                                    put("id", table.id)
                                    put("name", oracles.text(table.id, language)?.name ?: table.id)
                                }
                            },
                        ),
                    )
                },
            )
        }
    }

    private fun rowText(roll: TableRoll): String =
        (oracles.text(roll.table, language)?.rows?.get(roll.row.id) ?: roll.row.id) +
            roll.extra.joinToString("") { " / " + rowText(it) }

    private fun tableRoll(roll: TableRoll): JsonObject = buildJsonObject {
        put("table", roll.table)
        put("roll", roll.roll)
        put("row", roll.row.id)
        put("name", oracles.text(roll.table, language)?.name ?: roll.table)
        put("text", oracles.text(roll.table, language)?.rows?.get(roll.row.id) ?: roll.row.id)
        put("move", roll.row.move?.let(::JsonPrimitive) ?: JsonNull)
        put("repeated", roll.isRepeated)
        put("extra", JsonArray(roll.extra.map { tableRoll(it) }))
    }

    // ---- System --------------------------------------------------------------------------------

    private fun system() {
        action(
            "save", ActionArea.SYSTEM, ActionPermission.READ,
            "Writes the game to storage now. The game is also saved after every change, so this is rarely needed.",
            "the player asks to save",
            "Confirmation.",
        ) { _, _ ->
            game.sheet // Fails when no game is open.
            game.save()
            ActionResult("Saved")
        }

        action(
            UNDO, ActionArea.SYSTEM, ActionPermission.PLAY,
            "Undoes the last play action that changed the game, together with the options picked for it, and drops any open choice. Hand edits cannot be undone, nor anything before them.",
            "the player asks to take back the last thing done",
            "What was undone and the changes.",
            setOf(ActionEffect.CHANGES_CHARACTER, ActionEffect.CHANGES_TRACKS),
            answersChoice = true,
        ) { _, _ ->
            val history = game.history ?: throw GameAccess.noGame()
            val before = game.sheet
            val undone = history.undo()
            ActionResult(
                "Undid " + undone.joinToString(", ") { it.name },
                buildJsonObject { put("undone", JsonArray(undone.map { JsonPrimitive(it.sequence) })) },
                SheetChanges.between(before, game.sheet),
            )
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun action(
        name: String,
        area: ActionArea,
        permission: ActionPermission,
        description: String,
        whenToUse: String,
        returns: String,
        effects: Set<ActionEffect> = emptySet(),
        answersChoice: Boolean = false,
        params: ObjectSchemaBuilder.() -> Unit = {},
        handler: (ActionArguments, ActionCall) -> ActionResult,
    ) {
        definitions += ActionDefinition(name, area, description, whenToUse, permission, ActionReturn(returns), JsonSchema.obj(build = params), effects, answersChoice)
        handlers[name] = ActionHandler { arguments, call -> handler(arguments, call) }
    }

    /** Applies [change] to the sheet and reports what changed. */
    private fun changing(summary: String, change: (Character) -> Character): ActionResult {
        val before = game.sheet
        val after = change(before)
        game.update { after }
        return ActionResult(summary, changes = SheetChanges.between(before, after))
    }

    /** Runs engine code whose rule checks throw IllegalArgumentException, turning them into explained rule errors. */
    private fun <T> rules(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw ActionRejectedException.rule(e.message ?: "The rules do not allow it")
        }

    private fun trackId(args: ActionArguments): String {
        val id = args.string("track")
        val tracks = game.sheet.tracks
        if (id !in tracks) {
            throw ActionRejectedException.state(
                "There is no track $id",
                if (tracks.isEmpty()) "There are no tracks yet" else "The tracks are ${tracks.keys.sorted().joinToString(", ")}",
            )
        }
        return id
    }

    private fun optionIds(args: ActionArguments) = args.array("options").map { it.jsonPrimitive.content }

    private fun moveName(id: String) = moves.text(id, language)?.name ?: id

    private val moveIds get() = moves.moves.map { it.id }

    private fun statParameters(minimum: Int, maximum: Int): ObjectSchemaBuilder.() -> Unit = {
        for (stat in Stat.entries) integer(stat.name.lowercase(), "${stat.name.lowercase().replaceFirstChar { it.uppercase() }}, $minimum to $maximum", minimum = minimum, maximum = maximum)
    }

    private fun stats(args: ActionArguments): Map<Stat, Int> = Stat.entries.associateWith { args.int(it.name.lowercase()) }

    companion object {
        val VERSION = CatalogVersion(1, 0)

        const val UNDO = "undo"

        /** Option ids of the choice that offers burning momentum. */
        const val BURN = "burn"
        const val KEEP = "keep"

        private const val FACE_A_SETBACK = "face_a_setback"
        private const val OUT_OF_SUPPLY = "out_of_supply"

        private val STARTING_STATS = listOf(3, 2, 2, 1, 1)

        private val debilityNames = Debility.entries.map { it.name.lowercase() }

        /** A sheet nobody has filled in yet: the app creates campaigns with every stat at 0. */
        fun isPending(sheet: Character): Boolean = sheet.stats.values.all { it == 0 }

        private fun outcomeName(outcome: Outcome) = outcome.name.lowercase().replace('_', ' ')
    }
}
