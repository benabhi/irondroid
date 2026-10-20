package com.irondroid.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.dice.DiceReadyInfo
import com.irondroid.dice.DiceRollReport
import java.util.Locale
import kotlin.random.Random

/** Results the "engine" decided for one action roll: 1d6 action and 2d10 challenge. */
data class ActionRoll(val action: Int, val challenge1: Int, val challenge2: Int) {
    val values: List<Int> get() = listOf(action, challenge1, challenge2)

    companion object {
        fun random(random: Random = Random.Default) =
            ActionRoll(random.nextInt(1, 7), random.nextInt(1, 11), random.nextInt(1, 11))
    }
}

/** UI state of the 3D dice prototype (IRON-12). */
class DicePrototypeState {
    var ready by mutableStateOf<DiceReadyInfo?>(null)
    var error by mutableStateOf<String?>(null)
    var rolling by mutableStateOf(false)
    var sharp by mutableStateOf(false)
    var expected by mutableStateOf<ActionRoll?>(null)
    val reports = mutableStateListOf<Pair<ActionRoll, DiceRollReport>>()
}

/**
 * Throwaway screen to measure the dice overlay on the phone: fake game content underneath to
 * judge transparency, the engine result next to what the dice show, and frame metrics.
 */
@Composable
fun DicePrototypeScreen(
    state: DicePrototypeState,
    onRoll: () -> Unit,
    onSharpChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dice_proto_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.dice_proto_back)) }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FakeGameContent()
            ResultsCard(state)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dice_proto_sharp), modifier = Modifier.weight(1f))
            Switch(checked = state.sharp, onCheckedChange = onSharpChange, enabled = state.ready != null)
        }
        Button(
            onClick = onRoll,
            enabled = state.ready != null && !state.rolling,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.ready == null) R.string.dice_proto_loading else R.string.dice_proto_roll))
        }
    }
}

/** Stand-in for the game screen, only there to see the dice rolling over real content. */
@Composable
private fun FakeGameContent() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dice_proto_fake_heading), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dice_proto_fake_narration), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dice_proto_fake_stats),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ResultsCard(state: DicePrototypeState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.error?.let {
                Text(stringResource(R.string.dice_proto_error, it), color = MaterialTheme.colorScheme.error)
            }
            state.ready?.let {
                Text(
                    stringResource(
                        R.string.dice_proto_ready,
                        it.initMs.format(0),
                        it.width,
                        it.height,
                        it.pixelRatio.format(2),
                        it.gpu,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val expected = state.expected
            if (expected != null) {
                Spacer(Modifier.padding(2.dp))
                Text(
                    stringResource(R.string.dice_proto_engine, expected.action, expected.challenge1, expected.challenge2),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            val last = state.reports.lastOrNull()
            if (last != null && !state.rolling) {
                val (roll, report) = last
                val shown = report.shown
                Text(
                    stringResource(R.string.dice_proto_shown, shown.getOrNull(0) ?: 0, shown.getOrNull(1) ?: 0, shown.getOrNull(2) ?: 0),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(if (shown == roll.values) R.string.dice_proto_match else R.string.dice_proto_mismatch),
                    color = if (shown == roll.values) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        R.string.dice_proto_metrics,
                        report.avgFps.format(1),
                        report.worstFrameMs.format(0),
                        report.slowFrames,
                        report.frames,
                        (report.durationMs / 1000).format(2),
                        report.firstFrameMs.format(0),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val all = state.reports
                Text(
                    stringResource(
                        R.string.dice_proto_session,
                        all.size,
                        all.count { it.second.shown == it.first.values },
                        all.map { it.second.avgFps }.average().format(1),
                        all.minOf { it.second.avgFps }.format(1),
                        (all.map { it.second.durationMs }.average() / 1000).format(2),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun Double.format(decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", this)
