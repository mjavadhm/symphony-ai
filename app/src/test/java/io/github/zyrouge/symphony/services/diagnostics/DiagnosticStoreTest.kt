package io.github.zyrouge.symphony.services.diagnostics

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiagnosticStoreTest {
    @TempDir lateinit var dir: File
    private fun row(time: Long = 1, code: String = "event") = buildJsonObject { put("id", java.util.UUID.randomUUID().toString()); put("time", time); put("code", code) }
    @Test fun `reports survive a process restart`() {
        DiagnosticStore(dir).append(row())
        assertEquals(1, DiagnosticStore(dir).recent().size)
    }
    @Test fun `no time based expiry is applied`() {
        val store = DiagnosticStore(dir); store.append(row(time = 1))
        dir.listFiles()!!.forEach { it.setLastModified(1) }
        assertEquals(1L, store.recent().single()["time"]!!.jsonPrimitive.long)
    }
    @Test fun `volume stays under cap and newest records survive rotation`() {
        val store = DiagnosticStore(dir, 2000)
        repeat(200) { store.append(row(it.toLong())) }
        assertTrue(store.usage() <= 2000); assertEquals(199L, store.recent().first()["time"]!!.jsonPrimitive.long)
    }
    @Test fun `normal logs rotate before older crashes`() {
        val store = DiagnosticStore(dir, 2000); store.append(row(code = "crash"), crash = true)
        repeat(150) { store.append(row(it.toLong(), "normal")) }
        assertTrue(store.recent().any { it["code"]!!.jsonPrimitive.content == "crash" })
        assertTrue(store.usage() <= 2000)
    }
    @Test fun `crash only volume is also bounded`() {
        val store = DiagnosticStore(dir, 1000); repeat(50) { store.append(row(it.toLong()), crash = true) }
        assertTrue(store.usage() <= 1000); assertEquals(49L, store.recent().first()["time"]!!.jsonPrimitive.long)
    }
    @Test fun `export includes metadata and retained records only`() {
        val store = DiagnosticStore(dir); store.append(row(), crash = true)
        File(dir, "unrelated-secret.txt").writeText("DO NOT EXPORT")
        val output = ByteArrayOutputStream(); store.export(output, buildJsonObject { put("appVersion", "test") })
        val names = mutableListOf<String>(); val text = StringBuilder()
        ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break; names += entry.name; text.append(zip.readBytes().decodeToString())
        } }
        assertTrue("device.json" in names); assertTrue(names.any { it.endsWith(".crash") })
        assertFalse(text.contains("DO NOT EXPORT"))
    }
    @Test fun `clear removes reports without deleting unrelated files`() {
        val store = DiagnosticStore(dir); store.append(row()); File(dir, "keep").writeText("yes")
        store.clear(); assertEquals(0, store.recent().size); assertTrue(File(dir, "keep").exists())
    }
    @Test fun `corrupt lines are ignored without losing valid records`() {
        val store = DiagnosticStore(dir); store.append(row())
        dir.listFiles()!!.first { it.name.endsWith(".jsonl") }.appendText("broken-json\n")
        assertEquals(1, store.recent().size)
    }
    @Test fun `concurrent instances share a lock and obey a single cap`() {
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..4).map { Thread {
            try { val store = DiagnosticStore(dir, 4000); repeat(40) { store.append(row(it.toLong())) } }
            catch (e: Throwable) { errors.add(e) }
        }.also { it.start() } }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.toString()); assertTrue(DiagnosticStore(dir, 4000).usage() <= 4000)
    }
    @Test fun `large records cannot bypass the storage cap`() {
        assertThrows(IllegalArgumentException::class.java) { DiagnosticStore(dir).append(row(code = "a".repeat(40000))) }
    }
}

class DiagnosticPrivacyTest {
    @Test fun `exception messages and nested secret bodies are never stored`() {
        val e = IllegalStateException("Authorization Bearer SECRET lyrics", IllegalArgumentException("password=other-secret"))
        val text = DiagnosticPrivacy.throwable(e)
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("other-secret"))
        assertTrue(text.contains("IllegalStateException")); assertTrue(text.contains("IllegalArgumentException"))
    }
    @Test fun `URL paths keep route vocabulary not user IDs or tokens`() {
        val path = DiagnosticPrivacy.route("/v1/tracks/123456/download?key=SECRET")
        assertEquals("/v1/tracks/{value}/download", path); assertFalse(path.contains("SECRET"))
    }
    @Test fun `request IDs accept UUID forms but reject injection and arbitrary data`() {
        assertNotNull(DiagnosticPrivacy.requestId("12345678-1234-1234-1234-123456789abc"))
        assertNotNull(DiagnosticPrivacy.requestId("A".repeat(32)))
        assertNull(DiagnosticPrivacy.requestId("SECRET")); assertNull(DiagnosticPrivacy.requestId("abc\r\nAuthorization:secret"))
    }
}
