package com.irondroid.game

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.irondroid.R
import com.irondroid.engine.debility.Debility
import com.irondroid.theme.InkIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One meter of the status bar (IRON-68, RF-UI-09): its value large and its name small. When the
 * value changes it pulses and shows the difference ("−1", "+2") rising and fading, each step under
 * 300 ms (RF-UI-06). A [critical] meter (health, spirit or supply at 0) shows a warning icon that
 * pulses twice when it gets there, and its number underlined (RF-UI-01: no color). With
 * [reduceMotion] nothing moves: the difference shows still for a moment.
 */
@Composable
internal fun AnimatedMeterCell(@StringRes label: Int, value: Int, signed: Boolean, critical: Boolean, reduceMotion: Boolean, modifier: Modifier) {
    var previous by remember { mutableStateOf(value) }
    var change by remember { mutableStateOf<Int?>(null) }
    val pulse = remember { Animatable(1f) }
    val rise = remember { Animatable(0f) }
    val alert = remember { Animatable(1f) }
    LaunchedEffect(value) {
        if (value == previous) return@LaunchedEffect
        change = value - previous
        previous = value
        if (reduceMotion) {
            rise.snapTo(0f)
            delay(SHOW_MS)
            change = null
            return@LaunchedEffect
        }
        launch {
            pulse.animateTo(1.15f, tween(120))
            pulse.animateTo(1f, tween(150))
        }
        if (critical) {
            launch {
                repeat(2) {
                    alert.animateTo(1.4f, tween(70))
                    alert.animateTo(1f, tween(70))
                }
            }
        }
        rise.snapTo(0f)
        rise.animateTo(0.6f, tween(250))
        delay(SHOW_MS)
        rise.animateTo(1f, tween(200))
        change = null
    }

    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (critical) {
                    Icon(
                        InkIcons.Alert,
                        contentDescription = stringResource(R.string.meter_critical),
                        modifier = Modifier.size(16.dp).graphicsLayer { scaleX = alert.value; scaleY = alert.value },
                    )
                }
                // Lining figures: Cormorant's old-style digits dip below the line and look uneven side by side.
                Text(
                    if (signed && value >= 0) "+$value" else value.toString(),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFeatureSettings = "lnum",
                        textDecoration = if (critical) TextDecoration.Underline else null,
                    ),
                    modifier = Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
                )
            }
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        change?.let { delta ->
            Text(
                (if (delta > 0) "+" else "−") + kotlin.math.abs(delta),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .graphicsLayer {
                        translationY = -12.dp.toPx() * rise.value
                        // Fades only in the last part, after the player had time to read it.
                        alpha = if (rise.value <= 0.6f) 1f else 1f - (rise.value - 0.6f) / 0.4f
                    },
            )
        }
    }
}

/**
 * A marked debility as a small pen drawing (IRON-68). A [fresh] one pulses twice; tapping it calls
 * [onTap], so the bar can show its name.
 */
@Composable
internal fun DebilityIcon(debility: Debility, fresh: Boolean, reduceMotion: Boolean, onTap: () -> Unit) {
    val pulse = remember { Animatable(1f) }
    // Keyed once: the icon of a debility just marked is new, and pulses when it first shows.
    LaunchedEffect(Unit) {
        if (fresh && !reduceMotion) {
            repeat(2) {
                pulse.animateTo(1.4f, tween(90))
                pulse.animateTo(1f, tween(90))
            }
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onTap),
    ) {
        Icon(
            debilityIcon(debility),
            contentDescription = stringResource(debilityName(debility)),
            modifier = Modifier.size(20.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
        )
    }
}

private fun debilityIcon(debility: Debility): ImageVector = when (debility) {
    Debility.WOUNDED -> InkIcons.Wounded
    Debility.SHAKEN -> InkIcons.Shaken
    Debility.UNPREPARED -> InkIcons.Unprepared
    Debility.ENCUMBERED -> InkIcons.Encumbered
    Debility.MAIMED -> InkIcons.Maimed
    Debility.CORRUPTED -> InkIcons.Corrupted
    Debility.CURSED -> InkIcons.Cursed
    Debility.TORMENTED -> InkIcons.Tormented
}

/** How long a change stays readable before it fades, in milliseconds. */
private const val SHOW_MS = 700L
