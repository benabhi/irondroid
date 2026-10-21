package com.irondroid.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.dice.MatchableDice
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.oracle.OracleTableCatalog
import com.irondroid.engine.oracle.TableRoll

/**
 * Development-only screen (IRON-103) to roll on every oracle table and read the result in
 * [language], until the narrator rolls on them during play (IRON-51). It can also force a match
 * on the next action roll of [gameDice], to try the narrator's twists (IRON-55).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OracleTablesScreen(oracles: OracleTableCatalog, language: String, gameDice: MatchableDice, onBack: () -> Unit) {
    var matchArmed by remember { mutableStateOf(gameDice.matchPending) }
    val dice = remember { SeededDice.fromSeed(System.nanoTime()) }
    val rolls = remember { mutableStateListOf<TableRoll>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Text(stringResource(R.string.oracle_tables_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        }
        // The game's dice, not this screen's: the next action roll in a campaign comes out a match (IRON-55).
        OutlinedButton(onClick = {
            gameDice.forceMatch()
            matchArmed = true
        }) { Text(stringResource(R.string.oracle_tables_force_match)) }
        if (matchArmed) Text(stringResource(R.string.oracle_tables_force_match_armed), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.oracle_tables_hint), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            oracles.tables.forEach { table ->
                OutlinedButton(onClick = { rolls.add(0, oracles.roll(table.id, dice)) }) {
                    Text(oracles.text(table.id, language)?.name ?: table.id)
                }
            }
        }
        rolls.forEach { roll ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val texts = oracles.text(roll.table, language)
                    Text(
                        stringResource(R.string.oracle_tables_result, texts?.name ?: roll.table, roll.roll, texts?.rows?.get(roll.row.id) ?: roll.row.id),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ExtraRolls(roll, oracles, language, depth = 1)
                    if (roll.isRepeated) Text(stringResource(R.string.oracle_tables_repeated), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The extra rolls a row asked for, indented by [depth]. */
@Composable
private fun ExtraRolls(roll: TableRoll, oracles: OracleTableCatalog, language: String, depth: Int) {
    roll.extra.forEach { extra ->
        Text(
            stringResource(R.string.oracle_tables_extra, extra.roll, oracles.text(extra.table, language)?.rows?.get(extra.row.id) ?: extra.row.id),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = (16 * depth).dp),
        )
        ExtraRolls(extra, oracles, language, depth + 1)
    }
}
