package io.github.zyrouge.symphony.services.search

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.search.data.MyObjectBox
import io.github.zyrouge.symphony.services.search.data.SemanticSearchRepository
import io.github.zyrouge.symphony.services.search.ml.ClapModelRunner
import io.github.zyrouge.symphony.services.search.ml.RobertaTokenizer
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.github.zyrouge.symphony.services.search.ml.ModelManager
import io.github.zyrouge.symphony.services.search.data.TrackJson
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import io.github.zyrouge.symphony.services.groove.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import io.github.zyrouge.symphony.services.search.SemanticIndexingService
import io.github.zyrouge.symphony.services.search.ml.ImportedModelInfo

data class IndexingState(
    val isActive: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val currentTitle: String = "",
    val failedCount: Int = 0,
    val failedSongIds: List<String> = emptyList(),
    val startedAt: Long = 0L,
)

data class JsonImportState(
    val isActive: Boolean = false,
    val count: Int = 0,
    val text: String = "",
)

class SemanticSearchEngine(val symphony: Symphony) : Symphony.Hooks {
    companion object {
        @Volatile
        var activeInstance: SemanticSearchEngine? = null
    }

    private val indexingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        activeInstance = this
    }

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _indexingState = MutableStateFlow(IndexingState())
    val indexingState = _indexingState.asStateFlow()

    private val _jsonImportState = MutableStateFlow(JsonImportState())
    val jsonImportState = _jsonImportState.asStateFlow()

    private var indexingJob: Job? = null

    var boxStore: BoxStore? = null
        private set
    var repository: SemanticSearchRepository? = null
        private set
    var modelRunner: ClapModelRunner? = null
        private set
    var tokenizer: RobertaTokenizer? = null
        private set

    val modelManager = ModelManager(symphony.applicationContext)

    override fun onSymphonyReady() {
        // Wait until settings are available. 
        // We will add settings for isSemanticSearchEnabled next.
        // For now, we will assume it's always true or checked later.
        initializeEngine()
    }

    override fun onSymphonyDestroy() {
        boxStore?.close()
    }

    fun initializeEngine() {
        if (_isReady.value) return
        
        symphony.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Initialize ObjectBox
                    if (boxStore == null) {
                        boxStore = MyObjectBox.builder()
                            .androidContext(symphony.applicationContext)
                            .build()
                        repository = SemanticSearchRepository(boxStore!!)
                    }

                    // Initialize Tokenizer
                    if (tokenizer == null) {
                        tokenizer = RobertaTokenizer(symphony.applicationContext)
                    }

                    // Initialize Model Runner
                    if (modelRunner == null) {
                        modelRunner = ClapModelRunner(symphony.applicationContext)
                    }
                    _isReady.value = true
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Models might not be imported yet
                    _isReady.value = false
                }
            }
        }
    }

    suspend fun importModel(uri: Uri, isAudio: Boolean): Result<ImportedModelInfo> {
        return withContext(Dispatchers.IO) {
            modelManager.importModel(uri, isAudio)
        }
    }

    fun getModelInfo(isAudio: Boolean) = modelManager.getModelInfo(isAudio)
    fun deleteModel(isAudio: Boolean) = modelManager.deleteModel(isAudio)

    suspend fun importJsonDatabase(
        jsonUri: Uri,
        onProgress: suspend (Int, String) -> Unit = { _, _ -> }
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                if (repository == null) {
                    return@withContext Result.failure(Exception("Repository is not initialized. Enable AI Search first."))
                }
                
                val inputStream = symphony.applicationContext.contentResolver.openInputStream(jsonUri)
                    ?: return@withContext Result.failure(Exception("Failed to open file."))
                
                var saved = 0
                android.util.JsonReader(java.io.InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var filename = ""
                        var title = ""
                        var artist = ""
                        var duration = 0
                        val chunks = mutableListOf<FloatArray>()
                        
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "filename" -> {
                                    filename = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
                                }
                                "title" -> {
                                    title = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
                                }
                                "artist" -> {
                                    artist = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
                                }
                                "duration" -> {
                                    duration = if (reader.peek() == android.util.JsonToken.NULL) { reader.nextNull(); 0 } else reader.nextInt()
                                }
                                "chunks" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        val chunk = mutableListOf<Float>()
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            chunk.add(reader.nextDouble().toFloat())
                                        }
                                        reader.endArray()
                                        chunks.add(chunk.toFloatArray())
                                    }
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        
                        repository?.insertTrack(
                            filePath = filename,
                            title = title,
                            artist = artist,
                            durationSeconds = duration,
                            chunkEmbeddings = chunks
                        )
                        saved++
                        
                        if (saved % 5 == 0) {
                            onProgress(saved, "Importing: $title")
                        }
                    }
                    reader.endArray()
                }
                
                repository?.invalidateCache()
                Result.success(saved)
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    suspend fun embedSongLocal(song: io.github.zyrouge.symphony.services.groove.Song): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                if (_isReady.value.not() || modelRunner == null || repository == null) {
                    return@withContext Result.failure(Exception("AI engine is not ready."))
                }

                val decoder = io.github.zyrouge.symphony.services.search.ml.AudioDecoder(symphony.applicationContext)
                val melExtractor = io.github.zyrouge.symphony.services.search.ml.MelSpectrogramExtractor()

                // ✅ هر چانک: decode → mel → embed → دور انداختن PCM
                // فقط امبدینگهای ۵۱۲تایی نگه داشته میشن (ناچیز)
                val chunkEmbeddings = mutableListOf<FloatArray>()
                val count = decoder.streamChunks(song.uri) { chunk ->
                    val melSpec = melExtractor.extract(chunk.floatArray)
                    val embedding = modelRunner!!.getAudioEmbedding(melSpec)
                    chunkEmbeddings.add(embedding)
                }

                if (count == 0) {
                    return@withContext Result.failure(Exception("Could not extract audio chunks from the song."))
                }

                repository!!.insertTrack(
                    filePath = song.path,
                    title = song.title,
                    artist = song.artists.joinToString(),
                    durationSeconds = (song.duration / 1000).toInt(),
                    chunkEmbeddings = chunkEmbeddings
                )

                repository!!.invalidateCache()
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // کنسل شدن باید عبور کنه، وگرنه Cancel دکمه خراب میشه
            } catch (e: Throwable) {
                // ✅ Throwable به جای Exception → OutOfMemoryError هم گرفته میشه
                e.printStackTrace()
                Result.failure(Exception(e))
            }
        }
    }

    fun startJsonImport(uri: android.net.Uri) {
        if (_jsonImportState.value.isActive) return
        indexingScope.launch {
            _jsonImportState.value = JsonImportState(isActive = true, text = "Starting import…")
            val res = importJsonDatabase(uri) { count, text ->
                _jsonImportState.value = JsonImportState(true, count, text)
            }
            _jsonImportState.update {
                it.copy(
                    isActive = false,
                    text = if (res.isSuccess) "Imported ${res.getOrNull() ?: 0} tracks"
                    else "Import failed: ${res.exceptionOrNull()?.message}",
                )
            }
        }
    }

    fun startIndexing(songs: List<Song>) {
        if (_indexingState.value.isActive || songs.isEmpty()) return

        _indexingState.value = IndexingState(
            isActive = true,
            total = songs.size,
            startedAt = System.currentTimeMillis(),
        )
        SemanticIndexingService.start(symphony.applicationContext)

        indexingJob = indexingScope.launch {
            val failed = mutableListOf<String>()
            try {
                for (song in songs) {
                    ensureActive()
                    _indexingState.update { it.copy(currentTitle = song.title) }
                    val result = embedSongLocal(song)
                    if (result.isFailure) failed.add(song.id)
                    _indexingState.update {
                        it.copy(
                            current = it.current + 1,
                            failedCount = failed.size,
                            failedSongIds = failed.toList(),
                        )
                    }
                }
            } finally {
                _indexingState.update { it.copy(isActive = false, currentTitle = "") }
            }
        }
    }

    fun cancelIndexing() {
        indexingJob?.cancel()
        indexingJob = null
    }

    fun clearIndexingResult() {
        if (!_indexingState.value.isActive) {
            _indexingState.value = IndexingState()
        }
    }

    fun indexedTrackCount(): Long = repository?.getIndexedTrackCount() ?: 0L

    suspend fun search(query: String, limit: Int = 10): List<String> {
        return withContext(Dispatchers.Default) {
            try {
                if (_isReady.value.not() || tokenizer == null || modelRunner == null || repository == null) {
                    return@withContext emptyList<String>()
                }

                val (inputIds, attentionMask) = tokenizer!!.encode(query)
                val queryEmbedding = modelRunner!!.getTextEmbedding(inputIds, attentionMask)
                
                val results = repository!!.searchHybrid(queryEmbedding, topN = limit)
                
                // Return just the filenames or paths. We will map them in the UI.
                results.mapNotNull { it.track.filePath }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList<String>()
            }
        }
    }

    /**
     * Find songs similar to the given song by comparing audio embeddings.
     * Matches the source song by metadata (title + artist + duration).
     * @param title Song title
     * @param artist Song artist (can be empty)
     * @param durationSeconds Song duration in seconds
     * @param limit Maximum number of similar songs to return.
     * @return List of SearchResult containing track info and scores.
     */
    suspend fun findSimilarSongs(
        title: String,
        artist: String,
        durationSeconds: Int,
        limit: Int = 20
    ): List<io.github.zyrouge.symphony.services.search.data.SearchResult> {
        return withContext(Dispatchers.Default) {
            try {
                if (_isReady.value.not() || repository == null) {
                    return@withContext emptyList()
                }

                repository!!.searchSimilarByMetadata(
                    title = title,
                    artist = artist,
                    durationSeconds = durationSeconds,
                    topN = limit
                )
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
}
