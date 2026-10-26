package io.github.zyrouge.symphony.services.companion

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Tests the real DAO apply policy with an in-memory adapter; Android tests cover Room transactions. */
class CompanionRevisionTest {
    private val snapshot = CompanionCore.encodeSnapshot(CompanionCore.snapshot("Hello\nWorld"))
    private fun dao() = MemoryCompanionDao(CompanionTrack("track", "Song", "artist", "", 100, snapshot))
    private suspend fun draft(dao: CompanionDao, base: Long? = null, language: String = "فارسی") = dao.insertRevision(
        CompanionRevision(trackId = "track", section = "MEANING", target = "", language = language,
            snapshot = snapshot, payload = "{\"text\":\"Interpretation\"}", origin = "user", model = "", baseId = base))
    @Test fun `draft never replaces saved content by insertion alone`() = runBlocking {
        val dao = dao(); val id = draft(dao)
        assertNull(dao.saved("track", "MEANING", "", "فارسی"))
        assertEquals("draft", dao.revision(id)!!.state)
    }
    @Test fun `apply preserves history and accepts only the selected revision`() = runBlocking {
        val dao = dao(); val first = draft(dao); dao.apply(first)
        val second = draft(dao, first); dao.apply(second)
        assertEquals("archived", dao.revision(first)!!.state)
        assertEquals("saved", dao.revision(second)!!.state)
        assertEquals(2, dao.revisionList("track").size)
    }
    @Test fun `stale base cannot overwrite a newer applied version`() = runBlocking {
        val dao = dao(); val first = draft(dao); val second = draft(dao); dao.apply(second)
        try { dao.apply(first); fail<Unit>("Expected conflict") } catch (_: IllegalArgumentException) { }
        assertEquals("saved", dao.revision(second)!!.state)
        assertEquals("draft", dao.revision(first)!!.state)
    }
    @Test fun `changed lyric text blocks applying old proposals`() = runBlocking {
        val dao = dao(); val id = draft(dao)
        dao.updateTrack(dao.profile.copy(snapshot = CompanionCore.encodeSnapshot(CompanionCore.snapshot("Changed"))))
        try { dao.apply(id); fail<Unit>("Expected stale snapshot rejection") } catch (_: IllegalArgumentException) { }
        assertEquals("draft", dao.revision(id)!!.state)
    }
    @Test fun `retiming does not block apply`() = runBlocking {
        val dao = dao(); val id = draft(dao)
        dao.updateTrack(dao.profile.copy(snapshot = CompanionCore.encodeSnapshot(CompanionCore.snapshot("[00:10]Hello\n[00:20]World"))))
        dao.apply(id)
        assertEquals("saved", dao.revision(id)!!.state)
    }
    @Test fun `languages have independent saved versions`() = runBlocking {
        val dao = dao(); val fa = draft(dao); dao.apply(fa)
        val en = draft(dao, language = "English"); dao.apply(en)
        assertEquals("saved", dao.revision(fa)!!.state)
        assertEquals("saved", dao.revision(en)!!.state)
    }
    @Test fun `accepted version cannot be applied twice`() = runBlocking {
        val dao = dao(); val id = draft(dao); dao.apply(id)
        try { dao.apply(id); fail<Unit>("Expected draft-only apply") } catch (_: IllegalArgumentException) { }
        assertEquals("saved", dao.revision(id)!!.state)
    }
}

private class MemoryCompanionDao(var profile: CompanionTrack) : CompanionDao() {
    private val rows = linkedMapOf<Long, CompanionRevision>()
    override fun observeTracks() = flowOf(listOf(profile))
    override fun observeTrack(id: String) = flowOf(profile.takeIf { it.id == id })
    override suspend fun tracks() = listOf(profile)
    override suspend fun track(id: String) = profile.takeIf { it.id == id }
    override suspend fun linked(uri: String): CompanionTrack? = null
    override suspend fun insertTrack(track: CompanionTrack) { profile = track }
    override suspend fun updateTrack(track: CompanionTrack) { profile = track }
    override suspend fun link(link: CompanionLink) {}
    override fun sources(id: String) = flowOf(emptyList<CompanionSource>())
    override suspend fun sourceList(id: String) = emptyList<CompanionSource>()
    override suspend fun insertSource(source: CompanionSource) {}
    override fun revisions(id: String) = flowOf(rows.values.filter { it.trackId == id }.reversed())
    override suspend fun revisionList(id: String) = rows.values.filter { it.trackId == id }
    override suspend fun revision(id: Long) = rows[id]
    override suspend fun insertRevision(revision: CompanionRevision): Long {
        val id = (rows.keys.maxOrNull() ?: 0) + 1; rows[id] = revision.copy(id = id); return id
    }
    override suspend fun setBase(id: Long, base: Long?) { rows[id] = rows.getValue(id).copy(baseId = base) }
    override suspend fun saved(trackId: String, section: String, target: String, language: String) = rows.values.lastOrNull {
        it.trackId == trackId && it.section == section && it.target == target && it.language == language && it.state == "saved"
    }
    override suspend fun archive(trackId: String, section: String, target: String, language: String) {
        rows.replaceAll { _, it -> if (it.trackId == trackId && it.section == section && it.target == target && it.language == language && it.state == "saved") it.copy(state = "archived") else it }
    }
    override suspend fun accept(id: Long) { rows[id] = rows.getValue(id).copy(state = "saved") }
    override fun messages(id: String) = flowOf(emptyList<CompanionMessage>())
    override suspend fun messageList(id: String) = emptyList<CompanionMessage>()
    override suspend fun insertMessage(message: CompanionMessage) = 1L
}
