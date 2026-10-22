package com.irondroid.game

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R

/** The dice a roll needs when the player uses their own (RF-DAD-07, IRON-74). */
enum class OwnDiceKind {
    /** The action die and both challenge dice. */
    ACTION,

    /** Only the two challenge dice, as in a progress roll. */
    CHALLENGE,

    /** A 1 to 100 oracle roll, as two d10 for tens and units. */
    ORACLE,
}

/** One die to type in: its [label] and the faces to pick from. */
private class DieField(@StringRes val label: Int, val faces: IntRange)

private fun fields(kind: OwnDiceKind): List<DieField> = when (kind) {
    OwnDiceKind.ACTION -> listOf(DieField(R.string.own_dice_action, 1..6), DieField(R.string.own_dice_challenge_1, 1..10), DieField(R.string.own_dice_challenge_2, 1..10))
    OwnDiceKind.CHALLENGE -> listOf(DieField(R.string.own_dice_challenge_1, 1..10), DieField(R.string.own_dice_challenge_2, 1..10))
    OwnDiceKind.ORACLE -> listOf(DieField(R.string.own_dice_tens, 0..9), DieField(R.string.own_dice_units, 0..9))
}

/** The oracle roll that a tens and a units die show: both "0" is 100. */
internal fun percentileOf(tens: Int, units: Int): Int = (tens * 10 + units).takeIf { it > 0 } ?: 100

/**
 * Asks the player for what their own dice show before the engine rolls (IRON-74): one row of faces
 * per die. [onRoll] gets the values in the engine's order (an oracle as one 1 to 100 value), or null
 * when the player lets the app roll this time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwnDicePanel(kind: OwnDiceKind, title: String, onRoll: (List<Int>?) -> Unit) {
    val fields = remember(kind) { fields(kind) }
    val picked = remember(kind) { mutableStateListOf<Int?>().apply { repeat(fields.size) { add(null) } } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            fields.forEachIndexed { index, field ->
                Text(stringResource(field.label), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.faces.forEach { face ->
                        FilterChip(selected = picked[index] == face, onClick = { picked[index] = face }, label = { Text(face.toString()) })
                    }
                }
            }
            val values = picked.filterNotNull().takeIf { it.size == fields.size }
            if (kind == OwnDiceKind.ORACLE && values != null) {
                Text(stringResource(R.string.own_dice_oracle_total, percentileOf(values[0], values[1])), style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = values != null, onClick = {
                    values?.let { onRoll(if (kind == OwnDiceKind.ORACLE) listOf(percentileOf(it[0], it[1])) else it) }
                }) { Text(stringResource(R.string.own_dice_use)) }
                TextButton(onClick = { onRoll(null) }) { Text(stringResource(R.string.own_dice_app_rolls)) }
            }
        }
    }
}
