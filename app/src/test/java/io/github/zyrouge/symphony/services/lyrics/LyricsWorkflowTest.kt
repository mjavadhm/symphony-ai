package io.github.zyrouge.symphony.services.lyrics

import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LyricsInventoryTest {
    @Test fun `embedded lyrics avoid any second lookup`() {
        assertEquals(LyricsInventory.Presence.EMBEDDED, LyricsInventory.inspect({ "lyrics" }, { error("Should not read sidecar") }))
    }
    @Test fun `sidecar is not falsely reported missing`() {
        assertEquals(LyricsInventory.Presence.SIDECAR, LyricsInventory.inspect({ null }, { "[00:01]Words" }))
    }
    @Test fun `only truly absent lyrics are missing`() {
        assertEquals(LyricsInventory.Presence.MISSING, LyricsInventory.inspect({ null }, { null }))
    }
    @Test fun `blank tags still count as missing`() {
        assertEquals(LyricsInventory.Presence.MISSING, LyricsInventory.inspect({ " \n " }, { "" }))
    }
    @Test fun `read failure is not a missing file`() {
        assertThrows(java.io.IOException::class.java) { LyricsInventory.inspect({ throw java.io.IOException("Permission denied") }, { null }) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRequestGateTest {
    @Test fun `spacing starts after the previous response finished`() = runTest {
        val gate = LyricsRequestGate(1500, { testScheduler.currentTime })
        gate.request { delay(300) }
        val secondAt = gate.request { testScheduler.currentTime }
        assertEquals(1800L, secondAt)
    }
    @Test fun `concurrent lookups still have only one HTTP request in flight`() = runTest {
        val gate = LyricsRequestGate(10, { testScheduler.currentTime })
        var active = 0; var maximum = 0
        val jobs = (1..5).map { async { gate.request { active++; maximum = maxOf(maximum, active); delay(20); active-- } } }
        jobs.awaitAll()
        assertEquals(1, maximum)
    }
    @Test fun `restored cooldown prevents a request before its deadline`() = runTest {
        val gate = LyricsRequestGate(0, { testScheduler.currentTime })
        gate.restoreCooldown(5000)
        val waits = mutableListOf<Long>()
        val at = gate.request(waits::add) { testScheduler.currentTime }
        assertEquals(5000L, at)
        assertEquals(5000L, waits.first())
        assertEquals(0L, waits.last())
    }
    @Test fun `retry after uses positive jitter and never retries earlier`() {
        val gate = LyricsRequestGate(0, { 1000 })
        gate.defer(5000)
        assertTrue(gate.cooldownUntil in 6000..7000)
    }
    @Test fun `shorter cooldown cannot erase a longer deadline`() {
        val gate = LyricsRequestGate(0, { 1000 })
        gate.restoreCooldown(50000); gate.defer(2000)
        assertEquals(50000L, gate.cooldownUntil)
    }
    @Test fun `cancelling the countdown sends no HTTP request`() = runTest {
        val gate = LyricsRequestGate(0, { testScheduler.currentTime })
        gate.restoreCooldown(10000)
        var calls = 0
        val job = launch { gate.request { calls++ } }
        runCurrent(); job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(0, calls)
    }
    @Test fun `failed request also observes spacing on retry`() = runTest {
        val gate = LyricsRequestGate(1500, { testScheduler.currentTime })
        try { gate.request<Unit> { error("HTTP failed") } } catch (_: IllegalStateException) { }
        assertEquals(1500L, gate.request { testScheduler.currentTime })
    }
}

class LyricsLookupCacheTest {
    @TempDir lateinit var root: Path
    private val track = LyricsTrack("Song", setOf("Artist"), "Album", 180.0)
    private val candidate = LyricsCandidate(LyricsRecord(1, "Song", "Artist", "Album", 180.0, false, "[00:01]Words", "Words"), "[00:01]Words", true)
    private val base = "https://lrclib.net"
    @Test fun `positive result survives recreating the cache`() {
        LyricsLookupCache(root.toFile(), { 1000 }).put(base, track, candidate)
        assertEquals(candidate, LyricsLookupCache(root.toFile(), { 2000 }).get(base, track)!!.candidate)
    }
    @Test fun `negative result is distinct from a cache miss`() {
        val cache = LyricsLookupCache(root.toFile(), { 1000 })
        assertNull(cache.get(base, track)); cache.put(base, track, null)
        assertNotNull(cache.get(base, track)); assertNull(cache.get(base, track)!!.candidate)
    }
    @Test fun `negative cache expires after six hours`() {
        LyricsLookupCache(root.toFile(), { 0 }).put(base, track, null)
        assertNull(LyricsLookupCache(root.toFile(), { 6 * 60 * 60_000L }).get(base, track))
    }
    @Test fun `positive cache expires after a week`() {
        LyricsLookupCache(root.toFile(), { 0 }).put(base, track, candidate)
        assertNull(LyricsLookupCache(root.toFile(), { 7 * 24 * 60 * 60_000L }).get(base, track))
    }
    @Test fun `provider roots have isolated caches`() {
        val cache = LyricsLookupCache(root.toFile(), { 1000 }); cache.put(base, track, candidate)
        assertNull(cache.get("https://example.com", track))
    }
    @Test fun `api suffix normalizes to the same cache key`() {
        val cache = LyricsLookupCache(root.toFile(), { 1000 }); cache.put("$base/api/", track, candidate)
        assertNotNull(cache.get(base, track))
    }
    @Test fun `different recording metadata does not reuse lyrics`() {
        val cache = LyricsLookupCache(root.toFile(), { 1000 }); cache.put(base, track, candidate)
        assertNull(cache.get(base, track.copy(durationSeconds = 200.0)))
        assertNull(cache.get(base, track.copy(album = "Live")))
        assertNull(cache.get(base, track.copy(artists = setOf("Other artist"))))
    }
    @Test fun `corrupt cache is discarded without crashing`() {
        val cache = LyricsLookupCache(root.toFile(), { 1000 }); cache.put(base, track, candidate)
        root.toFile().listFiles()!!.single().writeText("bad json")
        assertNull(cache.get(base, track))
    }
    @Test fun `replacing cached results is atomic and readable`() {
        val cache = LyricsLookupCache(root.toFile(), { 1000 }); cache.put(base, track, null); cache.put(base, track, candidate)
        assertEquals(candidate, cache.get(base, track)!!.candidate)
        assertEquals(1, root.toFile().listFiles()!!.size)
    }
}
