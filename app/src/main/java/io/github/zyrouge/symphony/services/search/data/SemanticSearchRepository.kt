package io.github.zyrouge.symphony.services.search.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlin.math.sqrt

data class SearchResult(
    val track: TrackEntity,
    val hybridScore: Float,
    val maxScore: Float,
    val meanScore: Float
)

class SemanticSearchRepository(boxStore: BoxStore) {
    private val trackBox: Box<TrackEntity> = boxStore.boxFor(TrackEntity::class.java)
    private val chunkBox: Box<TrackChunkEntity> = boxStore.boxFor(TrackChunkEntity::class.java)

    private var embeddedTracksCache: Set<String>? = null

    fun isTrackEmbedded(title: String, artist: String, durationMs: Long): Boolean {
        if (embeddedTracksCache == null) {
            val cache = mutableSetOf<String>()
            val tracks = trackBox.all
            for (track in tracks) {
                val t = track.title?.lowercase() ?: ""
                val a = track.artist?.lowercase() ?: ""
                val d = track.durationSeconds
                cache.add("$t|$a|$d")
            }
            embeddedTracksCache = cache
        }
        
        val t = title.lowercase()
        val a = artist.lowercase()
        val d = (durationMs / 1000).toInt()
        
        // Check with ±2 seconds tolerance
        for (offset in -2..2) {
            if (embeddedTracksCache?.contains("$t|$a|${d + offset}") == true) {
                return true
            }
        }
        return false
    }

    fun invalidateCache() {
        embeddedTracksCache = null
    }

    fun getIndexedTrackCount(): Long = trackBox.count()

    /**
     * ذخیره یک آهنگ با چانک‌های متعدد و محاسبه میانگین امبدینگ.
     */
    fun insertTrack(filePath: String, title: String, artist: String, durationSeconds: Int, chunkEmbeddings: List<FloatArray>) {
        if (chunkEmbeddings.isEmpty()) return

        // ✅ اول تک‌تک چانک‌ها normalize میشن (فیکس باگ maxScore)
        val normalizedChunks = chunkEmbeddings.map { normalize(it) }

        // Calculate mean embedding از روی چانک‌های normalize شده
        val meanEmb = FloatArray(512)
        for (emb in normalizedChunks) {
            for (i in 0 until 512) {
                meanEmb[i] += emb[i]
            }
        }
        for (i in 0 until 512) {
            meanEmb[i] /= normalizedChunks.size.toFloat()
        }
        val normalizedMean = normalize(meanEmb)

        val track = TrackEntity(
            filePath = filePath,
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            meanEmbedding = normalizedMean
        )
        
        val chunksToInsert = normalizedChunks.mapIndexed { index, emb ->
            val chunk = TrackChunkEntity(
                offsetSeconds = index * 30,
                embedding = emb
            )
            chunk.track.target = track
            chunk
        }
        
        // This will save both Track and Chunks because of the relation
        track.chunks.addAll(chunksToInsert)
        trackBox.put(track)
    }

    /**
     * Hybrid Search: Uses HNSW to find candidate chunks, then extracts tracks and calculates exact scores.
     */
    fun searchHybrid(queryEmbedding: FloatArray, topN: Int = 10): List<SearchResult> {
        // 1. Get Top 200 closest chunks
        val candidateChunks = chunkBox.query(
            TrackChunkEntity_.embedding.nearestNeighbors(queryEmbedding, 200)
        ).build().find()

        // 2. Extract unique Tracks from candidates
        val candidateTracks = candidateChunks.mapNotNull { it.track.target }.distinctBy { it.id }

        // 3. Calculate scores exactly as in Python test.py
        val results = mutableListOf<SearchResult>()
        for (track in candidateTracks) {
            val trackChunks = track.chunks
            if (trackChunks.isEmpty()) continue
            
            val similarities = trackChunks.map { chunk ->
                if (chunk.embedding != null) cosineSimilarity(queryEmbedding, chunk.embedding!!) else 0f
            }
            
            val sorted = similarities.sortedDescending()
            val maxScore = sorted.firstOrNull() ?: 0f
            // میانگین «نمره‌ی» ۳ چانک برتر — نه میانگین بردار
            val top3 = sorted.take(3)
            val top3Mean = if (top3.isEmpty()) 0f else top3.sum() / top3.size
            val meanScore = if (track.meanEmbedding != null) cosineSimilarity(queryEmbedding, track.meanEmbedding!!) else 0f
            val hybridScore = 0.5f * top3Mean + 0.3f * meanScore + 0.2f * maxScore
            
            results.add(SearchResult(track, hybridScore, maxScore, meanScore))
        }

        // 4. Sort and return top N
        val sortedResults = results.sortedByDescending { it.hybridScore }
        return mmrRerank(sortedResults, topN)
    }

    /**
     * Find tracks similar to a given track by matching metadata (title + artist + duration).
     * Uses the matched track's mean embedding to search for similar tracks.
     * @param title Song title
     * @param artist Song artist (can be empty)
     * @param durationSeconds Song duration in seconds
     * @param toleranceSeconds Duration match tolerance (default ±2 seconds)
     */
    fun searchSimilarByMetadata(
        title: String,
        artist: String,
        durationSeconds: Int,
        topN: Int = 20,
        toleranceSeconds: Int = 2
    ): List<SearchResult> {
        // پیدا کردن ترک مبدأ با متادیتا — اسکن سبکه (بدون عملیات برداری)
        val sourceTrack = trackBox.all.find { track ->
            val titleMatch = track.title?.equals(title, ignoreCase = true) == true
            val artistMatch = if (artist.isNotEmpty() && !track.artist.isNullOrEmpty()) {
                track.artist!!.equals(artist, ignoreCase = true) ||
                track.artist!!.contains(artist, ignoreCase = true) ||
                artist.contains(track.artist!!, ignoreCase = true)
            } else {
                true
            }
            val durationMatch = kotlin.math.abs(track.durationSeconds - durationSeconds) <= toleranceSeconds
            titleMatch && artistMatch && durationMatch
        } ?: return emptyList()

        val sourceEmbedding = sourceTrack.meanEmbedding ?: return emptyList()

        // ✅ جستجوی همسایه‌ها با ایندکس HNSW به جای brute-force
        val neighbors = trackBox.query(
            TrackEntity_.meanEmbedding.nearestNeighbors(sourceEmbedding, topN + 1)
        ).build().findWithScores()

        return neighbors.mapNotNull { result ->
            val track = result.get()
            if (track.id == sourceTrack.id) return@mapNotNull null
            // با VectorDistanceType.COSINE: فاصله = 1 - cosine → شباهت = 1 - فاصله
            val score = (1.0 - result.score).toFloat()
            SearchResult(track, score, score, score)
        }.take(topN)
    }
    
    fun getAllTracksCount(): Long {
        return trackBox.count()
    }
    
    fun getAllTracks(): List<TrackEntity> = trackBox.all
    
    fun clearAll() {
        trackBox.removeAll()
        chunkBox.removeAll()
        invalidateCache()
    }
    
    private fun normalize(array: FloatArray): FloatArray {
        var norm = 0f
        for (v in array) {
            norm += v * v
        }
        norm = sqrt(norm)
        if (norm == 0f) return array
        return FloatArray(array.size) { array[it] / norm }
    }

    /**
     * MMR: تنوع در نتایج — از ردیف شدن ۱۰ آهنگ تقریباً یکسان جلوگیری میکنه.
     * diversityWeight = 0.3 یعنی ۷۰٪ relevance، ۳۰٪ جریمه‌ی شباهت به انتخاب‌شده‌ها.
     */
    private fun mmrRerank(
        candidates: List<SearchResult>,
        topN: Int,
        diversityWeight: Float = 0.3f
    ): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()
        // برای لیست‌های خیلی بزرگ (حالت Limit by Similarity) MMR لازم نیست و کنده
        if (topN > 100) return candidates.take(topN)

        val selected = mutableListOf<SearchResult>()
        val remaining = candidates.toMutableList()
        selected.add(remaining.removeAt(0))

        while (selected.size < topN && remaining.isNotEmpty()) {
            var best: SearchResult? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in remaining) {
                val emb = c.track.meanEmbedding ?: continue
                var maxSimToSelected = 0f
                for (s in selected) {
                    val se = s.track.meanEmbedding ?: continue
                    val sim = cosineSimilarity(emb, se)
                    if (sim > maxSimToSelected) maxSimToSelected = sim
                }
                val mmr = (1f - diversityWeight) * c.hybridScore - diversityWeight * maxSimToSelected
                if (mmr > bestScore) {
                    bestScore = mmr
                    best = c
                }
            }
            best?.let {
                selected.add(it)
                remaining.remove(it)
            } ?: break
        }
        return selected
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        // Since embeddings are L2 normalized, dot product == cosine similarity
        var dotProduct = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dotProduct += a[i] * b[i]
        }
        return dotProduct
    }
}
