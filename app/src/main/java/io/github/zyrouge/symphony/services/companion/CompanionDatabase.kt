package io.github.zyrouge.symphony.services.companion

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "companion_tracks")
data class CompanionTrack(
    @PrimaryKey val id: String,
    val title: String, val artists: String, val album: String, val duration: Long,
    val snapshot: String, val createdAt: Long = System.currentTimeMillis(),
)
@Entity(tableName = "companion_links", indices = [Index("trackId")], foreignKeys = [ForeignKey(
    entity = CompanionTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)])
data class CompanionLink(@PrimaryKey val uri: String, val trackId: String)
@Entity(tableName = "companion_sources", indices = [Index("trackId")], foreignKeys = [ForeignKey(
    entity = CompanionTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)])
data class CompanionSource(@PrimaryKey val id: String, val trackId: String, val title: String,
    val url: String, val excerpt: String, val origin: String, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "companion_revisions", indices = [Index(value = ["trackId", "section", "target", "language"])], foreignKeys = [ForeignKey(
    entity = CompanionTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)])
data class CompanionRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String, val section: String, val target: String, val language: String,
    val snapshot: String, val payload: String, val origin: String, val model: String,
    val baseId: Long?, val state: String = "draft", val createdAt: Long = System.currentTimeMillis(),
)
@Entity(tableName = "companion_messages", indices = [Index("trackId")], foreignKeys = [ForeignKey(
    entity = CompanionTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)])
data class CompanionMessage(@PrimaryKey(autoGenerate = true) val id: Long = 0, val trackId: String,
    val role: String, val text: String, val section: String, val target: String, val language: String,
    val revisionId: Long? = null, val createdAt: Long = System.currentTimeMillis())

@Dao
abstract class CompanionDao {
    @Query("SELECT * FROM companion_tracks ORDER BY createdAt DESC") abstract fun observeTracks(): Flow<List<CompanionTrack>>
    @Query("SELECT * FROM companion_tracks") abstract suspend fun tracks(): List<CompanionTrack>
    @Query("SELECT * FROM companion_tracks WHERE id = :id") abstract fun observeTrack(id: String): Flow<CompanionTrack?>
    @Query("SELECT * FROM companion_tracks WHERE id = :id") abstract suspend fun track(id: String): CompanionTrack?
    @Query("SELECT * FROM companion_tracks WHERE id IN (SELECT trackId FROM companion_links WHERE uri = :uri)") abstract suspend fun linked(uri: String): CompanionTrack?
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertTrack(track: CompanionTrack)
    @Update abstract suspend fun updateTrack(track: CompanionTrack)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun link(link: CompanionLink)
    @Query("SELECT * FROM companion_sources WHERE trackId = :id ORDER BY createdAt") abstract fun sources(id: String): Flow<List<CompanionSource>>
    @Query("SELECT * FROM companion_sources WHERE trackId = :id ORDER BY createdAt") abstract suspend fun sourceList(id: String): List<CompanionSource>
    @Insert abstract suspend fun insertSource(source: CompanionSource)
    @Query("SELECT * FROM companion_revisions WHERE trackId = :id ORDER BY id DESC") abstract fun revisions(id: String): Flow<List<CompanionRevision>>
    @Query("SELECT * FROM companion_revisions WHERE trackId = :id ORDER BY id") abstract suspend fun revisionList(id: String): List<CompanionRevision>
    @Query("SELECT * FROM companion_revisions WHERE id = :id") abstract suspend fun revision(id: Long): CompanionRevision?
    @Insert abstract suspend fun insertRevision(revision: CompanionRevision): Long
    @Query("UPDATE companion_revisions SET baseId = :base WHERE id = :id") abstract suspend fun setBase(id: Long, base: Long?)
    @Query("SELECT * FROM companion_revisions WHERE trackId = :trackId AND section = :section AND target = :target AND language = :language AND state = 'saved' ORDER BY id DESC LIMIT 1")
    abstract suspend fun saved(trackId: String, section: String, target: String, language: String): CompanionRevision?
    @Query("UPDATE companion_revisions SET state = 'archived' WHERE trackId = :trackId AND section = :section AND target = :target AND language = :language AND state = 'saved'")
    protected abstract suspend fun archive(trackId: String, section: String, target: String, language: String)
    @Query("UPDATE companion_revisions SET state = 'saved' WHERE id = :id") protected abstract suspend fun accept(id: Long)
    @Transaction open suspend fun apply(id: Long) {
        val draft = revision(id) ?: error("Draft no longer exists")
        require(draft.state == "draft") { "Only drafts can be applied" }
        val current = saved(draft.trackId, draft.section, draft.target, draft.language)
        require(current?.id == draft.baseId) { "The saved version changed. Create a new draft before applying." }
        val profile = track(draft.trackId) ?: error("Dossier no longer exists")
        require(CompanionCore.decodeSnapshot(profile.snapshot).hash == CompanionCore.decodeSnapshot(draft.snapshot).hash) {
            "Lyrics changed since this draft. Regenerate against the current lyrics."
        }
        archive(draft.trackId, draft.section, draft.target, draft.language); accept(id)
    }
    @Query("SELECT * FROM companion_messages WHERE trackId = :id ORDER BY id") abstract fun messages(id: String): Flow<List<CompanionMessage>>
    @Query("SELECT * FROM companion_messages WHERE trackId = :id ORDER BY id") abstract suspend fun messageList(id: String): List<CompanionMessage>
    @Insert abstract suspend fun insertMessage(message: CompanionMessage): Long
}

@Database(entities = [CompanionTrack::class, CompanionLink::class, CompanionSource::class, CompanionRevision::class, CompanionMessage::class], version = 1, exportSchema = false)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun dao(): CompanionDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, CompanionDatabase::class.java, "song_companion").build()
    }
}
