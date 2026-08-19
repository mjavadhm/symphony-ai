package io.github.zyrouge.symphony.services.spotizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs matching the Spotizer backend (api.spotizer.javadhm.online).
 * Shapes are taken directly from the backend source:
 * - app/services/deezer.py (map_track / map_album / map_artist normalizers)
 * - app/schemas/tracks.py (TrackStatusResponse)
 * - app/schemas/downloads.py (DownloadRequest / JobStatusResponse / FileInfo)
 * - app/schemas/users.py (ResolveResponse / SettingsModel)
 */

object SpotizerQuality {
    const val MP3_128 = "MP3_128"
    const val MP3_320 = "MP3_320"
    const val FLAC = "FLAC"
    val ALL = listOf(MP3_128, MP3_320, FLAC)

    fun label(value: String) = when (value) {
        MP3_128 -> "MP3 128kbps"
        MP3_320 -> "MP3 320kbps"
        FLAC -> "FLAC (lossless)"
        else -> value
    }
}

@Serializable
data class SpotizerTrack(
    val id: String? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("artist_id") val artistId: String? = null,
    val album: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    @SerialName("cover_small") val coverSmall: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    /** seconds */
    val duration: Int? = null,
    val explicit: Boolean = false,
    @SerialName("preview_url") val previewUrl: String? = null,
    val link: String? = null,
    // detail-only fields (GET /v1/tracks/{id})
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("track_position") val trackPosition: Int? = null,
    @SerialName("disk_number") val diskNumber: Int? = null,
    val bpm: Double? = null,
    val isrc: String? = null,
) {
    val bestCover: String? get() = coverXl ?: coverBig ?: coverMedium ?: coverSmall
    val listCover: String? get() = coverMedium ?: coverSmall ?: coverBig ?: coverXl
    val durationMs: Long get() = (duration ?: 0).toLong() * 1000L
}

@Serializable
data class SpotizerAlbum(
    val id: String? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("artist_id") val artistId: String? = null,
    @SerialName("cover_small") val coverSmall: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("nb_tracks") val trackCount: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    /** album / single / ep */
    @SerialName("record_type") val recordType: String? = null,
    val explicit: Boolean = false,
    val link: String? = null,
    /** filled by GET /v1/albums/{id} */
    val tracks: List<SpotizerTrack> = emptyList(),
) {
    val bestCover: String? get() = coverXl ?: coverBig ?: coverMedium ?: coverSmall
    val listCover: String? get() = coverMedium ?: coverSmall ?: coverBig ?: coverXl
    val releaseYear: String? get() = releaseDate?.take(4)
}

@Serializable
data class SpotizerArtist(
    val id: String? = null,
    val name: String? = null,
    @SerialName("picture_small") val pictureSmall: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("nb_album") val albumCount: Int? = null,
    @SerialName("nb_fan") val fanCount: Long? = null,
    val link: String? = null,
    // filled by GET /v1/artists/{id}
    @SerialName("top_tracks") val topTracks: List<SpotizerTrack> = emptyList(),
    val albums: List<SpotizerAlbum> = emptyList(),
    @SerialName("related_artists") val relatedArtists: List<SpotizerArtist> = emptyList(),
) {
    val bestPicture: String? get() = pictureXl ?: pictureBig ?: pictureMedium ?: pictureSmall
    val listPicture: String? get() = pictureMedium ?: pictureSmall ?: pictureBig ?: pictureXl
}

// ---------- search ----------

@Serializable
data class SpotizerTrackSearchResponse(
    val type: String = "track",
    val results: List<SpotizerTrack> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class SpotizerAlbumSearchResponse(
    val type: String = "album",
    val results: List<SpotizerAlbum> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class SpotizerArtistSearchResponse(
    val type: String = "artist",
    val results: List<SpotizerArtist> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

enum class SpotizerSearchType(val wire: String) {
    Track("track"),
    Album("album"),
    Artist("artist"),
}

// ---------- tracks ----------

@Serializable
data class TrackStatusResponse(
    @SerialName("track_id") val trackId: String,
    val quality: String,
    /** true -> stream/download responds instantly */
    val cached: Boolean,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: String? = null,
    val duration: Int? = null,
    val size: Long? = null,
    val format: String? = null,
    @SerialName("has_lyrics") val hasLyrics: Boolean = false,
)

// ---------- downloads (server-side job queue; used for album zip flow) ----------

@Serializable
data class DownloadRequest(
    @SerialName("user_id") val userId: Long,
    /** track / album / playlist */
    @SerialName("content_type") val contentType: String,
    @SerialName("source_id") val sourceId: String,
    val quality: String? = null,
    @SerialName("as_zip") val asZip: Boolean? = null,
)

@Serializable
data class SpotizerFileInfo(
    /** audio / zip / lrc / m3u */
    val kind: String,
    val title: String? = null,
    val artist: String? = null,
    val duration: Int? = null,
    val size: Long? = null,
    @SerialName("platform_file_id") val platformFileId: String? = null,
    /** relative URL like /v1/files/{token} */
    @SerialName("temp_url") val tempUrl: String? = null,
)

@Serializable
data class DownloadCreatedResponse(
    val cached: Boolean,
    @SerialName("job_id") val jobId: String? = null,
    val status: String? = null,
    val files: List<SpotizerFileInfo>? = null,
    @SerialName("download_id") val downloadId: Long? = null,
)

@Serializable
data class JobStatusResponse(
    @SerialName("job_id") val jobId: String,
    /** queued / processing / ready / failed / cancelled */
    val status: String,
    val progress: Int = 0,
    @SerialName("current_step") val currentStep: String? = null,
    val error: String? = null,
    val files: List<SpotizerFileInfo>? = null,
)

// ---------- users ----------

@Serializable
data class SpotizerServerSettings(
    val quality: String = SpotizerQuality.MP3_320,
    @SerialName("make_zip") val makeZip: Boolean = true,
    val language: String = "fa",
)

@Serializable
data class ResolveRequest(
    @SerialName("platform_user_id") val platformUserId: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class ResolveResponse(
    @SerialName("user_id") val userId: Long,
    @SerialName("is_new") val isNew: Boolean,
    val settings: SpotizerServerSettings,
)

@Serializable
data class LinkRequest(
    val code: String,
    @SerialName("platform_user_id") val platformUserId: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SettingsUpdate(
    val quality: String? = null,
    @SerialName("make_zip") val makeZip: Boolean? = null,
    val language: String? = null,
)
