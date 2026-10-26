package io.github.zyrouge.symphony.services.lyrics

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Disk cache of completed lookups only. Errors/rate limits are never cached as missing lyrics. */
class LyricsLookupCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Hit(val candidate: LyricsCandidate?)
    private fun file(base: String, track: LyricsTrack): File {
        val key = buildJsonObject {
            put("provider", LrclibClient.normalizeBaseUrl(base)); put("title", track.title.trim())
            put("artists", JsonArray(track.artists.sorted().map(::JsonPrimitive))); put("album", track.album.orEmpty())
            put("duration", track.durationSeconds)
        }.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
    @Synchronized fun get(base: String, track: LyricsTrack): Hit? {
        val file = file(base, track)
        if (!file.exists()) return null
        return try {
            require(file.length() <= 2_000_000)
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            if (obj.getValue("expires").jsonPrimitive.long <= now()) { file.delete(); return null }
            require(obj.containsKey("record"))
            val record = obj["record"]?.takeUnless { it is JsonNull }?.jsonObject?.let(LrclibClient::parseRecord)
            val result = record?.let { LyricsMatch.select(track, listOf(it)) }
            if (record != null && result == null) { file.delete(); null } else Hit(result)
        } catch (_: Exception) { file.delete(); null }
    }
    @Synchronized fun put(base: String, track: LyricsTrack, candidate: LyricsCandidate?) {
        directory.mkdirs()
        val target = file(base, track)
        val data = buildJsonObject {
            put("expires", now() + if (candidate == null) 6 * 60 * 60_000L else 7 * 24 * 60 * 60_000L)
            put("record", candidate?.record?.let { record -> buildJsonObject {
                put("id", record.id); put("trackName", record.trackName); put("artistName", record.artistName)
                put("albumName", record.albumName); put("duration", record.duration); put("instrumental", record.instrumental)
                put("syncedLyrics", record.syncedLyrics?.let(::JsonPrimitive) ?: JsonNull)
                put("plainLyrics", record.plainLyrics?.let(::JsonPrimitive) ?: JsonNull)
            } } ?: JsonNull)
        }.toString()
        require(data.toByteArray().size <= 2_000_000)
        val temp = File.createTempFile("lookup-", ".tmp", directory)
        try { temp.writeText(data); check(temp.renameTo(target)) { "Could not save lookup cache" } } finally { temp.delete() }
        var bytes = 0L
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, entry ->
            bytes += entry.length()
            if (index >= 1000 || bytes > 20 * 1024 * 1024) entry.delete()
        }
    }
}
