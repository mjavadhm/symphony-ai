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
    @Test fun `story requires evidence`() {
        assertThrows(IllegalArgumentException::class.java) { validate(CompanionSection.STORY, """{"paragraphs":[{"text":"A claim","sourceIds":[]}]}""") }
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
