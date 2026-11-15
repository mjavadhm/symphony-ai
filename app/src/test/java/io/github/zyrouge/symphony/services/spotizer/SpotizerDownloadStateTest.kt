package io.github.zyrouge.symphony.services.spotizer

import io.github.zyrouge.symphony.services.spotizer.SpotizerDownloadManager.Item
import io.github.zyrouge.symphony.services.spotizer.SpotizerDownloadManager.Phase
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpotizerDownloadStateTest {
    private fun item(id: Long, trackId: Long = id) = Item(id, SpotizerTrack(id = trackId), SpotizerQuality.MP3_320)

    @Test fun `simultaneous completion and progress updates both survive`() {
        val state = SpotizerDownloadState()
        state.enqueue(item(1)); state.enqueue(item(2))
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(1L, 2L).map { id -> executor.submit {
                val firstAttempt = AtomicBoolean(true)
                state.update(id) {
                    // Both writers initially read the same list. A CAS retry must preserve the winner.
                    if (firstAttempt.getAndSet(false)) barrier.await(5, TimeUnit.SECONDS)
                    it.copy(phase = if (id == 1L) Phase.Done else Phase.Downloading, downloadedBytes = 1024)
                }
            } }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(Phase.Done, state.items.value.first { it.id == 1L }.phase)
            assertEquals(Phase.Downloading, state.items.value.first { it.id == 2L }.phase)
            assertTrue(state.items.value.all { it.downloadedBytes == 1024L })
        } finally { executor.shutdownNow() }
    }

    @Test fun `concurrent enqueue accepts a track only once`() {
        val state = SpotizerDownloadState()
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(1L, 2L).map { id -> executor.submit<Boolean> {
                barrier.await(5, TimeUnit.SECONDS)
                state.enqueue(item(id, trackId = 42))
            } }
            assertEquals(1, jobs.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, state.items.value.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun `worker progress cannot resurrect a cancelled download`() {
        val state = SpotizerDownloadState()
        state.enqueue(item(1))
        state.update(1) { it.copy(phase = Phase.Cancelled) }
        state.update(1) { it.copy(phase = Phase.Downloading, downloadedBytes = 100) }
        assertEquals(Phase.Cancelled, state.items.value.single().phase)
        assertEquals(0L, state.items.value.single().downloadedBytes)
        assertTrue(state.retry(1))
        assertEquals(Phase.Queued, state.items.value.single().phase)
        assertFalse(state.retry(1))
    }

    @Test fun `clearing finished rows preserves active downloads`() {
        val state = SpotizerDownloadState()
        state.enqueue(item(1)); state.enqueue(item(2))
        state.update(1) { it.copy(phase = Phase.Done) }
        state.clearFinished()
        assertEquals(listOf(2L), state.items.value.map { it.id })
    }
}
