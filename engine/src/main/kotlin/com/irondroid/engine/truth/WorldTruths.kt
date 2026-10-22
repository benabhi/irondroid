package com.irondroid.engine.truth

import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * World truths as data (SRD chapter 4, RF-CAM-01). These classes mirror truths.json: structure only.
 * Every text the player sees lives in the per-language files (see TruthCategoryText).
 */

/** One of the options of a truth category, picked with the d100 results [min]..[max]. */
@Serializable
data class TruthOption(val id: String, val min: Int, val max: Int) {
    operator fun contains(roll: Int): Boolean = roll in min..max
}

/** A truth category, such as The Old World or Iron. Its options cover 1..100 once each (checked by the loader). */
@Serializable
data class TruthCategory(val id: String, val options: List<TruthOption>) {
    /** The option that [roll] lands on. */
    fun optionFor(roll: Int): TruthOption {
        require(Die.D100.accepts(roll)) { "Truth roll must be 1..100, was $roll" }
        return options.first { roll in it }
    }

    /** Picks an option at random with 1d100 from [dice]. */
    fun roll(dice: Dice): TruthOption = optionFor(dice.roll(Die.D100).value)
}

/** What a truth option says about the world, and the [quest] starter it may inspire. */
@Serializable
data class TruthOptionText(val text: String, val quest: String)

/** The texts of a category in one language: its [name] and its options by id. */
@Serializable
data class TruthCategoryText(val name: String, val options: Map<String, TruthOptionText>)

@Serializable
internal data class TruthFile(val version: Int, val categories: List<TruthCategory>)

@Serializable
internal data class TruthTextFile(val version: Int, val categories: Map<String, TruthCategoryText>)

/** Truth data that cannot be used. Lists every problem found, not only the first. */
class TruthDataException(val errors: List<String>) :
    IllegalArgumentException("Invalid world truth data:\n" + errors.joinToString("\n") { "- $it" })

/** Every truth category of the book, in book order, with its texts in every language. */
class TruthCatalog internal constructor(
    val categories: List<TruthCategory>,
    private val texts: Map<String, Map<String, TruthCategoryText>>,
) {
    private val byId = categories.associateBy { it.id }

    val languages: Set<String> get() = texts.keys

    operator fun get(id: String): TruthCategory? = byId[id]

    /** Texts of category [id] in [language]. */
    fun text(id: String, language: String): TruthCategoryText? = texts[language]?.get(id)

    /** Texts of option [optionId] of category [categoryId] in [language]. */
    fun optionText(categoryId: String, optionId: String, language: String): TruthOptionText? =
        text(categoryId, language)?.options?.get(optionId)
}

/** Reads and validates truths.json and its per-language text files. */
object TruthLoader {
    /** Languages every truth needs texts for: the app's base language and its translation. */
    val REQUIRED_LANGUAGES = setOf("en", "es")

    const val FORMAT_VERSION = 1

    private val json = Json
    private val idPattern = Regex("[a-z][a-z0-9_]*")

    /** Loads `<directory>/truths.json` and `<directory>/truths.<language>.json` from the classpath. */
    fun fromResources(directory: String = "truths"): TruthCatalog {
        fun read(name: String): String {
            val path = "$directory/$name"
            val stream = TruthLoader::class.java.classLoader.getResourceAsStream(path)
                ?: throw TruthDataException(listOf("$path: file not found"))
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return load(read("truths.json"), REQUIRED_LANGUAGES.associateWith { read("truths.$it.json") })
    }

    /** Parses and validates [structure] (truths.json) and [texts] (language to truths.<language>.json). */
    fun load(structure: String, texts: Map<String, String>): TruthCatalog {
        val errors = mutableListOf<String>()
        val file = parse(structure, "truths.json", errors) { json.decodeFromString<TruthFile>(it) }
        val textFiles = texts.mapNotNull { (language, content) ->
            parse(content, "truths.$language.json", errors) { json.decodeFromString<TruthTextFile>(it) }
                ?.let { language to it }
        }.toMap()
        if (file == null || errors.isNotEmpty()) throw TruthDataException(errors)

        validateStructure(file, errors)
        for (language in REQUIRED_LANGUAGES - textFiles.keys) errors += "truths.$language.json: missing"
        for ((language, textFile) in textFiles) validateTexts(file.categories, language, textFile, errors)
        if (errors.isNotEmpty()) throw TruthDataException(errors)

        return TruthCatalog(file.categories, textFiles.mapValues { it.value.categories })
    }

    private fun <T> parse(content: String, name: String, errors: MutableList<String>, decode: (String) -> T): T? =
        try {
            decode(content)
        } catch (e: SerializationException) { // Also covers malformed JSON: it is an IllegalArgumentException subclass.
            errors += "$name: ${e.message}"
            null
        }

    private fun validateStructure(file: TruthFile, errors: MutableList<String>) {
        if (file.version != FORMAT_VERSION) errors += "truths.json: version ${file.version}, expected $FORMAT_VERSION"
        file.categories.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "Category $it is defined more than once" }
        // Saved worlds keep option ids, so an id may not repeat even across categories.
        file.categories.flatMap { it.options }.groupBy { it.id }.filterValues { it.size > 1 }.keys
            .forEach { errors += "Option $it is defined more than once" }

        for (category in file.categories) {
            val where = "Category ${category.id}"
            if (!idPattern.matches(category.id)) errors += "$where: id must be snake_case"
            if (category.options.isEmpty()) errors += "$where: no options"
            category.options.filterNot { idPattern.matches(it.id) }.forEach { errors += "$where: option id ${it.id} must be snake_case" }
            category.options.filter { it.min > it.max }.forEach { errors += "$where: option ${it.id} goes from ${it.min} down to ${it.max}" }
            category.options.filter { it.min < 1 || it.max > 100 }.forEach { errors += "$where: option ${it.id} goes outside 1..100" }

            // Every d100 result must land on exactly one option.
            val hits = (1..100).associateWith { roll -> category.options.filter { roll in it }.map { it.id } }
            val gaps = hits.filterValues { it.isEmpty() }.keys
            if (gaps.isNotEmpty()) errors += "$where: no option for ${gaps.min()}..${gaps.max()}"
            hits.filterValues { it.size > 1 }.forEach { (roll, options) -> errors += "$where: $roll is in options ${options.joinToString()}" }
        }
    }

    private fun validateTexts(
        categories: List<TruthCategory>,
        language: String,
        file: TruthTextFile,
        errors: MutableList<String>,
    ) {
        val name = "truths.$language.json"
        if (file.version != FORMAT_VERSION) errors += "$name: version ${file.version}, expected $FORMAT_VERSION"
        (file.categories.keys - categories.map { it.id }.toSet()).forEach { errors += "$name: text for unknown category $it" }

        for (category in categories) {
            val text = file.categories[category.id]
            if (text == null) {
                errors += "$name: no text for category ${category.id}"
                continue
            }
            val where = "$name, category ${category.id}"
            if (text.name.isBlank()) errors += "$where: empty name"
            val optionIds = category.options.map { it.id }.toSet()
            (optionIds - text.options.keys).forEach { errors += "$where: missing text for option $it" }
            (text.options.keys - optionIds).forEach { errors += "$where: text for unknown option $it" }
            text.options.filterValues { it.text.isBlank() }.keys.forEach { errors += "$where: empty text for option $it" }
            text.options.filterValues { it.quest.isBlank() }.keys.forEach { errors += "$where: empty quest starter for option $it" }
        }
    }
}
