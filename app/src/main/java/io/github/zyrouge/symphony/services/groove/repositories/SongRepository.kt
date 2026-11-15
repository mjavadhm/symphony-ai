package io.github.zyrouge.symphony.services.groove.repositories

import android.net.Uri
import androidx.core.net.toUri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.Assets
import io.github.zyrouge.symphony.ui.helpers.createHandyImageRequest
import io.github.zyrouge.symphony.utils.FuzzySearchOption
import io.github.zyrouge.symphony.utils.FuzzySearcher
import io.github.zyrouge.symphony.utils.KeyGenerator
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.SimpleFileSystem
import io.github.zyrouge.symphony.utils.SimplePath
import io.github.zyrouge.symphony.utils.joinToStringIfNotEmpty
import io.github.zyrouge.symphony.utils.withCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SongRepository(private val symphony: Symphony) {
    enum class SortBy {
        CUSTOM,
        TITLE,
        ARTIST,
        ALBUM,
        DURATION,
        DATE_MODIFIED,
        COMPOSER,
        ALBUM_ARTIST,
        YEAR,
        FILENAME,
        TRACK_NUMBER,
    }

    private val index = SongLibraryIndex<Song>({ it.id }, { it.path })
    internal val pathCache get() = index.paths
    internal val idGenerator = KeyGenerator.TimeIncremental()
    private val searcher = FuzzySearcher<String>(
        options = listOf(
            FuzzySearchOption({ v -> get(v)?.title?.let { compareString(it) } }, 3),
            FuzzySearchOption({ v -> get(v)?.filename?.let { compareString(it) } }, 2),
            FuzzySearchOption({ v -> get(v)?.artists?.let { compareCollection(it) } }),
            FuzzySearchOption({ v -> get(v)?.album?.let { compareString(it) } })
        )
    )

    val isUpdating get() = symphony.groove.exposer.isUpdating
    val all = index.ids
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()
    private val _id = MutableStateFlow(System.currentTimeMillis())
    val id = _id.asStateFlow()
    var explorer = SimpleFileSystem.Folder()

    private fun emitCount() = _count.update { all.value.size }

    private fun emitIds() = _id.update {
        System.currentTimeMillis()
    }

    internal fun onSong(song: Song) {
        index.put(song)
        explorer.addChildFileIfAbsent(SimplePath(song.path))?.data = song.id
        emitIds()
        emitCount()
    }

    fun reset() {
        // Keep playback lookups available until the completed scan can reconcile them.
        explorer = SimpleFileSystem.Folder()
        index.reset()
        emitIds()
        emitCount()
    }

    internal fun finishScan(seenIds: Set<String>) {
        val retained = if (symphony.radio.hasPlayer) setOfNotNull(symphony.radio.queue.currentSongId) else emptySet()
        index.finishScan(seenIds, retained)
        explorer = SimpleFileSystem.Folder().also { tree ->
            index.values().forEach { tree.addChildFileIfAbsent(SimplePath(it.path))?.data = it.id }
        }
        emitIds()
        emitCount()
    }

    fun search(songIds: List<String>, terms: String, limit: Int = 7) = searcher
        .search(terms, songIds, maxLength = limit)

    fun sort(songIds: List<String>, by: SortBy, reverse: Boolean): List<String> {
        val sensitive = symphony.settings.caseSensitiveSorting.value
        val sorted = when (by) {
            SortBy.CUSTOM -> songIds
            SortBy.TITLE -> songIds.sortedBy { get(it)?.title?.withCase(sensitive) }
            SortBy.ARTIST -> songIds.sortedBy { get(it)?.artists?.joinToStringIfNotEmpty(sensitive) }
            SortBy.ALBUM -> songIds.sortedBy { get(it)?.album?.withCase(sensitive) }
            SortBy.DURATION -> songIds.sortedBy { get(it)?.duration }
            SortBy.DATE_MODIFIED -> songIds.sortedBy { get(it)?.dateModified }
            SortBy.COMPOSER -> songIds.sortedBy {
                get(it)?.composers?.joinToStringIfNotEmpty(sensitive)
            }

            SortBy.ALBUM_ARTIST -> songIds.sortedBy {
                get(it)?.albumArtists?.joinToStringIfNotEmpty(sensitive)
            }

            SortBy.YEAR -> songIds.sortedBy { get(it)?.year }
            SortBy.FILENAME -> songIds.sortedBy { get(it)?.filename?.withCase(sensitive) }
            SortBy.TRACK_NUMBER -> songIds.sortedWith(
                compareBy({ get(it)?.discNumber }, { get(it)?.trackNumber }),
            )
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun count() = all.value.size
    fun ids() = all.value
    fun values() = index.values()

    fun get(id: String) = index[id]
    fun get(ids: List<String>) = ids.mapNotNull { get(it) }

    fun getArtworkUri(songId: String): Uri = get(songId)?.coverFile
        ?.let { symphony.database.artworkCache.get(it) }?.toUri()
        ?: getDefaultArtworkUri()

    fun getDefaultArtworkUri() = Assets.getPlaceholderUri(symphony)

    fun createArtworkImageRequest(songId: String) = createHandyImageRequest(
        symphony.applicationContext,
        image = getArtworkUri(songId),
        fallback = Assets.getPlaceholderId(symphony),
    )

    /** Read-only sidecar lookup, also used by the offline missing-lyrics inventory. */
    fun getSidecarLyrics(song: Song): String? {
        val lrcPath = SimplePath(song.path).let {
            it.parent?.join(it.nameWithoutExtension + ".lrc")?.pathString
        }
        symphony.groove.exposer.uris[lrcPath]?.let { uri ->
            symphony.applicationContext.contentResolver.openInputStream(uri)?.use {
                return String(it.readBytes()).takeIf(String::isNotBlank)
            }
        }
        return null
    }

    suspend fun getLyrics(song: Song): String? {
        try {
            getSidecarLyrics(song)?.let { return it }
            symphony.database.lyricsCache.get(song.id)?.takeIf { it.isNotBlank() }?.let { return it }
            // Also handle lyrics embedded since the last library scan.
            return symphony.applicationContext.contentResolver.openFileDescriptor(song.uri, "r")?.use { descriptor ->
                me.zyrouge.symphony.metaphony.AudioMetadataParser.parse(song.filename, descriptor.fd)
                    ?.lyrics?.takeIf { it.isNotBlank() }?.also {
                        symphony.database.lyricsCache.put(song.id, it)
                    }
            }
        } catch (err: Exception) {
            Logger.error("LyricsRepository", "fetch lyrics failed", err)
        }
        return null
    }
}
