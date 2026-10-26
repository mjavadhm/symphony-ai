package io.github.zyrouge.symphony.services.lyrics

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LyricsManager(private val symphony: Symphony) : Symphony.Hooks {
    enum class Status { CHECKING, EXISTING, SEARCHING, READY, NOT_FOUND, FAILED, WRITING, EMBEDDED, SKIPPED }
    data class Item(val song: Song, val status: Status, val candidate: LyricsCandidate? = null, val message: String = "")
    data class State(
        val busy: Boolean = false,
        val progress: String = "",
        val items: List<Item> = emptyList(),
        val recoveries: List<EmbeddedLyricsStore.Recovery> = emptyList(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = EmbeddedLyricsStore(symphony.applicationContext)
    private val client = LrclibClient()
    private val mutableState = MutableStateFlow(State(recoveries = store.recoveries()))
    val state = mutableState.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private var job: Job? = null
    private val prefs = symphony.applicationContext.getSharedPreferences("lyrics_manager", 0)
    var baseUrl: String
        get() = prefs.getString("base_url", LrclibClient.DEFAULT_BASE_URL)!!
        set(value) { prefs.edit().putString("base_url", LrclibClient.normalizeBaseUrl(value)).apply() }

    private fun update(item: Item) = mutableState.update { state ->
        state.copy(items = state.items.map { if (it.song.id == item.song.id) item else it })
    }
    private fun launchTask(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) {
                mutableState.update { state -> state.copy(progress = "Stopped", items = state.items.map {
                    if (it.status in setOf(Status.CHECKING, Status.SEARCHING, Status.WRITING)) {
                        it.copy(status = Status.SKIPPED, message = "Stopped; scan again to retry")
                    } else it
                }) }
                throw e
            } catch (e: Exception) { mutableState.update { it.copy(progress = e.message ?: "Operation failed") } }
            finally { mutableState.update { it.copy(busy = false, recoveries = store.recoveries()) } }
        }
    }

    /** Read-only scan/search. Writing requires a separate, explicit confirmation. */
    fun scan() = launchTask {
        check(store.recoveries().isEmpty()) { "Restore pending recovery backups before starting another scan" }
        val songs = symphony.groove.song.values().toList().sortedBy { it.title.lowercase() }
        val base = LrclibClient.normalizeBaseUrl(baseUrl)
        mutableState.update { it.copy(items = songs.map { song -> Item(song, Status.CHECKING) }) }
        for ((index, song) in songs.withIndex()) {
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(progress = "Checking ${index + 1}/${songs.size}: ${song.title}") }
            if (!EmbeddedLyricsStore.supported(song.filename)) {
                update(Item(song, Status.SKIPPED, message = "Unsupported file format")); continue
            }
            try {
                if (store.readLyrics(song) != null) { update(Item(song, Status.EXISTING)); continue }
                update(Item(song, Status.SEARCHING))
                val candidate = client.find(base, LyricsTrack(song.title, song.artists, song.album, song.duration / 1000.0))
                update(Item(song, if (candidate == null) Status.NOT_FOUND else Status.READY, candidate))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                update(Item(song, Status.FAILED, message = e.message ?: "Search failed"))
                if (e is LyricsApiException && e.code in setOf(429, 503)) {
                    mutableState.update { it.copy(progress = "Provider is rate limited or busy. Retry after its cooldown.") }
                    return@launchTask
                }
            }
        }
        mutableState.update { it.copy(progress = "Scan complete — review the matches before embedding") }
    }

    fun embed(songIds: Set<String>) = launchTask {
        check(store.recoveries().isEmpty()) { "Restore pending recovery backups first" }
        val ready = mutableState.value.items.filter { it.song.id in songIds && it.status == Status.READY }
        for ((index, item) in ready.withIndex()) {
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(progress = "Embedding ${index + 1}/${ready.size}: ${item.song.title}") }
            if (symphony.radio.hasPlayer && symphony.radio.queue.currentSongId == item.song.id) {
                update(item.copy(message = "Currently loaded in player; stop playback and retry")); continue
            }
            update(item.copy(status = Status.WRITING))
            try {
                // Finish this file (including cache/state refresh) before honoring Stop.
                withContext(NonCancellable) {
                    val written = store.embed(item.song, item.candidate!!.text)
                    if (written) {
                        symphony.database.lyricsCache.delete(item.song.id)
                        symphony.database.lyricsCache.put(item.song.id, item.candidate.text)
                        mutableRevision.update { it + 1 }
                    }
                    update(item.copy(status = if (written) Status.EMBEDDED else Status.EXISTING, message = ""))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SecurityException) {
                update(item.copy(message = "Grant write access to this music folder, then retry"))
            } catch (e: Exception) {
                update(item.copy(message = e.message ?: "Embed failed; retry"))
                if (store.recoveries().isNotEmpty()) throw e
            }
        }
        mutableState.update { it.copy(progress = "Embedding complete") }
    }

    fun restore(recovery: EmbeddedLyricsStore.Recovery) = launchTask {
        check(!symphony.radio.hasPlayer) { "Stop playback before restoring a backup" }
        withContext(NonCancellable) {
            store.restore(recovery)
            symphony.groove.song.values().filter { it.uri == recovery.uri }.forEach {
                symphony.database.lyricsCache.delete(it.id)
                store.readLyrics(it)?.let { text -> symphony.database.lyricsCache.put(it.id, text) }
            }
            mutableState.update { it.copy(items = emptyList(), progress = "Original restored. Scan again to refresh the list.") }
            mutableRevision.update { it + 1 }
        }
    }
    fun stop() { job?.cancel() }
    override fun onSymphonyDestroy() { scope.cancel() }
}
