package io.github.zyrouge.symphony.services.lyrics

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LyricsManager(private val symphony: Symphony) : Symphony.Hooks {
    enum class Status { UNKNOWN, CHECKING, EXISTING, SIDECAR, MISSING, QUEUED, WAITING, SEARCHING, READY, NOT_FOUND, FAILED, WRITING, EMBEDDED, SKIPPED }
    data class Item(val song: Song, val status: Status, val candidate: LyricsCandidate? = null, val message: String = "", val missing: Boolean = false, val writeError: Boolean = false, val accessRequired: Boolean = false)
    data class State(
        val busy: Boolean = false,
        val scopeKey: String = "",
        val scanned: Boolean = false,
        val cooldownUntil: Long = 0,
        val progress: String = "",
        val items: List<Item> = emptyList(),
        val recoveries: List<EmbeddedLyricsStore.Recovery> = emptyList(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = EmbeddedLyricsStore(symphony.applicationContext)
    private val client = LrclibClient()
    private val cache = LyricsLookupCache(java.io.File(symphony.applicationContext.cacheDir, "lyrics_lookup_v1"))
    private val mutableState = MutableStateFlow(State(recoveries = store.recoveries()))
    val state = mutableState.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private var job: Job? = null
    private val prefs = symphony.applicationContext.getSharedPreferences("lyrics_manager", 0)
    private val inventory = LyricsInventoryIndex(java.io.File(symphony.applicationContext.filesDir, "lyrics_inventory_v1.json"))
    private val sessionItems = mutableMapOf<String, Pair<String, Item>>()
    private fun signature(song: Song) = "${song.size}:${song.dateModified}"
    private fun songs(ids: Set<String>?) = symphony.groove.song.values()
        .filter { ids == null || it.id in ids }.sortedBy { it.title.lowercase() }

    /** Loading a screen never reads audio files or contacts a provider. */
    fun prepare(songIds: Set<String>? = null, scopeKey: String = "library") = launchTask {
        val rows = songs(songIds).map { song ->
            sessionItems[song.uri.toString()]?.takeIf { it.first == signature(song) }?.second?.copy(song = song)
                ?: inventory.get(song.uri.toString(), signature(song))?.let { status ->
                    Item(song, Status.valueOf(status), missing = status == "MISSING")
                } ?: Item(song, Status.UNKNOWN)
        }
        mutableState.update { it.copy(scopeKey = scopeKey, scanned = rows.none { row -> row.status == Status.UNKNOWN },
            items = rows, progress = if (rows.any { row -> row.status == Status.UNKNOWN }) "Check local files to find songs without lyrics. No automatic scan or online requests." else "Saved library check · use Check changes to update") }
    }
    var baseUrl: String
        get() = prefs.getString("base_url", LrclibClient.DEFAULT_BASE_URL)!!
        set(value) { prefs.edit().putString("base_url", LrclibClient.normalizeBaseUrl(value)).apply() }

    var publicAppId: String
        get() = prefs.getString("public_app_id", "").orEmpty()
        set(value) {
            val id = value.trim()
            require(id.length <= 200 && id.all { it.code in 33..126 }) { "Invalid public app ID" }
            prefs.edit().putString("public_app_id", id).apply()
        }
    private fun cooldownKey(base: String) = "cooldown:" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(base.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun update(item: Item) {
        sessionItems[item.song.uri.toString()] = signature(item.song) to item
        val known = when (item.status) {
            Status.EXISTING, Status.EMBEDDED -> "EXISTING"
            Status.SIDECAR -> "SIDECAR"
            else -> if (item.missing) "MISSING" else null
        }
        if (known != null) inventory.put(item.song.uri.toString(), signature(item.song), known)
        mutableState.update { state -> state.copy(items = state.items.map { if (it.song.id == item.song.id) item else it }) }
    }
    private fun launchTask(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) {
                mutableState.update { state -> state.copy(progress = "Stopped", items = state.items.map {
                    if (it.status in setOf(Status.CHECKING, Status.SEARCHING, Status.QUEUED, Status.WAITING, Status.WRITING)) {
                        it.copy(status = if (it.status == Status.CHECKING) Status.UNKNOWN else if (it.missing) Status.MISSING else Status.SKIPPED, message = "Stopped; select and retry")
                    } else it
                }) }
                throw e
            } catch (e: Exception) { io.github.zyrouge.symphony.services.diagnostics.Diagnostics.event("ERROR", "lyrics", "operation_failed", e); mutableState.update { it.copy(progress = e.message ?: "Operation failed") } }
            finally {
                // Persist only settled results, never an interrupted queue or write state.
                mutableState.value.items.forEach { sessionItems[it.song.uri.toString()] = signature(it.song) to it }
                runCatching { inventory.save() }
                mutableState.update { it.copy(busy = false, recoveries = store.recoveries()) }
            }
        }
    }

    /** Local inventory ONLY: never constructs a URL or contacts any lyrics provider. */
    fun scan(songIds: Set<String>? = null, scopeKey: String = "library", force: Boolean = false) = launchTask {
        check(store.recoveries().isEmpty()) { "Restore pending recovery backups before starting another scan" }
        val songs = songs(songIds)
        mutableState.update { it.copy(scopeKey = scopeKey, scanned = false, items = songs.map { song ->
            if (!force) sessionItems[song.uri.toString()]?.takeIf { cached -> cached.first == signature(song) }?.second?.copy(song = song)
                ?: inventory.get(song.uri.toString(), signature(song))?.let { status -> Item(song, Status.valueOf(status), missing = status == "MISSING") }
                ?: Item(song, Status.CHECKING)
            else Item(song, Status.CHECKING)
        }) }
        for ((index, song) in songs.withIndex()) {
            currentCoroutineContext().ensureActive()
            val prior = mutableState.value.items.first { it.song.id == song.id }
            if (!force && prior.status !in setOf(Status.CHECKING, Status.UNKNOWN, Status.FAILED)) continue
            mutableState.update { it.copy(progress = "Local scan ${index + 1}/${songs.size}: ${song.title} · no internet requests") }
            if (!EmbeddedLyricsStore.supported(song.filename)) {
                update(Item(song, Status.SKIPPED, message = "Unsupported file format")); continue
            }
            try {
                update(inspect(song))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { io.github.zyrouge.symphony.services.diagnostics.Diagnostics.event("ERROR", "lyrics", "operation_failed", e); update(Item(song, Status.FAILED, message = "Could not inspect local file: ${e.message}")) }
        }
        mutableState.update { it.copy(scanned = true, progress = "Local scan complete. Select missing songs, then Search selected. No provider was contacted.") }
    }

    private fun inspect(song: Song): Item = when (LyricsInventory.inspect({ store.readLyrics(song) }, { symphony.groove.song.getSidecarLyrics(song) })) {
        LyricsInventory.Presence.EMBEDDED -> Item(song, Status.EXISTING)
        LyricsInventory.Presence.SIDECAR -> Item(song, Status.SIDECAR, message = "Local .lrc already available; no online search needed")
        LyricsInventory.Presence.MISSING -> Item(song, Status.MISSING, missing = true)
    }

    /** Explicit selected lookup. Successful results and not-found responses are cached, not errors. */
    fun search(songIds: Set<String>, refresh: Boolean = false) = launchTask {
        check(store.recoveries().isEmpty()) { "Restore pending recovery backups first" }
        val base = LrclibClient.normalizeBaseUrl(baseUrl)
        val appId = publicAppId
        client.gate.restoreCooldown(prefs.getLong(cooldownKey(base), 0))
        mutableState.update { it.copy(cooldownUntil = client.gate.cooldownUntil) }
        val selected = mutableState.value.items.filter { it.song.id in songIds && it.missing && (refresh || it.status != Status.READY) }
        selected.forEach { update(it.copy(status = Status.QUEUED, candidate = null, message = "")) }
        for ((index, original) in selected.withIndex()) {
            currentCoroutineContext().ensureActive()
            val song = original.song
            var item = original.copy(candidate = null)
            try {
                // A file may have gained lyrics since inventory. Never search/write over them.
                val local = inspect(song)
                if (!local.missing) { update(local); continue }
                val track = LyricsTrack(song.title, song.artists, song.album, song.duration / 1000.0)
                val cached = if (refresh) null else cache.get(base, track)
                var candidate: LyricsCandidate? = null
                var completed = cached != null
                if (cached != null) candidate = cached.candidate
                for (attempt in 0..1) {
                    if (completed) break
                    item = item.copy(status = Status.SEARCHING, message = "")
                    update(item)
                    mutableState.update { it.copy(progress = "Searching ${index + 1}/${selected.size}: ${song.title}") }
                    try {
                        candidate = client.find(base, track, appId) { remaining ->
                            update(item.copy(status = if (remaining > 0) Status.WAITING else Status.SEARCHING,
                                message = if (remaining > 0) "Provider cooldown: ${(remaining + 999) / 1000}s remaining" else ""))
                            mutableState.update { it.copy(cooldownUntil = client.gate.cooldownUntil,
                                progress = if (remaining > 0) "Waiting for provider · ${(remaining + 999) / 1000}s · ${selected.size - index} songs left" else "Searching ${index + 1}/${selected.size}: ${song.title}") }
                        }
                        completed = true
                        runCatching { cache.put(base, track, candidate) }
                    } catch (e: LyricsApiException) {
                        if (e.code !in setOf(429, 503)) throw e
                        prefs.edit().putLong(cooldownKey(base), client.gate.cooldownUntil).apply()
                        mutableState.update { it.copy(cooldownUntil = client.gate.cooldownUntil) }
                        update(item.copy(status = Status.WAITING, message = "Provider requested a cooldown; your selection is kept"))
                        if (attempt == 1) {
                            mutableState.update { state -> state.copy(progress = "Provider is still busy. Queue paused; select Search again after cooldown.", items = state.items.map {
                                if (it.status in setOf(Status.QUEUED, Status.WAITING, Status.SEARCHING)) it.copy(status = Status.MISSING, message = "Search paused; retry after provider cooldown") else it
                            }) }
                            return@launchTask
                        }
                    }
                }
                update(item.copy(status = if (candidate == null) Status.NOT_FOUND else Status.READY, candidate = candidate,
                    message = if (cached != null) "Cached result · use Refresh selected online to check again" else ""))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { io.github.zyrouge.symphony.services.diagnostics.Diagnostics.event("ERROR", "lyrics", "operation_failed", e); update(item.copy(status = Status.FAILED, message = e.message ?: "Search failed", missing = true)) }
        }
        mutableState.update { it.copy(progress = "Search complete. Review the selected matches, then embed them.") }
    }

    fun embed(songIds: Set<String>) = launchTask {
        check(store.recoveries().isEmpty()) { "Restore pending recovery backups first" }
        val ready = mutableState.value.items.filter { it.song.id in songIds && it.status == Status.READY }
        for ((index, item) in ready.withIndex()) {
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(progress = "Embedding ${index + 1}/${ready.size}: ${item.song.title}") }
            if (symphony.radio.hasPlayer && symphony.radio.queue.currentSongId == item.song.id) {
                update(item.copy(message = "Currently loaded in player; play a different song before embedding this file", writeError = true, accessRequired = false)); continue
            }
            update(item.copy(status = Status.WRITING, message = "", writeError = false, accessRequired = false))
            try {
                // Finish this file (including cache/state refresh) before honoring Stop.
                withContext(NonCancellable) {
                    val written = store.embed(item.song, item.candidate!!.text)
                    if (written) {
                        symphony.database.lyricsCache.delete(item.song.id)
                        symphony.database.lyricsCache.put(item.song.id, item.candidate.text)
                        mutableRevision.update { it + 1 }
                    }
                    update(item.copy(status = if (written) Status.EMBEDDED else Status.EXISTING, message = "", missing = false, writeError = false, accessRequired = false))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SecurityException) { io.github.zyrouge.symphony.services.diagnostics.Diagnostics.event("ERROR", "lyrics", "write_access_denied", e);
                update(item.copy(message = "Grant write access to this music folder, then retry", writeError = true, accessRequired = true))
            } catch (e: Exception) { io.github.zyrouge.symphony.services.diagnostics.Diagnostics.event("ERROR", "lyrics", "operation_failed", e);
                update(item.copy(message = if (LyricsWriteErrors.isAccess(e)) "Write access denied. Grant access to this music folder, then retry." else e.message ?: "Embed failed; retry", writeError = true, accessRequired = LyricsWriteErrors.isAccess(e)))
                if (store.recoveries().isNotEmpty()) throw e
            }
        }
        val results = mutableState.value.items.filter { it.song.id in songIds }
        val failed = results.count { it.writeError }
        val written = results.count { it.status == Status.EMBEDDED }
        mutableState.update { it.copy(progress = "$written embedded and verified · $failed need attention${if (failed > 0) ". Open Errors, grant access if needed, then retry." else ""}") }
    }

    fun restore(recovery: EmbeddedLyricsStore.Recovery) = launchTask {
        check(!symphony.radio.hasPlayer) { "Stop playback before restoring a backup" }
        withContext(NonCancellable) {
            store.restore(recovery)
            symphony.groove.song.values().filter { it.uri == recovery.uri }.forEach {
                symphony.database.lyricsCache.delete(it.id)
                store.readLyrics(it)?.let { text -> symphony.database.lyricsCache.put(it.id, text) }
            }
            inventory.clear(); sessionItems.clear()
            mutableState.update { it.copy(items = emptyList(), scanned = false, progress = "Original restored. Check files again to refresh the list.") }
            mutableRevision.update { it + 1 }
        }
    }
    fun stop() { job?.cancel() }
    override fun onSymphonyDestroy() { scope.cancel() }
}
