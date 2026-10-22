package com.irondroid.game

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.irondroid.engine.debility.Debility
import com.irondroid.theme.InkIcons
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.math.max
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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    /** Show what each turn cost under its narration (IRON-111). */
    showMetrics: Boolean,
    onOpenSheet: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var correcting by rememberSaveable { mutableStateOf(false) }
    var requesting by rememberSaveable { mutableStateOf<String?>(null) }
    var correction by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val live = player.live
    val shown = player.turns.size + (if (live != null) 1 else 0)
    // Follow the story as it grows while the player is at the bottom; reading further up stops it (IRON-62).
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, more) -> if (scrolling) follow = !more }
    }
    LaunchedEffect(shown) { follow = true }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.totalItemsCount to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0) }.collect { (count, _) ->
            if (follow && count > 0 && list.canScrollForward) list.scrollToItem(count - 1, scrollOffset = Int.MAX_VALUE / 2)
        }
    }

    val choice = player.choice
    val pendingMove = player.pendingMove
    // Take back the last turn, or have it narrated again with a correction (RF-ENV-09).
    val canTakeBack = player.canTakeBack && !player.busy && !correcting && requesting == null
    val idle = player.hasNarrator && !player.busy && pendingMove == null && choice == null && !correcting && requesting == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        StatusBar(title, characterName, sheet, onBack = onBack, onOpenSheet = onOpenSheet) {
            GameMenu(
                onOpenSheet = onOpenSheet,
                onUndo = if (canTakeBack) ({ scope.launch { player.undoLastTurn() } }) else null,
                onCorrect = if (canTakeBack && player.hasNarrator) ({ correcting = true }) else null,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (player.loaded && player.turns.isEmpty() && live == null) {
                item { Text(stringResource(R.string.game_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(player.turns) { TurnView(it, playing = false, player::moveName, showMetrics) }
            // While the player confirms a roll, the narrator waits for them, not the other way round.
            if (live != null) item { TurnView(live.copy(activity = live.activity.takeIf { pendingMove == null }), playing = true, player::moveName, showMetrics) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BottomPanel(
                player, sheet, choice, pendingMove, live, correcting, requesting, idle,
                input = input,
                onInput = { input = it },
                correction = correction,
                onCorrection = { correction = it },
                onSend = {
                    val text = input
                    input = ""
                    scope.launch { player.play(text) }
                },
                onCorrect = {
                    val text = correction
                    correction = ""
                    correcting = false
                    scope.launch { player.correctLastTurn(text) }
                },
                onCancelCorrect = { correcting = false },
                onRequest = { requesting = it },
                onRequestMove = { move, source, said ->
                    requesting = null
                    scope.launch { player.requestMove(move, source, said) }
                },
                onRequestOracle = { question, odds, said ->
                    requesting = null
                    scope.launch { player.requestOracle(question, odds, said) }
                },
                onChoose = { picked, text, own -> scope.launch { player.choose(picked, text, own) } },
            )
        }
    }
}

/**
 * The compact bar on top (SRD 3.15): back, the campaign and character, the menu, and health,
 * spirit, supply and momentum always in sight with the marked debilities. Tapping the meters opens
 * the sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBar(
    title: String,
    characterName: String,
    sheet: Character,
    onBack: () -> Unit,
    onOpenSheet: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(InkIcons.Back, contentDescription = stringResource(R.string.common_back)) }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(characterName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            menu()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSheet)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                MeterCell(R.string.meter_health, sheet.meters.health.value.toString(), Modifier.weight(1f))
                MeterCell(R.string.meter_spirit, sheet.meters.spirit.value.toString(), Modifier.weight(1f))
                MeterCell(R.string.meter_supply, sheet.meters.supply.value.toString(), Modifier.weight(1f))
                MeterCell(R.string.meter_momentum, String.format(LocalConfiguration.current.locales[0], "%+d", sheet.momentum.value), Modifier.weight(1f))
            }
            val debilities = Debility.entries.filter { it in sheet.debilities }
            if (debilities.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    debilities.forEach { debility ->
                        Text(
                            stringResource(debilityName(debility)),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One meter of the status bar: its value large, its name small under it. */
@Composable
private fun MeterCell(@StringRes label: Int, value: String, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        // Lining figures: Cormorant's old-style digits dip below the line and look uneven side by side.
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "lnum"))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The less frequent actions, out of the way: the sheet, undo and correct (RF-ENV-09). Null hides nothing, it disables. */
@Composable
private fun GameMenu(onOpenSheet: () -> Unit, onUndo: (() -> Unit)?, onCorrect: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(InkIcons.More, contentDescription = stringResource(R.string.game_menu)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.game_sheet)) }, onClick = { open = false; onOpenSheet() })
            DropdownMenuItem(text = { Text(stringResource(R.string.game_undo)) }, enabled = onUndo != null, onClick = { open = false; onUndo?.invoke() })
            DropdownMenuItem(text = { Text(stringResource(R.string.game_correct)) }, enabled = onCorrect != null, onClick = { open = false; onCorrect?.invoke() })
        }
    }
}

/** The name of a debility, as the status bar shows it. */
private fun debilityName(debility: Debility): Int = when (debility) {
    Debility.WOUNDED -> R.string.debility_wounded
    Debility.SHAKEN -> R.string.debility_shaken
    Debility.UNPREPARED -> R.string.debility_unprepared
    Debility.ENCUMBERED -> R.string.debility_encumbered
    Debility.MAIMED -> R.string.debility_maimed
    Debility.CORRUPTED -> R.string.debility_corrupted
    Debility.CURSED -> R.string.debility_cursed
    Debility.TORMENTED -> R.string.debility_tormented
}

/**
 * The bottom of the screen: what the turn waits for (a move to confirm, a choice, a correction or a
 * request) or, when nothing does, the bottom bar of the SRD: move and oracle at one tap (RF-UI-07,
 * RF-ENV-05) and the player's text.
 */
@Composable
private fun BottomPanel(
    player: TurnPlayer,
    sheet: Character,
    choice: PendingChoice?,
    pendingMove: TurnPlayer.PendingMove?,
    live: PlayedTurn?,
    correcting: Boolean,
    requesting: String?,
    idle: Boolean,
    input: String,
    onInput: (String) -> Unit,
    correction: String,
    onCorrection: (String) -> Unit,
    onSend: () -> Unit,
    onCorrect: () -> Unit,
    onCancelCorrect: () -> Unit,
    onRequest: (String?) -> Unit,
    onRequestMove: (move: String, source: String?, said: String) -> Unit,
    onRequestOracle: (question: String, odds: String, said: String) -> Unit,
    onChoose: (List<String>, String, Boolean) -> Unit,
) {
        when {
            !player.hasNarrator -> Text(stringResource(R.string.game_no_key), color = MaterialTheme.colorScheme.error)
            pendingMove != null -> MovePanel(pendingMove.proposal, player)
            requesting == REQUEST_MOVE -> MoveRequestPanel(player, onRoll = onRequestMove, onCancel = { onRequest(NO_REQUEST) })
            requesting == REQUEST_ORACLE -> OracleRequestPanel(onAsk = onRequestOracle, onCancel = { onRequest(NO_REQUEST) })
            correcting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = correction,
                    onValueChange = onCorrection,
                    label = { Text(stringResource(R.string.game_correct_input)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Column {
                    Button(enabled = !player.busy && correction.isNotBlank(), onClick = onCorrect) { Text(stringResource(R.string.game_correct_send)) }
                    TextButton(onClick = onCancelCorrect) { Text(stringResource(R.string.game_correct_cancel)) }
                }
            }
            choice != null && live == null -> ChoicePanel(choice, player, sheet.momentum.value, onChoose)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Ask for a move or the oracle directly, without waiting for the narrator to decide (RF-ENV-05).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = idle, onClick = { onRequest(REQUEST_MOVE) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.request_move))
                    }
                    OutlinedButton(enabled = idle, onClick = { onRequest(REQUEST_ORACLE) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.request_oracle))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInput,
                        placeholder = { Text(stringResource(R.string.game_input)) },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                    )
                    FilledIconButton(
                        enabled = !player.busy && input.isNotBlank(),
                        onClick = onSend,
                        modifier = Modifier.size(52.dp),
                    ) { Icon(InkIcons.Send, contentDescription = stringResource(R.string.game_send)) }
                }
            }
        }
}

@Composable
private fun TurnView(turn: PlayedTurn, playing: Boolean, moveName: (String) -> String, showMetrics: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turn.playerInput?.let { said ->
            Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxWidth().padding(start = 48.dp)) {
                Text(
                    said,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        turn.steps.forEach {
            Text(stringResource(R.string.game_step, stepLine(it, moveName)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (turn.narration.isNotBlank()) {
            Text(
                if (playing) revealed(turn.narration) else turn.narration,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp),
            )
        }
        if (playing) turn.activity?.let { ActivityLine(it) }
        turn.error?.let { Text(stringResource(R.string.game_error, stringResource(aiErrorText(it.kind))), color = MaterialTheme.colorScheme.error) }
        if (showMetrics) turn.metrics?.let { MetricsLine(it) }
    }
}

/**
 * [text] as it streams in, shown at a steady pace instead of in jumps (IRON-62): about 60 letters
 * a second, faster the further behind it is, so it never lags much.
 */
@Composable
private fun revealed(text: String): String {
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        while (shown < text.length) {
            shown += max(1, (text.length - shown) / 12)
            delay(16)
        }
    }
    return text.take(shown)
}

/** What the narrator is doing while the player waits (IRON-62), gently pulsing. */
@Composable
private fun ActivityLine(activity: NarratorActivity) {
    val pulse = rememberInfiniteTransition(label = "activity")
    val alpha by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    val label = when (activity) {
        NarratorActivity.Thinking -> R.string.game_thinking
        is NarratorActivity.Acting -> when (activity.action) {
            "roll_move", "roll_progress" -> R.string.game_acting_roll
            "ask_oracle", "roll_oracle_table" -> R.string.game_acting_oracle
            "create_progress_track", "mark_progress" -> R.string.game_acting_track
            else -> R.string.game_acting_other
        }
    }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    )
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

/**
 * The line a history card shows, in the player's language: a move with its stat, outcome and a
 * match when the challenge dice matched (RF-ENV-06), or an oracle's answer. Other cards keep the
 * engine's summary.
 */
@Composable
private fun stepLine(card: StepCard, moveName: (String) -> String): String {
    val data = card.data ?: return card.summary
    return when (card.action) {
        "roll_move", "roll_progress" -> {
            val move = data["move"]?.jsonPrimitive?.contentOrNull ?: return card.summary
            val outcome = when (data["outcome"]?.jsonPrimitive?.contentOrNull) {
                "strong_hit" -> R.string.outcome_strong_hit
                "weak_hit" -> R.string.outcome_weak_hit
                "miss" -> R.string.outcome_miss
                else -> return moveName(move) // A move without dice, such as Pay the Price.
            }
            val source = (data["roll"] as? JsonObject)?.get("source")?.jsonPrimitive?.contentOrNull
            val line = if (source != null) {
                stringResource(R.string.step_move_stat, moveName(move), stringResource(sourceName(source)), stringResource(outcome))
            } else {
                stringResource(R.string.step_move, moveName(move), stringResource(outcome))
            }
            if (data["match"]?.jsonPrimitive?.booleanOrNull == true) stringResource(R.string.step_match, line) else line
        }
        "ask_oracle" -> {
            val question = data["question"]?.jsonPrimitive?.contentOrNull ?: return card.summary
            val yes = data["answer"]?.jsonPrimitive?.contentOrNull == "yes"
            val answer = stringResource(if (yes) R.string.oracle_yes else R.string.oracle_no)
            val shown = if (data["extreme"]?.jsonPrimitive?.booleanOrNull == true) stringResource(R.string.oracle_extreme, answer) else answer
            stringResource(R.string.step_oracle, question, shown)
        }
        else -> card.summary
    }
}

/** The move the player asks for themselves (RF-ENV-05): a move with dice and, when it lets them pick, its stat. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveRequestPanel(player: TurnPlayer, onRoll: (move: String, source: String?, said: String) -> Unit, onCancel: () -> Unit) {
    var move by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.request_move_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                player.rollableMoves.forEach { (id, label) ->
                    FilterChip(
                        selected = id == move,
                        onClick = {
                            move = id
                            source = player.sources(id).firstOrNull()
                        },
                        label = { Text(label) },
                    )
                }
            }
            val chosen = move
            val sources = chosen?.let(player::sources).orEmpty()
            if (sources.size > 1) {
                Text(stringResource(R.string.move_confirm_stat), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { option ->
                        FilterChip(selected = option == source, onClick = { source = option }, label = { Text(stringResource(sourceName(option))) })
                    }
                }
            }
            val said = chosen?.let {
                val stat = source?.takeIf { it in sources }
                if (stat != null) {
                    stringResource(R.string.request_move_said_stat, player.moveName(it), stringResource(sourceName(stat)))
                } else {
                    stringResource(R.string.request_move_said, player.moveName(it))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = chosen != null, onClick = { onRoll(chosen!!, source?.takeIf { it in sources }, said!!) }) {
                    Text(stringResource(R.string.move_confirm_roll))
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.game_correct_cancel)) }
            }
        }
    }
}

/** A yes/no question the player asks the oracle themselves, with its odds (RF-ENV-05, RF-ORA-01). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OracleRequestPanel(onAsk: (question: String, odds: String, said: String) -> Unit, onCancel: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    var odds by rememberSaveable { mutableStateOf(FIFTY_FIFTY) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text(stringResource(R.string.request_oracle_question)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ODDS.forEach { (id, label) ->
                    FilterChip(selected = id == odds, onClick = { odds = id }, label = { Text(stringResource(label)) })
                }
            }
            val said = stringResource(R.string.request_oracle_said, question.trim(), stringResource(ODDS.getValue(odds)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = question.isNotBlank(), onClick = { onAsk(question.trim(), odds, said) }) {
                    Text(stringResource(R.string.request_oracle_ask))
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.game_correct_cancel)) }
            }
        }
    }
}

private const val FIFTY_FIFTY = "fifty_fifty"

/** The odds of the yes/no oracle, as the engine names them, with their labels. */
private val ODDS = linkedMapOf(
    "almost_certain" to R.string.odds_almost_certain,
    "likely" to R.string.odds_likely,
    FIFTY_FIFTY to R.string.odds_fifty_fifty,
    "unlikely" to R.string.odds_unlikely,
    "small_chance" to R.string.odds_small_chance,
)

private val NO_REQUEST: String? = null
private const val REQUEST_MOVE = "move"
private const val REQUEST_ORACLE = "oracle"

/** What a turn cost, in small grey text under its narration (IRON-111). Always in US dollars. */
@Composable
private fun MetricsLine(metrics: TurnMetrics) {
    val locale = LocalConfiguration.current.locales[0]
    fun tokens(count: Int) = if (count >= 1000) String.format(locale, "%.1f k", count / 1000.0) else count.toString()
    val parts = buildList {
        add(stringResource(R.string.metrics_tokens, tokens(metrics.promptTokens), tokens(metrics.cachedTokens), tokens(metrics.completionTokens)))
        add(stringResource(R.string.metrics_time, String.format(locale, "%.1f", metrics.totalMs / 1000.0)))
        metrics.costUsd?.let { add(stringResource(R.string.metrics_cost, String.format(locale, "%.4f", it))) }
        metrics.effort?.let { effort ->
            val label = when (effort) {
                "high" -> R.string.settings_effort_high
                "max" -> R.string.settings_effort_max
                else -> R.string.settings_effort_low
            }
            add(stringResource(R.string.metrics_effort, stringResource(label).lowercase(locale)))
        }
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
