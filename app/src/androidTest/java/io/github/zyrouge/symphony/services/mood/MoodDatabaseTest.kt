package io.github.zyrouge.symphony.services.mood

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Compiled in Codemagic; execution requires Android, never a desktop JVM pretending to be SQLite. */
class MoodDatabaseTest {
    @Test fun savedProfileRunAndFeedbackSurviveReopeningAndVisitsAreIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = Json { encodeDefaults = true }
        val name = "mood-test-${UUID.randomUUID()}"
        val track = MoodTrackRef(uri = "uri:test", path = "test.mp3", title = "Test", artists = listOf("Artist"), durationMs = 180000)
        val profile = MoodProfile(seeds = listOf(track), saved = true, reactions = mapOf(track.key to MoodReaction(track, 1)))
        val run = MoodRun(profileId = profile.id, queue = listOf(MoodQueueEntry(track = track)), positionMs = 12345)
        val visit = MoodVisit("one-visit", profile.id, run.id, track, 10000, 10000, "next", -0.05f)
        var db = Room.databaseBuilder(context, MoodDatabase::class.java, name).build()
        try {
            db.dao().putProfile(MoodProfileRow(profile.id, profile.updatedAt, json.encodeToString(profile)))
            db.dao().putRun(MoodRunRow(run.id, profile.id, run.updatedAt, json.encodeToString(run)))
            val row = MoodVisitRow(visit.id, profile.id, run.id, visit.endedAt, json.encodeToString(visit))
            assertTrue(db.dao().putVisit(row) != -1L)
            assertEquals(-1L, db.dao().putVisit(row))
            db.close()
            db = Room.databaseBuilder(context, MoodDatabase::class.java, name).build()
            assertEquals(profile, json.decodeFromString<MoodProfile>(db.dao().profile(profile.id)!!.payload))
            assertEquals(run, json.decodeFromString<MoodRun>(db.dao().latestRun(profile.id)!!.payload))
            assertEquals(1, db.dao().visits(profile.id).size)
            db.dao().deleteProfile(profile.id)
            assertNull(db.dao().run(run.id))
            assertTrue(db.dao().allVisits().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
