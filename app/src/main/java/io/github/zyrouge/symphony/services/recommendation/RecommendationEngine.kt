package io.github.zyrouge.symphony.services.recommendation

import android.content.Context
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.llm.LlmClient
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import io.github.zyrouge.symphony.services.database.entities.MixContext
import io.github.zyrouge.symphony.services.database.entities.MixFeedback
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.database.entities.promptList
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.flow.first
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class RecommendationEngine(private val symphony: Symphony) {
    data class DailyMix(val name: String, val songIds: List<String>)

    companion object {
        private const val DIM = 512
        private const val PREFS = "recommendation_prefs"
        private const val MIN_HISTORY_FOR_TASTE = 10
        private const val MIN_POINTS_FOR_CLUSTERING = 40
        private const val MAX_SONGS_PER_ARTIST = 3
        private const val SKIP_EXCLUDE_DAYS = 30L
        private const val KEY_DAILY_DATE = "daily_mixes_date"
        private const val KEY_DAILY_DATA = "daily_mixes_data"

        // Auto hour contexts
        private const val KEY_AUTO_CONTEXT_IDS = "auto_context_ids"
        private const val KEY_AUTO_CONTEXTS_REFRESHED_AT = "auto_contexts_refreshed_at"
        private const val AUTO_CONTEXT_MIN_HISTORY = 40
        private const val AUTO_CONTEXT_MAX_COUNT = 3
        // Radio (song-seeded autoplay): fixed weight of the seed song in the query vector
        private const val RADIO_ANCHOR_WEIGHT = 0.4f
    }

    private val prefs
        get() = symphony.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Customizable settings ----
    var discoveryRatio: Float
        get() = prefs.getFloat("discovery_ratio", 0.3f)
        set(v) = prefs.edit().putFloat("discovery_ratio", v.coerceIn(0f, 1f)).apply()

    var recencyHalfLifeDays: Int
        get() = prefs.getInt("recency_half_life_days", 14)
        set(v) = prefs.edit().putInt("recency_half_life_days", v.coerceAtLeast(1)).apply()

    var dailyMixSize: Int
        get() = prefs.getInt("daily_mix_size", 30)
        set(v) = prefs.edit().putInt("daily_mix_size", v.coerceIn(10, 100)).apply()

    var dailyMixCount: Int
        get() = prefs.getInt("daily_mix_count", 2)
        set(v) = prefs.edit().putInt("daily_mix_count", v.coerceIn(1, 3)).apply()

    fun resetToDefaults() {
        val date = prefs.getString(KEY_DAILY_DATE, null)
        val data = prefs.getString(KEY_DAILY_DATA, null)
        prefs.edit().clear().apply()
        if (date != null && data != null) {
            prefs.edit().putString(KEY_DAILY_DATE, date).putString(KEY_DAILY_DATA, data).apply()
        }
    }

    // ---------------------------------------------------------------
    // Daily Mixes (several of them, via clustering) — cached for one day
    // ---------------------------------------------------------------
    private var memoryCache: Pair<String, List<DailyMix>>? = null

    // Salt used for reroll; kept in memory only. Key = mix id
    private val mixSalts = mutableMapOf<Long, Long>()

    private fun todayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    suspend fun getDailyMixes(forceRefresh: Boolean = false): List<DailyMix> {
        val key = todayKey()
        if (!forceRefresh) {
            memoryCache?.let { if (it.first == key) return it.second }
            if (prefs.getString(KEY_DAILY_DATE, null) == key) {
                val cached = decodeMixes(prefs.getString(KEY_DAILY_DATA, "") ?: "")
                    .map { mix ->
                        mix.copy(songIds = mix.songIds.filter { symphony.groove.song.get(it) != null })
                    }
                    .filter { it.songIds.isNotEmpty() }
                if (cached.isNotEmpty()) {
                    memoryCache = key to cached
                    return cached
                }
            }
        }
        val mixes = generateDailyMixes()
        memoryCache = key to mixes
        prefs.edit()
            .putString(KEY_DAILY_DATE, key)
            .putString(KEY_DAILY_DATA, encodeMixes(mixes))
            .apply()
        return mixes
    }

    private fun encodeMixes(mixes: List<DailyMix>) =
        mixes.joinToString(";") { "${it.name}|${it.songIds.joinToString(",")}" }

    private fun decodeMixes(s: String): List<DailyMix> = s.split(";").mapNotNull { part ->
        val i = part.indexOf('|')
        if (i < 0) null else DailyMix(
            part.substring(0, i),
            part.substring(i + 1).split(",").filter { it.isNotBlank() },
        )
    }

    private suspend fun generateDailyMixes(): List<DailyMix> {
        val points = collectTastePoints()
        if (points.size < MIN_HISTORY_FOR_TASTE) return emptyList()

        val playedIds = try {
            symphony.database.playbackHistory.getAllPlayedSongIds().toSet()
        } catch (e: Exception) { emptySet() }
        val skippedIds = try {
            symphony.database.playbackHistory.getRecentlySkippedSongIds(
                System.currentTimeMillis() - SKIP_EXCLUDE_DAYS * 86_400_000L
            ).toSet()
        } catch (e: Exception) { emptySet() }
        val dislikedIds = try {
            symphony.database.mixFeedback.getAll().filter { !it.liked }.map { it.songId }.toSet()
        } catch (e: Exception) { emptySet() }

        val exclude = skippedIds + dislikedIds
        val positive = points.filter { it.weight > 0f }

        // Cluster only when there is enough data; otherwise fall back to a single vector
        val centroids = when {
            dailyMixCount > 1 && positive.size >= MIN_POINTS_FOR_CLUSTERING ->
                kMeans(positive.map { it.emb }, positive.map { it.weight }, dailyMixCount)
            else -> emptyList()
        }.ifEmpty { listOfNotNull(buildVector(points)) }
        if (centroids.isEmpty()) return emptyList()

        val used = mutableSetOf<String>()
        val mixes = centroids.mapIndexedNotNull { i, c ->
            val ids = generateMixFromVector(c, dailyMixSize, playedIds, exclude + used)
            used += ids
            when {
                ids.isEmpty() -> null
                centroids.size == 1 -> DailyMix("Daily Mix", ids)
                else -> DailyMix("Daily Mix ${i + 1}", ids)
            }
        }
        return maybeNameDailyMixes(mixes)
    }

    /**
     * Auto mode only: asks the LLM for a real name for each Daily Mix.
     * Any failure falls back to the default name. It never breaks the main path.
     */
    private suspend fun maybeNameDailyMixes(mixes: List<DailyMix>): List<DailyMix> {
        val llm = symphony.llm
        if (llm.usageMode != LlmClient.UsageMode.Auto || !llm.isConfigured) return mixes
        return mixes.map { mix ->
            val songs = mix.songIds.take(8).mapNotNull { symphony.groove.song.get(it) }
            if (songs.isEmpty()) return@map mix
            val name = try {
                symphony.llmTasks.nameMix(
                    titles = songs.map { it.title },
                    artists = songs.flatMap { it.artists }.distinct().take(8),
                )
            } catch (e: Exception) {
                null
            }
            when (name) {
                null -> mix
                // These two characters are separators in the cache format, so they must not appear in a name
                else -> mix.copy(name = name.replace("|", " ").replace(";", " ").trim())
            }
        }
    }

    // ---------------------------------------------------------------
    // Shared core: taste points, vector building, mix generation
    // ---------------------------------------------------------------
    private class TastePoint(val emb: FloatArray, val weight: Float)

    private suspend fun collectTastePoints(hours: Set<Int>? = null): List<TastePoint> {
        val since = System.currentTimeMillis() - 90L * 86_400_000L
        val history = try {
            symphony.database.playbackHistory.getHistorySince(since)
        } catch (e: Exception) { return emptyList() }
        val filtered = hours?.let { h -> history.filter { it.hourOfDay in h } } ?: history

        val now = System.currentTimeMillis()
        val halfLifeMs = recencyHalfLifeDays * 86_400_000f
        val cache = HashMap<String, FloatArray?>()
        val points = mutableListOf<TastePoint>()

        for (rec in filtered) {
            if (rec.title.isBlank()) continue
            val emb = cache.getOrPut("${rec.title}|${rec.artist}".lowercase()) {
                symphony.semanticSearch.getTrackEmbedding(rec.title, rec.artist)
            } ?: continue
            val recency = 0.5f.pow((now - rec.playedAt) / halfLifeMs)
            val weight = if (rec.skipped) -0.5f * recency else rec.completionRate * recency
            points.add(TastePoint(emb, weight))
        }
        // Explicit user feedback — the strongest signal
        try {
            for (fb in symphony.database.mixFeedback.getAll()) {
                val emb = cache.getOrPut("${fb.title}|${fb.artist}".lowercase()) {
                    symphony.semanticSearch.getTrackEmbedding(fb.title, fb.artist)
                } ?: continue
                points.add(TastePoint(emb, if (fb.liked) 1f else -0.8f))
            }
        } catch (e: Exception) { /* ignore */ }
        return points
    }

    private fun buildVector(points: List<TastePoint>): FloatArray? {
        if (points.size < MIN_HISTORY_FOR_TASTE) return null
        val acc = FloatArray(DIM)
        var totalWeight = 0f
        for (p in points) {
            for (i in 0 until DIM) acc[i] += p.emb[i] * p.weight
            totalWeight += kotlin.math.abs(p.weight)
        }
        if (totalWeight < 1f) return null
        return normalized(acc)
    }

    private suspend fun generateMixFromVector(
        vector: FloatArray,
        limit: Int,
        playedIds: Set<String>,
        excludeIds: Set<String>,
    ): List<String> {
        val candidates = try {
            symphony.semanticSearch.searchByVector(vector, limit * 3)
        } catch (e: Exception) { return emptyList() }

        val familiar = mutableListOf<String>()
        val discovery = mutableListOf<String>()
        val artistCount = HashMap<String, Int>()

        for (track in candidates) {
            val path = track.filePath ?: continue
            val songId = resolvePathToSongId(path) ?: continue
            if (songId in excludeIds) continue
            val song = symphony.groove.song.get(songId) ?: continue
            val artistKey = song.artists.firstOrNull()?.lowercase()?.trim() ?: ""
            val count = artistCount.getOrDefault(artistKey, 0)
            if (artistKey.isNotEmpty() && count >= MAX_SONGS_PER_ARTIST) continue
            artistCount[artistKey] = count + 1
            if (songId in playedIds) familiar.add(songId) else discovery.add(songId)
        }

        val discoveryCount = (limit * discoveryRatio).toInt()
        var mix = (familiar.take(limit - discoveryCount) + discovery.take(discoveryCount)).distinct()
        if (mix.size < limit) {
            val remaining = (familiar + discovery).distinct().filter { it !in mix.toSet() }
            mix = mix + remaining.take(limit - mix.size)
        }
        return mix.shuffled()
    }

    // ---- Small k-means over normalized vectors ----
    private fun kMeans(
        points: List<FloatArray>,
        weights: List<Float>,
        k: Int,
        iterations: Int = 8,
    ): List<FloatArray> {
        if (points.size < k * 5) return emptyList()
        // Init: heaviest point, then the farthest ones (farthest-first)
        val centroids = mutableListOf(points[weights.indices.maxBy { weights[it] }])
        while (centroids.size < k) {
            var best = 0
            var bestDist = -1f
            for (i in points.indices) {
                val d = centroids.minOf { c -> 1f - dot(points[i], c) }
                if (d > bestDist) { bestDist = d; best = i }
            }
            centroids.add(points[best])
        }
        val assign = IntArray(points.size)
        repeat(iterations) {
            for (i in points.indices) {
                assign[i] = centroids.indices.maxBy { dot(points[i], centroids[it]) }
            }
            for (c in centroids.indices) {
                val acc = FloatArray(DIM)
                var tw = 0f
                for (i in points.indices) {
                    if (assign[i] != c) continue
                    val w = weights[i]
                    for (j in 0 until DIM) acc[j] += points[i][j] * w
                    tw += w
                }
                if (tw > 0.5f) normalized(acc)?.let { centroids[c] = it }
            }
        }
        val counts = IntArray(centroids.size)
        for (a in assign) counts[a]++
        return centroids.filterIndexed { i, _ -> counts[i] >= 5 }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until DIM) s += a[i] * b[i]
        return s
    }

    private fun normalized(v: FloatArray): FloatArray? {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm < 1e-6f) return null
        val out = FloatArray(DIM)
        for (i in 0 until DIM) out[i] = v[i] / norm
        return out
    }

    // ---------------------------------------------------------------
    // Context Mixes (hour ranges, including ranges that wrap past midnight)
    // ---------------------------------------------------------------
    fun hoursOf(ctx: MixContext): Set<Int> = when {
        ctx.startHour <= ctx.endHour -> (ctx.startHour..ctx.endHour).toSet()
        else -> ((ctx.startHour..23) + (0..ctx.endHour)).toSet()
    }

    fun activeContext(contexts: List<MixContext>): MixContext? {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return contexts.firstOrNull { it.enabled && hour in hoursOf(it) }
    }

    suspend fun getContextMixSongIds(ctx: MixContext): List<String> {
        val vector = buildVector(collectTastePoints(hoursOf(ctx))) ?: return emptyList()
        val playedIds = try {
            symphony.database.playbackHistory.getAllPlayedSongIds().toSet()
        } catch (e: Exception) { emptySet() }
        val dislikedIds = try {
            symphony.database.mixFeedback.getAll().filter { !it.liked }.map { it.songId }.toSet()
        } catch (e: Exception) { emptySet() }
        return generateMixFromVector(vector, dailyMixSize, playedIds, dislikedIds)
    }

    suspend fun seedDefaultContextsIfNeeded() {
        val store = symphony.database.mixContexts
        if (store.count() == 0) {
            // With enough listening history, learn the hours instead of hardcoding them
            if (maybeRefreshAutoContexts(force = true)) return
            store.insert(MixContext(name = "Morning", icon = "☀️", startHour = 6, endHour = 11))
            store.insert(MixContext(name = "Night", icon = "🌙", startHour = 21, endHour = 2))
            return
        }
        // Keep auto contexts in sync with listening habits (throttled internally)
        maybeRefreshAutoContexts()
    }

    // ---------------------------------------------------------------
    // Auto hour contexts: learn active listening hours from history
    // ---------------------------------------------------------------
    private val autoContextIds: Set<Long>
        get() = prefs.getStringSet(KEY_AUTO_CONTEXT_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    private fun saveAutoContextIds(ids: Set<Long>) {
        prefs.edit()
            .putStringSet(KEY_AUTO_CONTEXT_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    /** Untouched default seeds may be replaced by learned contexts; user contexts may not. */
    private fun isDefaultSeedContext(ctx: MixContext) = when {
        ctx.name == "Morning" && ctx.startHour == 6 && ctx.endHour == 11 -> true
        ctx.name == "Night" && ctx.startHour == 21 && ctx.endHour == 2 -> true
        else -> false
    }

    private data class HourBand(val start: Int, val end: Int, val weight: Float, val peak: Int)

    /**
     * Builds a completion-weighted 24-bin histogram of the last 90 days of playback,
     * smooths it circularly, and returns up to three contiguous "active" hour bands
     * (midnight wrap supported).
     */
    private suspend fun detectActiveHourBands(): List<HourBand> {
        val since = System.currentTimeMillis() - 90L * 86_400_000L
        val history = try {
            symphony.database.playbackHistory.getHistorySince(since)
        } catch (e: Exception) { return emptyList() }
        if (history.size < AUTO_CONTEXT_MIN_HISTORY) return emptyList()

        // Completion-weighted histogram (skips add nothing)
        val raw = FloatArray(24)
        for (rec in history) {
            if (rec.skipped) continue
            raw[rec.hourOfDay.coerceIn(0, 23)] += rec.completionRate.coerceIn(0f, 1f)
        }
        // Circular smoothing so one noisy hour doesn't create or break a band
        val bins = FloatArray(24)
        for (h in 0 until 24) {
            bins[h] = 0.25f * raw[(h + 23) % 24] + 0.5f * raw[h] + 0.25f * raw[(h + 1) % 24]
        }
        val mean = bins.sum() / 24f
        if (mean <= 0f) return emptyList()
        val active = BooleanArray(24) { bins[it] >= mean * 1.1f }

        val bands = mutableListOf<HourBand>()
        val visited = BooleanArray(24)
        for (h in 0 until 24) {
            if (!active[h] || visited[h]) continue
            var start = h
            while (active[(start + 23) % 24] && (start + 23) % 24 != h) start = (start + 23) % 24
            var end = h
            while (active[(end + 1) % 24] && (end + 1) % 24 != start) end = (end + 1) % 24
            var weight = 0f
            var peak = start
            var length = 0
            var i = start
            while (true) {
                visited[i] = true
                weight += bins[i]
                if (bins[i] > bins[peak]) peak = i
                length++
                if (i == end) break
                i = (i + 1) % 24
            }
            // Too short = noise, too long = not a distinct context
            if (length in 2..10) bands.add(HourBand(start, end, weight, peak))
        }
        return bands.sortedByDescending { it.weight }.take(AUTO_CONTEXT_MAX_COUNT)
    }

    private fun daypartNameAndIcon(hour: Int): Pair<String, String> = when (hour) {
        in 5..11 -> "Morning" to "☀️"
        in 12..16 -> "Afternoon" to "🌤️"
        in 17..20 -> "Evening" to "🌆"
        21, 22, 23, 0, 1 -> "Night" to "🌙"
        else -> "Late Night" to "🌃"
    }

    /**
     * Learns which hours the user actually listens at and keeps up to three auto
     * contexts in sync with them. Throttled to once a week unless forced.
     * User-created contexts are never modified or deleted.
     * @return true when at least one auto context exists afterwards.
     */
    suspend fun maybeRefreshAutoContexts(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_AUTO_CONTEXTS_REFRESHED_AT, 0L)
        if (!force && now - last < 7L * 86_400_000L) return autoContextIds.isNotEmpty()

        val bands = detectActiveHourBands()
        if (bands.isEmpty()) {
            // Not enough listening data yet — retry in a day instead of a week
            prefs.edit().putLong(KEY_AUTO_CONTEXTS_REFRESHED_AT, now - 6L * 86_400_000L).apply()
            return false
        }
        prefs.edit().putLong(KEY_AUTO_CONTEXTS_REFRESHED_AT, now).apply()

        val store = symphony.database.mixContexts
        val existing = try { store.getAll().first() } catch (e: Exception) { return false }
        val autoIds = autoContextIds
        val replaceable = existing.filter { it.id in autoIds || isDefaultSeedContext(it) }
        val manual = existing.filter { ctx -> replaceable.none { it.id == ctx.id } }

        fun sharedHours(ctx: MixContext, band: HourBand) = (
            hoursOf(ctx) intersect
                hoursOf(MixContext(name = "", startHour = band.start, endHour = band.end))
            ).size

        val keptIds = mutableSetOf<Long>()
        val usedNames = mutableSetOf<String>()
        val leftover = replaceable.toMutableList()
        for (band in bands) {
            val bandLength =
                hoursOf(MixContext(name = "", startHour = band.start, endHour = band.end)).size
            // The user already covers most of these hours with their own context → skip
            if (manual.any { sharedHours(it, band) * 2 >= bandLength }) continue
            val (baseName, icon) = daypartNameAndIcon(band.peak)
            val name = when {
                usedNames.add(baseName) -> baseName
                else -> "$baseName ${band.start}–${band.end}"
            }
            // Reuse the overlapping auto context instead of recreating it
            val match = leftover
                .maxByOrNull { sharedHours(it, band) }
                ?.takeIf { sharedHours(it, band) > 0 }
            if (match != null) {
                leftover.remove(match)
                val updated = match.copy(
                    name = name,
                    icon = icon,
                    startHour = band.start,
                    endHour = band.end,
                )
                if (updated != match) {
                    try {
                        store.update(updated)
                    } catch (e: Exception) {
                        Logger.error("RecommendationEngine", "auto context update failed", e)
                        continue
                    }
                }
                keptIds.add(match.id)
            } else {
                val id = try {
                    store.insert(
                        MixContext(
                            name = name,
                            icon = icon,
                            startHour = band.start,
                            endHour = band.end,
                        )
                    )
                } catch (e: Exception) {
                    Logger.error("RecommendationEngine", "auto context insert failed", e)
                    continue
                }
                keptIds.add(id)
            }
        }
        // Stale auto contexts (and superseded default seeds) get removed
        for (stale in leftover) {
            val removable = stale.id in autoIds ||
                (isDefaultSeedContext(stale) && keptIds.isNotEmpty())
            if (removable) {
                try {
                    store.delete(stale)
                } catch (e: Exception) { /* ignore */ }
            }
        }
        saveAutoContextIds(keptIds)
        return keptIds.isNotEmpty()
    }

    // ---------------------------------------------------------------
    // Explicit feedback (layer 3)
    // ---------------------------------------------------------------
    suspend fun setFeedback(song: Song, liked: Boolean) {
        try {
            symphony.database.mixFeedback.upsert(
                MixFeedback(
                    songId = song.id,
                    title = song.title,
                    artist = song.artists.joinToString(),
                    liked = liked,
                )
            )
        } catch (e: Exception) {
            Logger.error("RecommendationEngine", "setFeedback failed", e)
        }
    }

    // ---------------------------------------------------------------
    // Mood / Custom Mixes  (unchanged)
    // ---------------------------------------------------------------
    private val coverCache = HashMap<String, List<String>>()

    suspend fun getMixCoverSongIds(mix: CustomMix): List<String> {
        coverCache[mix.prompt]?.let { return it }
        val ids = getMixSongIds(mix.copy(trackCount = 4))
        if (ids.isNotEmpty()) coverCache[mix.prompt] = ids
        return ids
    }

    suspend fun getMixSongIds(mix: CustomMix, reroll: Boolean = false): List<String> {
        val promptList = mix.promptList()
        if (promptList.isEmpty()) return emptyList()

        if (reroll) {
            mixSalts[mix.id] = System.currentTimeMillis()
        }
        val salt = mixSalts[mix.id] ?: 0L

        // seed = mix id + today's date + reroll salt
        val seed = mix.id * 31L + todayKey().hashCode().toLong() + salt
        val rng = kotlin.random.Random(seed)

        // Prompt rotation: each day (and each reroll) picks one of the prompts
        val prompt = promptList[rng.nextInt(promptList.size)]

        // Pull a 3x pool so there is room for variety
        val pool = symphony.semanticSearch.searchDetailed(prompt, mix.trackCount * 3)
        if (pool.isEmpty()) return emptyList()

        // Weighted noise: more relevant songs get better odds, but the pick isn't deterministic
        val topScore = pool.first().hybridScore
        val jitter = 0.25f * topScore

        return pool
            .map { it to (it.hybridScore + rng.nextFloat() * jitter) }
            .sortedByDescending { it.second }
            .mapNotNull { entry ->
                entry.first.track.filePath?.let { resolvePathToSongId(it) }
            }
            .distinct()
            .take(mix.trackCount)
    }

    suspend fun seedDefaultMixesIfNeeded() {
        val store = symphony.database.customMixes
        if (store.count() > 0) return
        listOf(
            CustomMix(
                name = "Sad", icon = "😢", isBuiltIn = true, sortOrder = 0,
                prompt = "sad emotional songs",
                description = "For when you feel down and want to sit with the feeling",
                prompts = "melancholic slow piano ballad\n" +
                        "sad acoustic guitar with soft emotional vocals\n" +
                        "heartbreak songs, quiet and lonely mood",
            ),
            CustomMix(
                name = "Workout", icon = "⚡", isBuiltIn = true, sortOrder = 1,
                prompt = "energetic workout music",
                description = "High-energy tracks to keep you moving at the gym",
                prompts = "energetic rock with heavy drums for gym\n" +
                        "fast upbeat electronic dance music, high tempo\n" +
                        "aggressive hip hop with powerful bass",
            ),
            CustomMix(
                name = "Chill", icon = "🌊", isBuiltIn = true, sortOrder = 2,
                prompt = "chill relaxing music",
                description = "Laid-back songs for doing nothing in particular",
                prompts = "chill lofi beats, relaxed and mellow\n" +
                        "soft indie pop, warm and easygoing\n" +
                        "smooth ambient music for relaxing",
            ),
            CustomMix(
                name = "Night Drive", icon = "🌙", isBuiltIn = true, sortOrder = 3,
                prompt = "night drive music",
                description = "Moody tracks for late-night driving",
                prompts = "dark synthwave for driving at night\n" +
                        "moody atmospheric r&b, midnight vibe\n" +
                        "slow electronic music with deep bass, nocturnal",
            ),
            CustomMix(
                name = "Focus", icon = "🎯", isBuiltIn = true, sortOrder = 4,
                prompt = "focus instrumental music",
                description = "Instrumental music that stays out of your way while you work",
                prompts = "calm instrumental music for concentration\n" +
                        "minimal piano and strings, no vocals\n" +
                        "steady downtempo electronic for deep work",
            ),
        ).forEach { store.insert(it) }
    }

    // ---- Helpers ----
    private var pathIndexCache: Pair<List<String>, Map<String, String>>? = null

    private fun pathIndex(): Map<String, String> {
        val songIds = symphony.groove.song.all.value
        pathIndexCache?.let {
            if (it.first === songIds) return it.second
        }
        val map = HashMap<String, String>()
        for (id in songIds) {
            val song = symphony.groove.song.get(id) ?: continue
            map[song.path.lowercase()] = song.id
            map[song.path.substringAfterLast('/').lowercase()] = song.id
        }
        pathIndexCache = songIds to map
        return map
    }

    fun resolvePathToSongId(path: String): String? {
        val index = pathIndex()
        val p = path.lowercase()
        return index[p] ?: index[p.substringAfterLast('/')]
    }

    // ==================== Autoplay ====================

    suspend fun getAutoplaySongs(
        seedSongIds: List<String>,
        excludeSongIds: Set<String>,
        limit: Int = 10,
        anchorSongId: String? = null,
    ): List<String> {
        if (!symphony.semanticSearch.isReady.value) return emptyList()

        fun embeddingOf(songId: String): FloatArray? {
            val song = symphony.groove.song.get(songId) ?: return null
            return symphony.semanticSearch.getTrackEmbedding(
                song.title,
                song.artists.joinToString(),
            )
        }

        // Average embedding of the last three songs in the queue = "the current mood"
        val vectors = seedSongIds.takeLast(3).mapNotNull { embeddingOf(it) }
        // Song-radio mode: the seed song keeps a fixed weight in the query so the
        // station holds on to the seed's identity instead of drifting away
        val anchor = anchorSongId?.let { embeddingOf(it) }
        if (vectors.isEmpty() && anchor == null) return emptyList()

        val dim = (anchor ?: vectors.first()).size
        val recent = when {
            vectors.isEmpty() -> null
            else -> FloatArray(dim).also { acc ->
                for (v in vectors) {
                    for (i in 0 until dim) acc[i] += v[i] / vectors.size
                }
            }
        }
        val centroid = when {
            anchor != null && recent != null -> FloatArray(dim) { i ->
                RADIO_ANCHOR_WEIGHT * anchor[i] + (1f - RADIO_ANCHOR_WEIGHT) * recent[i]
            }
            anchor != null -> anchor
            else -> recent!!
        }
        val query = normalized(centroid) ?: return emptyList()

        // Don't suggest songs that were skipped this week
        val skipped = try {
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            symphony.database.playbackHistory.getRecentlySkippedSongIds(weekAgo).toSet()
        } catch (err: Exception) {
            emptySet()
        }

        val results = symphony.semanticSearch.searchByVector(query, limit * 5)
        val picked = mutableListOf<String>()
        for (track in results) {
            val path = track.filePath ?: continue
            val songId = resolvePathToSongId(path) ?: continue
            if (songId in excludeSongIds || songId in skipped || songId in picked) continue
            picked.add(songId)
            if (picked.size >= limit) break
        }
        return picked
    }

    // ==================== Smart Shuffle ====================

    suspend fun smartShuffleOrder(
        songIds: List<String>,
        firstSongId: String? = null,
    ): List<String> {
        if (songIds.size <= 2) return songIds

        val monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val history = try {
            symphony.database.playbackHistory.getHistorySince(monthAgo)
        } catch (err: Exception) {
            emptyList()
        }

        val plays = HashMap<String, Int>()
        val skips = HashMap<String, Int>()
        for (h in history) {
            if (h.skipped) skips[h.songId] = (skips[h.songId] ?: 0) + 1
            else plays[h.songId] = (plays[h.songId] ?: 0) + 1
        }

        val rng = Random(System.nanoTime())
        // Gumbel noise: the standard trick for a "weighted shuffle"
        fun gumbelNoise(): Double {
            val u = rng.nextDouble().coerceIn(1e-9, 1.0 - 1e-9)
            return -ln(-ln(u))
        }

        val ordered = songIds.sortedByDescending { songId ->
            val score = ln(1.0 + (plays[songId] ?: 0)) -
                    1.2 * ln(1.0 + (skips[songId] ?: 0))
            score + gumbelNoise()
        }.toMutableList()

        // Always keep the currently playing song first
        firstSongId?.let {
            if (ordered.remove(it)) ordered.add(0, it)
        }
        return ordered
    }
}
