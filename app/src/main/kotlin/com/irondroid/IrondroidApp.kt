package com.irondroid

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.AiException
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ConnectionCheck
import com.irondroid.ai.ConnectionResult
import com.irondroid.ai.ProviderInfo
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
import com.irondroid.engine.oracle.OracleTableCatalog
import com.irondroid.engine.oracle.OracleTableLoader
import com.irondroid.settings.AiSettings
import com.irondroid.settings.AiSettingsActions
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

    /** Language of the rule texts: Spanish when the phone is in Spanish, English otherwise. */
    val rulesLanguage: String by lazy { if (resources.configuration.locales[0].language == "es") "es" else "en" }

    /** The oracle tables (RF-ORA-02), with the inspiration tables of IRON-103. */
    val oracles: OracleTableCatalog by lazy { OracleTableLoader.fromResources() }

    /** The actions of the MVP catalog (IRON-43), played on the open campaign, with texts in [rulesLanguage]. */
    val actions: GameActions by lazy {
        GameActions(games, MoveLoader.fromResources(), oracles, SeededDice.fromSeed(System.nanoTime()), rulesLanguage)
    }

    /** The single door for every action (RF-API-04), for the screens and the AI alike. */
    val dispatcher: ActionDispatcher by lazy { actions.dispatcher() }

    /** The AI providers the app offers (RF-IA-01); for now only DeepSeek (RF-IA-02). */
    val aiProviders: ProviderRegistry by lazy { ProviderRegistry(listOf(DeepSeek.registration())) }

    /** The players' API keys, one per provider, encrypted (IRON-36). */
    val apiKeys: ApiKeyStore by lazy { ApiKeyStore.open(this) }

    /** The player's provider and model choices (IRON-49). */
    val aiSettings: AiSettings by lazy { AiSettings(getSharedPreferences(AiSettings.FILE_NAME, Context.MODE_PRIVATE), aiProviders) }

    /** The chosen provider ready to use with its saved key, or null when it has no key yet. */
    fun aiProvider(provider: ProviderInfo = aiSettings.provider): AiProvider? =
        if (!provider.needsApiKey) {
            aiProviders.create(provider.id, ProviderSettings())
        } else {
            apiKeys.load(provider.id)?.let { aiProviders.create(provider.id, ProviderSettings(apiKey = it)) }
        }

    /** What the AI settings screen reads and changes (RF-IA-03, RF-IA-05). */
    val aiSettingsActions: AiSettingsActions by lazy {
        object : AiSettingsActions {
            override val providers get() = aiProviders.providers
            override var provider: ProviderInfo
                get() = aiSettings.provider
                set(value) {
                    aiSettings.provider = value
                }

            override fun model(provider: ProviderInfo) = aiSettings.model(provider)
            override fun setModel(provider: ProviderInfo, model: String) = aiSettings.setModel(provider, model)
            override fun maskedKey(provider: ProviderInfo) = apiKeys.load(provider.id)?.let { "••••" + it.takeLast(4) }
            override fun saveKey(provider: ProviderInfo, key: String) = apiKeys.save(provider.id, key)
            override fun deleteKey(provider: ProviderInfo) = apiKeys.delete(provider.id)
            override suspend fun checkConnection(provider: ProviderInfo): ConnectionResult {
                val ai = aiProvider(provider)
                    ?: return ConnectionResult.Failed(AiException(AiErrorKind.AUTHENTICATION, "No API key saved"), 0)
                return ConnectionCheck.run(ai, aiSettings.model(provider))
            }
        }
    }

    private val sessionPreferences by lazy { getSharedPreferences("session", Context.MODE_PRIVATE) }

    /** The campaign that was open when the app was last left, to go back to it; null when on the list. */
    var lastCampaignId: Long?
        get() = sessionPreferences.getLong(KEY_LAST_CAMPAIGN, 0L).takeIf { it > 0 }
        set(value) = sessionPreferences.edit { if (value == null) remove(KEY_LAST_CAMPAIGN) else putLong(KEY_LAST_CAMPAIGN, value) }

    private companion object {
        const val KEY_LAST_CAMPAIGN = "last_campaign_id"
    }
}
