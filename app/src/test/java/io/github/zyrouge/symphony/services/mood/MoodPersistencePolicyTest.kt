package io.github.zyrouge.symphony.services.mood

import io.github.zyrouge.symphony.services.database.entities.MixFeedback
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MoodPersistencePolicyTest {
    private val ref = MoodTrackRef("stable", "uri:a", "music/a.mp3", "Song", listOf("Artist"), "Album", 180000)
    private fun local(id: String = "new-id", path: String = ref.path, uri: String = ref.uri) =
        MoodLocalTrack(id, uri, path, ref.title, ref.artists, ref.album, ref.durationMs)

    @Test fun `session snapshots retain identity reactions settings and checkpoint`() {
        val profile = MoodProfile(seeds = listOf(ref), saved = true, name = "Night", discovery = 0.7f, familiarDays = 14,
            reactions = mapOf(ref.key to MoodReaction(ref, 1)))
        val restored = MoodJson.profile(MoodJson.row(profile))
        assertEquals(profile, restored)
        val run = MoodRun(profileId = profile.id, queue = listOf(MoodQueueEntry(track = ref, kind = "seed")), positionMs = 45000,
            playedKeys = setOf("other"), discoveryHistory = listOf(true, false))
        assertEquals(run, MoodJson.run(MoodJson.row(run)))
        val fresh = MoodRun(profileId = restored.id)
        assertNotEquals(run.id, fresh.id)
        assertTrue(fresh.playedKeys.isEmpty())
        assertEquals(profile.reactions, restored.reactions)
    }
    @Test fun `rescanning can change a transient song id without losing the recording`() {
        assertEquals("new-id", MoodCatalog(listOf(local())).resolve(ref)?.id)
    }
    @Test fun `a uniquely moved recording can reattach by metadata`() {
        assertEquals("moved", MoodCatalog(listOf(local("moved", "new/path.mp3", "uri:new"))).resolve(ref)?.id)
    }
    @Test fun `ambiguous recordings and missing files never attach arbitrarily`() {
        val catalog = MoodCatalog(listOf(local("one", "one", "one"), local("two", "two", "two")))
        assertNull(catalog.resolve(ref))
        assertNull(MoodCatalog(emptyList()).resolve(ref))
    }
    @Test fun `direct file identity beats a same-title recording elsewhere`() {
        assertEquals("new-id", MoodCatalog(listOf(local(), local("other", "elsewhere", "other-uri"))).resolve(ref)?.id)
    }
    @Test fun `refresh replaces only generated rows preserving current seed and manual slots`() {
        val current = MoodQueueEntry(track = ref, kind = "seed")
        val seed = MoodQueueEntry(track = ref.copy(key = "seed2"), kind = "seed")
        val old = MoodQueueEntry(track = ref.copy(key = "old"))
        val manual = MoodQueueEntry(track = ref.copy(key = "manual"), kind = "manual")
        val fresh = MoodQueueEntry(track = ref.copy(key = "new"))
        val updated = MoodQueuePolicy.replaceTail(listOf(current, seed, old, manual), 0, listOf(fresh))
        assertEquals(listOf(current, seed, fresh, manual), updated)
        assertEquals(listOf(current, seed, manual), MoodQueuePolicy.replaceTail(listOf(current, seed, old, manual), 0, emptyList()))
    }
    @Test fun `clearing a mood reaction exposes the last ordinary explicit reaction`() {
        val ordinary = MixFeedback("a", "Song", "Artist", false, 1)
        val mood = ordinary.copy(liked = true, updatedAt = 2)
        assertTrue(MoodGlobalFeedback.merge(listOf(ordinary), listOf(mood)).single().liked)
        assertFalse(MoodGlobalFeedback.merge(listOf(ordinary), emptyList()).single().liked)
    }
    @Test fun `the most recent explicit reaction wins across moods and ordinary feedback`() {
        val older = MixFeedback("a", "Song", "Artist", true, 1)
        val newer = older.copy(liked = false, updatedAt = 3)
        assertFalse(MoodGlobalFeedback.merge(listOf(newer), listOf(older)).single().liked)
        assertTrue(MoodGlobalFeedback.overridesHistory(listOf(older), "rescanned-id", "Song", "Artist"))
        assertFalse(MoodGlobalFeedback.overridesHistory(listOf(older), "another", "Different", "Artist"))
    }
}
