package io.github.zyrouge.symphony.services.mood

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "mood_profiles")
data class MoodProfileRow(@PrimaryKey val id: String, val updatedAt: Long, val payload: String)

@Entity(tableName = "mood_runs", indices = [Index("profileId")], foreignKeys = [ForeignKey(
    entity = MoodProfileRow::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)])
data class MoodRunRow(@PrimaryKey val id: String, val profileId: String, val updatedAt: Long, val payload: String)

@Entity(tableName = "mood_visits", indices = [Index("profileId"), Index("runId")], foreignKeys = [ForeignKey(
    entity = MoodProfileRow::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)])
data class MoodVisitRow(@PrimaryKey val id: String, val profileId: String, val runId: String, val endedAt: Long, val payload: String)

@Dao
interface MoodDao {
    @Query("SELECT * FROM mood_profiles ORDER BY updatedAt DESC") fun observeProfiles(): Flow<List<MoodProfileRow>>
    @Query("SELECT * FROM mood_profiles ORDER BY updatedAt DESC") suspend fun profiles(): List<MoodProfileRow>
    @Query("SELECT * FROM mood_profiles WHERE id = :id") suspend fun profile(id: String): MoodProfileRow?
    @Upsert suspend fun putProfile(row: MoodProfileRow)
    @Query("DELETE FROM mood_profiles WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("SELECT * FROM mood_runs WHERE id = :id") suspend fun run(id: String): MoodRunRow?
    @Query("SELECT * FROM mood_runs WHERE profileId = :profileId ORDER BY updatedAt DESC LIMIT 1") suspend fun latestRun(profileId: String): MoodRunRow?
    @Upsert suspend fun putRun(row: MoodRunRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putVisit(row: MoodVisitRow): Long
    @Query("SELECT * FROM mood_visits WHERE profileId = :profileId ORDER BY endedAt") suspend fun visits(profileId: String): List<MoodVisitRow>
    @Query("SELECT * FROM mood_visits") suspend fun allVisits(): List<MoodVisitRow>
    @Query("SELECT * FROM mood_visits WHERE id = :id") suspend fun visit(id: String): MoodVisitRow?
}

@Database(entities = [MoodProfileRow::class, MoodRunRow::class, MoodVisitRow::class], version = 1, exportSchema = false)
abstract class MoodDatabase : RoomDatabase() {
    abstract fun dao(): MoodDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, MoodDatabase::class.java, "mood_sessions").build()
    }
}

internal object MoodJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun profile(row: MoodProfileRow): MoodProfile = json.decodeFromString(row.payload)
    fun run(row: MoodRunRow): MoodRun = json.decodeFromString(row.payload)
    fun visit(row: MoodVisitRow): MoodVisit = json.decodeFromString(row.payload)
    fun row(profile: MoodProfile) = MoodProfileRow(profile.id, profile.updatedAt, json.encodeToString(profile))
    fun row(run: MoodRun) = MoodRunRow(run.id, run.profileId, run.updatedAt, json.encodeToString(run))
    fun row(visit: MoodVisit) = MoodVisitRow(visit.id, visit.profileId, visit.runId, visit.endedAt, json.encodeToString(visit))
}
