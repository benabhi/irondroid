package com.irondroid

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.irondroid.data.CampaignStore
import com.irondroid.data.IrondroidDatabase
import com.irondroid.engine.action.ActionCatalog
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.CatalogVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the objects the whole app shares, such as the one database instance. */
class IrondroidApp : Application() {
    /** Work that must outlive a screen, such as saving the game. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: IrondroidDatabase by lazy { IrondroidDatabase.open(this) }

    val campaignStore: CampaignStore by lazy { CampaignStore(database) }

    /** The single door for every action (RF-API-04). The actions themselves arrive with IRON-40 and later. */
    val dispatcher: ActionDispatcher by lazy {
        ActionDispatcher(ActionCatalog.of(CatalogVersion(1, 0), emptyList()), emptyMap())
    }

    val games: GameController by lazy { GameController(campaignStore, dispatcher, appScope) }

    private val sessionPreferences by lazy { getSharedPreferences("session", Context.MODE_PRIVATE) }

    /** The campaign that was open when the app was last left, to go back to it; null when on the list. */
    var lastCampaignId: Long?
        get() = sessionPreferences.getLong(KEY_LAST_CAMPAIGN, 0L).takeIf { it > 0 }
        set(value) = sessionPreferences.edit { if (value == null) remove(KEY_LAST_CAMPAIGN) else putLong(KEY_LAST_CAMPAIGN, value) }

    private companion object {
        const val KEY_LAST_CAMPAIGN = "last_campaign_id"
    }
}
