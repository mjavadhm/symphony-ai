package io.github.zyrouge.symphony.services.companion

import io.github.zyrouge.symphony.utils.TimedContent
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class CompanionLine(val id: String, val index: Int, val time: Long, val text: String)
data class CompanionSnapshot(val hash: String, val lines: List<CompanionLine>)
enum class CompanionSection { STORY, MEANING, TRANSLATION, NOTE }
data class CompanionReply(val reply: String, val proposal: String?)

object CompanionCore {
    fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    fun snapshot(lyrics: String): CompanionSnapshot {
        require(lyrics.length <= 60_000) { "Lyrics are too long for one dossier" }
        val seen = mutableMapOf<String, Int>()
        val lines = TimedContent.fromLyrics(lyrics).pairs.mapIndexedNotNull { index, pair ->
            val text = pair.second.trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val n = seen.getOrDefault(text, 0); seen[text] = n + 1
            CompanionLine(sha("$text\u0000$n").take(24), index, pair.first, text)
        }
        require(lines.size <= 400 && lines.all { it.text.length <= 8000 }) { "Lyrics are too long for one dossier" }
        return CompanionSnapshot(if (lines.isEmpty()) "" else sha(lines.joinToString("\n") { it.id + ":" + it.text }), lines)
    }
    fun encodeSnapshot(snapshot: CompanionSnapshot): String = buildJsonObject {
        put("hash", snapshot.hash)
        put("lines", buildJsonArray { snapshot.lines.forEach { line -> add(buildJsonObject {
            put("id", line.id); put("index", line.index); put("time", line.time); put("text", line.text)
        }) } })
    }.toString()
    fun decodeSnapshot(json: String): CompanionSnapshot {
        val obj = Json.parseToJsonElement(json).jsonObject
        val lines = obj.getValue("lines").jsonArray.map { item -> item.jsonObject.let {
            CompanionLine(it.getValue("id").jsonPrimitive.content, it.getValue("index").jsonPrimitive.int,
                it.getValue("time").jsonPrimitive.long, it.getValue("text").jsonPrimitive.content)
        } }
        require(lines.size <= 400 && lines.map { it.id }.distinct().size == lines.size)
        require(lines.all { it.id.matches(Regex("[a-f0-9]{24}")) && it.text.isNotBlank() && it.text.length <= 8000 && it.index >= 0 && it.time >= 0 })
        require(lines.zipWithNext().all { (a, b) -> a.index < b.index && a.time <= b.time })
        require(lines.sumOf { it.text.length } <= 60_000)
        val seen = mutableMapOf<String, Int>()
        require(lines.all { line ->
            val n = seen.getOrDefault(line.text, 0); seen[line.text] = n + 1
            line.id == sha("${line.text}\u0000$n").take(24)
        }) { "Invalid line identity" }
        val hash = obj.getValue("hash").jsonPrimitive.content
        require(hash == if (lines.isEmpty()) "" else sha(lines.joinToString("\n") { it.id + ":" + it.text })) { "Invalid lyric fingerprint" }
        return CompanionSnapshot(hash, lines)
    }
    fun parseObject(text: String): JsonObject {
        require(text.length <= 150_000) { "Model response is too large" }
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return Json.parseToJsonElement(clean).jsonObject
    }
    private fun text(obj: JsonObject, key: String, max: Int = 20000): String =
        obj[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull?.trim()?.also { require(it.isNotEmpty() && it.length <= max) { "Invalid $key" } }
            ?: throw IllegalArgumentException("Missing $key")

    fun validatePayload(section: CompanionSection, target: String, snapshot: CompanionSnapshot,
                        sourceIds: Set<String>, payload: JsonObject): String {
        require(section == CompanionSection.NOTE || target.isEmpty()) { "Only line notes may have a target" }
        require(payload.toString().length <= 140_000)
        when (section) {
            CompanionSection.STORY -> {
                require(sourceIds.isNotEmpty()) { "Add a source before generating a factual story" }
                val paragraphs = payload.getValue("paragraphs").jsonArray
                require(paragraphs.size in 1..20)
                paragraphs.forEach { item ->
                    text(item.jsonObject, "text", 6000)
                    val citations = item.jsonObject.getValue("sourceIds").jsonArray.map { it.jsonPrimitive.content }
                    require(citations.isNotEmpty() && citations.all { it in sourceIds }) { "Story has missing or invented sources" }
                }
            }
            CompanionSection.MEANING -> text(payload, "text")
            CompanionSection.NOTE -> {
                require(snapshot.lines.any { it.id == target }) { "Select an existing lyric line" }
                text(payload, "text")
            }
            CompanionSection.TRANSLATION -> {
                require(snapshot.lines.isNotEmpty()) { "No lyrics available" }
                val lines = payload.getValue("lines").jsonArray
                val ids = lines.map { text(it.jsonObject, "id", 24) }
                require(ids.size == ids.distinct().size && ids.toSet() == snapshot.lines.map { it.id }.toSet()) {
                    "Translation must contain every original line exactly once"
                }
                lines.forEach { text(it.jsonObject, "translation", 8000) }
            }
        }
        return payload.toString()
    }
    fun parseReply(text: String, section: CompanionSection, target: String, snapshot: CompanionSnapshot,
                   sourceIds: Set<String>): CompanionReply {
        val obj = parseObject(text)
        val reply = text(obj, "reply", 12000)
        val proposal = obj["proposal"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
            require(it.getValue("section").jsonPrimitive.content == section.name &&
                (it["target"]?.jsonPrimitive?.contentOrNull ?: "") == target) { "Proposal is outside the selected section" }
            validatePayload(section, target, snapshot, sourceIds, it.getValue("payload").jsonObject)
        }
        return CompanionReply(reply, proposal)
    }
    fun translations(payload: String?): Map<String, String> = payload?.let {
        runCatching { parseObject(it).getValue("lines").jsonArray.associate { row ->
            row.jsonObject.getValue("id").jsonPrimitive.content to row.jsonObject.getValue("translation").jsonPrimitive.content
        } }.getOrNull()
    }.orEmpty()
    fun display(section: CompanionSection, payload: String): String {
        val obj = parseObject(payload)
        return when (section) {
            CompanionSection.STORY -> obj.getValue("paragraphs").jsonArray.joinToString("\n\n") {
                it.jsonObject.getValue("text").jsonPrimitive.content
            }
            CompanionSection.TRANSLATION -> obj.getValue("lines").jsonArray.joinToString("\n") {
                it.jsonObject.getValue("translation").jsonPrimitive.content
            }
            else -> obj.getValue("text").jsonPrimitive.content
        }
    }
}
