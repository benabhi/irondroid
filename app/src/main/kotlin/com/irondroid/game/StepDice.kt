package com.irondroid.game

import com.irondroid.ai.narrator.NarratorStep
import com.irondroid.dice.ThrownDie
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The dice a turn's [step] rolled, from the result the engine already computed (RF-DAD-04, IRON-72),
 * so the 3D dice show it; null when the step rolled none, such as burning momentum or a failed call.
 */
internal fun diceOf(step: NarratorStep): List<ThrownDie>? {
    if (!step.succeeded) return null
    val data = step.data ?: return null
    return when (step.action) {
        "roll_move", "roll_progress" -> moveDice(data["roll"] as? JsonObject ?: return null)
        "ask_oracle", "roll_oracle_table" -> data["roll"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..100 }?.let(ThrownDie::percentile)
        else -> null
    }
}

private fun moveDice(roll: JsonObject): List<ThrownDie>? {
    val challenge = (roll["challenge_dice"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
    if (challenge?.size != 2 || challenge.any { it !in 1..10 }) return null
    return when (roll["type"]?.jsonPrimitive?.contentOrNull) {
        "action" -> roll["action_die"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..6 }
            ?.let { ThrownDie.actionRoll(it, challenge[0], challenge[1]) }
        "progress" -> ThrownDie.progressRoll(challenge[0], challenge[1])
        else -> null
    }
}
