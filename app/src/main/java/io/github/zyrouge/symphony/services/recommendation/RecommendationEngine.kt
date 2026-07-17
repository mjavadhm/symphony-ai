package io.github.zyrouge.symphony.services.recommendation

import android.content.Context
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import io.github.zyrouge.symphony.utils.Logger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class RecommendationEngine(private val symphony: Symphony) {
    companion object {
        private const val DIM = 512
        private const val PREFS = "recommendation_prefs"
        private const val MIN_HISTORY_FOR_TASTE = 10
    }

    private val prefs
        get() = symphony.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- تنظیمات قابل شخصیسازی (لایه ۲) ----
    var discoveryRatio: Float // 0f = فقط آشنا، 1f = فقط کشف جدید
        get() = prefs.getFloat("discovery_ratio", 0.3f)
        set(v) = prefs.edit().putFloat("discovery_ratio", v.coerceIn(0f, 1f)).apply()

    var recencyHalfLifeDays: Int // نیمعمر وزن تازگی
        get() = prefs.getInt("recency_half_life_days", 14)
        set(v) = prefs.edit().putInt("recency_half_life_days", v.coerceAtLeast(1)).apply()

    var dailyMixSize: Int
        get() = prefs.getInt("daily_mix_size", 30)
        set(v) = prefs.edit().putInt("daily_mix_size", v.coerceIn(10, 100)).apply()

    fun resetToDefaults() = prefs.edit().clear().apply()

    // ---- Daily Mix (کش روزانه در حافظه) ----
    private var cachedDailyMix: Pair<String, List<String>>? = null

    private fun todayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    /** خروجی: لیست songId — خالی یعنی هنوز داده/ایندکس کافی نیست */
    suspend fun getDailyMix(forceRefresh: Boolean = false): List<String> {
        val key = todayKey()
        cachedDailyMix?.let { if (!forceRefresh && it.first == key) return it.second }

        val taste = buildTasteVector() ?: return emptyList()
        val limit = dailyMixSize
        val candidates = symphony.semanticSearch.searchByVector(taste, limit * 3)
        if (candidates.isEmpty()) return emptyList()

        val playedIds = try {
            symphony.database.playbackHistory.getAllPlayedSongIds().toSet()
        } catch (e: Exception) { emptySet() }

        // تبدیل TrackEntity → Song و تفکیک آشنا/جدید
        val familiar = mutableListOf<String>()
        val discovery = mutableListOf<String>()
        for (track in candidates) {
            val songId = resolvePathToSongId(track.filePath ?: continue) ?: continue
            if (songId in playedIds) familiar.add(songId) else discovery.add(songId)
        }

        // ترکیب بر اساس discoveryRatio
        val discoveryCount = (limit * discoveryRatio).toInt()
        val mix = (familiar.take(limit - discoveryCount) + discovery.take(discoveryCount))
            .distinct().shuffled()
        cachedDailyMix = key to mix
        return mix
    }

    /** بردار سلیقه؛ hourRange بدی میشه Hourly/Context Mix — مجانی! */
    suspend fun buildTasteVector(hourRange: IntRange? = null): FloatArray? {
        val since = System.currentTimeMillis() - 90L * 86_400_000L
        val history = try {
            symphony.database.playbackHistory.getHistorySince(since)
        } catch (e: Exception) { return null }

        val filtered = when (hourRange) {
            null -> history
            else -> history.filter { it.hourOfDay in hourRange }
        }
        if (filtered.size < MIN_HISTORY_FOR_TASTE) return null

        val acc = FloatArray(DIM)
        var totalWeight = 0f
        val now = System.currentTimeMillis()
        val halfLifeMs = recencyHalfLifeDays * 86_400_000f
        val embeddingCache = HashMap<String, FloatArray?>()

        for (h in filtered) {
            if (h.title.isBlank()) continue
            val cacheKey = "${h.title}|${h.artist}"
            val emb = embeddingCache.getOrPut(cacheKey) {
                symphony.semanticSearch.getTrackEmbedding(h.title, h.artist)
            } ?: continue

            val recency = 0.5f.pow((now - h.playedAt) / halfLifeMs)
            val weight = when {
                h.skipped -> -0.5f * recency          // سیگنال منفی
                else -> h.completionRate * recency     // سیگنال مثبت
            }
            for (i in 0 until DIM) acc[i] += emb[i] * weight
            totalWeight += abs(weight)
        }
        if (totalWeight < 1f) return null

        // normalize
        var norm = 0f
        for (v in acc) norm += v * v
        norm = sqrt(norm)
        if (norm < 1e-6f) return null
        for (i in acc.indices) acc[i] /= norm
        return acc
    }

    // ---- Mood / Custom Mixes ----
    suspend fun getMixSongIds(mix: CustomMix): List<String> {
        return try {
            symphony.semanticSearch.searchDetailed(mix.prompt, mix.trackCount)
                .mapNotNull { it.track.filePath }
                .mapNotNull { resolvePathToSongId(it) }
        } catch (e: Exception) {
            Logger.error("RecommendationEngine", "getMixSongIds failed", e)
            emptyList()
        }
    }

    suspend fun seedDefaultMixesIfNeeded() {
        val store = symphony.database.customMixes
        if (store.count() > 0) return
        listOf(
            CustomMix(name = "Sad", prompt = "sad melancholic emotional slow", icon = "😢", isBuiltIn = true, sortOrder = 0),
            CustomMix(name = "Workout", prompt = "energetic powerful workout gym motivation", icon = "⚡", isBuiltIn = true, sortOrder = 1),
            CustomMix(name = "Chill", prompt = "chill relaxing calm ambient", icon = "🌊", isBuiltIn = true, sortOrder = 2),
            CustomMix(name = "Night Drive", prompt = "dark synthwave night driving atmospheric", icon = "🌙", isBuiltIn = true, sortOrder = 3),
            CustomMix(name = "Focus", prompt = "instrumental focus study concentration minimal", icon = "🎯", isBuiltIn = true, sortOrder = 4),
        ).forEach { store.insert(it) }
    }

    // path دیتابیس امبدینگ ↔ path واقعی دستگاه (همون منطق DiscoverView)
    private fun resolvePathToSongId(path: String): String? {
        val songIds = symphony.groove.song.all.value
        for (id in songIds) {
            val song = symphony.groove.song.get(id) ?: continue
            if (song.path == path || song.path.endsWith(path, ignoreCase = true)) return song.id
        }
        return null
    }
}
