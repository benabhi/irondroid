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
import androidx.compose.material3.OutlinedButton
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
import com.irondroid.ai.narrator.MoveDecision
import com.irondroid.ai.narrator.MoveProposal
import com.irondroid.engine.character.Character
import com.irondroid.engine.action.ChoiceOption
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
        val pendingMove = player.pendingMove
        when {
            !player.hasNarrator -> Text(stringResource(R.string.game_no_key), color = MaterialTheme.colorScheme.error)
            pendingMove != null -> MovePanel(pendingMove.proposal, player)
            choice != null && live == null -> ChoicePanel(choice, player, sheet.momentum.value) { picked, text, own -> scope.launch { player.choose(picked, text, own) } }
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

/**
 * The options of [choice], picked by the player (RF-ENV-11), with the narrator's suggestion marked
 * and why. Choices of several options wait for all of them. Pay the Price (RF-ENV-07) shows the
 * consequence the narrator proposes and lets the player write their own instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoicePanel(choice: PendingChoice, player: TurnPlayer, momentum: Int, onChoose: (List<String>, String, Boolean) -> Unit) {
    var picked by remember(choice.id) { mutableStateOf(emptyList<String>()) }
    var writing by remember(choice.id) { mutableStateOf(false) }
    var own by remember(choice.id) { mutableStateOf("") }
    val suggestion = choice.suggestion
    val isBurn = choice.options.map { it.id }.toSet() == setOf(BURN, KEEP)
    val isPayThePrice = choice.move == PAY_THE_PRICE && choice.options.any { it.id == OBVIOUS_OUTCOME }
    val moveName = choice.move?.let(player::moveName).orEmpty()

    @Composable
    fun label(option: ChoiceOption): String = when {
        isBurn && option.id == BURN -> stringResource(R.string.choice_burn)
        isBurn -> stringResource(R.string.choice_keep)
        isPayThePrice && option.id == OBVIOUS_OUTCOME && suggestion?.proposal != null -> stringResource(R.string.choice_proposal, suggestion.proposal!!)
        else -> option.text
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    isBurn -> stringResource(R.string.choice_burn_title, moveName, momentum)
                    choice.count == 1 -> stringResource(R.string.choice_title_one, moveName)
                    else -> stringResource(R.string.choice_title_many, moveName, choice.count)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choice.options.forEach { option ->
                    val suggested = suggestion?.optionIds?.contains(option.id) == true
                    val text = label(option)
                    FilterChip(
                        selected = option.id in picked && !writing,
                        onClick = {
                            writing = false
                            picked = when {
                                option.id in picked -> picked - option.id
                                choice.count == 1 -> listOf(option.id)
                                picked.size < choice.count -> picked + option.id
                                else -> picked
                            }
                        },
                        label = { Text(if (suggested) stringResource(R.string.choice_suggested, text) else text) },
                    )
                }
                if (isPayThePrice) {
                    FilterChip(
                        selected = writing,
                        onClick = {
                            writing = !writing
                            picked = emptyList()
                        },
                        label = { Text(stringResource(R.string.choice_write_own)) },
                    )
                }
            }
            if (suggestion != null) {
                Text(stringResource(R.string.choice_why, suggestion.why), style = MaterialTheme.typography.bodySmall)
            }
            if (writing) {
                OutlinedTextField(
                    value = own,
                    onValueChange = { own = it },
                    label = { Text(stringResource(R.string.choice_write_own_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
            val labels = choice.options.associate { it.id to label(it) }
            Button(
                enabled = if (writing) own.isNotBlank() else picked.size == choice.count,
                onClick = {
                    // Writing their own consequence is the obvious outcome, told by the player.
                    if (writing) onChoose(listOf(OBVIOUS_OUTCOME), own.trim(), true)
                    else onChoose(picked, picked.joinToString(" · ") { labels.getValue(it) }, false)
                },
            ) { Text(stringResource(R.string.game_choose)) }
        }
    }
}

/** Option ids of the engine's choices that the panel shows its own way. */
private const val BURN = "burn"
private const val KEEP = "keep"
private const val PAY_THE_PRICE = "pay_the_price"
private const val OBVIOUS_OUTCOME = "obvious_outcome"

/**
 * The move the narrator wants to roll (RF-ENV-04): the player rolls it, changes its stat or the
 * move, or skips the roll.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovePanel(proposal: MoveProposal, player: TurnPlayer) {
    var move by remember(proposal) { mutableStateOf(proposal.move) }
    var source by remember(proposal) { mutableStateOf(proposal.source) }
    var picking by remember(proposal) { mutableStateOf(false) }
    val sources = player.sources(move)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val name = player.moveName(move)
            val shownSource = source?.takeIf { it in sources }
            Text(
                if (shownSource != null) {
                    stringResource(R.string.move_confirm_title_stat, name, stringResource(sourceName(shownSource)))
                } else {
                    stringResource(R.string.move_confirm_title, name)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (sources.size > 1) {
                Text(stringResource(R.string.move_confirm_stat), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { option ->
                        FilterChip(selected = option == source, onClick = { source = option }, label = { Text(stringResource(sourceName(option))) })
                    }
                }
            }
            if (picking) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    player.rollableMoves.forEach { (id, label) ->
                        FilterChip(
                            selected = id == move,
                            onClick = {
                                move = id
                                val allowed = player.sources(id)
                                if (source !in allowed) source = allowed.firstOrNull()
                                picking = false
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A move that picks its own source keeps whatever the narrator sent.
                val rolled = if (sources.isEmpty()) proposal.source.takeIf { move == proposal.move } else source
                Button(onClick = { player.answer(MoveDecision.Roll(move, rolled)) }) { Text(stringResource(R.string.move_confirm_roll)) }
                OutlinedButton(onClick = { picking = !picking }) { Text(stringResource(R.string.move_confirm_other)) }
                TextButton(onClick = { player.answer(MoveDecision.Skip) }) { Text(stringResource(R.string.move_confirm_skip)) }
            }
        }
    }
}

/** The name of a roll source, such as `edge`. */
private fun sourceName(source: String): Int = when (source) {
    "edge" -> R.string.stat_edge
    "heart" -> R.string.stat_heart
    "iron" -> R.string.stat_iron
    "shadow" -> R.string.stat_shadow
    "wits" -> R.string.stat_wits
    "health" -> R.string.meter_health
    "spirit" -> R.string.meter_spirit
    else -> R.string.meter_supply
}
