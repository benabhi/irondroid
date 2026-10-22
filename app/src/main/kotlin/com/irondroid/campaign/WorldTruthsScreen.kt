package com.irondroid.campaign

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.ai.world.TruthSuggestion
import com.irondroid.data.WorldTruth
import com.irondroid.engine.truth.TruthCatalog
import com.irondroid.engine.truth.TruthCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The truths being chosen for a new world (IRON-77): the [step] shown (one per category, then the
 * summary at `categories.size`), the [choices] by category id and what the AI [suggestions] are.
 */
class WorldTruthsState(val categories: List<TruthCategory>) {
    var step by mutableIntStateOf(0)

    /** The player came from the summary to change one truth: Next goes back there. */
    var fromSummary by mutableStateOf(false)
    val choices = mutableStateMapOf<String, WorldTruth>()

    /** What the player wrote for each category, kept while they try the book options too. */
    val written = mutableStateMapOf<String, String>()

    /** The d100 rolled last for each category. */
    val rolls = mutableStateMapOf<String, Int>()

    var suggestions by mutableStateOf<Map<String, TruthSuggestion>>(emptyMap())
    var suggesting by mutableStateOf(false)

    val atSummary: Boolean get() = step >= categories.size

    /** Every category has a truth, in book order; null while some is missing. */
    val complete: List<WorldTruth>?
        get() = categories.map { choices[it.id] ?: return null }
}

/** A campaign in a new world on its way to be created: what the player filled in and the truths chosen so far. */
class WorldDraft(val campaign: NewCampaign, val truths: WorldTruthsState) {
    /** The request for the AI's suggestions, cancelled if the player leaves. */
    var suggestions: Job? = null
}

/**
 * Sets the truths of a new world, one category per step (RF-CAM-01): the book's three options with
 * the one the AI suggests marked, a roll that picks one, or a truth the player writes. A summary at
 * the end shows them all; tapping one goes back to it.
 */
@Composable
fun WorldTruthsScreen(
    state: WorldTruthsState,
    catalog: TruthCatalog,
    language: String,
    onRoll: suspend () -> Int,
    onBack: () -> Unit,
    onDone: (List<WorldTruth>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.step) { scroll.scrollTo(0) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.atSummary) {
            Summary(state, catalog, language, onBack = { state.step-- }, onDone = onDone)
        } else {
            CategoryStep(state, state.categories[state.step], catalog, language, onRoll, onBack)
        }
    }
}

@Composable
private fun CategoryStep(
    state: WorldTruthsState,
    category: TruthCategory,
    catalog: TruthCatalog,
    language: String,
    onRoll: suspend () -> Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rolling by remember { mutableStateOf(false) }
    val choice = state.choices[category.id]
    val suggestion = state.suggestions[category.id]
    val writing = if (choice != null) choice.optionId == null else category.id in state.written

    Text(
        stringResource(R.string.truths_step, state.step + 1, state.categories.size),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(catalog.text(category.id, language)?.name ?: category.id, style = MaterialTheme.typography.headlineMedium)
    if (state.suggesting) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.truths_suggesting), style = MaterialTheme.typography.bodySmall)
        }
    }

    for (option in category.options) {
        val text = catalog.optionText(category.id, option.id, language) ?: continue
        OptionCard(
            selected = choice?.optionId == option.id,
            text = text.text,
            quest = text.quest,
            suggestedReason = if (suggestion?.optionId == option.id) suggestion.reason.orEmpty() else null,
            rolled = state.rolls[category.id]?.takeIf { choice?.optionId == option.id && it in option },
        ) {
            state.choices[category.id] = WorldTruth(category.id, option.id, text.text)
        }
    }

    WriteOwnCard(
        selected = writing,
        text = state.written[category.id].orEmpty(),
        onSelect = {
            val written = state.written[category.id].orEmpty()
            state.written[category.id] = written
            if (written.isBlank()) state.choices.remove(category.id) else state.choices[category.id] = WorldTruth(category.id, null, written.trim())
        },
        onTextChange = { text ->
            state.written[category.id] = text
            if (text.isBlank()) state.choices.remove(category.id) else state.choices[category.id] = WorldTruth(category.id, null, text.trim())
        },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                rolling = true
                scope.launch {
                    val roll = onRoll()
                    val option = category.optionFor(roll)
                    catalog.optionText(category.id, option.id, language)?.let {
                        state.choices[category.id] = WorldTruth(category.id, option.id, it.text)
                    }
                    state.rolls[category.id] = roll
                    rolling = false
                }
            },
            enabled = !rolling,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.truths_roll)) }
        Button(onClick = { if (state.fromSummary) state.step = state.categories.size else state.step++ }, enabled = choice != null && !rolling, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.truths_next))
        }
    }
    val missing = state.categories.filter { it.id !in state.choices && state.suggestions[it.id] != null }
    if (missing.isNotEmpty()) {
        TextButton(
            onClick = {
                for (missingCategory in missing) {
                    val pick = state.suggestions.getValue(missingCategory.id).optionId
                    catalog.optionText(missingCategory.id, pick, language)?.let {
                        state.choices[missingCategory.id] = WorldTruth(missingCategory.id, pick, it.text)
                    }
                }
                state.step = state.categories.size
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.truths_use_suggested)) }
    }
    TextButton(onClick = { if (state.step == 0) onBack() else state.step-- }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.truths_previous))
    }
}

@Composable
private fun OptionCard(
    selected: Boolean,
    text: String,
    quest: String,
    suggestedReason: String?,
    rolled: Int?,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        border = BorderStroke(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(modifier = Modifier.padding(start = 4.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (suggestedReason != null) {
                    Text(stringResource(R.string.truths_suggested), style = MaterialTheme.typography.labelLarge)
                    if (suggestedReason.isNotEmpty()) Text(suggestedReason, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
                rolled?.let { Text(stringResource(R.string.truths_rolled, it), style = MaterialTheme.typography.labelLarge) }
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.truths_quest, quest), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WriteOwnCard(selected: Boolean, text: String, onSelect: () -> Unit, onTextChange: (String) -> Unit) {
    OutlinedCard(
        border = BorderStroke(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
            ) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                Text(stringResource(R.string.truths_write_own), style = MaterialTheme.typography.bodyMedium)
            }
            if (selected) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text(stringResource(R.string.truths_write_own_label)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Summary(
    state: WorldTruthsState,
    catalog: TruthCatalog,
    language: String,
    onBack: () -> Unit,
    onDone: (List<WorldTruth>) -> Unit,
) {
    Text(stringResource(R.string.truths_summary_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.truths_summary_hint), style = MaterialTheme.typography.bodySmall)
    state.categories.forEachIndexed { index, category ->
        Card(modifier = Modifier
            .fillMaxWidth()
            .clickable {
                state.fromSummary = true
                state.step = index
            }) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(catalog.text(category.id, language)?.name ?: category.id, style = MaterialTheme.typography.titleMedium)
                Text(
                    state.choices[category.id]?.text ?: stringResource(R.string.truths_summary_missing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    val complete = state.complete
    Button(onClick = { complete?.let(onDone) }, enabled = complete != null, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.campaign_new_create))
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.truths_previous)) }
}
