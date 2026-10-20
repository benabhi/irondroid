package com.irondroid.engine.debility

import com.irondroid.engine.momentum.Momentum
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Group of a debility, which decides how it is cleared. */
enum class DebilityKind {
    /** Cleared by moves such as Heal, Make Camp or Resupply. */
    CONDITION,

    /** Permanent once marked. */
    BANE,

    /** Cleared when the quest tied to it is completed. */
    BURDEN,
}

/** The eight debilities of Ironsworn (RF-MOT-09). */
@Serializable
enum class Debility(val kind: DebilityKind) {
    @SerialName("wounded") WOUNDED(DebilityKind.CONDITION),
    @SerialName("shaken") SHAKEN(DebilityKind.CONDITION),
    @SerialName("unprepared") UNPREPARED(DebilityKind.CONDITION),
    @SerialName("encumbered") ENCUMBERED(DebilityKind.CONDITION),
    @SerialName("maimed") MAIMED(DebilityKind.BANE),
    @SerialName("corrupted") CORRUPTED(DebilityKind.BANE),
    @SerialName("cursed") CURSED(DebilityKind.BURDEN),
    @SerialName("tormented") TORMENTED(DebilityKind.BURDEN),
}

/** The debilities a character has marked. Immutable: marking or clearing returns a new set. */
data class Debilities(val marked: Set<Debility> = emptySet()) {
    val count: Int get() = marked.size

    operator fun contains(debility: Debility): Boolean = debility in marked

    /** Marks [debility]; marking one that is already marked changes nothing. */
    fun mark(debility: Debility): Debilities = copy(marked = marked + debility)

    /** Clears [debility]. Banes are permanent and cannot be cleared. */
    fun clear(debility: Debility): Debilities {
        require(debility.kind != DebilityKind.BANE) { "$debility is a bane and cannot be cleared" }
        return copy(marked = marked - debility)
    }

    /** [momentum] with the maximum and reset that match these debilities. */
    fun applyTo(momentum: Momentum): Momentum = momentum.withDebilities(count)
}
