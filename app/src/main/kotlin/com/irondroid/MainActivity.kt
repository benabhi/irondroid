package com.irondroid

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.irondroid.campaign.CampaignDetails
import com.irondroid.campaign.CampaignListScreen
import com.irondroid.campaign.CampaignScreen
import com.irondroid.campaign.NewCampaignScreen
import com.irondroid.data.entity.WorldEntity
import com.irondroid.dice.DiceOverlayView
import com.irondroid.dice.DiceReadyInfo
import com.irondroid.dice.DiceRollReport
import com.irondroid.engine.character.Character
import com.irondroid.prototype.ActionRoll
import com.irondroid.prototype.DicePrototypeScreen
import com.irondroid.prototype.DicePrototypeState
import kotlinx.coroutines.launch

private enum class Screen { CAMPAIGNS, NEW_CAMPAIGN, CAMPAIGN, DICE_PROTOTYPE }

/** Sheet saved for a new campaign until guided character creation exists (IRON-PLAN-11): every stat at 0. */
private val PENDING_SHEET = Character.create(edge = 0, heart = 0, iron = 0, shadow = 0, wits = 0)

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
        val store = (application as IrondroidApp).campaignStore
        val scope = rememberCoroutineScope()
        var screen by rememberSaveable { mutableStateOf(Screen.CAMPAIGNS) }
        var openCampaignId by rememberSaveable { mutableLongStateOf(0L) }
        var creating by remember { mutableStateOf(false) }

        when (screen) {
            Screen.CAMPAIGNS -> {
                val campaigns by remember { store.observeCampaigns() }.collectAsState(initial = null)
                CampaignListScreen(
                    campaigns = campaigns,
                    onOpen = { openCampaignId = it; screen = Screen.CAMPAIGN },
                    onNew = { screen = Screen.NEW_CAMPAIGN },
                    onDelete = { id -> scope.launch { store.deleteCampaign(id) } },
                    onOpenDicePrototype = { screen = Screen.DICE_PROTOTYPE },
                )
            }
            Screen.NEW_CAMPAIGN -> {
                BackHandler { screen = Screen.CAMPAIGNS }
                val worlds by produceState(emptyList<WorldEntity>()) { value = store.worlds() }
                NewCampaignScreen(
                    worlds = worlds,
                    onCreate = { new ->
                        if (!creating) {
                            creating = true
                            scope.launch {
                                openCampaignId = if (new.worldId == null) {
                                    store.createCampaignInNewWorld(new.newWorldName, new.title, new.characterName, PENDING_SHEET)
                                } else {
                                    store.createCampaign(new.worldId, new.title, new.characterName, PENDING_SHEET)
                                }
                                creating = false
                                screen = Screen.CAMPAIGN
                            }
                        }
                    },
                    onCancel = { screen = Screen.CAMPAIGNS },
                )
            }
            Screen.CAMPAIGN -> {
                BackHandler { screen = Screen.CAMPAIGNS }
                val details by produceState<CampaignDetails?>(null, openCampaignId) {
                    value = store.campaign(openCampaignId)?.let { campaign ->
                        CampaignDetails(campaign, store.world(campaign.worldId), store.loadCharacter(campaign.id))
                    }
                }
                CampaignScreen(details, onBack = { screen = Screen.CAMPAIGNS })
            }
            Screen.DICE_PROTOTYPE -> {
                BackHandler { screen = Screen.CAMPAIGNS; hideDiceOverlay() }
                DicePrototypeScreen(
                    state = diceState,
                    onRoll = ::rollDice,
                    onSharpChange = { sharp ->
                        diceState.sharp = sharp
                        diceOverlay?.setSharp(sharp)
                    },
                    onBack = { screen = Screen.CAMPAIGNS; hideDiceOverlay() },
                )
                LaunchedEffect(Unit) { showDiceOverlay() }
            }
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
