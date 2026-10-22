package com.irondroid.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.engine.move.MoveCategory
import java.text.Normalizer

/**
 * The move picker (IRON-64, RF-UI-11): a sheet from the bottom with a search box, the moves the AI
 * suggests for the scene first, then every move with dice by category, each with when it is made.
 * Picking one shows its stats, if the player chooses, and the roll button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MovePicker(player: TurnPlayer, onRoll: (move: String, source: String?, said: String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { player.loadSuggestions() }
    var query by remember { mutableStateOf("") }
    var move by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    val pick = { id: String ->
        move = id
        source = player.sources(id).firstOrNull()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.request_move_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.move_search)) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            val key = plain(query)
            val shown = player.pickerMoves.filter { key.isEmpty() || key in plain(it.name) }
            // Suggestions arrive above the first move: show them if the player has not scrolled away.
            val list = rememberLazyListState()
            LaunchedEffect(player.suggestedMoves) { if (list.firstVisibleItemIndex <= 2) list.scrollToItem(0) }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (key.isEmpty()) {
                    val suggested = player.suggestedMoves
                    when {
                        suggested == null -> {
                            header(R.string.move_suggested)
                            item { Text(stringResource(R.string.move_suggesting), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic) }
                        }
                        suggested.isNotEmpty() -> {
                            header(R.string.move_suggested)
                            items(suggested.mapNotNull { id -> player.pickerMoves.firstOrNull { it.id == id } }, key = { "s-${it.id}" }) {
                                MoveRow(it, it.id == move) { pick(it.id) }
                            }
                        }
                    }
                }
                CATEGORIES.forEach { (category, label) ->
                    val group = shown.filter { it.category == category }
                    if (group.isNotEmpty()) {
                        header(label)
                        items(group, key = { it.id }) { MoveRow(it, it.id == move) { pick(it.id) } }
                    }
                }
                if (shown.isEmpty()) item { Text(stringResource(R.string.move_none), style = MaterialTheme.typography.bodyMedium) }
            }

            val chosen = move
            if (chosen != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val sources = player.sources(chosen)
                if (sources.size > 1) {
                    Text(stringResource(R.string.move_confirm_stat), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sources.forEach { option ->
                            FilterChip(selected = option == source, onClick = { source = option }, label = { Text(stringResource(sourceName(option))) })
                        }
                    }
                }
                val stat = source?.takeIf { it in sources }
                val said = if (stat != null) {
                    stringResource(R.string.request_move_said_stat, player.moveName(chosen), stringResource(sourceName(stat)))
                } else {
                    stringResource(R.string.request_move_said, player.moveName(chosen))
                }
                Button(onClick = { onRoll(chosen, stat, said) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.move_roll_named, player.moveName(chosen)))
                }
            }
        }
    }
}

private fun LazyListScope.header(label: Int) {
    item(key = "h-$label") {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
    }
}

/** One move: its name and when it is made; the picked one is shaded. */
@Composable
private fun MoveRow(move: PickerMove, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(move.name, style = MaterialTheme.typography.titleMedium)
        if (move.trigger.isNotBlank()) {
            Text(move.trigger, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** [text] without accents or case, so "reunir" finds "Reunir información" and "informacion" too. */
private fun plain(text: String): String =
    Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** The categories of the manual, in its order, with their labels. */
private val CATEGORIES = listOf(
    MoveCategory.ADVENTURE to R.string.move_category_adventure,
    MoveCategory.RELATIONSHIP to R.string.move_category_relationship,
    MoveCategory.COMBAT to R.string.move_category_combat,
    MoveCategory.SUFFER to R.string.move_category_suffer,
    MoveCategory.QUEST to R.string.move_category_quest,
    MoveCategory.FATE to R.string.move_category_fate,
)
