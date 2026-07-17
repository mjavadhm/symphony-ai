package io.github.zyrouge.symphony.services.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.store.PlaylistStore
import io.github.zyrouge.symphony.services.database.entities.PlaybackHistory
import io.github.zyrouge.symphony.services.database.store.PlaybackHistoryStore
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import io.github.zyrouge.symphony.services.database.store.CustomMixStore
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.utils.RoomConvertors
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Playlist::class, PlaybackHistory::class, CustomMix::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(RoomConvertors::class)
abstract class PersistentDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistStore
    abstract fun playbackHistory(): PlaybackHistoryStore
    abstract fun customMixes(): CustomMixStore

    companion object {
        val MIGRATION_TELEMETRY_V3 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_history ADD COLUMN source TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE playback_history ADD COLUMN audioOutput TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_mixes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, prompt TEXT NOT NULL, icon TEXT NOT NULL, " +
                    "isBuiltIn INTEGER NOT NULL DEFAULT 0, trackCount INTEGER NOT NULL DEFAULT 25, " +
                    "sortOrder INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        fun create(symphony: Symphony) = Room
            .databaseBuilder(
                symphony.applicationContext,
                PersistentDatabase::class.java,
                "persistent"
            )
            .addMigrations(MIGRATION_TELEMETRY_V3)
            .fallbackToDestructiveMigration()
            .build()
    }
}
