package io.github.zyrouge.symphony.services.mood

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MoodTrackRef(
    val key: String = UUID.randomUUID().toString(),
    val uri: String,
    val path: String,
    val title: String,
    val artists: List<String>,
    val album: String = "",
    val durationMs: Long,
)

@Serializable
data class MoodReaction(val track: MoodTrackRef, val value: Int, val updatedAt: Long = System.currentTimeMillis())

@Serializable
data class MoodProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mood session",
    val saved: Boolean = false,
    val seeds: List<MoodTrackRef>,
    val discovery: Float = 0.5f,
    val familiarDays: Int = 7,
    val reactions: Map<String, MoodReaction> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

@Serializable
data class MoodQueueEntry(
    val id: String = UUID.randomUUID().toString(),
    val track: MoodTrackRef,
    val kind: String = "generated", // seed / manual / generated
    val discovery: Boolean = false,
)

@Serializable
data class MoodRun(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val queue: List<MoodQueueEntry> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val playedKeys: Set<String> = emptySet(),
    val discoveryHistory: List<Boolean> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class MoodVisit(
    val id: String,
    val profileId: String,
    val runId: String,
    val track: MoodTrackRef,
    val listenedMs: Long,
    val coveredMs: Long,
    val reason: String,
    val signal: Float,
    val endedAt: Long = System.currentTimeMillis(),
)

data class MoodLocalTrack(
    val id: String, val uri: String, val path: String, val title: String,
    val artists: List<String>, val album: String, val durationMs: Long,
)

data class MoodPlaybackTag(val profileId: String, val runId: String, val track: MoodTrackRef,
                           val generated: Boolean, val discovery: Boolean)

/** File aliases take priority; ambiguous metadata is never attached to a different recording. */
class MoodCatalog(private val tracks: List<MoodLocalTrack>) {
    private val byUri = tracks.groupBy { it.uri }
    private val byPath = tracks.groupBy { it.path }
    fun resolve(ref: MoodTrackRef): MoodLocalTrack? {
        val aliases = (byUri[ref.uri].orEmpty() + byPath[ref.path].orEmpty()).distinctBy { it.id }
        if (aliases.isNotEmpty()) return aliases.singleOrNull()
        return tracks.filter {
            text(it.title) == text(ref.title) && it.artists.map(::text).sorted() == ref.artists.map(::text).sorted() &&
                kotlin.math.abs(it.durationMs - ref.durationMs) <= 3000 &&
                (it.album.isBlank() || ref.album.isBlank() || text(it.album) == text(ref.album))
        }.singleOrNull()
    }

    companion object {
        private fun text(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }
}
