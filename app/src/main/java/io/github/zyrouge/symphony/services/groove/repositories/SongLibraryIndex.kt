package io.github.zyrouge.symphony.services.groove.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Keeps playback lookups alive during a rescan, then reconciles the active library. */
internal class SongLibraryIndex<T : Any>(private val idOf: (T) -> String, private val pathOf: (T) -> String) {
    private val songs = ConcurrentHashMap<String, T>()
    val paths = ConcurrentHashMap<String, String>()
    private val mutableIds = MutableStateFlow<List<String>>(emptyList())
    val ids = mutableIds.asStateFlow()

    @Synchronized fun put(song: T) {
        val id = idOf(song)
        val path = pathOf(song)
        val previous = paths.put(path, id)
        songs[id] = song
        mutableIds.value = mutableIds.value.filter { it != previous || it == id }.let {
            if (id in it) it else it + id
        }
    }

    /** Reset visible rows, but keep metadata available until the scan has completed. */
    @Synchronized fun reset() { mutableIds.value = emptyList() }

    @Synchronized fun finishScan(seenIds: Set<String>, retainedIds: Set<String> = emptySet()) {
        songs.keys.retainAll(seenIds + retainedIds)
        paths.entries.removeAll { it.value !in seenIds }
        mutableIds.value = mutableIds.value.filter { it in seenIds }
    }

    operator fun get(id: String): T? = songs[id]
    fun values(): List<T> = ids.value.mapNotNull(songs::get)
}
