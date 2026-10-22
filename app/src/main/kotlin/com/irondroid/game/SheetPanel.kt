package com.irondroid.game

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.data.entity.CharacterGender
import com.irondroid.engine.character.Character
import com.irondroid.engine.character.Stat
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.meters.Meter
import com.irondroid.engine.momentum.Momentum
import kotlin.random.Random

/**
 * The character sheet as a panel over the scene (IRON-66, RF-UI-08, RF-PJ-03), on a faint paper
 * texture (RF-UI-02). The − and + buttons and the debilities change a draft; "Apply changes" sends it
 * to the engine as one hand edit, which stays in the story (RF-PJ-04).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SheetPanel(
    characterName: String,
    sheet: Character,
    gender: CharacterGender?,
    onGender: (CharacterGender) -> Unit,
    canEdit: Boolean,
    onApply: (SheetEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stats = remember(sheet) { mutableStateMapOf<Stat, Int>().apply { putAll(sheet.stats) } }
    val meters = remember(sheet) {
        mutableStateMapOf(
            HEALTH to sheet.meters.health.value,
            SPIRIT to sheet.meters.spirit.value,
            SUPPLY to sheet.meters.supply.value,
            MOMENTUM to sheet.momentum.value,
        )
    }
    var debilities by remember(sheet) { mutableStateOf(sheet.debilities.marked) }
    val paper = MaterialTheme.colorScheme.onSurface

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .drawBehind {
                    // Paper grain: a few thousand faint specks, always in the same places.
                    val random = Random(SEED)
                    repeat((size.width * size.height / 900).toInt()) {
                        drawCircle(paper.copy(alpha = 0.035f), radius = 1.1f, center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height))
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(characterName, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.campaign_gender), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    CharacterGender.FEMININE to R.string.gender_feminine,
                    CharacterGender.MASCULINE to R.string.gender_masculine,
                    CharacterGender.NEUTRAL to R.string.gender_neutral,
                ).forEach { (option, label) ->
                    // No choice yet reads as neutral, which is how the narrator treats it.
                    FilterChip(selected = (gender ?: CharacterGender.NEUTRAL) == option, onClick = { onGender(option) }, label = { Text(stringResource(label)) })
                }
            }

            Section(R.string.sheet_stats)
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat.entries.forEach { stat ->
                    Stepper(statName(stat), stats.getValue(stat), 0, STAT_MAX, Modifier.weight(1f)) { stats[stat] = it }
                }
            }
            if (stats.values.all { it == 0 }) Text(stringResource(R.string.campaign_sheet_pending), style = MaterialTheme.typography.bodySmall)

            Section(R.string.sheet_meters)
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(HEALTH to R.string.meter_health, SPIRIT to R.string.meter_spirit, SUPPLY to R.string.meter_supply).forEach { (meter, label) ->
                    Stepper(stringResource(label), meters.getValue(meter), Meter.MIN, Meter.MAX, Modifier.weight(1f)) { meters[meter] = it }
                }
                val momentumMax = Momentum.maxFor(debilities.size)
                Stepper(stringResource(R.string.meter_momentum), meters.getValue(MOMENTUM), Momentum.MIN, momentumMax, Modifier.weight(1f), signed = true) { meters[MOMENTUM] = it }
            }
            Text(
                stringResource(R.string.sheet_momentum_limits, Momentum.maxFor(debilities.size), Momentum.resetFor(debilities.size)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(R.string.sheet_debilities)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Debility.entries.forEach { debility ->
                    FilterChip(
                        selected = debility in debilities,
                        onClick = {
                            debilities = if (debility in debilities) debilities - debility else debilities + debility
                            // Each debility lowers the most momentum can be, as the engine will.
                            meters[MOMENTUM] = meters.getValue(MOMENTUM).coerceAtMost(Momentum.maxFor(debilities.size))
                        },
                        label = { Text(stringResource(debilityName(debility))) },
                    )
                }
            }

            Section(R.string.sheet_experience)
            Text(stringResource(R.string.sheet_experience_value, sheet.experience), style = MaterialTheme.typography.bodyLarge)

            val original = mapOf(
                HEALTH to sheet.meters.health.value,
                SPIRIT to sheet.meters.spirit.value,
                SUPPLY to sheet.meters.supply.value,
                MOMENTUM to sheet.momentum.value,
            )
            val edit = SheetEdit(
                meters = meters.toMap().filter { (meter, value) -> value != original[meter] },
                mark = debilities - sheet.debilities.marked,
                clear = sheet.debilities.marked - debilities,
                stats = stats.toMap().takeIf { it != sheet.stats },
            )
            val changed = edit.meters.isNotEmpty() || edit.mark.isNotEmpty() || edit.clear.isNotEmpty() || edit.stats != null
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
            if (edit.stats != null) Text(stringResource(R.string.sheet_stats_note), style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = canEdit && changed,
                onClick = { onApply(edit) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.sheet_apply)) }
            Text(stringResource(R.string.sheet_apply_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

/** A value with − below and + above it, kept within [min] and [max]. */
@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, modifier: Modifier, signed: Boolean = false, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        StepButton("+", stringResource(R.string.sheet_increase, label), enabled = value < max) { onChange(value + 1) }
        Text(
            if (signed && value > 0) "+$value" else value.toString(),
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "lnum"),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp),
        )
        StepButton("−", stringResource(R.string.sheet_decrease, label), enabled = value > min) { onChange(value - 1) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A round, thin-lined button with a sign, at least 48 dp to touch (RF-UI-13). */
@Composable
private fun StepButton(sign: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        val ink = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
        Text(
            sign,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = ink,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(32.dp)
                .border(1.dp, ink, CircleShape)
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun statName(stat: Stat): String = stringResource(sourceName(stat.name.lowercase()))

private const val HEALTH = "health"
private const val SPIRIT = "spirit"
private const val SUPPLY = "supply"
private const val MOMENTUM = "momentum"
private const val STAT_MAX = 5
private const val SEED = 7
