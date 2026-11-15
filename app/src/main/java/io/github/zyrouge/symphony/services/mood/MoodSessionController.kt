package io.github.zyrouge.symphony.services.mood

import androidx.room.withTransaction
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.entities.MixFeedback
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.services.radio.RadioQueue
import io.github.zyrouge.symphony.utils.EventUnsubscribeFn
import io.github.zyrouge.symphony.services.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class MoodSessionState(
    val profiles: List<MoodProfile> = emptyList(),
    val profile: MoodProfile? = null,
    val run: MoodRun? = null,
    val visits: List<MoodVisit> = emptyList(),
    val attached: Boolean = false,
    val busy: Boolean = false,
    val exhausted: Boolean = false,
    val message: String = "",
)

/** The mood owns only its generated tail. Ordinary playback and radio keep their own policies. */
class MoodSessionController(private val symphony: Symphony) : Symphony.Hooks {
    private val db = MoodDatabase.create(symphony.applicationContext)
    private val dao = db.dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val revision = AtomicLong()
    private val ownershipRevision = AtomicLong()
    private val playIntentRevision = AtomicLong()
    private val prefs = symphony.applicationContext.getSharedPreferences("mood_playback", 0)
    private val mutableState = MutableStateFlow(MoodSessionState())
    val state = mutableState.asStateFlow()
    @Volatile var attached = false
        private set
    private var unsubscribe: EventUnsubscribeFn? = null
    private var checkpointJob: Job? = null
    private var profilesJob: Job? = null
    @Volatile private var shuttingDown = false
    private var changingQueue = false
    private var catalogIds: List<String>? = null
    private var catalogCache: MoodCatalog? = null
    private var lastSavedProfile: MoodProfileRow? = null

    private inline fun changeQueue(block: () -> Unit) {
        changingQueue = true
        try { block() } finally { changingQueue = false }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun command(block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                mutableState.update { it.copy(busy = true, message = "") }
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Diagnostics.event("ERROR", "mood", "operation_failed", e)
                    mutableState.update { it.copy(message = e.message ?: "Mood operation failed") }
                } finally { mutableState.update { it.copy(busy = false, attached = attached) } }
            }
        }
    }

    private fun local(song: Song) = MoodLocalTrack(song.id, song.uri.toString(), song.path, song.title,
        song.artists.toList().sorted(), song.album.orEmpty(), song.duration)
    @Synchronized private fun catalog(): MoodCatalog {
        val ids = symphony.groove.song.all.value
        if (catalogIds !== ids || catalogCache == null) {
            catalogCache = MoodCatalog(symphony.groove.song.values().map(::local))
            catalogIds = ids
        }
        return catalogCache!!
    }
    fun song(ref: MoodTrackRef): Song? = catalog().resolve(ref)?.id?.let(symphony.groove.song::get)
        ?: symphony.radio.queue.currentSongId?.let(symphony.groove.song::get)?.takeIf {
            // The repository retains the loaded song even if its file vanished during a rescan.
            it.uri.toString() == ref.uri || it.path == ref.path
        }
    private fun reference(song: Song, known: List<MoodTrackRef> = emptyList()): MoodTrackRef {
        val catalog = catalog()
        return known.firstOrNull { it.uri == song.uri.toString() || it.path == song.path }
            ?: known.firstOrNull { catalog.resolve(it)?.id == song.id }
            ?: MoodTrackRef(uri = song.uri.toString(), path = song.path, title = song.title,
                artists = song.artists.toList().sorted(), album = song.album.orEmpty(), durationMs = song.duration)
    }
    private fun known(profile: MoodProfile, visits: List<MoodVisit>, run: MoodRun?) =
        (profile.seeds + profile.reactions.values.map { it.track } + visits.map { it.track } + run?.queue.orEmpty().map { it.track }).distinctBy { it.key }

    private suspend fun save() {
        val profile = state.value.profile ?: return
        val run = state.value.run
        val row = MoodJson.row(profile)
        io { db.withTransaction {
            if (lastSavedProfile != row) dao.putProfile(row)
            if (run != null) dao.putRun(MoodJson.row(run.copy(updatedAt = System.currentTimeMillis())))
        } }
        lastSavedProfile = row
    }

    fun create(seedIds: List<String>) {
        val ownerToken = ownershipRevision.incrementAndGet()
        revision.incrementAndGet()
        command {
            require(seedIds.distinct().size in 1..5) { "Choose 1–5 different songs" }
            val songs = seedIds.distinct().map { symphony.groove.song.get(it) ?: error("A selected song is unavailable") }
            io { require(songs.all { embedding(it) != null }) { "Analyze every selected song first in AI indexing settings" } }
            if (ownerToken != ownershipRevision.get()) return@command
            val profile = MoodProfile(seeds = songs.map { reference(it) })
            val run = MoodRun(profileId = profile.id, queue = profile.seeds.map { MoodQueueEntry(track = it, kind = "seed") })
            if (!install(profile, run, emptyList(), autostart = true, ownerToken = ownerToken)) return@command
            refill(replace = true)
        }
    }

    fun resume(profileId: String, fresh: Boolean = false) {
        val ownerToken = ownershipRevision.incrementAndGet()
        revision.incrementAndGet()
        command {
            if (attached) { snapshot(); save() }
            val profile = io { dao.profile(profileId)?.let(MoodJson::profile) } ?: error("Mood no longer exists")
            val visits = io { dao.visits(profileId).map(MoodJson::visit) }
            val previous = if (fresh) null else io { dao.latestRun(profileId)?.let(MoodJson::run) }
            val run = previous ?: MoodRun(profileId = profile.id)
            if (ownerToken != ownershipRevision.get()) return@command
            if (!install(profile, run, visits, autostart = previous != null, ownerToken = ownerToken)) return@command
            val needsStart = previous == null || state.value.run?.queue?.isEmpty() == true
            refill(replace = needsStart, advance = needsStart)
        }
    }

    /** Called by the single startup queue-restoration path, never auto-starts audio. */
    suspend fun restorePlayback(): Boolean = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            val ownerToken = ownershipRevision.get()
            val id = prefs.getString("active_run", null) ?: return@withLock false
            try {
                val run = io { dao.run(id)?.let(MoodJson::run) } ?: return@withLock false
                val profile = io { dao.profile(run.profileId)?.let(MoodJson::profile) } ?: return@withLock false
                val visits = io { dao.visits(profile.id).map(MoodJson::visit) }
                install(profile, run, visits, autostart = false, ownerToken = ownerToken)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Diagnostics.event("ERROR", "mood", "restore_failed", e); false }
        }
    }

    private suspend fun install(profile: MoodProfile, run: MoodRun, visits: List<MoodVisit>, autostart: Boolean, ownerToken: Long): Boolean {
        if (attached) { snapshot(); save() }
        if (ownerToken != ownershipRevision.get()) return false
        attached = false
        symphony.radio.stop(ended = false)
        symphony.radio.queue.setShuffleMode(false)
        symphony.radio.queue.setLoopMode(RadioQueue.LoopMode.None)
        val available = run.queue.mapNotNull { entry -> song(entry.track)?.let { entry to it.id } }
        val oldCurrent = run.queue.getOrNull(run.index)?.id
        val index = available.indexOfFirst { it.first.id == oldCurrent }.coerceAtLeast(0)
        val restored = run.copy(queue = available.map { it.first }, index = index,
            positionMs = if (available.getOrNull(index)?.first?.id == oldCurrent) run.positionMs else 0)
        mutableState.update { it.copy(profile = profile.copy(updatedAt = System.currentTimeMillis()), run = restored, visits = visits, attached = true, exhausted = false) }
        attached = true
        symphony.radio.playbackSource = "mood:${restored.id}"
        prefs.edit().putString("active_run", restored.id).apply()
        if (available.isNotEmpty()) changeQueue {
            symphony.radio.queue.add(available.map { it.second }, options = Radio.PlayOptions(
                index = index, autostart = autostart, startPosition = restored.positionMs))
        }
        save()
        return attached && state.value.run?.id == run.id
    }

    fun react(profileId: String, track: MoodTrackRef, value: Int?) {
        require(value == null || value in setOf(-1, 1))
        revision.incrementAndGet()
        command {
            val profile = state.value.profile ?: return@command
            if (profile.id != profileId) return@command
            val reactions = profile.reactions.toMutableMap()
            if (value == null) reactions.remove(track.key) else reactions[track.key] = MoodReaction(track, value)
            mutableState.update { it.copy(profile = profile.copy(reactions = reactions, updatedAt = System.currentTimeMillis())) }
            save(); symphony.recommendation.invalidateMoodFeedback()
            if (attached) refill(replace = true)
        }
    }

    fun configure(profileId: String, discovery: Float, familiarDays: Int) {
        require(discovery.isFinite() && discovery in 0f..1f && familiarDays in setOf(3, 7, 14))
        revision.incrementAndGet()
        command {
            val profile = state.value.profile ?: return@command
            if (profile.id != profileId) return@command
            mutableState.update { it.copy(profile = profile.copy(discovery = discovery, familiarDays = familiarDays, updatedAt = System.currentTimeMillis())) }
            save(); if (attached) refill(replace = true)
        }
    }

    fun rename(profileId: String, name: String) = command {
        require(name.isNotBlank() && name.length <= 100) { "Enter a name of up to 100 characters" }
        val profile = io { dao.profile(profileId)?.let(MoodJson::profile) } ?: return@command
        val updated = profile.copy(name = name.trim(), saved = true, updatedAt = System.currentTimeMillis())
        io { dao.putProfile(MoodJson.row(updated)) }
        mutableState.update { if (it.profile?.id == profileId) it.copy(profile = updated) else it }
    }

    fun delete(profileId: String) {
        revision.incrementAndGet()
        command {
            if (state.value.profile?.id == profileId) {
                leavePlayback()
                mutableState.update { it.copy(profile = null, run = null, visits = emptyList()) }
            }
            io { dao.deleteProfile(profileId) }
            symphony.recommendation.invalidateMoodFeedback()
        }
    }

    /** Synchronous detach prevents a stale generation from taking over ordinary playback. */
    fun leavePlayback() {
        ownershipRevision.incrementAndGet()
        revision.incrementAndGet()
        if (!attached) return
        snapshot()
        attached = false
        mutableState.update { it.copy(attached = false) }
        if (!shuttingDown) prefs.edit().remove("active_run").apply()
        if (symphony.radio.playbackSource.startsWith("mood:")) symphony.radio.playbackSource = "queue"
        command { save() }
    }

    fun normalPlayback() {
        val radio = symphony.radio
        val options = Radio.PlayOptions(radio.queue.currentSongIndex, radio.isPlaying, radio.currentPlaybackPosition?.played)
        leavePlayback()
        if (radio.queue.hasSongAt(options.index)) radio.play(options, reason = "stop")
    }

    fun cancelPendingAdvance() { if (attached) playIntentRevision.incrementAndGet() }

    fun prepareShutdown() { shuttingDown = true }

    fun playbackTag(songId: String): MoodPlaybackTag? {
        if (!attached) return null
        val profile = state.value.profile ?: return null
        val run = state.value.run ?: return null
        val entry = run.queue.getOrNull(symphony.radio.queue.currentSongIndex)?.takeIf { song(it.track)?.id == songId }
            ?: run.queue.firstOrNull { song(it.track)?.id == songId } ?: return null
        return MoodPlaybackTag(profile.id, run.id, entry.track, entry.kind == "generated", entry.discovery)
    }

    fun recordVisit(tag: MoodPlaybackTag, id: String, listenedMs: Long, coveredMs: Long, reason: String) {
        if (attached && state.value.run?.id == tag.runId) revision.incrementAndGet()
        val visit = MoodVisit(id, tag.profileId, tag.runId, tag.track, listenedMs, coveredMs, reason,
            MoodEvidence.signal(tag.track.durationMs, listenedMs, coveredMs, reason))
        command {
            val exists = io { dao.profile(tag.profileId) != null }
            if (!exists) return@command
            val inserted = io { dao.putVisit(MoodJson.row(visit)) != -1L }
            if (!inserted) return@command
            if (state.value.profile?.id == tag.profileId) mutableState.update { it.copy(visits = it.visits + visit) }
            val run = if (state.value.run?.id == tag.runId) state.value.run else io { dao.run(tag.runId)?.let(MoodJson::run) }
            if (run != null) {
                val updated = run.copy(playedKeys = run.playedKeys + tag.track.key,
                    discoveryHistory = if (tag.generated)
                        (run.discoveryHistory + tag.discovery).takeLast(10) else run.discoveryHistory)
                io { dao.putRun(MoodJson.row(updated)) }
                if (state.value.run?.id == run.id) mutableState.update { it.copy(run = updated) }
            }
            symphony.recommendation.invalidateMoodFeedback()
            if (attached && state.value.run?.id == tag.runId) {
                snapshot(); save()
                if (symphony.radio.hasPlayer) refill(replace = true)
            }
        }
    }

    fun requestNext() {
        if (!attached) return
        val playToken = playIntentRevision.incrementAndGet()
        revision.incrementAndGet()
        command { if (attached) { snapshot(); refill(replace = false, advance = true, playToken = playToken) } }
    }

    private fun snapshot() {
        if (!attached) return
        val profile = state.value.profile ?: return
        val run = state.value.run ?: return
        val previous = run.queue.toMutableList()
        val refs = known(profile, state.value.visits, run)
        val actualIndex = symphony.radio.queue.currentSongIndex
        val entries = symphony.radio.queue.currentQueue.mapIndexedNotNull { index, id ->
            val song = symphony.groove.song.get(id) ?: return@mapIndexedNotNull null
            val i = previous.indexOfFirst { this.song(it.track)?.id == id }
            index to if (i >= 0) previous.removeAt(i) else MoodQueueEntry(track = reference(song, refs), kind = "manual")
        }
        val index = entries.indexOfFirst { it.first == actualIndex }.takeIf { it >= 0 }
            ?: entries.count { it.first < actualIndex }.coerceAtMost((entries.size - 1).coerceAtLeast(0))
        mutableState.update { it.copy(run = run.copy(queue = entries.map { it.second }, index = index,
            positionMs = symphony.radio.currentPlaybackPosition?.played
                ?: if (entries.getOrNull(index)?.second?.id == run.queue.getOrNull(run.index)?.id) run.positionMs else 0)) }
    }

    private fun embedding(song: Song) = symphony.semanticSearch.getMoodEmbedding(song.path, song.title, song.artists.joinToString(), song.duration)
        ?.let(MoodRecommender::normalized)

    private suspend fun refill(replace: Boolean, advance: Boolean = false, playToken: Long = playIntentRevision.get()) {
        val profile = state.value.profile ?: return
        val run = state.value.run ?: return
        if (!attached) return
        val token = revision.get()
        val allKnown = known(profile, state.value.visits, run)
        val localSongs = symphony.groove.song.values()
        val catalog = MoodCatalog(localSongs.map(::local))
        val byId = localSongs.associateBy { it.id }
        val byPath = localSongs.groupBy { it.path }
        val knownById = allKnown.mapNotNull { ref -> catalog.resolve(ref)?.id?.let { it to ref } }.toMap()
        val behavior = state.value.visits.groupBy { it.track.key }.mapValues { (_, rows) -> rows.map { it.signal } }
        val rejected = profile.reactions.values.filter { it.value < 0 }.map { it.track.key }.toSet()
        val points = io { allKnown.mapNotNull { ref ->
            val weight = MoodEvidence.effective(profile.reactions[ref.key]?.value, profile.seeds.any { it.key == ref.key }, behavior[ref.key].orEmpty())
            val vector = catalog.resolve(ref)?.id?.let(byId::get)?.let(::embedding)
            if (weight == 0f || vector == null) null else MoodPoint(ref.key, vector, weight)
        } }
        val current = run.queue.take(run.index + 1)
        val pending = run.queue.drop(run.index + 1)
        val pinned = pending.filter { it.kind != "generated" }
        val existingGenerated = if (replace) emptyList() else pending.filter { it.kind == "generated" }
        val excluded = run.playedKeys + current.map { it.track.key } + pinned.map { it.track.key } + existingGenerated.map { it.track.key }
        val picks = io {
            val positive = points.filter { it.weight > 0 }
            val probes = profile.seeds.mapNotNull { ref -> points.firstOrNull { it.key == ref.key && it.weight > 0 }?.vector }.toMutableList()
            if (positive.isNotEmpty()) MoodRecommender.normalized(FloatArray(MoodRecommender.DIM) { i ->
                positive.sumOf { (it.vector[i] * it.weight).toDouble() }.toFloat()
            })?.let { probes.add(it) }
            val tracks = probes.flatMap { symphony.semanticSearch.searchByVector(it, 200) }.distinctBy { it.filePath }.take(1000)
            val history = symphony.database.playbackHistory.getHistorySince(System.currentTimeMillis() - profile.familiarDays * 86_400_000L)
            val familiar = history.filter { it.durationPlayed >= minOf(30_000L, it.songDurationMs / 2) && it.songDurationMs > 0 }
            val candidates = tracks.mapNotNull { track ->
                val path = track.filePath ?: return@mapNotNull null
                val local = byPath[path]?.singleOrNull() ?: localSongs.filter {
                    it.title.equals(track.title, true) && it.artists.joinToString().equals(track.artist, true) &&
                        kotlin.math.abs(it.duration - track.durationSeconds * 1000L) <= 3000
                }.singleOrNull() ?: return@mapNotNull null
                val ref = knownById[local.id] ?: MoodTrackRef(uri = local.uri.toString(), path = local.path,
                    title = local.title, artists = local.artists.toList().sorted(), album = local.album.orEmpty(), durationMs = local.duration)
                val vector = track.meanEmbedding ?: return@mapNotNull null
                val heard = familiar.any { it.songId == local.id || (it.title == local.title && it.artist == local.artists.joinToString() && kotlin.math.abs(it.songDurationMs - local.duration) <= 3000) }
                MoodCandidate(ref, vector, heard)
            }
            MoodRecommender.pick(points, candidates, excluded, rejected, profile.discovery,
                run.discoveryHistory + existingGenerated.map { it.discovery }, (5 - existingGenerated.size).coerceAtLeast(0))
        }
        if (!attached || token != revision.get() || state.value.run?.id != run.id) return
        val generated = existingGenerated + picks.map { MoodQueueEntry(track = it.track, discovery = it.discovery) }
        val queue = if (replace) MoodQueuePolicy.replaceTail(run.queue, run.index, generated) else run.queue + generated.drop(existingGenerated.size)
        val playable = MoodQueuePolicy.align(queue, run.queue.getOrNull(run.index)?.id) { song(it)?.id }
        val updated = run.copy(queue = playable.entries, index = playable.index)
        val exhausted = playable.advanceIndex >= playable.entries.size
        mutableState.update { it.copy(run = updated, exhausted = exhausted,
            message = if (points.none { point -> point.weight > 0 }) "No available positive references. Analyze songs or create a new mood."
                else if (exhausted) "No more matching songs in this run. Start fresh to hear this mood again." else "") }
        changeQueue { symphony.radio.queue.replaceMoodQueue(playable.songIds, playable.index) }
        save()
        if (advance && playToken == playIntentRevision.get()) {
            val startAt = playable.advanceIndex
            if (symphony.radio.queue.hasSongAt(startAt)) symphony.radio.play(Radio.PlayOptions(startAt), reason = "next")
            else symphony.radio.pauseInstant()
        }
    }

    suspend fun globalFeedback(base: List<MixFeedback>): List<MixFeedback> = io {
        val reactions = dao.profiles().mapNotNull { runCatching { MoodJson.profile(it) }.getOrNull() }
            .flatMap { it.reactions.values }.mapNotNull { reaction ->
            song(reaction.track)?.let { MixFeedback(it.id, it.title, it.artists.joinToString(), reaction.value > 0, reaction.updatedAt) }
        }
        MoodGlobalFeedback.merge(base, reactions)
    }

    suspend fun historySignal(source: String): Float? {
        if (!source.startsWith("mood:")) return null
        val id = source.substringAfterLast(':')
        return io { dao.visit(id)?.let(MoodJson::visit)?.signal }
    }

    override fun onSymphonyReady() {
        profilesJob = scope.launch {
            dao.observeProfiles().catch { e ->
                Diagnostics.event("ERROR", "mood", "profile_load_failed", e)
                mutableState.update { it.copy(message = "Could not load saved moods") }
            }.collect { rows ->
                mutableState.update { it.copy(profiles = rows.mapNotNull { row -> runCatching { MoodJson.profile(row) }.getOrNull() }) }
            }
        }
        unsubscribe = symphony.radio.onUpdate.subscribe { event ->
            if (attached && !changingQueue && (event is Radio.Events.Queue || event is Radio.Events.Player.Paused || event is Radio.Events.Player.Seeked)) {
                if (event is Radio.Events.Queue) revision.incrementAndGet()
                command { if (attached) { snapshot(); save() } }
            }
        }
        checkpointJob = scope.launch { while (isActive) { delay(5000); if (attached) command { if (attached) { snapshot(); save() } } } }
    }
    override fun onSymphonyActivityPause() { if (attached) command { snapshot(); save() } }
    override fun onSymphonyDestroy() {
        unsubscribe?.invoke(); checkpointJob?.cancel(); profilesJob?.cancel()
        scope.launch { mutex.withLock { save() }; io { db.close() }; scope.cancel() }
    }
}
