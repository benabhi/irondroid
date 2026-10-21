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
import com.irondroid.data.WorldSummary
import com.irondroid.dice.DiceOverlayView
import com.irondroid.dice.DiceReadyInfo
import com.irondroid.dice.DiceRollReport
import com.irondroid.engine.character.Character
import com.irondroid.ai.tools.ActionTools
import com.irondroid.prototype.ActionRoll
import com.irondroid.prototype.AiTestScreen
import com.irondroid.prototype.DicePrototypeScreen
import com.irondroid.prototype.DicePrototypeState
import com.irondroid.prototype.OracleTablesScreen
import com.irondroid.settings.AiSettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Screen { CAMPAIGNS, NEW_CAMPAIGN, CAMPAIGN, DICE_PROTOTYPE, AI_TEST, AI_SETTINGS, ORACLE_TABLES }

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
        val app = application as IrondroidApp
        val store = app.campaignStore
        val scope = rememberCoroutineScope()
        // Back where the player left: the campaign that was open when the app was closed (IRON-34).
        val resumeId = remember { app.lastCampaignId }
        var screen by rememberSaveable { mutableStateOf(if (resumeId != null) Screen.CAMPAIGN else Screen.CAMPAIGNS) }
        var openCampaignId by rememberSaveable { mutableLongStateOf(resumeId ?: 0L) }
        val leaveCampaign = {
            app.games.close()
            app.lastCampaignId = null
            screen = Screen.CAMPAIGNS
        }
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
                    onOpenSettings = { screen = Screen.AI_SETTINGS },
                    onOpenAiTest = if (BuildConfig.DEBUG) ({ screen = Screen.AI_TEST }) else null,
                    onOpenOracleTables = if (BuildConfig.DEBUG) ({ screen = Screen.ORACLE_TABLES }) else null,
                )
            }
            Screen.NEW_CAMPAIGN -> {
                BackHandler { screen = Screen.CAMPAIGNS }
                val worlds by produceState<List<WorldSummary>?>(null) { value = store.worldSummaries() }
                // Waits for the worlds so the suggested world name already avoids the taken ones.
                worlds?.let { loaded ->
                    NewCampaignScreen(
                        worlds = loaded,
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
            }
            Screen.CAMPAIGN -> {
                BackHandler(onBack = leaveCampaign)
                val details by produceState<CampaignDetails?>(null, openCampaignId) {
                    val campaign = store.campaign(openCampaignId)
                    if (campaign == null || app.games.open(openCampaignId) == null) {
                        leaveCampaign()
                        return@produceState
                    }
                    app.lastCampaignId = campaign.id
                    value = CampaignDetails(campaign, store.world(campaign.worldId), store.loadCharacter(campaign.id))
                }
                CampaignScreen(details, onBack = leaveCampaign)
            }
            Screen.AI_TEST -> {
                val leaveTest = {
                    app.games.close()
                    screen = Screen.CAMPAIGNS
                }
                BackHandler(onBack = leaveTest)
                val provider = remember { app.aiProvider() }
                // The AI plays its actions on the last campaign played (IRON-48).
                val campaign by produceState<Pair<String, ActionTools>?>(null) {
                    val last = store.observeCampaigns().first().firstOrNull() ?: return@produceState
                    if (app.games.open(last.id) != null) value = last.title to ActionTools(app.dispatcher)
                }
                AiTestScreen(provider, app.aiSettings.model(), campaign?.second, campaign?.first, onBack = leaveTest)
            }
            Screen.ORACLE_TABLES -> {
                BackHandler { screen = Screen.CAMPAIGNS }
                OracleTablesScreen(app.oracles, app.rulesLanguage, onBack = { screen = Screen.CAMPAIGNS })
            }
            Screen.AI_SETTINGS -> {
                BackHandler { screen = Screen.CAMPAIGNS }
                AiSettingsScreen(app.aiSettingsActions, onBack = { screen = Screen.CAMPAIGNS })
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

    override fun onStop() {
        super.onStop()
        // Leaving the screen may be the last chance before the system ends the app: write pending saves now.
        (application as IrondroidApp).games.flush()
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
