package io.github.zyrouge.symphony.ui.view.spotizer

import io.github.zyrouge.symphony.services.spotizer.SpotizerAlbum
import io.github.zyrouge.symphony.services.spotizer.SpotizerArtist
import io.github.zyrouge.symphony.services.spotizer.SpotizerClient
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Debounced search state for the online (Spotizer) search mode. */
class OnlineSearchState(
    private val client: SpotizerClient,
    private val scope: CoroutineScope,
) {
    enum class Kind { Track, Album, Artist }

    companion object {
        const val PAGE_SIZE = 25
        private const val DEBOUNCE_MS = 450L
    }

    private val _kind = MutableStateFlow(Kind.Track)
    val kind: StateFlow<Kind> = _kind

    private val _tracks = MutableStateFlow<List<SpotizerTrack>>(emptyList())
    val tracks: StateFlow<List<SpotizerTrack>> = _tracks

    private val _albums = MutableStateFlow<List<SpotizerAlbum>>(emptyList())
    val albums: StateFlow<List<SpotizerAlbum>> = _albums

    private val _artists = MutableStateFlow<List<SpotizerArtist>>(emptyList())
    val artists: StateFlow<List<SpotizerArtist>> = _artists

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore

    private var query = ""
    private var searchJob: Job? = null

    val currentQuery: String get() = query

    fun onQueryChanged(nQuery: String) {
        if (nQuery == query) {
            return
        }
        query = nQuery
        searchJob?.cancel()
        if (nQuery.isBlank()) {
            clearResults()
            return
        }
        _isLoading.value = true
        searchJob = scope.launch {
            delay(DEBOUNCE_MS)
            performSearch(reset = true)
        }
    }

    fun setKind(nKind: Kind) {
        if (nKind == _kind.value) {
            return
        }
        _kind.value = nKind
        searchJob?.cancel()
        if (query.isBlank()) {
            clearResults()
            return
        }
        _isLoading.value = true
        searchJob = scope.launch {
            performSearch(reset = true)
        }
    }

    fun loadMore() {
        if (_isLoading.value || !_canLoadMore.value) {
            return
        }
        _isLoading.value = true
        searchJob = scope.launch {
            performSearch(reset = false)
        }
    }

    fun retry() {
        if (query.isBlank()) {
            return
        }
        _isLoading.value = true
        searchJob?.cancel()
        searchJob = scope.launch {
            performSearch(reset = true)
        }
    }

    private fun clearResults() {
        _tracks.value = emptyList()
        _albums.value = emptyList()
        _artists.value = emptyList()
        _error.value = null
        _isLoading.value = false
        _canLoadMore.value = false
    }

    private suspend fun performSearch(reset: Boolean) {
        if (query.isBlank()) {
            clearResults()
            return
        }
        val offset = if (reset) 0 else when (_kind.value) {
            Kind.Track -> _tracks.value.size
            Kind.Album -> _albums.value.size
            Kind.Artist -> _artists.value.size
        }
        _error.value = null
        try {
            when (_kind.value) {
                Kind.Track -> {
                    val response = client.searchTracks(query, PAGE_SIZE, offset)
                    _tracks.value =
                        if (reset) response.results else _tracks.value + response.results
                    _canLoadMore.value = response.results.size >= PAGE_SIZE
                }

                Kind.Album -> {
                    val response = client.searchAlbums(query, PAGE_SIZE, offset)
                    _albums.value =
                        if (reset) response.results else _albums.value + response.results
                    _canLoadMore.value = response.results.size >= PAGE_SIZE
                }

                Kind.Artist -> {
                    val response = client.searchArtists(query, PAGE_SIZE, offset)
                    _artists.value =
                        if (reset) response.results else _artists.value + response.results
                    _canLoadMore.value = response.results.size >= PAGE_SIZE
                }
            }
        } catch (failure: Exception) {
            _error.value = failure.message ?: "Search failed"
            if (reset) {
                _tracks.value = emptyList()
                _albums.value = emptyList()
                _artists.value = emptyList()
            }
            _canLoadMore.value = false
        } finally {
            _isLoading.value = false
        }
    }
}
