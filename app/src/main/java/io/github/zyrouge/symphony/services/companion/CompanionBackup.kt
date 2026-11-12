package io.github.zyrouge.symphony.services.companion

import kotlinx.serialization.json.*

data class CompanionBundle(val tracks: List<CompanionTrack>, val sources: List<CompanionSource>,
    val revisions: List<CompanionRevision>, val messages: List<CompanionMessage>)

object CompanionBackup {
    const val MAX_BYTES = 50 * 1024 * 1024
    private fun row(vararg values: Pair<String, Any?>) = buildJsonObject { values.forEach { (key, value) ->
        when (value) { null -> put(key, JsonNull); is Long -> put(key, value); else -> put(key, value.toString()) }
    } }
    private fun JsonObject.s(key: String, max: Int = 150000): String = getValue(key).jsonPrimitive.content.also { require(it.length <= max) }
    private fun JsonObject.l(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.n(key: String): Long? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
    fun encode(bundle: CompanionBundle): String = buildJsonObject {
        put("format", "symphony-song-companion"); put("version", 1)
        put("tracks", JsonArray(bundle.tracks.map { row("id" to it.id, "title" to it.title, "artists" to it.artists,
            "album" to it.album, "duration" to it.duration, "snapshot" to it.snapshot, "createdAt" to it.createdAt) }))
        put("sources", JsonArray(bundle.sources.map { row("id" to it.id, "trackId" to it.trackId, "title" to it.title,
            "url" to it.url, "excerpt" to it.excerpt, "origin" to it.origin, "createdAt" to it.createdAt) }))
        put("revisions", JsonArray(bundle.revisions.map { row("id" to it.id, "trackId" to it.trackId, "section" to it.section,
            "target" to it.target, "language" to it.language, "snapshot" to it.snapshot, "payload" to it.payload,
            "origin" to it.origin, "model" to it.model, "baseId" to it.baseId, "state" to it.state, "createdAt" to it.createdAt) }))
        put("messages", JsonArray(bundle.messages.map { row("id" to it.id, "trackId" to it.trackId, "role" to it.role,
            "text" to it.text, "section" to it.section, "target" to it.target, "language" to it.language,
            "revisionId" to it.revisionId, "createdAt" to it.createdAt) }))
    }.toString().also { require(it.toByteArray().size <= MAX_BYTES) { "Backup exceeds 50 MB" } }

    fun decode(text: String): CompanionBundle {
        require(text.toByteArray().size <= MAX_BYTES) { "Backup exceeds 50 MB" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.s("format") == "symphony-song-companion" && root.l("version") == 1L) { "Unsupported backup" }
        fun rows(key: String): List<JsonObject> = root.getValue(key).jsonArray.also { require(it.size <= 30000) }.map { it.jsonObject }
        val bundle = CompanionBundle(
            rows("tracks").map { CompanionTrack(it.s("id", 36), it.s("title", 1000), it.s("artists", 3000),
                it.s("album", 1000), it.l("duration"), it.s("snapshot"), it.l("createdAt")) },
            rows("sources").map { CompanionSource(it.s("id", 36), it.s("trackId", 36), it.s("title", 1000),
                it.s("url", 4000), it.s("excerpt", 12000), it.s("origin", 20), it.l("createdAt")) },
            rows("revisions").map { CompanionRevision(it.l("id"), it.s("trackId", 36), it.s("section", 20),
                it.s("target", 24), it.s("language", 80), it.s("snapshot"), it.s("payload"), it.s("origin", 20),
                it.s("model", 300), it.n("baseId"), it.s("state", 20), it.l("createdAt")) },
            rows("messages").map { CompanionMessage(it.l("id"), it.s("trackId", 36), it.s("role", 20),
                it.s("text", 16000), it.s("section", 20), it.s("target", 24), it.s("language", 80),
                it.n("revisionId"), it.l("createdAt")) },
        )
        val uuids = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        val tracks = bundle.tracks.associateBy { it.id }
        require(tracks.size == bundle.tracks.size && tracks.size <= 5000)
        bundle.tracks.forEach { require(uuids.matches(it.id) && it.duration >= 0); CompanionCore.decodeSnapshot(it.snapshot) }
        require(bundle.sources.map { it.id }.distinct().size == bundle.sources.size)
        bundle.sources.forEach { require(uuids.matches(it.id) && it.trackId in tracks && CompanionReferences.validUrl(it.url) && it.origin in setOf("user", "wikipedia")) }
        val sourceIdsByTrack = bundle.sources.groupBy { it.trackId }.mapValues { (_, sources) -> sources.map { it.id }.toSet() }
        require(sourceIdsByTrack.values.all { it.size <= 8 })
        val revisions = bundle.revisions.associateBy { it.id }
        require(revisions.size == bundle.revisions.size && revisions.keys.all { it > 0 })
        bundle.revisions.forEach { revision ->
            require(revision.trackId in tracks && revision.state in setOf("draft", "saved", "archived", "discarded") && revision.origin in setOf("ai", "user", "restore"))
            require(revision.language.isNotBlank())
            val snapshot = CompanionCore.decodeSnapshot(revision.snapshot)
            CompanionCore.validatePayload(CompanionSection.valueOf(revision.section), revision.target, snapshot,
                sourceIdsByTrack[revision.trackId].orEmpty(), CompanionCore.parseObject(revision.payload))
            revision.baseId?.let { baseId ->
                val base = revisions[baseId] ?: error("Missing base revision")
                require(baseId < revision.id && base.trackId == revision.trackId && base.section == revision.section &&
                    base.target == revision.target && base.language == revision.language) { "Invalid revision ancestry" }
            }
        }
        require(bundle.revisions.filter { it.state == "saved" }.groupBy { listOf(it.trackId, it.section, it.target, it.language) }.values.all { it.size == 1 })
        require(bundle.messages.map { it.id }.distinct().size == bundle.messages.size)
        bundle.messages.forEach { message ->
            require(message.id > 0 && message.trackId in tracks && message.role in setOf("user", "assistant", "error"))
            CompanionSection.valueOf(message.section)
            require(message.language.isNotBlank() && (message.section == "NOTE" || message.target.isEmpty()))
            message.revisionId?.let { id ->
                val revision = revisions[id] ?: error("Missing chat proposal")
                require(revision.trackId == message.trackId && revision.section == message.section && revision.target == message.target && revision.language == message.language)
            }
        }
        return bundle
    }
}
