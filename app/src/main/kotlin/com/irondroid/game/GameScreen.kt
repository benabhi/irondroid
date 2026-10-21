package com.irondroid.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.engine.character.Character
import com.irondroid.engine.action.PendingChoice
import com.irondroid.settings.aiErrorText
import kotlinx.coroutines.launch

/**
 * The play screen by text (IRON-52): the story so far as a conversation with the narrator, the
 * player's input at the bottom, and the options of a choice when one is open (RF-ENV-11).
 */
@Composable
fun GameScreen(
    title: String,
    characterName: String,
    sheet: Character,
    player: TurnPlayer,
    onOpenSheet: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val live = player.live
    val shown = player.turns.size + (if (live != null) 1 else 0)
    LaunchedEffect(shown, live?.narration?.length) {
        if (shown > 0) list.animateScrollToItem(shown - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.game_header,
                        characterName,
                        stringResource(R.string.campaign_meters, sheet.meters.health.value, sheet.meters.spirit.value, sheet.meters.supply.value, sheet.momentum.value),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onOpenSheet) { Text(stringResource(R.string.game_sheet)) }
        }

        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (player.loaded && player.turns.isEmpty() && live == null) {
                item { Text(stringResource(R.string.game_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(player.turns) { TurnView(it, playing = false) }
            if (live != null) item { TurnView(live, playing = true) }
        }

        val choice = player.choice
        when {
            !player.hasNarrator -> Text(stringResource(R.string.game_no_key), color = MaterialTheme.colorScheme.error)
            choice != null && live == null -> ChoicePanel(choice) { picked -> scope.launch { player.choose(picked) } }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.game_input)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Button(
                    enabled = !player.busy && input.isNotBlank(),
                    onClick = {
                        val text = input
                        input = ""
                        scope.launch { player.play(text) }
                    },
                ) { Text(stringResource(R.string.game_send)) }
            }
        }
    }
}

@Composable
private fun TurnView(turn: PlayedTurn, playing: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        turn.playerInput?.let { said ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
            ) {
                Text(said, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
        }
        turn.steps.forEach { Text(stringResource(R.string.game_step, it), style = MaterialTheme.typography.bodySmall) }
        when {
            turn.narration.isNotBlank() -> Text(turn.narration, style = MaterialTheme.typography.bodyLarge)
            playing -> Text(stringResource(R.string.game_thinking), style = MaterialTheme.typography.bodyMedium)
        }
        turn.error?.let { Text(stringResource(R.string.game_error, stringResource(aiErrorText(it.kind))), color = MaterialTheme.colorScheme.error) }
    }
}

/** The options of [choice], picked by the player. Choices of several options wait for all of them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoicePanel(choice: PendingChoice, onChoose: (List<String>) -> Unit) {
    var picked by remember(choice.id) { mutableStateOf(emptyList<String>()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(choice.prompt, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choice.options.forEach { option ->
                    FilterChip(
                        selected = option.id in picked,
                        onClick = {
                            picked = when {
                                option.id in picked -> picked - option.id
                                choice.count == 1 -> listOf(option.id)
                                picked.size < choice.count -> picked + option.id
                                else -> picked
                            }
                        },
                        label = { Text(option.text) },
                    )
                }
            }
            Button(enabled = picked.size == choice.count, onClick = { onChoose(picked) }) {
                Text(stringResource(R.string.game_choose))
            }
        }
    }
}
