package io.github.zyrouge.symphony.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SimpleFileSystemTest {
    @Test fun `adding a new file creates its folders`() {
        val root = SimpleFileSystem.Folder()
        val file = root.addChildFileIfAbsent(SimplePath("Music/Tool/H.mp3"))
        assertNotNull(file)
        assertEquals(SimplePath("root/Music/Tool/H.mp3").pathString, file!!.fullPath.pathString)
    }

    @Test fun `a file that is already there returns null instead of throwing`() {
        val root = SimpleFileSystem.Folder()
        assertNotNull(root.addChildFileIfAbsent(SimplePath("Music/a.mp3")))
        assertNull(root.addChildFileIfAbsent(SimplePath("Music/a.mp3")))
        assertEquals(1, (root.children.getValue("Music") as SimpleFileSystem.Folder).children.size)
    }

    @Test fun `a file where a folder should be is still an error`() {
        val root = SimpleFileSystem.Folder()
        root.addChildFileIfAbsent(SimplePath("Music"))
        assertThrows(Exception::class.java) { root.addChildFileIfAbsent(SimplePath("Music/a.mp3")) }
    }

    @Test fun `parallel scans adding files to the same new folder keep every file`() {
        val root = SimpleFileSystem.Folder()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val added = (0 until 200).map { i ->
            pool.submit<Boolean> {
                start.await()
                root.addChildFileIfAbsent(SimplePath("Music/New/song-$i.mp3")) != null
            }
        }
        start.countDown()
        val results = added.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(results.all { it })
        val folder = (root.children.getValue("Music") as SimpleFileSystem.Folder).children.getValue("New") as SimpleFileSystem.Folder
        assertEquals(200, folder.children.size)
    }
}
