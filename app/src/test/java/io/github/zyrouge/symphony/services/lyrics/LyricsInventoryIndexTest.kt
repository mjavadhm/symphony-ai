package io.github.zyrouge.symphony.services.lyrics

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LyricsInventoryIndexTest {
    @TempDir lateinit var directory: File
    private fun index() = LyricsInventoryIndex(File(directory, "inventory.json"))
    @Test fun `inventory survives reopening without reading audio files`() {
        index().apply { put("content://music/1", "100:20", "MISSING"); save() }
        assertEquals("MISSING", index().get("content://music/1", "100:20"))
    }
    @Test fun `changed file metadata requires a fresh check`() {
        val index = index(); index.put("song", "100:20", "EXISTING")
        assertNull(index.get("song", "100:21")); assertNull(index.get("song", "101:20"))
    }
    @Test fun `library album and single song reuse the same stable URI`() {
        val index = index(); index.put("content://music/1", "1", "SIDECAR")
        repeat(3) { assertEquals("SIDECAR", index.get("content://music/1", "1")) }
        assertNull(index.get("content://music/2", "1"))
    }
    @Test fun `corrupt inventory is not treated as missing lyrics`() {
        File(directory, "inventory.json").writeText("broken")
        assertNull(index().get("song", "1"))
    }
    @Test fun `only verified presence states can be persisted`() {
        assertThrows(IllegalArgumentException::class.java) { index().put("song", "1", "SEARCHING") }
    }
    @Test fun `restoring audio invalidates the saved inventory`() {
        val index = index(); index.put("song", "1", "EXISTING"); index.clear(); index.save()
        assertNull(index().get("song", "1"))
    }
    @Test fun `permission exceptions wrapped by IO remain actionable`() {
        assertTrue(LyricsWriteErrors.isAccess(java.io.IOException("Write failed", SecurityException("denied"))))
        assertTrue(LyricsWriteErrors.isAccess(java.io.FileNotFoundException("open failed: EACCES (Permission denied)")))
        assertTrue(LyricsWriteErrors.isAccess(java.io.IOException("EPERM")))
        assertFalse(LyricsWriteErrors.isAccess(java.io.IOException("Disk full")))
    }
}
