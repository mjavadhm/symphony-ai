package io.github.zyrouge.symphony.services.search.data

import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder
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

    companion object {
        /** "Yesterday (Remastered 2009) " → "yesterday" */
        fun normalizeKey(s: String): String = s
            .lowercase()
            .replace(Regex("[\\(\\[].*?[\\)\\]]"), "")
            .replace(Regex("\\bfeat\\.?.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    @Volatile
    private var embeddedTracksCache: Set<String>? = null

    // O(1) metadata → track lookups for the taste vector (rebuilt lazily)
    @Volatile
    private var keyToTrackId: Map<String, Long>? = null

    @Volatile
    private var titleToTrackIds: Map<String, List<Long>>? = null

    private fun getCache(): Set<String> {
        embeddedTracksCache?.let { return it }
        synchronized(this) {
            embeddedTracksCache?.let { return it }
            val cache = mutableSetOf<String>()
            for (track in trackBox.all) {
                val t = normalizeKey(track.title ?: "")
                val a = normalizeKey(track.artist ?: "")
                cache.add("$t|$a|${track.durationSeconds}")
            }
            embeddedTracksCache = cache
            return cache
        }
    }

    private fun ensureLookupCaches() {
        if (keyToTrackId != null && titleToTrackIds != null) return
        synchronized(this) {
            if (keyToTrackId != null && titleToTrackIds != null) return
            val byKey = HashMap<String, Long>()
            val byTitle = HashMap<String, MutableList<Long>>()
            for (track in trackBox.all) {
                val t = normalizeKey(track.title ?: "")
                if (t.isEmpty()) continue
                val a = normalizeKey(track.artist ?: "")
                byKey.putIfAbsent("$t|$a", track.id)
                byTitle.getOrPut(t) { mutableListOf() }.add(track.id)
            }
            keyToTrackId = byKey
            titleToTrackIds = byTitle
        }
    }

    fun isTrackEmbedded(title: String, artist: String, durationMs: Long): Boolean {
        val cache = getCache()
        val t = normalizeKey(title)
        val a = normalizeKey(artist)
        val d = (durationMs / 1000).toInt()
        
        // ±5 second tolerance, so copies from a different source/quality still match
        for (offset in -5..5) {
            if (cache.contains("$t|$a|${d + offset}")) return true
        }
        return false
    }

    fun invalidateCache() {
        embeddedTracksCache = null
        keyToTrackId = null
        titleToTrackIds = null
    }

    fun updateDevicePath(track: TrackEntity, devicePath: String) {
        if (track.filePath == devicePath) return
        track.filePath = devicePath
        trackBox.put(track)
    }

    fun getIndexedTrackCount(): Long = trackBox.count()

    /**
     * Stores one song together with its chunks and computes the mean embedding.
     */
    fun insertTrack(filePath: String, title: String, artist: String, durationSeconds: Int, chunkEmbeddings: List<FloatArray>) {
        if (chunkEmbeddings.isEmpty()) return

        // ✅ Remove earlier copies of this same track (prevents duplicates on re-index / re-import)
        val existing = trackBox.query(
            TrackEntity_.title.equal(title, QueryBuilder.StringOrder.CASE_INSENSITIVE)
        ).build().find().filter { old ->
            normalizeKey(old.artist ?: "") == normalizeKey(artist) &&
            kotlin.math.abs(old.durationSeconds - durationSeconds) <= 2
        }
        for (old in existing) {
            chunkBox.remove(old.chunks)   // chunks first, then the track itself
            trackBox.remove(old)
        }

        // ✅ Normalize every chunk individually first (fixes the maxScore bug)
        val normalizedChunks = chunkEmbeddings.map { normalize(it) }

        // Calculate the mean embedding from the normalized chunks
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

        // The lookup caches are stale now
        invalidateCache()
    }

    /**
     * Hybrid Search: Uses HNSW to find candidate chunks, then extracts tracks and calculates exact scores.
     */
    fun searchHybrid(queryEmbedding: FloatArray, topN: Int = 10): List<SearchResult> {
        // 1. Get Top 200 closest chunks
        val candidateChunks = chunkBox.query(
            TrackChunkEntity_.embedding.nearestNeighbors(queryEmbedding, 200)
        ).build().find()

        // ✅ Group the candidate chunks by track — without loading each track's other chunks
        val chunksByTrack = candidateChunks.groupBy { it.track.targetId }

        val results = mutableListOf<SearchResult>()
        for ((_, chunks) in chunksByTrack) {
            val track = chunks.first().track.target ?: continue
            val similarities = chunks.mapNotNull { c ->
                c.embedding?.let { cosineSimilarity(queryEmbedding, it) }
            }
            if (similarities.isEmpty()) continue

            val sorted = similarities.sortedDescending()
            val maxScore = sorted.first()
            val top3 = sorted.take(3)
            val top3Mean = top3.sum() / top3.size
            val meanScore = track.meanEmbedding?.let { cosineSimilarity(queryEmbedding, it) } ?: 0f
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
        // Find the source track by metadata — a cheap scan, no vector work involved
        val sourceTrack = trackBox.query(
            TrackEntity_.title.equal(title, QueryBuilder.StringOrder.CASE_INSENSITIVE)
        ).build().find().find { track ->
            val artistMatch = if (artist.isNotEmpty() && !track.artist.isNullOrEmpty()) {
                normalizeKey(track.artist!!) == normalizeKey(artist) ||
                track.artist!!.contains(artist, ignoreCase = true) ||
                artist.contains(track.artist!!, ignoreCase = true)
            } else true
            val durationMatch = kotlin.math.abs(track.durationSeconds - durationSeconds) <= toleranceSeconds
            artistMatch && durationMatch
        } ?: return emptyList()

        val sourceEmbedding = sourceTrack.meanEmbedding ?: return emptyList()

        // ✅ Neighbor search through the HNSW index instead of brute force
        val neighbors = trackBox.query(
            TrackEntity_.meanEmbedding.nearestNeighbors(sourceEmbedding, topN + 1)
        ).build().findWithScores()

        return neighbors.mapNotNull { result ->
            val track = result.get()
            if (track.id == sourceTrack.id) return@mapNotNull null
            // With VectorDistanceType.COSINE: distance = 1 - cosine → similarity = 1 - distance
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
     * MMR: keeps the results varied — it stops ten near-identical songs from lining up.
     * diversityWeight = 0.3 means 70% relevance and a 30% penalty for being similar to
     * what has already been picked.
     */
    private fun mmrRerank(
        candidates: List<SearchResult>,
        topN: Int,
        diversityWeight: Float = 0.3f
    ): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()
        // For very large lists (Limit by Similarity mode) MMR isn't needed and is slow
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

    /**
     * Mean embedding of a track looked up by metadata (used for the taste vector).
     *
     * Matching is deliberately conservative:
     * 1. exact normalized "title|artist" key,
     * 2. unique title — safe even when artist tags differ between sources,
     * 3. ambiguous title (covers!) — requires a partial artist match.
     * It NEVER falls back to an arbitrary candidate: a wrong embedding silently
     * poisons the taste vector, which is worse than having no embedding at all.
     */
    fun findTrackEmbedding(title: String, artist: String): FloatArray? {
        ensureLookupCaches()
        val tKey = normalizeKey(title)
        if (tKey.isEmpty()) return null
        val aKey = normalizeKey(artist)

        // 1. Exact normalized title+artist match
        keyToTrackId?.get("$tKey|$aKey")?.let { id ->
            return trackBox.get(id)?.meanEmbedding
        }

        val candidateIds = titleToTrackIds?.get(tKey) ?: return null

        // 2. Only one track has this title → unambiguous
        if (candidateIds.size == 1) {
            return trackBox.get(candidateIds.first())?.meanEmbedding
        }

        // 3. Several tracks share the title → require a partial artist match
        if (aKey.isEmpty()) return null
        for (id in candidateIds) {
            val track = trackBox.get(id) ?: continue
            val ta = normalizeKey(track.artist ?: "")
            if (ta.isNotEmpty() && (ta.contains(aKey) || aKey.contains(ta))) {
                return track.meanEmbedding
            }
        }
        return null
    }

    /** Direct search by vector — the core of Daily Mix */
    fun searchByVector(vector: FloatArray, limit: Int): List<TrackEntity> {
        return trackBox.query(
            TrackEntity_.meanEmbedding.nearestNeighbors(vector, limit)
        ).build().use { q -> q.findWithScores().map { it.get() } }
    }
}
