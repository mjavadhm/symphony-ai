package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoplaySessionTest {
    @Test
    fun `ordinary autoplay has no radio anchor`() {
        val session = AutoplaySession()
        assertNull(session.begin()!!.anchorSongId)
    }

    @Test
    fun `radio request keeps its seed`() {
        val session = AutoplaySession()
        session.reset("song-a")
        assertEquals("song-a", session.begin()!!.anchorSongId)
    }

    @Test
    fun `stopping clears the anchor and rejects old results`() {
        val session = AutoplaySession()
        session.reset("song-a")
        val old = session.begin()!!
        assertTrue(session.reset())
        var applied = false
        assertFalse(session.complete(old) { applied = true })
        assertFalse(applied)
        assertNull(session.begin()!!.anchorSongId)
    }

    @Test
    fun `switching radios rejects the first radio result`() {
        val session = AutoplaySession()
        session.reset("song-a")
        val old = session.begin()!!
        session.reset("song-b")
        val current = session.begin()!!
        assertFalse(session.complete(old) { error("Stale result was applied") })
        assertEquals("song-b", current.anchorSongId)
        assertTrue(session.complete(current) {})
    }

    @Test
    fun `repeated skips share one request`() {
        val session = AutoplaySession()
        val request = session.begin(advanceFromIndex = 0)!!
        repeat(5) { assertNull(session.begin(advanceFromIndex = 0)) }
        assertTrue(session.complete(request) { assertEquals(0, it) })
    }

    @Test
    fun `end of song during prefetch upgrades it to an advance`() {
        val session = AutoplaySession()
        val prefetch = session.begin()!!
        assertNull(session.begin(advanceFromIndex = 0))
        assertTrue(session.complete(prefetch) { assertEquals(0, it) })
    }

    @Test
    fun `prefetch alone does not request a playback jump`() {
        val session = AutoplaySession()
        val prefetch = session.begin()!!
        assertTrue(session.complete(prefetch) { assertNull(it) })
    }

    @Test
    fun `reset cancels the pending job`() {
        val session = AutoplaySession()
        val request = session.begin()!!
        var cancelled = false
        session.onCancel(request) { cancelled = true }
        session.reset()
        assertTrue(cancelled)
    }

    @Test
    fun `job attached after reset is cancelled immediately`() {
        val session = AutoplaySession()
        val old = session.begin()!!
        session.reset()
        var cancelled = false
        session.onCancel(old) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test
    fun `old job cleanup cannot clear a new request`() {
        val session = AutoplaySession()
        val old = session.begin()!!
        session.reset()
        val current = session.begin()!!
        session.abandon(old)
        assertNull(session.begin())
        assertTrue(session.complete(current) {})
    }

    @Test
    fun `completed request cannot be applied twice`() {
        val session = AutoplaySession()
        val request = session.begin()!!
        assertTrue(session.complete(request) {})
        assertFalse(session.complete(request) { error("Result was applied twice") })
        assertNotNull(session.begin())
    }

    @Test
    fun `abandoned request can be retried`() {
        val session = AutoplaySession()
        val request = session.begin()!!
        session.abandon(request)
        assertNotNull(session.begin())
    }
}
