package com.irondroid.data

/** Helpers to keep world names unique, ignoring case and surrounding spaces. */
object WorldNames {
    fun isTaken(name: String, existing: Collection<String>): Boolean =
        existing.any { it.trim().equals(name.trim(), ignoreCase = true) }

    /** [base] if it is free, otherwise "[base] (2)", "[base] (3)"... the first one not taken. */
    fun suggest(base: String, existing: Collection<String>): String {
        val trimmed = base.trim()
        if (!isTaken(trimmed, existing)) return trimmed
        return generateSequence(2) { it + 1 }.map { "$trimmed ($it)" }.first { !isTaken(it, existing) }
    }
}
