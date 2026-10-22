package com.irondroid.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.irondroid.data.dao.CampaignDao
import com.irondroid.data.dao.CharacterDao
import com.irondroid.data.dao.HistoryDao
import com.irondroid.data.dao.WorldDao
import com.irondroid.data.entity.ActionCallEntity
import com.irondroid.data.entity.CampaignEntity
import com.irondroid.data.entity.CharacterEntity
import com.irondroid.data.entity.HistoryEntryEntity
import com.irondroid.data.entity.PendingBonusEntity
import com.irondroid.data.entity.ProgressTrackEntity
import com.irondroid.data.entity.TurnEntity
import com.irondroid.data.entity.WorldEntity
import com.irondroid.data.entity.WorldTruthEntity

/**
 * The local database with every saved world and campaign (RF-PAR-00). Schemas are exported to
 * `data/schemas`; a new version needs a migration, never a destructive rebuild.
 */
@Database(
    entities = [
        WorldEntity::class,
        WorldTruthEntity::class,
        CampaignEntity::class,
        CharacterEntity::class,
        ProgressTrackEntity::class,
        PendingBonusEntity::class,
        TurnEntity::class,
        HistoryEntryEntity::class,
        ActionCallEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // 1 → 2 (IRON-41): adds action_calls, the log of calls to the actions interface.
    // 2 → 3 (IRON-56): adds the character's gender for the narrator.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class IrondroidDatabase : RoomDatabase() {
    abstract fun worlds(): WorldDao

    abstract fun campaigns(): CampaignDao

    abstract fun characters(): CharacterDao

    abstract fun history(): HistoryDao

    companion object {
        const val FILE_NAME = "irondroid.db"

        /** Opens the database file in the app's private storage. Keep one instance for the whole app. */
        fun open(context: Context): IrondroidDatabase =
            Room.databaseBuilder(context.applicationContext, IrondroidDatabase::class.java, FILE_NAME).build()
    }
}
