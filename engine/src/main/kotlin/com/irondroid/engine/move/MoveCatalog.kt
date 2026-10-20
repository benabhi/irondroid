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
     * Moves named by trigger_move effects that are not in this catalog. Not an error while loading,
     * because moves arrive in separate files and tasks; a complete catalog must return none.
     */
    fun unresolvedReferences(): Set<String> =
        moves.flatMap { it.allEffects }.filterIsInstance<Effect.TriggerMove>().map { it.move }.toSet() - byId.keys
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
        } catch (e: SerializationException) {
            errors += "$name: ${e.message}"
            null
        } catch (e: IllegalArgumentException) {
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
            if (roll is MoveRoll.None && move.outcomes != null) errors += "$where: a move without a roll has no outcomes"
            if (roll !is MoveRoll.None && move.outcomes == null) errors += "$where: a rolled move needs outcomes"

            move.optionIds.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { errors += "$where: option $it is repeated" }
            move.optionIds.filterNot { idPattern.matches(it) }.forEach { errors += "$where: option id $it must be snake_case" }
            listOfNotNull(move.outcomes?.strongHit, move.outcomes?.weakHit, move.outcomes?.miss)
                .mapNotNull { it.choice }
                .filter { it.count !in 1..it.options.size }
                .forEach { errors += "$where: cannot choose ${it.count} of ${it.options.size} options" }
            move.allEffects.forEach { effect -> validateEffect(effect)?.let { errors += "$where: $it" } }
        }
    }

    private fun validateEffect(effect: Effect): String? = when (effect) {
        is Effect.AdjustMomentum -> "momentum effect of 0".takeIf { effect.amount == 0 }
        is Effect.AdjustMeter -> "${effect.meter} effect of 0".takeIf { effect.amount == 0 }
        is Effect.MarkProgress -> "mark_progress needs times >= 1".takeIf { effect.times < 1 }
        is Effect.NextRollBonus -> "next_roll_bonus needs amount >= 1".takeIf { effect.amount < 1 }
        is Effect.TriggerMove -> "trigger_move to an invalid id ${effect.move}".takeUnless { idPattern.matches(effect.move) }
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
            (move.optionIds.toSet() - text.options.keys).forEach { errors += "$where: missing text for option $it" }
            (text.options.keys - move.optionIds.toSet()).forEach { errors += "$where: text for unknown option $it" }
            text.options.filterValues { it.isBlank() }.keys.forEach { errors += "$where: empty text for option $it" }
        }
    }
}
