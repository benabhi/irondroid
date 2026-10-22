package com.irondroid.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrack
import com.irondroid.engine.progress.ProgressTrackType

/**
 * The vows and progress tracks of the character (IRON-67, RF-UI-12), as a panel over the scene:
 * by type, each with its rank and its ten boxes drawn as on the manual's sheet. Only to look at:
 * swearing, marking and fulfilling from the screen is IRON-81.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressPanel(tracks: Collection<ProgressTrack>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.progress_title), style = MaterialTheme.typography.headlineMedium)
            if (tracks.isEmpty()) {
                Text(stringResource(R.string.progress_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GROUPS.forEach { (type, label) ->
                val group = tracks.filter { it.type == type }.sortedBy { it.name }
                if (group.isNotEmpty()) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    group.forEach { TrackRow(it) }
                }
            }
        }
    }
}

/** One track: its name, rank and filled boxes, then the ten boxes. */
@Composable
private fun TrackRow(track: ProgressTrack) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(track.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (track.isComplete) stringResource(R.string.progress_complete) else stringResource(R.string.progress_boxes, track.filledBoxes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        track.rank?.let { Text(stringResource(rankName(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TrackBoxes(track, stringResource(R.string.progress_boxes, track.filledBoxes))
    }
}

/**
 * The ten boxes of [track] in thin ink. Each box takes its ticks in the manual's order, \ / | —,
 * so four make the star of a full box.
 */
@Composable
private fun TrackBoxes(track: ProgressTrack, description: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ProgressTrack.BOXES.toFloat())
            .semantics { contentDescription = description },
    ) {
        val box = size.width / ProgressTrack.BOXES
        val inset = box * 0.2f
        val stroke = 1.dp.toPx()
        val tick = 1.8.dp.toPx()
        for (index in 0 until ProgressTrack.BOXES) {
            val left = index * box
            drawRect(line, topLeft = Offset(left + stroke / 2, stroke / 2), size = Size(box - stroke, box - stroke), style = Stroke(stroke))
            val a = left + inset
            val b = left + box - inset
            val top = inset
            val bottom = box - inset
            val middle = box / 2
            val center = left + middle
            val marks = listOf(
                Offset(a, top) to Offset(b, bottom),
                Offset(b, top) to Offset(a, bottom),
                Offset(center, top) to Offset(center, bottom),
                Offset(a, middle) to Offset(b, middle),
            )
            marks.take(track.ticksInBox(index)).forEach { (start, end) ->
                drawLine(ink, start, end, strokeWidth = tick, cap = StrokeCap.Round)
            }
        }
    }
}

private fun rankName(rank: ChallengeRank): Int = when (rank) {
    ChallengeRank.TROUBLESOME -> R.string.rank_troublesome
    ChallengeRank.DANGEROUS -> R.string.rank_dangerous
    ChallengeRank.FORMIDABLE -> R.string.rank_formidable
    ChallengeRank.EXTREME -> R.string.rank_extreme
    ChallengeRank.EPIC -> R.string.rank_epic
}

/** The kinds of track, in the order of the manual's sheet, with their headers. */
private val GROUPS = listOf(
    ProgressTrackType.VOW to R.string.progress_vows,
    ProgressTrackType.JOURNEY to R.string.progress_journeys,
    ProgressTrackType.COMBAT to R.string.progress_combats,
    ProgressTrackType.BONDS to R.string.progress_bonds,
)
