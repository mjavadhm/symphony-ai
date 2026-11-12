package io.github.zyrouge.symphony.services.lyrics

import java.io.File
import kotlinx.serialization.json.*

/** Presence only: no lyrics, API keys or candidates. Invalid/old entries simply need a local check. */
class LyricsInventoryIndex(private val file: File) {
    private val entries = linkedMapOf<String, Pair<String, String>>()
    private var loaded = false
    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists() || file.length() > 10_000_000) return
        runCatching {
            val rows = Json.parseToJsonElement(file.readText()).jsonObject
            rows.forEach { (uri, value) ->
                val row = value.jsonArray
                val status = row[1].jsonPrimitive.content
                if (status in setOf("EXISTING", "SIDECAR", "MISSING")) entries[uri] = row[0].jsonPrimitive.content to status
            }
        }.onFailure { entries.clear() }
    }
    @Synchronized fun get(uri: String, signature: String): String? { load(); return entries[uri]?.takeIf { it.first == signature }?.second }
    @Synchronized fun put(uri: String, signature: String, status: String) {
        require(status in setOf("EXISTING", "SIDECAR", "MISSING"))
        load(); entries[uri] = signature to status
    }
    @Synchronized fun clear() { load(); entries.clear() }
    @Synchronized fun save() {
        load()
        val data = buildJsonObject { entries.entries.toList().takeLast(50000).forEach { (uri, row) -> put(uri, buildJsonArray { add(row.first); add(row.second) }) } }.toString()
        require(data.toByteArray().size <= 10_000_000)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(data)
        check(tmp.renameTo(file)) { "Could not save local lyrics inventory" }
    }
}

object LyricsWriteErrors {
    fun isAccess(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(12).any {
        it is SecurityException || it.message.orEmpty().let { text ->
            listOf("EACCES", "EPERM", "permission denied", "access denied").any { marker -> text.contains(marker, true) }
        }
    }
}
