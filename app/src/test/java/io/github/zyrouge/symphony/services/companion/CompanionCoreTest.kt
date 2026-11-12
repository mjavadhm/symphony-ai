package io.github.zyrouge.symphony.services.companion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.*

class CompanionCoreTest {
    private val snapshot = CompanionCore.snapshot("[00:01]Hello\n[00:02]World\n[00:03]Hello")
    private fun validate(section: CompanionSection, json: String, target: String = "", sources: Set<String> = emptySet()) =
        CompanionCore.validatePayload(section, target, snapshot, sources, CompanionCore.parseObject(json))
    private fun translation(ids: List<String> = snapshot.lines.map { it.id }) = buildJsonObject {
        put("lines", JsonArray(ids.map { id -> buildJsonObject { put("id", id); put("translation", "ترجمه") } }))
    }.toString()
    @Test fun `repeated words get distinct stable IDs`() {
        assertEquals(3, snapshot.lines.map { it.id }.distinct().size)
        assertEquals(snapshot, CompanionCore.snapshot("[00:01]Hello\n[00:02]World\n[00:03]Hello"))
    }
    @Test fun `retiming does not invalidate saved translation`() {
        val retimed = CompanionCore.snapshot("[00:10]Hello\n[00:20]World\n[00:30]Hello")
        assertEquals(snapshot.hash, retimed.hash)
        assertEquals(snapshot.lines.map { it.id }, retimed.lines.map { it.id })
        assertNotEquals(snapshot.lines.map { it.time }, retimed.lines.map { it.time })
    }
    @Test fun `changing lyrics invalidates fingerprint`() {
        assertNotEquals(snapshot.hash, CompanionCore.snapshot("Hello\nEarth\nHello").hash)
    }
    @Test fun `blank lines do not break render indices`() {
        val result = CompanionCore.snapshot("First\n\nSecond")
        assertEquals(listOf(0, 2), result.lines.map { it.index })
    }
    @Test fun `snapshot round trip including unicode`() {
        val result = CompanionCore.snapshot("سلام 🎵\n世界")
        assertEquals(result, CompanionCore.decodeSnapshot(CompanionCore.encodeSnapshot(result)))
    }
    @Test fun `bad fingerprints rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CompanionCore.decodeSnapshot(CompanionCore.encodeSnapshot(snapshot).replace(snapshot.hash, "fake")) }
    }
    @Test fun `line limits enforced`() {
        assertThrows(IllegalArgumentException::class.java) { CompanionCore.snapshot((1..401).joinToString("\n") { "Line $it" }) }
    }
    @Test fun `empty lyrics use empty fingerprint`() { assertEquals("", CompanionCore.snapshot("").hash) }
    @Test fun `translation requires each exact line once`() {
        assertNotNull(validate(CompanionSection.TRANSLATION, translation()))
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.TRANSLATION, translation(snapshot.lines.drop(1).map { it.id })) }
    }
    @Test fun `translation duplicates rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.TRANSLATION, translation(snapshot.lines.map { it.id } + snapshot.lines.first().id)) }
    }
    @Test fun `invented translation line rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.TRANSLATION, translation(listOf("fake"))) }
    }
    @Test fun `story cannot use invented citations`() {
        val story = """{"paragraphs":[{"text":"A claim","sourceIds":["unknown"]}]}"""
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.STORY, story, sources = setOf("known")) }
    }
    @Test fun `story from model knowledge needs no sources`() {
        val story = """{"basis":"known","paragraphs":[{"text":"Written after the birth of his son"}]}"""
        assertEquals(story, validate(CompanionSection.STORY, story))
    }
    @Test fun `story basis must be known or lyrics`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.STORY, """{"basis":"certain","paragraphs":[{"text":"A"}]}""") }
    }
    @Test fun `story needs at least one non-empty paragraph`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.STORY, """{"basis":"known","paragraphs":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.STORY, """{"basis":"known","paragraphs":[{"text":" "}]}""") }
    }
    @Test fun `model story with plain paragraphs is normalized`() {
        val normalized = CompanionCore.normalizeStory(CompanionCore.parseObject("""{"basis":"Known","paragraphs":["One","Two"]}"""))
        assertEquals("""{"basis":"known","paragraphs":[{"text":"One"},{"text":"Two"}]}""", normalized.toString())
        assertNotNull(validate(CompanionSection.STORY, normalized.toString()))
    }
    @Test fun `story without a basis is labelled as interpretation, never as known`() {
        val normalized = CompanionCore.normalizeStory(CompanionCore.parseObject("""{"paragraphs":[{"text":"One"}]}"""))
        assertEquals("lyrics", CompanionCore.story(normalized.toString()).basis)
        val invented = CompanionCore.normalizeStory(CompanionCore.parseObject("""{"basis":"verified","paragraphs":[{"text":"One"}]}"""))
        assertEquals("lyrics", CompanionCore.story(invented.toString()).basis)
    }
    @Test fun `older source-cited stories still load`() {
        val legacy = """{"paragraphs":[{"text":"From an excerpt","sourceIds":["known"]}]}"""
        assertNotNull(validate(CompanionSection.STORY, legacy, sources = setOf("known")))
        assertEquals(CompanionCore.Story(null, listOf("From an excerpt")), CompanionCore.story(legacy))
    }
    private fun meaning(vararg lines: Pair<String, String>) = buildJsonObject {
        put("summary", "About growing up")
        put("lines", JsonArray(lines.map { (id, text) -> buildJsonObject { put("id", id); put("explanation", text) } }))
    }.toString()
    @Test fun `meaning explains only selected existing lines`() {
        val payload = meaning(snapshot.lines[1].id to "A metaphor for change")
        assertNotNull(validate(CompanionSection.MEANING, payload))
        val parsed = CompanionCore.meaning(payload)
        assertEquals("About growing up", parsed.summary)
        assertEquals(mapOf(snapshot.lines[1].id to "A metaphor for change"), parsed.lines)
    }
    @Test fun `meaning rejects invented or duplicate line ids`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.MEANING, meaning("fake" to "X")) }
        val id = snapshot.lines.first().id
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.MEANING, meaning(id to "X", id to "Y")) }
    }
    @Test fun `meaning with only a summary is valid`() {
        assertNotNull(validate(CompanionSection.MEANING, """{"summary":"About growing up"}"""))
        assertTrue(CompanionCore.meaning("""{"summary":"About growing up"}""").lines.isEmpty())
    }
    @Test fun `old single-article meaning is recognised and still displays`() {
        assertTrue(CompanionCore.isLegacyMeaning("""{"text":"Old behind the song article"}"""))
        assertFalse(CompanionCore.isLegacyMeaning(meaning()))
        assertEquals("Old behind the song article", CompanionCore.display(CompanionSection.MEANING, """{"text":"Old behind the song article"}"""))
        assertEquals("About growing up", CompanionCore.display(CompanionSection.MEANING, meaning()))
    }
    @Test fun `story preserves supplied source IDs`() {
        val story = """{"paragraphs":[{"text":"An excerpt summary","sourceIds":["known"]}]}"""
        assertEquals(story, validate(CompanionSection.STORY, story, sources = setOf("known")))
    }
    @Test fun `notes require an existing line`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.NOTE, """{"text":"Note"}""", "fake") }
        assertNotNull(validate(CompanionSection.NOTE, """{"text":"Possible metaphor"}""", snapshot.lines.first().id))
    }
    @Test fun `meaning may not target a line`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.MEANING, """{"text":"Meaning"}""", snapshot.lines.first().id) }
    }
    @Test fun `numeric model text is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.MEANING, """{"text":42}""") }
    }
    @Test fun `code fenced response accepted`() {
        assertNotNull(CompanionCore.parseObject("```json\n{\"text\":\"Meaning\"}\n```"))
    }
    @Test fun `ordinary chat question has no edit`() {
        val result = CompanionCore.parseReply("""{"reply":"It could mean several things","proposal":null}""", CompanionSection.MEANING, "", snapshot, emptySet())
        assertNull(result.proposal)
    }
    @Test fun `chat proposal cannot change a different section`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionCore.parseReply("""{"reply":"Done","proposal":{"section":"STORY","target":"","payload":{"text":"Oops"}}}""", CompanionSection.MEANING, "", snapshot, emptySet())
        }
    }
    @Test fun `chat proposal cannot change a different line`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionCore.parseReply("""{"reply":"Done","proposal":{"section":"NOTE","target":"other","payload":{"text":"Oops"}}}""", CompanionSection.NOTE, snapshot.lines.first().id, snapshot, emptySet())
        }
    }
    @Test fun `chat edit remains a separate proposal`() {
        val result = CompanionCore.parseReply("""{"reply":"Suggested correction","proposal":{"section":"MEANING","target":"","payload":{"text":"Another interpretation"}}}""", CompanionSection.MEANING, "", snapshot, emptySet())
        assertEquals("Suggested correction", result.reply)
        assertEquals("{\"text\":\"Another interpretation\"}", result.proposal)
    }
    @Test fun `URLs require HTTPS without credentials`() {
        assertTrue(CompanionReferences.validUrl("https://example.com/song"))
        assertFalse(CompanionReferences.validUrl("http://example.com"))
        assertFalse(CompanionReferences.validUrl("https://user:password@example.com"))
        assertFalse(CompanionReferences.validUrl("javascript:alert(1)"))
    }
}
