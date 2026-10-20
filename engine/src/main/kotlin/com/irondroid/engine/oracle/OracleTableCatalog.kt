package com.irondroid.engine.oracle

import com.irondroid.engine.dice.Dice
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Table data that cannot be used. Lists every problem found, not only the first. */
class OracleTableDataException(val errors: List<String>) :
    IllegalArgumentException("Invalid oracle table data:\n" + errors.joinToString("\n") { "- $it" })

/** All the oracle tables the engine knows, with their texts in every language. */
class OracleTableCatalog internal constructor(
    val tables: List<OracleTable>,
    private val texts: Map<String, Map<String, OracleTableText>>,
) {
    private val byId = tables.associateBy { it.id }

    val languages: Set<String> get() = texts.keys

    operator fun get(id: String): OracleTable? = byId[id]

    /** Texts of table [id] in [language]. */
    fun text(id: String, language: String): OracleTableText? = texts[language]?.get(id)

    /** Rolls on table [id] (a roll_table follow-up of a move). Fails if the table is unknown. */
    fun roll(id: String, dice: Dice): TableRoll = requireNotNull(byId[id]) { "Unknown oracle table $id" }.roll(dice)

    /** Moves named by table rows. They live in the move catalog; a complete set of data has them all. */
    fun referencedMoves(): Set<String> = tables.flatMap { table -> table.rows.mapNotNull { it.move } }.toSet()
}

/** Reads and validates tables.json and its per-language text files. */
object OracleTableLoader {
    /** Languages every table needs texts for: the app's base language and its translation. */
    val REQUIRED_LANGUAGES = setOf("en", "es")

    const val FORMAT_VERSION = 1

    private val json = Json
    private val idPattern = Regex("[a-z][a-z0-9_]*")

    /** Loads `<directory>/tables.json` and `<directory>/tables.<language>.json` from the classpath. */
    fun fromResources(directory: String = "oracles"): OracleTableCatalog {
        fun read(name: String): String {
            val path = "$directory/$name"
            val stream = OracleTableLoader::class.java.classLoader.getResourceAsStream(path)
                ?: throw OracleTableDataException(listOf("$path: file not found"))
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return load(read("tables.json"), REQUIRED_LANGUAGES.associateWith { read("tables.$it.json") })
    }

    /** Parses and validates [structure] (tables.json) and [texts] (language to tables.<language>.json). */
    fun load(structure: String, texts: Map<String, String>): OracleTableCatalog {
        val errors = mutableListOf<String>()
        val file = parse(structure, "tables.json", errors) { json.decodeFromString<OracleTableFile>(it) }
        val textFiles = texts.mapNotNull { (language, content) ->
            parse(content, "tables.$language.json", errors) { json.decodeFromString<OracleTableTextFile>(it) }
                ?.let { language to it }
        }.toMap()
        if (file == null || errors.isNotEmpty()) throw OracleTableDataException(errors)

        validateStructure(file, errors)
        for (language in REQUIRED_LANGUAGES - textFiles.keys) errors += "tables.$language.json: missing"
        for ((language, textFile) in textFiles) validateTexts(file.tables, language, textFile, errors)
        if (errors.isNotEmpty()) throw OracleTableDataException(errors)

        return OracleTableCatalog(file.tables, textFiles.mapValues { it.value.tables })
    }

    private fun <T> parse(content: String, name: String, errors: MutableList<String>, decode: (String) -> T): T? =
        try {
            decode(content)
        } catch (e: SerializationException) { // Also covers malformed JSON: it is an IllegalArgumentException subclass.
            errors += "$name: ${e.message}"
            null
        }

    private fun validateStructure(file: OracleTableFile, errors: MutableList<String>) {
        if (file.version != FORMAT_VERSION) errors += "tables.json: version ${file.version}, expected $FORMAT_VERSION"
        file.tables.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "Table $it is defined more than once" }

        for (table in file.tables) {
            val where = "Table ${table.id}"
            if (!idPattern.matches(table.id)) errors += "$where: id must be snake_case"
            table.rows.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { errors += "$where: row $it is repeated" }
            table.rows.filterNot { idPattern.matches(it.id) }.forEach { errors += "$where: row id ${it.id} must be snake_case" }
            table.rows.mapNotNull { it.move }.filterNot { idPattern.matches(it) }.forEach { errors += "$where: move id $it must be snake_case" }
            table.rows.filter { it.min > it.max }.forEach { errors += "$where: row ${it.id} goes from ${it.min} down to ${it.max}" }

            // Every d100 result must land on exactly one row.
            val hits = (1..100).associateWith { roll -> table.rows.filter { roll in it }.map { it.id } }
            hits.filterValues { it.isEmpty() }.keys.toRanges().forEach { errors += "$where: no row for $it" }
            hits.filterValues { it.size > 1 }.forEach { (roll, rows) -> errors += "$where: $roll is in rows ${rows.joinToString()}" }
            table.rows.filter { it.min < 1 || it.max > 100 }.forEach { errors += "$where: row ${it.id} goes outside 1..100" }
        }
    }

    private fun validateTexts(tables: List<OracleTable>, language: String, file: OracleTableTextFile, errors: MutableList<String>) {
        val name = "tables.$language.json"
        if (file.version != FORMAT_VERSION) errors += "$name: version ${file.version}, expected $FORMAT_VERSION"
        (file.tables.keys - tables.map { it.id }.toSet()).forEach { errors += "$name: text for unknown table $it" }

        for (table in tables) {
            val text = file.tables[table.id]
            if (text == null) {
                errors += "$name: no text for table ${table.id}"
                continue
            }
            val where = "$name, table ${table.id}"
            if (text.name.isBlank()) errors += "$where: empty name"
            val rowIds = table.rows.map { it.id }.toSet()
            (rowIds - text.rows.keys).forEach { errors += "$where: missing text for row $it" }
            (text.rows.keys - rowIds).forEach { errors += "$where: text for unknown row $it" }
            text.rows.filterValues { it.isBlank() }.keys.forEach { errors += "$where: empty text for row $it" }
        }
    }

    /** Groups sorted numbers into readable ranges, such as "3-5" or "7". */
    private fun Set<Int>.toRanges(): List<String> {
        val ranges = mutableListOf<IntRange>()
        for (n in sorted()) {
            val last = ranges.lastOrNull()
            if (last != null && last.last == n - 1) ranges[ranges.lastIndex] = last.first..n else ranges += n..n
        }
        return ranges.map { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
    }
}
