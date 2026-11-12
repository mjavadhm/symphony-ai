package io.github.zyrouge.symphony.services.lyrics

import java.text.Normalizer
import java.util.Locale

data class LyricsTrack(val title: String, val artists: Set<String>, val album: String?, val durationSeconds: Double)
data class LyricsRecord(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val duration: Double,
    val instrumental: Boolean,
    val syncedLyrics: String?,
    val plainLyrics: String?,
)
data class LyricsCandidate(val record: LyricsRecord, val text: String, val synced: Boolean)

/** Conservative matching: never use a same-title result from a different artist/version. */
object LyricsMatch {
    private val timestamp = Regex("""\[\d{1,3}:[0-5]\d(?:\.\d{1,3})?]""")
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun matches(track: LyricsTrack, record: LyricsRecord): Boolean =
        normalize(track.title).isNotEmpty() && normalize(track.title) == normalize(record.trackName) &&
            track.artists.any { normalize(it).isNotEmpty() && normalize(it) == normalize(record.artistName) } &&
            track.durationSeconds > 0 && record.duration > 0 &&
            kotlin.math.abs(track.durationSeconds - record.duration) <= 3.0

    fun candidate(record: LyricsRecord): LyricsCandidate? {
        if (record.instrumental) return null
        val synced = record.syncedLyrics?.trim()?.takeIf { timestamp.containsMatchIn(it) }
        if (synced != null) return LyricsCandidate(record, synced, true)
        return record.plainLyrics?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { LyricsCandidate(record, it, false) }
    }

    fun select(track: LyricsTrack, records: List<LyricsRecord>): LyricsCandidate? = records
        .filter { matches(track, it) }.mapNotNull(::candidate)
        .sortedWith(compareByDescending<LyricsCandidate> { it.synced }
            .thenByDescending { !track.album.isNullOrBlank() && normalize(track.album) == normalize(it.record.albumName) }
            .thenBy { kotlin.math.abs(track.durationSeconds - it.record.duration) })
        .firstOrNull()
}
