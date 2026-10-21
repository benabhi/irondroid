package com.irondroid.data

import androidx.room.TypeConverter
import com.irondroid.engine.debility.Debility

/** Column types Room does not store on its own. */
class Converters {
    /** Marked debilities as their names, comma separated, in declaration order; empty when none. */
    @TypeConverter
    fun debilitiesToText(debilities: Set<Debility>): String =
        debilities.sortedBy { it.ordinal }.joinToString(",") { it.name }

    @TypeConverter
    fun textToDebilities(text: String): Set<Debility> =
        if (text.isEmpty()) emptySet() else text.split(",").map { Debility.valueOf(it) }.toSet()
}
