package io.github.zyrouge.symphony.ui.view.spotizer

import io.github.zyrouge.symphony.services.spotizer.SpotizerAlbum
import io.github.zyrouge.symphony.services.spotizer.SpotizerArtist
import io.github.zyrouge.symphony.services.spotizer.SpotizerClient
import io.github.zyrouge.symphony.services.spotizer.SpotizerSearchType
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the Online tab of the search view.
 *
 * Debounces the query (450ms), supports the Track/Album/Artist type filter
 * and offset-based "load more" pagination (backend limit is max 50).
 */
class OnlineSearchState(
    private val client: SpotizerClient,
    private val scope: CoroutineScope,
) {
    data class Results(
        val tracks: List<SpotizerTrack> = emptyList(),
        val albums: List<SpotizerAlbum> = emptyList(),
        val artists: List<SpotizerArtist> = emptyList(),
        val total: Int = 0,
        val canLoadMore: Boolean = false,
    )

    private val _searchType = MutableStateFlow(SpotizerSearchType.Track)
    val searchType: StateFlow<SpotizerSearchType> = _searchType

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _results = MutableStateFlow(Results())
    val results: StateFlow<Results> = _results

    private var query: String = ""
    private var searchJob: Job? = null

    companion object {
        private const val PAGE_SIZE = 25
        private const val DEBOUNCE_MS = 450L
    }

    fun onQueryChanged(newQuery: String) {
        query = newQuery.trim()
        searchJob?.cancel()
        if (query.isEmpty()) {
            _results.value = Results()
            _loading.value = false
            _error.value = null
            return
        }
        searchJob = scope.launch {
            delay(DEBOUNCE_MS)
            runSearch(reset = true)
        }
    }

    fun onTypeChanged(type: SpotizerSearchType) {
        if (_searchType.value == type) return
        _searchType.value = type
        _results.value = Results()
        if (query.isNotEmpty()) {
            searchJob?.cancel()
            searchJob = scope.launch { runSearch(reset = true) }
        }
    }

    fun loadMore() {
        if (_loading.value || _loadingMore.value || !_results.value.canLoadMore) return
        searchJob = scope.launch { runSearch(reset = false) }
    }

    fun retry() {
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = scope.launch { runSearch(reset = true) }
    }

    private suspend fun runSearch(reset: Boolean) {
        val current = _results.value
        val offset = if (reset) 0 else when (_searchType.value) {
            SpotizerSearchType.Track -> current.tracks.size
            SpotizerSearchType.Album -> current.albums.size
            SpotizerSearchType.Artist -> current.artists.size
        }
        if (reset) _loading.value = true else _loadingMore.value = true
        _error.value = null
        try {
            when (_searchType.value) {
                SpotizerSearchType.Track -> {
                    val response = client.searchTracks(query, PAGE_SIZE, offset)
                    val tracks = (if (reset) emptyList() else current.tracks) + response.results
                    _results.value = Results(
                        tracks = tracks,
                        total = response.total,
                        canLoadMore = response.results.size >= PAGE_SIZE,
                    )
                }
                SpotizerSearchType.Album -> {
                    val response = client.searchAlbums(query, PAGE_SIZE, offset)
                    val albums = (if (reset) emptyList() else current.albums) + response.results
                    _results.value = Results(
                        albums = albums,
                        total = response.total,
                        canLoadMore = response.results.size >= PAGE_SIZE,
                    )
                }
                SpotizerSearchType.Artist -> {
                    val response = client.searchArtists(query, PAGE_SIZE, offset)
                    val artists = (if (reset) emptyList() else current.artists) + response.results
                    _results.value = Results(
                        artists = artists,
                        total = response.total,
                        canLoadMore = response.results.size >= PAGE_SIZE,
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: e.toString()
        } finally {
            _loading.value = false
            _loadingMore.value = false
        }
    }
}
