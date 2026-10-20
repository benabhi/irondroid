package com.irondroid.engine.move

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Move data that cannot be used. Lists every problem found, not only the first. */
class MoveDataException(val errors: List<String>) :
    IllegalArgumentException("Invalid move data:\n" + errors.joinToString("\n") { "- $it" })

/** All the moves the engine knows, with their texts in every language. */
class MoveCatalog internal constructor(
    val moves: List<MoveDefinition>,
    private val texts: Map<String, Map<String, MoveText>>,
) {
    private val byId = moves.associateBy { it.id }

    val languages: Set<String> get() = texts.keys

    operator fun get(id: String): MoveDefinition? = byId[id]

    /** Texts of move [id] in [language]. */
    fun text(id: String, language: String): MoveText? = texts[language]?.get(id)

    /**
     * Moves named by effects that are not in this catalog. Not an error while loading,
     * because moves arrive in separate files and tasks; a complete catalog must return none.
     */
    fun unresolvedReferences(): Set<String> =
        moves.flatMap { it.allEffects }.mapNotNull { effect ->
            when (effect) {
                is Effect.TriggerMove -> effect.move
                is Effect.NextRollBonus -> effect.move
                else -> null
            }
        }.toSet() - byId.keys

    /** Oracle tables named by roll_table effects. They are loaded with the oracles, not with the moves. */
    fun referencedTables(): Set<String> =
        moves.flatMap { it.allEffects }.filterIsInstance<Effect.RollTable>().map { it.table }.toSet()
}

/** Reads and validates moves.json and its per-language text files. */
object MoveLoader {
    /** Languages every move needs texts for: the app's base language and its translation. */
    val REQUIRED_LANGUAGES = setOf("en", "es")

    const val FORMAT_VERSION = 1

    private val json = Json { classDiscriminator = "type" }
    private val idPattern = Regex("[a-z][a-z0-9_]*")

    /** Loads `<directory>/moves.json` and `<directory>/moves.<language>.json` from the classpath. */
    fun fromResources(directory: String = "moves"): MoveCatalog {
        fun read(name: String): String {
            val path = "$directory/$name"
            val stream = MoveLoader::class.java.classLoader.getResourceAsStream(path)
                ?: throw MoveDataException(listOf("$path: file not found"))
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return load(read("moves.json"), REQUIRED_LANGUAGES.associateWith { read("moves.$it.json") })
    }

    /** Parses and validates [structure] (moves.json) and [texts] (language to moves.<language>.json). */
    fun load(structure: String, texts: Map<String, String>): MoveCatalog {
        val errors = mutableListOf<String>()
        val file = parse(structure, "moves.json", errors) { json.decodeFromString<MoveFile>(it) }
        val textFiles = texts.mapNotNull { (language, content) ->
            parse(content, "moves.$language.json", errors) { json.decodeFromString<MoveTextFile>(it) }?.let { language to it }
        }.toMap()
        if (file == null || errors.isNotEmpty()) throw MoveDataException(errors)

        validateStructure(file, errors)
        for (language in REQUIRED_LANGUAGES - textFiles.keys) errors += "moves.$language.json: missing"
        for ((language, textFile) in textFiles) validateTexts(file.moves, language, textFile, errors)
        if (errors.isNotEmpty()) throw MoveDataException(errors)

        return MoveCatalog(file.moves, textFiles.mapValues { it.value.moves })
    }

    private fun <T> parse(content: String, name: String, errors: MutableList<String>, decode: (String) -> T): T? =
        try {
            decode(content)
        } catch (e: SerializationException) { // Also covers malformed JSON: it is an IllegalArgumentException subclass.
            errors += "$name: ${e.message}"
            null
        }

    private fun validateStructure(file: MoveFile, errors: MutableList<String>) {
        if (file.version != FORMAT_VERSION) errors += "moves.json: version ${file.version}, expected $FORMAT_VERSION"
        file.moves.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "Move $it is defined more than once" }

        for (move in file.moves) {
            val where = "Move ${move.id}"
            if (!idPattern.matches(move.id)) errors += "$where: id must be snake_case"
            val roll = move.roll
            if (roll is MoveRoll.Action && roll.sources.isEmpty()) errors += "$where: action roll needs at least one source"
            if (roll is MoveRoll.Action && roll.pick != SourcePick.ANY && roll.sources.size < 2) {
                errors += "$where: picking the highest or lowest needs two sources or more"
            }
            if (roll is MoveRoll.None && move.outcomes != null) errors += "$where: a move without a roll has no outcomes"
            if (roll !is MoveRoll.None && move.outcomes == null) errors += "$where: a rolled move needs outcomes"
            if (roll !is MoveRoll.None && move.choice != null) errors += "$where: a rolled move puts its choices in outcomes"

            for (choice in move.choices) {
                choice.options.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "$where: option $it is repeated" }
                if (choice.count !in 1..choice.options.size) errors += "$where: cannot choose ${choice.count} of ${choice.options.size} options"
            }
            // An option listed in several outcomes (Make Camp) must do the same everywhere: it has one text.
            move.choices.flatMap { it.options }.groupBy { it.id }
                .filterValues { same -> same.map { it.effects }.distinct().size > 1 }
                .keys.forEach { errors += "$where: option $it has different effects in different outcomes" }
            move.optionIds.filterNot { idPattern.matches(it) }.forEach { errors += "$where: option id $it must be snake_case" }

            val bonuses = (roll as? MoveRoll.Action)?.bonuses.orEmpty()
            bonuses.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "$where: bonus $it is repeated" }
            bonuses.filterNot { idPattern.matches(it.id) }.forEach { errors += "$where: bonus id ${it.id} must be snake_case" }
            bonuses.filter { it.amount < 1 }.forEach { errors += "$where: bonus ${it.id} needs amount >= 1" }

            move.allEffects.forEach { effect -> validateEffect(effect, move)?.let { errors += "$where: $it" } }
        }
    }

    private fun validateEffect(effect: Effect, move: MoveDefinition): String? {
        val needsTrack = effect is Effect.MarkProgress || effect is Effect.ClearProgress || effect is Effect.RaiseRank ||
            effect is Effect.StartTrack || effect is Effect.EndTrack || effect is Effect.MarkExperience ||
            (effect is Effect.TriggerMove && effect.amountByRank)
        if (needsTrack && move.progressTrack == null) return "progress effects need the move's track"
        return when (effect) {
            is Effect.AdjustMomentum -> "momentum effect of 0".takeIf { effect.amount == 0 }
            is Effect.AdjustMeter -> "${effect.meter} effect of 0".takeIf { effect.amount == 0 }
            is Effect.MarkProgress -> "mark_progress needs times >= 1".takeIf { effect.times < 1 }
            is Effect.ClearProgress -> "clear_progress keeps 0..9 boxes".takeIf { effect.keepBoxes !in 0..9 }
            is Effect.RaiseRank, is Effect.ClearDebility, is Effect.StartTrack, is Effect.EndTrack,
            is Effect.Suffer, is Effect.MarkDebility, is Effect.AskOracle, is Effect.EndCharacter -> null
            is Effect.RollTable -> "roll_table with an invalid id ${effect.table}".takeUnless { idPattern.matches(effect.table) }
            is Effect.MarkExperience -> "mark_experience offset must be -4..0".takeIf { effect.rankOffset !in -4..0 }
            is Effect.SpendExperience -> "spend_experience needs amount >= 1".takeIf { effect.amount < 1 }
            is Effect.NextRollBonus -> when {
                effect.amount < 1 -> "next_roll_bonus needs amount >= 1"
                effect.move != null && !idPattern.matches(effect.move) -> "next_roll_bonus for an invalid move id ${effect.move}"
                else -> null
            }
            is Effect.TriggerMove -> when {
                !idPattern.matches(effect.move) -> "trigger_move to an invalid id ${effect.move}"
                effect.amountByRank && effect.amount != null -> "trigger_move has both amount and amount_by_rank"
                else -> null
            }
        }
    }

    private fun validateTexts(moves: List<MoveDefinition>, language: String, file: MoveTextFile, errors: MutableList<String>) {
        val name = "moves.$language.json"
        if (file.version != FORMAT_VERSION) errors += "$name: version ${file.version}, expected $FORMAT_VERSION"
        (file.moves.keys - moves.map { it.id }.toSet()).forEach { errors += "$name: text for unknown move $it" }

        for (move in moves) {
            val text = file.moves[move.id]
            if (text == null) {
                errors += "$name: no text for move ${move.id}"
                continue
            }
            val where = "$name, move ${move.id}"
            if (text.name.isBlank()) errors += "$where: empty name"
            if (text.trigger.isBlank()) errors += "$where: empty trigger"
            val outcomeTexts = mapOf("strong_hit" to text.strongHit, "weak_hit" to text.weakHit, "miss" to text.miss)
            if (move.outcomes != null) {
                outcomeTexts.filterValues { it.isNullOrBlank() }.keys.forEach { errors += "$where: missing $it text" }
            } else {
                outcomeTexts.filterValues { it != null }.keys.forEach { errors += "$where: $it text for a move without a roll" }
            }
            (move.optionIds - text.options.keys).forEach { errors += "$where: missing text for option $it" }
            (text.options.keys - move.optionIds).forEach { errors += "$where: text for unknown option $it" }
            text.options.filterValues { it.isBlank() }.keys.forEach { errors += "$where: empty text for option $it" }
            (move.bonusIds - text.bonuses.keys).forEach { errors += "$where: missing text for bonus $it" }
            (text.bonuses.keys - move.bonusIds).forEach { errors += "$where: text for unknown bonus $it" }
            text.bonuses.filterValues { it.isBlank() }.keys.forEach { errors += "$where: empty text for bonus $it" }
        }
    }
}
