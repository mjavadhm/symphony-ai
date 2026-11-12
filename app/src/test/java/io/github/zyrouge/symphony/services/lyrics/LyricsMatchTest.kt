package io.github.zyrouge.symphony.services.lyrics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LyricsMatchTest {
    private val track = LyricsTrack("Test Song", setOf("Test Artist"), "Album", 180.0)
    private fun record(title: String = "Test Song", artist: String = "Test Artist", duration: Double = 180.0,
                       synced: String? = null, plain: String? = "Plain test text", instrumental: Boolean = false) =
        LyricsRecord(1, title, artist, "Album", duration, instrumental, synced, plain)
    @Test fun `synchronized match wins over plain match`() {
        val result = LyricsMatch.select(track, listOf(record(), record(synced = "[00:01.20] Timed test")))
        assertTrue(result!!.synced)
    }
    @Test fun `plain lyrics are the fallback`() { assertFalse(LyricsMatch.select(track, listOf(record()))!!.synced) }
    @Test fun `same title from wrong artist is rejected`() {
        assertNull(LyricsMatch.select(track, listOf(record(artist = "Different Artist"))))
    }
    @Test fun `wrong recording duration is rejected`() {
        assertNull(LyricsMatch.select(track, listOf(record(duration = 185.0))))
    }
    @Test fun `duration tolerance includes three seconds`() {
        assertNotNull(LyricsMatch.select(track, listOf(record(duration = 183.0))))
    }
    @Test fun `unknown duration is not a confident automatic match`() {
        assertNull(LyricsMatch.select(track, listOf(record(duration = 0.0))))
    }
    @Test fun `live recording cannot match the studio title`() {
        assertNull(LyricsMatch.select(track, listOf(record(title = "Test Song (Live)"))))
    }
    @Test fun `instrumentals do not produce lyrics`() {
        assertNull(LyricsMatch.select(track, listOf(record(instrumental = true))))
    }
    @Test fun `empty synced field falls back to plain`() {
        assertFalse(LyricsMatch.select(track, listOf(record(synced = " ")))!!.synced)
    }
    @Test fun `non timed text in synced field is not treated as timed`() {
        assertFalse(LyricsMatch.select(track, listOf(record(synced = "not synchronized")))!!.synced)
    }
    @Test fun `unicode artist normalization supports Persian forms`() {
        assertEquals(LyricsMatch.normalize("كی"), LyricsMatch.normalize("کي"))
    }
    @Test fun `case punctuation and spacing are normalized`() {
        assertNotNull(LyricsMatch.select(track, listOf(record(title = "TEST   SONG", artist = "test artist"))))
    }
}
