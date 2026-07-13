package io.github.zyrouge.symphony.services.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.store.PlaylistStore
import io.github.zyrouge.symphony.services.database.entities.PlaybackHistory
import io.github.zyrouge.symphony.services.database.store.PlaybackHistoryStore
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.utils.RoomConvertors

@Database(
    entities = [Playlist::class, PlaybackHistory::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(RoomConvertors::class)
abstract class PersistentDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistStore
    abstract fun playbackHistory(): PlaybackHistoryStore

    companion object {
        fun create(symphony: Symphony) = Room
            .databaseBuilder(
                symphony.applicationContext,
                PersistentDatabase::class.java,
                "persistent"
            )
            .fallbackToDestructiveMigration()
            .build()
    }
}
