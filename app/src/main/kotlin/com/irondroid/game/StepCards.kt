package com.irondroid.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.engine.action.StateChange
import com.irondroid.engine.debility.Debility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * A card of the story (IRON-63, RF-NAR-03): a roll with its dice and outcome, an oracle's answer, or
 * what a choice changed, with the sheet changes it brought. Drawn with a thin ink border, no color.
 */
@Composable
fun StepCardView(card: StepCard, moveName: (String) -> String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val data = card.data
        when {
            data == null -> Text(card.summary, style = MaterialTheme.typography.bodyMedium)
            card.action == "roll_move" || card.action == "roll_progress" -> MoveCard(data, moveName)
            card.action == "ask_oracle" -> OracleCard(data)
            card.action == "roll_oracle_table" -> TableCard(data)
            card.action == "choose_option" -> CardTitle(
                data["move"]?.jsonPrimitive?.contentOrNull?.let { stringResource(R.string.card_choice, moveName(it)) } ?: stringResource(R.string.card_choice_plain),
            )
            else -> Text(card.summary, style = MaterialTheme.typography.bodyMedium)
        }
        ChangesLine(card.changes)
    }
}

@Composable
private fun CardTitle(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
    }
}

/** A move: its name and outcome, then the action die and score against the two challenge dice. */
@Composable
private fun MoveCard(data: JsonObject, moveName: (String) -> String) {
    val move = data["move"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val outcome = when (data["outcome"]?.jsonPrimitive?.contentOrNull) {
        "strong_hit" -> stringResource(R.string.outcome_strong_hit)
        "weak_hit" -> stringResource(R.string.outcome_weak_hit)
        "miss" -> stringResource(R.string.outcome_miss)
        else -> null // A move without dice, such as Pay the Price.
    }
    CardTitle(moveName(move), outcome)
    val roll = data["roll"] as? JsonObject ?: return
    val challenge = (roll["challenge_dice"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (roll["type"]?.jsonPrimitive?.contentOrNull) {
            "action" -> {
                val die = roll["action_die"]?.jsonPrimitive?.intOrNull ?: 0
                val stat = roll["stat"]?.jsonPrimitive?.intOrNull ?: 0
                val adds = roll["adds"]?.jsonPrimitive?.intOrNull ?: 0
                val score = roll["action_score"]?.jsonPrimitive?.intOrNull ?: 0
                val source = roll["source"]?.jsonPrimitive?.contentOrNull
                // The black action die and the white challenge dice of RF-UI-05.
                Die(die, filled = true)
                val bonus = buildString {
                    append(" + ").append(stat)
                    source?.let { append(" ").append(stringResource(sourceName(it))) }
                    if (adds != 0) append(if (adds > 0) " + " else " − ").append(abs(adds))
                    append(" = ").append(score)
                }
                Text(bonus, style = MaterialTheme.typography.bodyMedium)
            }
            else -> Text(
                stringResource(R.string.card_progress, roll["progress_score"]?.jsonPrimitive?.intOrNull ?: 0),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(stringResource(R.string.card_against), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        challenge.forEach { Die(it, filled = false) }
    }
    val notes = buildList {
        roll["burned_momentum"]?.jsonPrimitive?.intOrNull?.let { add(stringResource(R.string.card_burned, it)) }
        if (data["match"]?.jsonPrimitive?.booleanOrNull == true) add(stringResource(R.string.card_match))
    }
    if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
}

/** A die face: the action die filled with ink, the challenge dice drawn in outline. */
@Composable
private fun Die(value: Int, filled: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .background(if (filled) ink else Color.Transparent, RoundedCornerShape(5.dp))
            .border(1.dp, ink, RoundedCornerShape(5.dp)),
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) MaterialTheme.colorScheme.surface else ink,
        )
    }
}

/** A yes/no question: the question, the answer and the odds with the roll. */
@Composable
private fun OracleCard(data: JsonObject) {
    val yes = data["answer"]?.jsonPrimitive?.contentOrNull == "yes"
    val answer = stringResource(if (yes) R.string.oracle_yes else R.string.oracle_no)
    val shown = if (data["extreme"]?.jsonPrimitive?.booleanOrNull == true) stringResource(R.string.oracle_extreme, answer) else answer
    CardTitle(stringResource(R.string.card_oracle), shown)
    data["question"]?.jsonPrimitive?.contentOrNull?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic) }
    val odds = data["odds"]?.jsonPrimitive?.contentOrNull?.let { oddsName(it) }?.let { stringResource(it) }
    val roll = data["roll"]?.jsonPrimitive?.intOrNull
    if (odds != null && roll != null) {
        Text(stringResource(R.string.card_odds_roll, odds, roll), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A roll on an oracle table, such as a name or Pay the Price, with the extra rolls a row asked for. */
@Composable
private fun TableCard(data: JsonObject) {
    val table = data["table"]?.jsonPrimitive?.contentOrNull.orEmpty()
    CardTitle(data["name"]?.jsonPrimitive?.contentOrNull ?: table.replace('_', ' ').replaceFirstChar { it.uppercase() }, data["roll"]?.jsonPrimitive?.intOrNull?.toString())
    fun rows(roll: JsonObject): List<String> =
        listOfNotNull(roll["text"]?.jsonPrimitive?.contentOrNull) + (roll["extra"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap(::rows)
    rows(data).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/** The sheet changes a card brought, such as "−1 health · +2 momentum" (IRON-63). */
@Composable
private fun ChangesLine(changes: List<StateChange>) {
    val parts = changes.mapNotNull { change -> changeText(change) }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun changeText(change: StateChange): String? {
    fun int(value: JsonElement) = (value as? JsonPrimitive)?.intOrNull
    fun signed(delta: Int) = (if (delta > 0) "+" else "−") + abs(delta)
    val field = change.field
    val delta = int(change.after)?.let { after -> int(change.before)?.let { after - it } }
    val meter = when (field) {
        "health" -> R.string.change_health
        "spirit" -> R.string.change_spirit
        "supply" -> R.string.change_supply
        "momentum" -> R.string.change_momentum
        "experience" -> R.string.change_experience
        else -> null
    }
    return when {
        meter != null && delta != null -> stringResource(meter, signed(delta))
        field == "debilities" -> {
            fun names(value: JsonElement) = (value as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
            val before = names(change.before)
            val after = names(change.after)
            (after - before).map { stringResource(R.string.change_debility_marked, debilityLabel(it)) }
                .plus((before - after).map { stringResource(R.string.change_debility_cleared, debilityLabel(it)) })
                .joinToString(" · ")
                .ifEmpty { null }
        }
        field.startsWith("tracks.") && field.endsWith(".ticks") && delta != null -> stringResource(R.string.change_progress, signed(delta))
        field.startsWith("tracks.") && field.count { it == '.' } == 1 -> (change.after as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?.let { stringResource(R.string.change_track_new, it) }
        field == "is_ended" -> stringResource(R.string.change_ended)
        else -> null // Stats and pending bonuses change through other screens.
    }
}

@Composable
private fun debilityLabel(name: String): String =
    Debility.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { stringResource(debilityName(it)) } ?: name

/** The label of the yes/no oracle's odds, as the engine names them. */
private fun oddsName(odds: String): Int? = when (odds) {
    "almost_certain" -> R.string.odds_almost_certain
    "likely" -> R.string.odds_likely
    "fifty_fifty" -> R.string.odds_fifty_fifty
    "unlikely" -> R.string.odds_unlikely
    "small_chance" -> R.string.odds_small_chance
    else -> null
}
