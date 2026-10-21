package com.irondroid

import android.app.Application
import com.irondroid.data.CampaignStore
import com.irondroid.data.IrondroidDatabase

/** Holds the objects the whole app shares, such as the one database instance. */
class IrondroidApp : Application() {
    val database: IrondroidDatabase by lazy { IrondroidDatabase.open(this) }

    val campaignStore: CampaignStore by lazy { CampaignStore(database) }
}
