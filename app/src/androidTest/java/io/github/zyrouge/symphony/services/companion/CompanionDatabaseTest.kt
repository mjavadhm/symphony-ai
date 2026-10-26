package io.github.zyrouge.symphony.services.companion

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** Run on Android: ./gradlew :app:connectedDebugAndroidTest */
class CompanionDatabaseTest {
    private lateinit var db: CompanionDatabase
    private val track = CompanionTrack("12345678-1234-1234-1234-123456789abc", "Song", "artist", "", 100000,
        CompanionCore.encodeSnapshot(CompanionCore.snapshot("Hello\nWorld")))
    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CompanionDatabase::class.java).build()
        db.dao().insertTrack(track)
    }
    @After fun close() { db.close() }
    private suspend fun draft(text: String = "Interpretation", base: Long? = null) = db.dao().insertRevision(CompanionRevision(
        trackId = track.id, section = "MEANING", target = "", language = "فارسی", snapshot = track.snapshot,
        payload = "{\"text\":\"$text\"}", origin = "user", model = "", baseId = base))
    @Test fun draftIsNotAutomaticallyAccepted() = runBlocking {
        val id = draft()
        assertNull(db.dao().saved(track.id, "MEANING", "", "فارسی"))
        assertEquals("draft", db.dao().revision(id)!!.state)
    }
    @Test fun applyingArchivesButKeepsPreviousVersion() = runBlocking {
        val first = draft(); db.dao().apply(first)
        val second = draft("Changed", first); db.dao().apply(second)
        assertEquals("archived", db.dao().revision(first)!!.state)
        assertEquals(second, db.dao().saved(track.id, "MEANING", "", "فارسی")!!.id)
        assertEquals(2, db.dao().revisionList(track.id).size)
    }
    @Test fun outdatedDraftCannotOverwriteANewerAcceptedVersion() = runBlocking {
        val old = draft(); val new = draft("Newer")
        db.dao().apply(new)
        try { db.dao().apply(old); fail("Expected optimistic conflict") } catch (_: IllegalArgumentException) { }
        assertEquals("draft", db.dao().revision(old)!!.state)
        assertEquals("saved", db.dao().revision(new)!!.state)
    }
    @Test fun changedWordsBlockApplyWithoutLosingDraft() = runBlocking {
        val id = draft()
        db.dao().updateTrack(track.copy(snapshot = CompanionCore.encodeSnapshot(CompanionCore.snapshot("Different words"))))
        try { db.dao().apply(id); fail("Expected stale lyrics rejection") } catch (_: IllegalArgumentException) { }
        assertEquals("draft", db.dao().revision(id)!!.state)
    }
    @Test fun retimingAllowsApply() = runBlocking {
        val id = draft()
        db.dao().updateTrack(track.copy(snapshot = CompanionCore.encodeSnapshot(CompanionCore.snapshot("[00:10]Hello\n[00:20]World"))))
        db.dao().apply(id)
        assertEquals("saved", db.dao().revision(id)!!.state)
    }
    @Test fun linksDoNotDependOnVolatileLibraryIds() = runBlocking {
        db.dao().link(CompanionLink("content://media/external/audio/media/42", track.id))
        assertEquals(track.id, db.dao().linked("content://media/external/audio/media/42")!!.id)
    }
    @Test fun chatAndDraftSurviveDatabaseReadsIndependently() = runBlocking {
        val revision = draft()
        db.dao().insertMessage(CompanionMessage(trackId = track.id, role = "assistant", text = "A correction", section = "MEANING",
            target = "", language = "فارسی", revisionId = revision))
        assertEquals(revision, db.dao().messageList(track.id).single().revisionId)
        assertEquals("draft", db.dao().revision(revision)!!.state)
    }
}
