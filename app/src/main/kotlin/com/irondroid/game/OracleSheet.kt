package com.irondroid.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R

/**
 * The yes/no oracle (IRON-65, RF-ORA-01): a sheet with the player's question and the five odds with
 * their chance of a yes. The answer is narrated in the scene or, without the narrator, shown at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OracleSheet(
    canNarrate: Boolean,
    onAsk: (question: String, odds: String, said: String) -> Unit,
    onAnswerOnly: (question: String, odds: String, said: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var question by rememberSaveable { mutableStateOf("") }
    var odds by rememberSaveable { mutableStateOf(FIFTY_FIFTY) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.oracle_sheet_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text(stringResource(R.string.request_oracle_question)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Text(
                stringResource(R.string.oracle_sheet_odds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            ODDS.forEach { option ->
                val selected = option.id == odds
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .clickable { odds = option.id }
                        .heightIn(min = 48.dp)
                        .padding(end = 12.dp),
                ) {
                    RadioButton(selected = selected, onClick = { odds = option.id })
                    Text(stringResource(option.label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.oracle_sheet_chance, option.yesChance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val asked = question.trim()
            val said = stringResource(R.string.request_oracle_said, asked, stringResource(ODDS.first { it.id == odds }.label))
            if (canNarrate) {
                Button(
                    enabled = asked.isNotEmpty(),
                    onClick = { onAsk(asked, odds, said) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
                ) { Text(stringResource(R.string.oracle_sheet_ask)) }
            }
            OutlinedButton(
                enabled = asked.isNotEmpty(),
                onClick = { onAnswerOnly(asked, odds, said) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.oracle_sheet_answer_only)) }
            Text(stringResource(R.string.oracle_sheet_answer_only_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One odds of the yes/no oracle: its engine [id], its [label] and the chance of a yes, in percent. */
private class OddsOption(val id: String, val label: Int, val yesChance: Int)

private const val FIFTY_FIFTY = "fifty_fifty"

/** The odds as the engine names them, most likely first; the chances match its yes/no oracle table. */
private val ODDS = listOf(
    OddsOption("almost_certain", R.string.odds_almost_certain, 90),
    OddsOption("likely", R.string.odds_likely, 75),
    OddsOption(FIFTY_FIFTY, R.string.odds_fifty_fifty, 50),
    OddsOption("unlikely", R.string.odds_unlikely, 25),
    OddsOption("small_chance", R.string.odds_small_chance, 10),
)
