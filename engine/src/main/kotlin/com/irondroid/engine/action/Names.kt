package com.irondroid.engine.action

/** The candidate nearest to [name], when it is close enough to be a typo; for "did you mean" hints. */
internal fun closestName(name: String, candidates: List<String>): String? =
    candidates
        .map { it to editDistance(it, name.lowercase()) }
        .filter { (candidate, distance) -> distance <= maxOf(2, candidate.length / 4) }
        .minByOrNull { it.second }?.first

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        previous = current
    }
    return previous[b.length]
}
