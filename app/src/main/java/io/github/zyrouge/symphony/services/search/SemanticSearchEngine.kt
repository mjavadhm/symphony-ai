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
import java.io.File

class SemanticSearchEngine(val symphony: Symphony) : Symphony.Hooks {
    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

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

    suspend fun importModel(uri: Uri, isAudio: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            modelManager.importModel(uri, isAudio)
        }
    }

    suspend fun importJsonDatabase(jsonUri: Uri): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                if (repository == null) {
                    return@withContext Result.failure(Exception("Repository is not initialized. Enable AI Search first."))
                }
                
                val inputStream = symphony.applicationContext.contentResolver.openInputStream(jsonUri)
                    ?: return@withContext Result.failure(Exception("Failed to open file."))
                
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                
                val jsonFormat = Json { ignoreUnknownKeys = true }
                val tracks = jsonFormat.decodeFromString<List<TrackJson>>(jsonString)
                
                var saved = 0
                for (track in tracks) {
                    val floatArrayChunks = track.chunks.map { it.toFloatArray() }
                    repository?.insertTrack(
                        filePath = track.filename,
                        title = track.title,
                        durationSeconds = track.duration,
                        chunkEmbeddings = floatArrayChunks
                    )
                    saved++
                }
                Result.success(saved)
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

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
     * @param songPath The file path of the source song.
     * @param limit Maximum number of similar songs to return.
     * @return List of file paths of similar songs.
     */
    suspend fun findSimilarSongs(songPath: String, limit: Int = 20): List<String> {
        return withContext(Dispatchers.Default) {
            try {
                if (_isReady.value.not() || repository == null) {
                    return@withContext emptyList<String>()
                }

                val results = repository!!.searchSimilarByFilePath(songPath, topN = limit)
                results.mapNotNull { it.track.filePath }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList<String>()
            }
        }
    }
}
