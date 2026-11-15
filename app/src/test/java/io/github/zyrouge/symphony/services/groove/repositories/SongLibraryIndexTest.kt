package io.github.zyrouge.symphony.services.groove.repositories

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SongLibraryIndexTest {
    private data class Track(val id: String, val path: String)
    private fun index() = SongLibraryIndex<Track>({ it.id }, { it.path })
    private val first = Track("a", "music/a.mp3")
    private val second = Track("b", "music/b.mp3")

    @Test fun `reset keeps playback metadata until scan completion`() {
        val index = index()
        index.put(first); index.put(second)
        index.reset()
        assertTrue(index.ids.value.isEmpty())
        assertEquals(first, index["a"])
        assertEquals(second, index["b"])
    }

    @Test fun `completed rescan removes deleted songs and path mappings`() {
        val index = index()
        index.put(first); index.put(second)
        index.reset(); index.put(first)
        index.finishScan(setOf("a"))
        assertEquals(listOf("a"), index.ids.value)
        assertEquals(listOf(first), index.values())
        assertEquals(mapOf(first.path to "a"), index.paths)
        assertNull(index["b"])
    }

    @Test fun `incremental scan also removes deleted songs`() {
        val index = index()
        index.put(first); index.put(second)
        index.finishScan(setOf("b"))
        assertEquals(listOf("b"), index.ids.value)
        assertNull(index["a"])
        assertEquals(mapOf(second.path to "b"), index.paths)
    }

    @Test fun `current playback metadata is retained without appearing in the library`() {
        val index = index()
        index.put(first); index.put(second)
        index.finishScan(setOf("a"), retainedIds = setOf("b"))
        assertEquals(second, index["b"])
        assertEquals(listOf(first), index.values())
        assertEquals(listOf("a"), index.ids.value)
        assertFalse(second.path in index.paths)
        index.finishScan(setOf("a"))
        assertNull(index["b"])
    }

    @Test fun `an interrupted scan does not evict unseen playback lookups`() {
        val index = index()
        index.put(first); index.put(second)
        index.reset(); index.put(first)
        // No finishScan: the rest of the library may simply have been inaccessible.
        assertEquals(second, index["b"])
        assertEquals("b", index.paths[second.path])
    }

    @Test fun `a reindexed path replaces the visible id without duplicate library rows`() {
        val index = index()
        index.put(first)
        val changed = first.copy(id = "new-a")
        index.put(changed); index.put(changed)
        index.finishScan(setOf("new-a"), retainedIds = setOf("a"))
        assertEquals(listOf("new-a"), index.ids.value)
        assertEquals(listOf(changed), index.values())
        assertEquals("new-a", index.paths[first.path])
        assertEquals(first, index["a"])
    }
}
