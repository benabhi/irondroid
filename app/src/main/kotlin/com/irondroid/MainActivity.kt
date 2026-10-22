package com.irondroid

import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.irondroid.campaign.CampaignDetails
import com.irondroid.campaign.CampaignListScreen
import com.irondroid.campaign.CampaignScreen
import com.irondroid.campaign.NewCampaignScreen
import com.irondroid.data.WorldSummary
import com.irondroid.dice.DiceTable
import com.irondroid.dice.LocalDiceTable
import com.irondroid.engine.dice.Die
import com.irondroid.engine.character.Character
import com.irondroid.ai.narrator.CharacterGender as NarratorGender
import com.irondroid.ai.narrator.Narrator
import com.irondroid.ai.narrator.MoveOption
import com.irondroid.ai.narrator.MoveSuggester
import com.irondroid.ai.narrator.NarratorPrompt
import com.irondroid.ai.narrator.NarratorStyle
import com.irondroid.ai.tools.ActionTools
import com.irondroid.game.GameScreen
import com.irondroid.game.TurnPlayer
import com.irondroid.game.turnContext
import com.irondroid.prototype.AiTestScreen
import com.irondroid.prototype.OracleTablesScreen
import com.irondroid.settings.AiSettingsScreen
import com.irondroid.theme.IrondroidTheme
import com.irondroid.theme.isDark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Screen { CAMPAIGNS, NEW_CAMPAIGN, CAMPAIGN, AI_TEST, AI_SETTINGS, ORACLE_TABLES }

/** Log tag of the turns played, as in [TurnPlayer]. */
private const val TURN_TAG = "Turn"

/** Sheet saved for a new campaign until guided character creation exists (IRON-PLAN-11): every stat at 0. */
private val PENDING_SHEET = Character.create(edge = 0, heart = 0, iron = 0, shadow = 0, wits = 0)

/**
 * Single activity. The Compose UI fills the window and the 3D dice overlay sits above it as a
 * separate transparent view, so dice can roll over any screen (RF-DAD-01).
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var diceTable: DiceTable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        root = FrameLayout(this)
        diceTable = DiceTable(root)
        val composeView = ComposeView(this).apply {
            setContent {
                val dark = (application as IrondroidApp).themeMode.isDark()
                // The clock and battery icons follow the theme: dark on paper, light on ink.
                LaunchedEffect(dark) {
                    val bars = if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
                    enableEdgeToEdge(bars, bars)
                    diceTable.setDark(dark)
                }
                IrondroidTheme(dark) {
                    // The player's text size on top of the phone's font scale (IRON-69).
                    val density = LocalDensity.current
                    val scale = (application as IrondroidApp).textSize.scale
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, density.fontScale * scale),
                        LocalDiceTable provides diceTable,
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            AppContent()
                        }
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
        var settingsFrom by rememberSaveable { mutableStateOf(Screen.CAMPAIGNS) }

        when (screen) {
            Screen.CAMPAIGNS -> {
                val campaigns by remember { store.observeCampaigns() }.collectAsState(initial = null)
                CampaignListScreen(
                    campaigns = campaigns,
                    onOpen = { openCampaignId = it; screen = Screen.CAMPAIGN },
                    onNew = { screen = Screen.NEW_CAMPAIGN },
                    onDelete = { id -> scope.launch { store.deleteCampaign(id) } },
                    onOpenSettings = {
                        settingsFrom = Screen.CAMPAIGNS
                        screen = Screen.AI_SETTINGS
                    },
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
                val loaded = details
                val session = app.games.session
                if (loaded != null && session != null && session.campaignId == openCampaignId) {
                    val sheet by session.sheet.collectAsState()
                    val player = remember(openCampaignId) {
                        val narrator = app.aiProvider()?.let { provider ->
                            { player: TurnPlayer ->
                                Narrator(
                                    provider,
                                    app.aiSettings.model(),
                                    ActionTools.forNarrator(app.dispatcher),
                                    {
                                        val gender = player.gender?.let { NarratorGender.valueOf(it.name) }
                                        NarratorPrompt.build(session.characterName, app.rulesLanguage, NarratorStyle.PERFORMED, gender)
                                    },
                                    confirmer = player,
                                    effort = { app.playSettings.narratorEffort },
                                    context = {
                                        turnContext(
                                            session.characterName,
                                            session.sheet.value,
                                            store.truths(loaded.campaign.worldId),
                                            app.games.history?.calls.orEmpty(),
                                            app.oracles,
                                        ).also { Log.d(TURN_TAG, it.render()) }
                                    },
                                )
                            }
                        }
                        TurnPlayer(
                            openCampaignId,
                            store,
                            app.dispatcher,
                            app.moves,
                            app.rulesLanguage,
                            confirmMoves = { app.playSettings.confirmMoves },
                            narrator = narrator,
                            flush = app.games::flushNow,
                            history = { app.games.history },
                            pricing = { app.aiSettings.provider.model(app.aiSettings.model())?.pricing },
                            sheet = { session.sheet.value },
                            // The model reads the triggers in English, like the rest of its instructions.
                            suggester = app.aiProvider()?.let { provider ->
                                MoveSuggester(provider, app.aiSettings.model(), app.moves.moves.map { MoveOption(it.id, app.moves.text(it.id, "en")?.trigger ?: it.id) })
                            },
                        )
                    }
                    LaunchedEffect(player) { player.load() }
                    // The dice page loads with the game, so the first roll does not wait for it.
                    LaunchedEffect(Unit) { diceTable.preload() }
                    GameScreen(
                        loaded.campaign.title,
                        session.characterName,
                        sheet,
                        player,
                        tableName = { app.oracles.text(it, app.rulesLanguage)?.name ?: it },
                        showMetrics = app.playSettings.showMetrics,
                        // The player's setting, or the phone's animations turned off (RF-UI-06).
                        reduceMotion = app.playSettings.reduceMotion ||
                            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
                        onBack = leaveCampaign,
                        onOpenSettings = {
                            settingsFrom = Screen.CAMPAIGN
                            screen = Screen.AI_SETTINGS
                        },
                        // Rolls the 3D dice with the game's dice until the moves use them (IRON-72).
                        onTestDice = if (BuildConfig.DEBUG) ({
                            scope.launch { diceTable.roll(app.dice.roll(Die.D6).value, app.dice.roll(Die.D10).value, app.dice.roll(Die.D10).value) }
                        }) else null,
                    )
                } else {
                    CampaignScreen(null, onBack = leaveCampaign)
                }
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
                AiTestScreen(
                    provider,
                    app.aiSettings.model(),
                    campaign?.second,
                    campaign?.first,
                    characterName = campaign?.let { app.games.characterName },
                    language = app.rulesLanguage,
                    onBack = leaveTest,
                )
            }
            Screen.ORACLE_TABLES -> {
                BackHandler { screen = Screen.CAMPAIGNS }
                OracleTablesScreen(app.oracles, app.rulesLanguage, app.dice, onBack = { screen = Screen.CAMPAIGNS })
            }
            Screen.AI_SETTINGS -> {
                // Back to where Settings was opened: the campaign list or the game (IRON-69).
                BackHandler { screen = settingsFrom }
                AiSettingsScreen(app.aiSettingsActions, onBack = { screen = settingsFrom })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen may be the last chance before the system ends the app: write pending saves now.
        (application as IrondroidApp).games.flush()
    }

    override fun onPause() {
        super.onPause()
        diceTable.pause()
    }

    override fun onResume() {
        super.onResume()
        diceTable.resume()
    }

    override fun onDestroy() {
        diceTable.destroy()
        super.onDestroy()
    }
}
