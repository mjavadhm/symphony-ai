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

        // Calculate mean embedding
        val meanEmb = FloatArray(512)
        for (emb in chunkEmbeddings) {
            for (i in 0 until 512) {
                meanEmb[i] += emb[i]
            }
        }
        for (i in 0 until 512) {
            meanEmb[i] /= chunkEmbeddings.size.toFloat()
        }
        val normalizedMean = normalize(meanEmb)

        val track = TrackEntity(
            filePath = filePath,
            title = title,
            artist = artist,
            durationSeconds = durationSeconds,
            meanEmbedding = normalizedMean
        )
        
        val chunksToInsert = chunkEmbeddings.mapIndexed { index, emb ->
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
            
            val maxScore = similarities.maxOrNull() ?: 0f
            val meanScore = if (track.meanEmbedding != null) cosineSimilarity(queryEmbedding, track.meanEmbedding!!) else 0f
            val hybridScore = 0.6f * meanScore + 0.4f * maxScore
            
            results.add(SearchResult(track, hybridScore, maxScore, meanScore))
        }

        // 4. Sort and return top N
        return results.sortedByDescending { it.hybridScore }.take(topN)
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
        val allTracks = trackBox.all
        
        // Find source track by metadata
        val sourceTrack = allTracks.find { track ->
            val titleMatch = track.title?.equals(title, ignoreCase = true) == true
            val artistMatch = if (artist.isNotEmpty() && !track.artist.isNullOrEmpty()) {
                track.artist!!.equals(artist, ignoreCase = true) ||
                track.artist!!.contains(artist, ignoreCase = true) ||
                artist.contains(track.artist!!, ignoreCase = true)
            } else {
                true // skip artist check if not available
            }
            val durationMatch = kotlin.math.abs(track.durationSeconds - durationSeconds) <= toleranceSeconds
            titleMatch && artistMatch && durationMatch
        } ?: return emptyList()
        
        val sourceEmbedding = sourceTrack.meanEmbedding ?: return emptyList()

        val results = mutableListOf<SearchResult>()
        for (track in allTracks) {
            if (track.id == sourceTrack.id) continue
            val meanEmb = track.meanEmbedding ?: continue
            val score = cosineSimilarity(sourceEmbedding, meanEmb)
            results.add(SearchResult(track, score, score, score))
        }

        return results.sortedByDescending { it.hybridScore }.take(topN)
    }
    
    fun getAllTracksCount(): Long {
        return trackBox.count()
    }
    
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
