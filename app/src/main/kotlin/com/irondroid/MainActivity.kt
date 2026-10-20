package com.irondroid

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.irondroid.dice.DiceOverlayView
import com.irondroid.dice.DiceReadyInfo
import com.irondroid.dice.DiceRollReport
import com.irondroid.prototype.ActionRoll
import com.irondroid.prototype.DicePrototypeScreen
import com.irondroid.prototype.DicePrototypeState

/**
 * Single activity. The Compose UI fills the window and the 3D dice overlay sits above it as a
 * separate transparent view, so dice can roll over any screen (RF-DAD-01).
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private var diceOverlay: DiceOverlayView? = null
    private val diceState = DicePrototypeState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        root = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppContent()
                    }
                }
            }
        }
        root.addView(composeView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    @Composable
    private fun AppContent() {
        var showDicePrototype by rememberSaveable { mutableStateOf(false) }
        if (showDicePrototype) {
            BackHandler { showDicePrototype = false; hideDiceOverlay() }
            DicePrototypeScreen(
                state = diceState,
                onRoll = ::rollDice,
                onSharpChange = { sharp ->
                    diceState.sharp = sharp
                    diceOverlay?.setSharp(sharp)
                },
                onBack = { showDicePrototype = false; hideDiceOverlay() },
            )
            LaunchedEffect(Unit) { showDiceOverlay() }
        } else {
            WelcomeScreen(onOpenDicePrototype = { showDicePrototype = true })
        }
    }

    /** Creates the overlay on first use, so the WebView does not slow down app start. */
    private fun showDiceOverlay() {
        val overlay = diceOverlay ?: DiceOverlayView(this, diceListener).also {
            root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            diceOverlay = it
        }
        overlay.visibility = View.VISIBLE
    }

    private fun hideDiceOverlay() {
        diceOverlay?.clearDice()
        diceOverlay?.visibility = View.GONE
    }

    private fun rollDice() {
        val roll = ActionRoll.random()
        diceState.expected = roll
        diceState.rolling = true
        Log.i(DiceOverlayView.TAG, "engine result: $roll")
        diceOverlay?.roll(roll.action, roll.challenge1, roll.challenge2)
    }

    private val diceListener = object : DiceOverlayView.Listener {
        override fun onReady(info: DiceReadyInfo) {
            diceState.ready = info
        }

        override fun onRollComplete(report: DiceRollReport) {
            diceState.expected?.let { diceState.reports.add(it to report) }
            diceState.rolling = false
        }

        override fun onError(message: String) {
            diceState.error = message
            diceState.rolling = false
        }
    }
}

/** Placeholder start screen; for now it also opens the dice prototype. */
@Composable
fun WelcomeScreen(onOpenDicePrototype: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onOpenDicePrototype) {
            Text(stringResource(R.string.dice_proto_open))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    MaterialTheme { WelcomeScreen(onOpenDicePrototype = {}) }
}
