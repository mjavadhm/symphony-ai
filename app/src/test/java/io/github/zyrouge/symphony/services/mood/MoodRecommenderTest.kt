package io.github.zyrouge.symphony.services.mood

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MoodRecommenderTest {
    private fun vector(axis: Int) = FloatArray(512) { if (it == axis) 1f else 0f }
    private fun ref(key: String) = MoodTrackRef(key, "uri:$key", "$key.mp3", key, listOf("artist"), durationMs = 180000)
    private fun candidate(key: String, familiar: Boolean = false, axis: Int = 0) = MoodCandidate(ref(key), vector(axis), familiar)
    private val positive = listOf(MoodPoint("seed", vector(0), 0.6f))

    @Test fun `short complete listening is positive but weaker than minutes of listening`() {
        val short = MoodEvidence.signal(20000, 16000, 16000, "end")
        val normal = MoodEvidence.signal(180000, 120000, 120000, "next")
        assertTrue(short > 0f)
        assertTrue(short < normal)
        assertEquals(0.029f, short, 0.001f)
    }
    @Test fun `three minutes of a twenty minute song followed by next is weak positive`() {
        val score = MoodEvidence.signal(1200000, 180000, 180000, "next")
        assertTrue(score > 0f && score < 0.15f)
    }
    @Test fun `time influence saturates and never outweighs a direct reaction`() {
        assertEquals(0.25f, MoodEvidence.signal(1800000, 1800000, 1800000, "end"), 0.0001f)
        assertEquals(1f, MoodEvidence.effective(1, false, listOf(-0.1f)))
        assertEquals(-1f, MoodEvidence.effective(-1, true, List(100) { 0.25f }))
    }
    @Test fun `quick next is weak negative but stop error and interruption are not`() {
        assertTrue(MoodEvidence.signal(180000, 3000, 3000, "next") < 0)
        listOf("stop", "error", "interrupted").forEach { assertTrue(MoodEvidence.signal(180000, 3000, 3000, it) >= 0) }
    }
    @Test fun `short clips can be positive without reaching a fixed duration minimum`() {
        assertTrue(MoodEvidence.signal(3000, 3000, 3000, "end") > 0)
    }
    @Test fun `unknown duration is neutral and long next does not become a negative`() {
        assertEquals(0f, MoodEvidence.signal(0, 3000, 3000, "next"))
        assertTrue(MoodEvidence.signal(1800000, 120000, 120000, "next") > 0)
    }
    @Test fun `removing explicit feedback exposes stored behavioral evidence again`() {
        assertEquals(1f, MoodEvidence.effective(1, false, listOf(-0.1f)))
        assertEquals(-0.1f, MoodEvidence.effective(null, false, listOf(-0.1f)))
    }
    @Test fun `seed choice is counted once and repeated visits do not accumulate weight`() {
        assertEquals(0.6f, MoodEvidence.effective(null, true, List(100) { 0.2f }))
        assertEquals(0.2f, MoodEvidence.effective(null, false, List(100) { 0.2f }), 0.0001f)
    }
    @Test fun `multiple positive neighborhoods remain eligible`() {
        val points = positive + MoodPoint("other seed", vector(1), 0.6f)
        val picks = MoodRecommender.pick(points, listOf(candidate("a"), candidate("b", axis = 1)), emptySet(), emptySet(), 1f, emptyList())
        assertEquals(setOf("a", "b"), picks.map { it.track.key }.toSet())
    }
    @Test fun `explicitly rejected seen and queued recordings are never automatically repeated`() {
        val picks = MoodRecommender.pick(positive, listOf(candidate("seen"), candidate("queued"), candidate("rejected"), candidate("ok")),
            setOf("seen", "queued"), setOf("rejected"), 0.5f, emptyList())
        assertEquals(listOf("ok"), picks.map { it.track.key })
    }
    @Test fun `balanced discovery selects five from each cohort over ten suggestions`() {
        val candidates = (0..9).map { candidate("new$it") } + (0..9).map { candidate("known$it", familiar = true) }
        val picks = MoodRecommender.pick(positive, candidates, emptySet(), emptySet(), 0.5f, emptyList(), 10)
        assertEquals(5, picks.count { it.discovery })
    }
    @Test fun `rolling quota accounts for recently suggested music`() {
        val picks = MoodRecommender.pick(positive, listOf(candidate("new"), candidate("known", true)),
            emptySet(), emptySet(), 0.5f, List(9) { true }, 1)
        assertFalse(picks.single().discovery)
    }
    @Test fun `a scarce cohort falls back without duplicates or random unrelated fill`() {
        val picks = MoodRecommender.pick(positive, listOf(candidate("only", true), candidate("only", true)),
            emptySet(), emptySet(), 1f, emptyList())
        assertEquals(listOf("only"), picks.map { it.track.key })
    }
    @Test fun `invalid embeddings and an entirely negative mood produce no recommendations`() {
        assertNull(MoodRecommender.normalized(FloatArray(512)))
        assertNull(MoodRecommender.normalized(FloatArray(4)))
        assertNull(MoodRecommender.normalized(FloatArray(512) { Float.NaN }))
        assertTrue(MoodRecommender.pick(listOf(MoodPoint("no", vector(0), -1f)), listOf(candidate("x")), emptySet(), emptySet(), 1f, emptyList()).isEmpty())
    }
    @Test fun `negative similarity penalizes its neighborhood without globally discarding others`() {
        val points = positive + MoodPoint("no", vector(1), -1f)
        assertTrue(MoodRecommender.score(vector(0), points) > MoodRecommender.score(vector(1), points))
    }
    @Test fun `advancing the wall clock cannot decay mood points`() {
        val candidates = listOf(candidate("one"), candidate("two", axis = 1))
        val before = MoodRecommender.pick(positive, candidates, emptySet(), emptySet(), 1f, emptyList())
        val after = MoodRecommender.pick(positive, candidates, emptySet(), emptySet(), 1f, emptyList())
        assertEquals(before, after) // No clock or timestamp is an input to the mood scorer.
    }
}

class MoodPlaybackEvidenceTest {
    @Test fun `paused and merely prepared players do not accumulate listening time`() {
        val evidence = MoodPlaybackEvidence()
        evidence.resetPosition(0)
        evidence.sample(0, 1000, false)
        assertEquals(0L, evidence.listenedMs)
        assertEquals(0L, evidence.coveredMs)
    }
    @Test fun `seek to the end cannot pretend the full song was heard`() {
        val evidence = MoodPlaybackEvidence()
        evidence.resetPosition(0)
        evidence.sample(1000, 1000, true)
        evidence.resetPosition()
        evidence.sample(179000, 1000, true)
        evidence.sample(180000, 1000, true)
        assertEquals(3000L, evidence.listenedMs)
        assertEquals(2000L, evidence.coveredMs)
    }
    @Test fun `replaying the same part increases time but not unique coverage`() {
        val evidence = MoodPlaybackEvidence()
        repeat(2) { evidence.resetPosition(0); evidence.sample(1000, 1000, true) }
        assertEquals(2000L, evidence.listenedMs)
        assertEquals(1000L, evidence.coveredMs)
    }
    @Test fun `media coverage accounts for playback speed without doubling actual time`() {
        val evidence = MoodPlaybackEvidence()
        evidence.resetPosition(0); evidence.sample(2000, 1000, true, 2f)
        assertEquals(1000L, evidence.listenedMs)
        assertEquals(2000L, evidence.coveredMs)
    }
    @Test fun `a large unreported position discontinuity is not coverage`() {
        val evidence = MoodPlaybackEvidence()
        evidence.resetPosition(0); evidence.sample(100000, 1000, true)
        assertEquals(0L, evidence.coveredMs)
    }
}
