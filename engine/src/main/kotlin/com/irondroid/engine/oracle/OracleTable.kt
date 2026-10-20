package com.irondroid.engine.oracle

import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Oracle tables as data (RF-ENV-07, RF-ORA-02). These classes mirror tables.json: structure only.
 * Every text the player sees lives in the per-language files (see OracleTableText).
 */

/** Extra rolls a row asks for on the same table. */
@Serializable
enum class RowAction {
    /** Roll once more and use that result, made worse (Pay the Price 1-2). */
    @SerialName("roll_again") ROLL_AGAIN,

    /** Roll twice more; both results happen (Pay the Price 99-100). */
    @SerialName("roll_twice") ROLL_TWICE,
}

/**
 * A row of an oracle table: the d100 results [min]..[max].
 *
 * [move] is a move the row always leads to, such as Face Death on Endure Harm 1-10.
 * Moves that depend on the situation stay in the row text for the narrator.
 */
@Serializable
data class TableRow(
    val id: String,
    val min: Int,
    val max: Int,
    val then: RowAction? = null,
    val move: String? = null,
) {
    operator fun contains(roll: Int): Boolean = roll in min..max
}

/** An oracle table rolled with 1d100. Its rows cover 1..100 once each (checked by the loader). */
@Serializable
data class OracleTable(val id: String, val rows: List<TableRow>) {
    /** The row that [roll] lands on. */
    fun rowFor(roll: Int): TableRow {
        require(Die.D100.accepts(roll)) { "Table roll must be 1..100, was $roll" }
        return rows.first { roll in it }
    }

    /** Rolls 1d100 from [dice] and follows the extra rolls the row asks for. */
    fun roll(dice: Dice): TableRoll = roll(dice, depth = 0, parent = null)

    private fun roll(dice: Dice, depth: Int, parent: RowAction?): TableRoll {
        val value = dice.roll(Die.D100).value
        val row = rowFor(value)
        // Rolling 1-2 right after 1-2 ends the chain: the player invents something dreadful instead.
        val stops = depth >= MAX_DEPTH || (row.then == RowAction.ROLL_AGAIN && parent == RowAction.ROLL_AGAIN)
        val extraRolls = when {
            stops -> 0
            row.then == RowAction.ROLL_AGAIN -> 1
            row.then == RowAction.ROLL_TWICE -> 2
            else -> 0
        }
        return TableRoll(id, value, row, List(extraRolls) { roll(dice, depth + 1, row.then) })
    }

    companion object {
        /** Most nested extra rolls followed, so a chain of 99-100 results cannot go on forever. */
        const val MAX_DEPTH = 4
    }
}

/** A roll on [table]: the d100 [roll], its [row] and the extra rolls that row asked for. */
data class TableRoll(
    val table: String,
    val roll: Int,
    val row: TableRow,
    val extra: List<TableRoll> = emptyList(),
) {
    /** Two extra rolls landed on the same row: the SRD says to make that result worse. */
    val isRepeated: Boolean get() = extra.size == 2 && extra[0].row.id == extra[1].row.id

    /** Rows that apply in the end, leaving out the rows that only ask for more rolls. */
    val results: List<TableRow>
        get() = if (extra.isEmpty()) listOf(row) else extra.flatMap { it.results }

    /** Moves the final rows lead to, in order. The caller makes them next, like a move's follow-ups. */
    val moves: List<String> get() = results.mapNotNull { it.move }
}

/** Root of tables.json. */
@Serializable
internal data class OracleTableFile(val version: Int, val tables: List<OracleTable>)

/** Texts of one table in one language: its name and the text of each row, by row id. */
@Serializable
data class OracleTableText(val name: String, val rows: Map<String, String>)

/** Root of tables.<language>.json. */
@Serializable
internal data class OracleTableTextFile(val version: Int, val tables: Map<String, OracleTableText>)
