package io.github.zyrouge.symphony.services.spotizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object SpotizerQuality {
    const val MP3_128 = "MP3_128"
    const val MP3_320 = "MP3_320"
    const val FLAC = "FLAC"

    val all = listOf(MP3_128, MP3_320, FLAC)

    fun label(quality: String) = when (quality) {
        MP3_128 -> "MP3 128 kbps"
        MP3_320 -> "MP3 320 kbps"
        FLAC -> "FLAC (lossless)"
        else -> quality
    }
}

@Serializable
data class SpotizerTrack(
    val id: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("artist_id") val artistId: Long? = null,
    val album: String? = null,
    @SerialName("album_id") val albumId: Long? = null,
    @SerialName("cover_small") val coverSmall: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    val duration: Int? = null,
    val explicit: Boolean? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val link: String? = null,
) {
    val durationMs: Long get() = (duration ?: 0) * 1000L
    val cover: String? get() = coverXl ?: coverBig ?: coverMedium ?: coverSmall
    val smallCover: String? get() = coverSmall ?: coverMedium ?: coverBig ?: coverXl
}

@Serializable
data class SpotizerAlbum(
    val id: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("artist_id") val artistId: Long? = null,
    @SerialName("cover_small") val coverSmall: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("nb_tracks") val nbTracks: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("record_type") val recordType: String? = null,
    val link: String? = null,
    val tracks: List<SpotizerTrack> = emptyList(),
) {
    val cover: String? get() = coverXl ?: coverBig ?: coverMedium ?: coverSmall
    val smallCover: String? get() = coverMedium ?: coverSmall ?: coverBig ?: coverXl
}

@Serializable
data class SpotizerArtist(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("picture_small") val pictureSmall: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("nb_fans") val nbFans: Long? = null,
    val link: String? = null,
    @SerialName("top_tracks") val topTracks: List<SpotizerTrack> = emptyList(),
    val albums: List<SpotizerAlbum> = emptyList(),
    @SerialName("related_artists") val relatedArtists: List<SpotizerArtist> = emptyList(),
) {
    val picture: String? get() = pictureXl ?: pictureBig ?: pictureMedium ?: pictureSmall
    val smallPicture: String? get() = pictureMedium ?: pictureSmall ?: pictureBig ?: pictureXl
}

@Serializable
data class SpotizerTrackSearchResponse(
    val type: String? = null,
    val results: List<SpotizerTrack> = emptyList(),
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

@Serializable
data class SpotizerAlbumSearchResponse(
    val type: String? = null,
    val results: List<SpotizerAlbum> = emptyList(),
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

@Serializable
data class SpotizerArtistSearchResponse(
    val type: String? = null,
    val results: List<SpotizerArtist> = emptyList(),
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

@Serializable
data class SpotizerTrackStatus(
    @SerialName("track_id") val trackId: Long? = null,
    val quality: String? = null,
    val cached: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: String? = null,
    val duration: Int? = null,
    val size: Long? = null,
    val format: String? = null,
    @SerialName("has_lyrics") val hasLyrics: Boolean? = null,
)

@Serializable
data class SpotizerUserSettings(
    val quality: String? = null,
    val language: String? = null,
)

@Serializable
data class SpotizerUser(
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("is_new") val isNew: Boolean? = null,
    val settings: SpotizerUserSettings? = null,
)
