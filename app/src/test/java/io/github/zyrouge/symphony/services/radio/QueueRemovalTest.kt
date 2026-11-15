package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QueueRemovalTest {
    @Test fun `removing the first two songs while the fourth plays keeps the current song`() {
        val queue = listOf("a", "b", "c", "d")
        val result = QueueRemoval.remove(queue, queue, 3, listOf(0, 1))
        assertEquals(listOf("c", "d"), result.current)
        assertEquals(result.current, result.original)
        assertEquals(1, result.currentIndex)
        assertFalse(result.currentRemoved)
        assertEquals("d", result.current[result.currentIndex])
    }

    @Test fun `shuffled deletion removes the selected song from the original order`() {
        val result = QueueRemoval.remove(listOf("a", "b", "c", "d"), listOf("c", "a", "d", "b"), 0, listOf(1))
        assertEquals(listOf("c", "d", "b"), result.current)
        assertEquals(listOf("b", "c", "d"), result.original)
        assertEquals("c", result.current[result.currentIndex])
        assertFalse(result.currentRemoved)
    }

    @Test fun `removing the current song and earlier rows selects its successor`() {
        val queue = listOf("a", "b", "c", "d", "e")
        val result = QueueRemoval.remove(queue, queue, 2, listOf(0, 2, 4))
        assertEquals(listOf("b", "d"), result.current)
        assertEquals("d", result.current[result.currentIndex])
        assertTrue(result.currentRemoved)
    }

    @Test fun `deleting all rows leaves no current song`() {
        val result = QueueRemoval.remove(listOf("a", "b"), listOf("b", "a"), 1, listOf(0, 1))
        assertTrue(result.original.isEmpty())
        assertTrue(result.current.isEmpty())
        assertEquals(-1, result.currentIndex)
        assertTrue(result.currentRemoved)
    }

    @Test fun `stale and repeated selections do not delete additional rows`() {
        val queue = listOf("a", "b", "c")
        val result = QueueRemoval.remove(queue, queue, 2, listOf(-1, 0, 0, 3))
        assertEquals(listOf("b", "c"), result.current)
        assertEquals(1, result.currentIndex)
    }

    @Test fun `duplicate songs lose only the selected occurrence`() {
        val result = QueueRemoval.remove(listOf("a", "b", "a", "c"), listOf("c", "a", "b", "a"), 0, listOf(1))
        assertEquals(listOf("c", "b", "a"), result.current)
        assertEquals(listOf("b", "a", "c"), result.original)
    }

    @Test fun `deleting the last song keeps the new index in bounds`() {
        val queue = listOf("a", "b", "c")
        val result = QueueRemoval.remove(queue, queue, 2, listOf(2))
        assertEquals("b", result.current[result.currentIndex])
        assertTrue(result.currentRemoved)
    }
}
