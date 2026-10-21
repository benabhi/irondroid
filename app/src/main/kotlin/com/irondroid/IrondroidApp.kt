package com.irondroid

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ProviderRegistry
import com.irondroid.ai.ProviderSettings
import com.irondroid.ai.openai.DeepSeek
import com.irondroid.data.CampaignStore
import com.irondroid.data.IrondroidDatabase
import com.irondroid.data.secret.ApiKeyStore
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the objects the whole app shares, such as the one database instance. */
class IrondroidApp : Application() {
    /** Work that must outlive a screen, such as saving the game. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: IrondroidDatabase by lazy { IrondroidDatabase.open(this) }

    val campaignStore: CampaignStore by lazy { CampaignStore(database) }

    val games: GameController by lazy { GameController(campaignStore, { dispatcher }, appScope) }

    /**
     * The actions of the MVP catalog (IRON-43), played on the open campaign. Rule texts follow the
     * phone's language: Spanish when it is Spanish, English otherwise.
     */
    val actions: GameActions by lazy {
        val language = if (resources.configuration.locales[0].language == "es") "es" else "en"
        GameActions(games, MoveLoader.fromResources(), OracleTableLoader.fromResources(), SeededDice.fromSeed(System.nanoTime()), language)
    }

    /** The single door for every action (RF-API-04), for the screens and the AI alike. */
    val dispatcher: ActionDispatcher by lazy { actions.dispatcher() }

    /** The AI providers the app offers (RF-IA-01); for now only DeepSeek (RF-IA-02). */
    val aiProviders: ProviderRegistry by lazy { ProviderRegistry(listOf(DeepSeek.registration())) }

    /** The players' API keys, one per provider, encrypted (IRON-36). */
    val apiKeys: ApiKeyStore by lazy { ApiKeyStore.open(this) }

    /** The provider [id] ready to use with its saved key, or null when it has no key yet. */
    fun aiProvider(id: String = DeepSeek.ID): AiProvider? =
        apiKeys.load(id)?.let { aiProviders.create(id, ProviderSettings(apiKey = it)) }

    private val sessionPreferences by lazy { getSharedPreferences("session", Context.MODE_PRIVATE) }

    /** The campaign that was open when the app was last left, to go back to it; null when on the list. */
    var lastCampaignId: Long?
        get() = sessionPreferences.getLong(KEY_LAST_CAMPAIGN, 0L).takeIf { it > 0 }
        set(value) = sessionPreferences.edit { if (value == null) remove(KEY_LAST_CAMPAIGN) else putLong(KEY_LAST_CAMPAIGN, value) }

    private companion object {
        const val KEY_LAST_CAMPAIGN = "last_campaign_id"
    }
}
