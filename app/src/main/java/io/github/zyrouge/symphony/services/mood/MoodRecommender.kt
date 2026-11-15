package io.github.zyrouge.symphony.services.mood

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pilot heuristics, deliberately independent of Android, wall-clock age and playback position. */
object MoodEvidence {
    fun signal(durationMs: Long, listenedMs: Long, coveredMs: Long, reason: String): Float {
        if (durationMs <= 0 || listenedMs < 0) return 0f
        val seconds = listenedMs / 1000f
        val coverage = (coveredMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        val skipAt = minOf(20f, durationMs / 1000f * 0.2f)
        if (reason in setOf("next", "jump") && seconds < skipAt && coverage < 0.5f) {
            return -0.1f * (1f - seconds / skipAt)
        }
        return 0.25f * (seconds / 120f).coerceIn(0f, 1f) * (0.35f + 0.65f * coverage)
    }

    fun effective(reaction: Int?, seed: Boolean, behavior: List<Float>): Float = when (reaction) {
        1 -> 1f
        -1 -> -1f
        else -> if (seed) 0.6f else behavior.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
    }
}

/** Union of actually traversed media intervals. Seeking and replaying cannot fake coverage. */
class MoodCoverage {
    private val intervals = mutableListOf<Pair<Long, Long>>()
    @Synchronized fun add(start: Long, end: Long) {
        if (start < 0 || end <= start) return
        intervals.add(start to end)
        val merged = mutableListOf<Pair<Long, Long>>()
        for (range in intervals.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
            else merged.add(range)
        }
        intervals.clear(); intervals.addAll(merged)
    }
    @Synchronized fun coveredMs(): Long = intervals.sumOf { it.second - it.first }
}

class MoodPlaybackEvidence {
    private val coverage = MoodCoverage()
    private var previous: Long? = null
    var listenedMs: Long = 0
        private set
    val coveredMs get() = coverage.coveredMs()
    @Synchronized fun resetPosition(position: Long? = null) { previous = position }
    @Synchronized fun sample(position: Long?, elapsedMs: Long, playing: Boolean, speed: Float = 1f) {
        val elapsed = elapsedMs.coerceAtLeast(0)
        if (playing) {
            listenedMs += elapsed
            val before = previous
            if (before != null && position != null && position >= before &&
                position - before <= elapsed * speed.coerceAtLeast(0.1f) + 500) coverage.add(before, position)
        }
        previous = position
    }
}

object MoodQueuePolicy {
    fun replaceTail(queue: List<MoodQueueEntry>, index: Int, generated: List<MoodQueueEntry>): List<MoodQueueEntry> {
        val prefix = queue.take(index + 1)
        val replacements = generated.iterator()
        val tail = queue.drop(index + 1).mapNotNull { entry ->
            if (entry.kind != "generated") entry else if (replacements.hasNext()) replacements.next() else null
        }.toMutableList()
        while (replacements.hasNext()) tail.add(replacements.next())
        return prefix + tail
    }
}

data class MoodPoint(val key: String, val vector: FloatArray, val weight: Float)
data class MoodCandidate(val track: MoodTrackRef, val vector: FloatArray, val familiar: Boolean)
data class MoodPick(val track: MoodTrackRef, val discovery: Boolean)

object MoodRecommender {
    const val DIM = 512
    fun normalized(vector: FloatArray): FloatArray? {
        if (vector.size != DIM || vector.any { !it.isFinite() }) return null
        val norm = sqrt(vector.sumOf { it.toDouble() * it }.toFloat())
        if (norm < 0.00001f || !norm.isFinite()) return null
        return FloatArray(DIM) { vector[it] / norm }
    }
    private fun cosine(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    fun score(vector: FloatArray, points: List<MoodPoint>): Float {
        val positive = points.filter { it.weight > 0 }
        if (positive.isEmpty()) return Float.NEGATIVE_INFINITY
        val nearest = positive.maxOf { it.weight * cosine(vector, it.vector) }
        val mean = positive.sumOf { (it.weight * cosine(vector, it.vector)).toDouble() }.toFloat() / positive.sumOf { it.weight.toDouble() }.toFloat()
        val negative = points.filter { it.weight < 0 }
        val penalty = negative.sumOf { (-it.weight * cosine(vector, it.vector).coerceAtLeast(0f)).toDouble() }.toFloat() /
            maxOf(1f, negative.sumOf { -it.weight.toDouble() }.toFloat())
        return 0.6f * nearest + 0.4f * mean - 0.5f * penalty
    }

    fun pick(points: List<MoodPoint>, candidates: List<MoodCandidate>, excluded: Set<String>,
             rejected: Set<String>, discovery: Float, recentDiscovery: List<Boolean>, limit: Int = 5): List<MoodPick> {
        val valid = points.mapNotNull { point -> normalized(point.vector)?.let { point.copy(vector = it) } }
        if (valid.none { it.weight > 0 }) return emptyList()
        val ranked = candidates.distinctBy { it.track.key }.filter { it.track.key !in excluded && it.track.key !in rejected }
            .mapNotNull { candidate -> normalized(candidate.vector)?.let { candidate to score(it, valid) } }
            .sortedWith(compareByDescending<Pair<MoodCandidate, Float>> { it.second }.thenBy { it.first.track.key }).toMutableList()
        val recent = recentDiscovery.takeLast(9).toMutableList()
        val result = mutableListOf<MoodPick>()
        repeat(limit) {
            if (ranked.isEmpty()) return result
            val wantNew = ((recent.size + 1) * discovery.coerceIn(0f, 1f)).roundToInt() > recent.count { it }
            val index = ranked.indexOfFirst { !it.first.familiar == wantNew }.takeIf { it >= 0 } ?: 0
            val candidate = ranked.removeAt(index).first
            val isNew = !candidate.familiar
            result.add(MoodPick(candidate.track, isNew))
            recent.add(isNew); if (recent.size > 9) recent.removeAt(0)
        }
        return result
    }
}
