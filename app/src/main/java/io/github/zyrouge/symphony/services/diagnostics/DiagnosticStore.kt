package io.github.zyrouge.symphony.services.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/** Byte-bounded journal shared safely with the separate crash-report process. No age expiry. */
class DiagnosticStore(private val root: File, val maxBytes: Long = MAX_BYTES) {
    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_RECORD_BYTES = 32 * 1024
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
    private val mutex = locks.getOrPut(root.canonicalPath) { Any() }
    private fun <T> locked(block: () -> T): T = synchronized(mutex) {
        root.mkdirs()
        RandomAccessFile(File(root, ".lock"), "rw").use { f -> f.channel.lock().use { block() } }
    }
    private fun files() = root.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".jsonl") || it.name.endsWith(".crash")) }
    private fun reserve(incoming: Long) {
        require(incoming <= maxBytes)
        val candidates = files().sortedWith(compareBy<File> { it.name.endsWith(".crash") }.thenBy { it.name })
        var total = candidates.sumOf { it.length() }
        for (file in candidates) {
            if (total + incoming <= maxBytes) break
            val size = file.length()
            check(file.delete()) { "Cannot rotate diagnostics" }; total -= size
        }
    }
    fun append(record: JsonObject, crash: Boolean = false) = locked {
        val bytes = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECORD_BYTES)
        reserve(bytes.size.toLong())
        if (crash) {
            val file = File(root, String.format(java.util.Locale.ROOT, "%019d-%s.crash", System.currentTimeMillis(), UUID.randomUUID()))
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        } else {
            val active = files().filter { it.name.endsWith(".jsonl") }.maxByOrNull { it.name }
                ?.takeIf { it.length() + bytes.size <= minOf(256 * 1024L, maxBytes) }
                ?: File(root, String.format(java.util.Locale.ROOT, "%019d-%s.jsonl", System.currentTimeMillis(), UUID.randomUUID()))
            FileOutputStream(active, true).use { it.write(bytes) }
        }
    }
    fun usage(): Long = locked { files().sumOf { it.length() } }
    fun clear() = locked { files().forEach { check(it.delete()) { "Cannot remove report" } } }
    fun recent(limit: Int = 200): List<JsonObject> = locked {
        val result = java.util.PriorityQueue<JsonObject>(compareBy { it["time"]?.jsonPrimitive?.longOrNull ?: 0L })
        files().forEach { file -> file.useLines { lines -> lines.forEach { line ->
            if (line.length <= MAX_RECORD_BYTES) runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()?.let {
                result.add(it); if (result.size > limit) result.poll()
            }
        } } }
        result.toList().sortedByDescending { it["time"]?.jsonPrimitive?.longOrNull ?: 0L }
    }
    fun export(output: OutputStream, metadata: JsonObject) = locked {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("device.json")); zip.write(metadata.toString().toByteArray()); zip.closeEntry()
            files().sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry("reports/" + file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}

/** Deliberately no arbitrary message/body/URL fields: secrets and user content stay out by construction. */
object DiagnosticPrivacy {
    fun token(value: String) = value.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(100)
    fun throwable(error: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val text = StringBuilder(); var current: Throwable? = error
        repeat(8) {
            val e = current ?: return@repeat
            if (!seen.add(e)) { current = null; return@repeat }
            text.append(e.javaClass.name).append('\n')
            e.stackTrace.take(35).forEach { frame ->
                text.append("  at ").append(token(frame.className)).append('.').append(token(frame.methodName))
                    .append('(').append(token(frame.fileName ?: "Unknown")).append(':').append(frame.lineNumber).append(")\n")
            }
            current = e.cause
            if (current != null) text.append("Caused by: ")
        }
        return text.take(16000).toString()
    }
    fun requestId(value: String?): String? = value?.takeIf {
        it.matches(Regex("[a-fA-F0-9]{32}|[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
    }?.lowercase()
    fun route(path: String): String {
        // Only literal API vocabulary survives; track IDs, user IDs, tokens and filenames do not.
        val known = setOf("v1", "api", "tracks", "status", "stream", "download", "downloads", "search", "albums", "artists",
            "users", "resolve", "settings", "lyrics", "chat", "completions", "health", "w", "index.php", "recommendations")
        return path.substringBefore('?').split('/').filter { it.isNotBlank() }.take(10)
            .joinToString("/", prefix = "/") { if (it in known) it else "{value}" }
    }
}
