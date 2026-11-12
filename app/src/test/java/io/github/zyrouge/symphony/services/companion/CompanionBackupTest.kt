package io.github.zyrouge.symphony.services.companion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanionBackupTest {
    private val track = CompanionTrack("12345678-1234-1234-1234-123456789abc", "Title", "artist", "Album", 120000,
        CompanionCore.encodeSnapshot(CompanionCore.snapshot("Hello")), 1)
    private val revision = CompanionRevision(1, track.id, "MEANING", "", "فارسی", track.snapshot,
        "{\"text\":\"برداشت\"}", "user", "", null, "saved", 2)
    private val message = CompanionMessage(1, track.id, "assistant", "پیشنهاد", "MEANING", "", "فارسی", 1, 3)
    private fun bundle() = CompanionBundle(listOf(track), emptyList(), listOf(revision), listOf(message))
    private fun decode(bundle: CompanionBundle) = CompanionBackup.decode(CompanionBackup.encode(bundle))
    @Test fun `full bundle round trips unicode chats and documents`() { assertEquals(bundle(), decode(bundle())) }
    @Test fun `backup does not include device links or provider credentials`() {
        val encoded = CompanionBackup.encode(bundle())
        assertFalse(encoded.contains("content://")); assertFalse(encoded.contains("apiKey")); assertFalse(encoded.contains("companion_links"))
    }
    @Test fun `discarded drafts round trip without becoming saved`() {
        val b = bundle().copy(revisions = listOf(revision, revision.copy(id = 2, baseId = 1, state = "discarded")))
        assertEquals(b, decode(b))
    }
    @Test fun `empty bundle round trips`() { val b = CompanionBundle(emptyList(), emptyList(), emptyList(), emptyList()); assertEquals(b, decode(b)) }
    @Test fun `unknown version rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CompanionBackup.decode(CompanionBackup.encode(bundle()).replace("\"version\":1", "\"version\":2")) }
    }
    @Test fun `duplicate profiles rejected`() {
        assertThrows(IllegalArgumentException::class.java) { decode(bundle().copy(tracks = listOf(track, track))) }
    }
    @Test fun `orphan revision rejected`() {
        assertThrows(IllegalArgumentException::class.java) { decode(bundle().copy(tracks = emptyList())) }
    }
    @Test fun `missing chat proposal rejected`() {
        assertThrows(IllegalStateException::class.java) { decode(bundle().copy(messages = listOf(message.copy(revisionId = 999)))) }
    }
    @Test fun `two saved versions of the same document rejected`() {
        assertThrows(IllegalArgumentException::class.java) { decode(bundle().copy(revisions = listOf(revision, revision.copy(id = 2)))) }
    }
    @Test fun `revision ancestry survives export`() {
        val b = bundle().copy(revisions = listOf(revision.copy(state = "archived"), revision.copy(id = 2, baseId = 1)))
        assertEquals(b, decode(b))
    }
    @Test fun `revision ancestry cannot cross section`() {
        assertThrows(IllegalArgumentException::class.java) { decode(bundle().copy(revisions = listOf(revision, revision.copy(id = 2, baseId = 1, language = "English")))) }
    }
    @Test fun `revision ancestry cannot point forward`() {
        assertThrows(IllegalArgumentException::class.java) { decode(bundle().copy(revisions = listOf(revision.copy(baseId = 1)))) }
    }
    @Test fun `invalid snapshots rejected even in unused revisions`() {
        assertThrows(IllegalArgumentException::class.java) { decode(bundle().copy(revisions = listOf(revision.copy(snapshot = track.snapshot.replace("Hello", "Changed"))))) }
    }
    @Test fun `chat proposal scope must match`() {
        assertThrows(IllegalArgumentException::class.java) { decode(bundle().copy(messages = listOf(message.copy(language = "English")))) }
    }
}
