package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.online.*
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineView(context: ViewContext) {
    var query by remember { mutableStateOf("") }
    var searchType by remember { mutableStateOf(SpotizerSearchType.TRACK) }
    var results by remember { mutableStateOf<List<SpotizerResult>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val downloads = remember { mutableStateMapOf<String, Float?>() }
    val scope = rememberCoroutineScope()
    val service = remember(context.symphony.settings.onlineServiceBaseUrl.value) {
        OnlineService(context.symphony.settings.onlineServiceBaseUrl.value)
    }
    val homePadding = io.github.zyrouge.symphony.ui.components.LocalHomeContentPadding.current

    fun search() {
        if (query.isBlank() || loading) return
        scope.launch {
            loading = true
            hasSearched = true
            message = null
            results = emptyList()
            runCatching { service.search(query.trim(), searchType) }
                .onSuccess { results = it }
                .onFailure { message = it.message ?: "Spotizer is unavailable" }
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(homePadding).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Spotizer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Find music by track, artist, or album.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SpotizerSearchType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = searchType == type,
                    onClick = {
                        searchType = type
                        results = emptyList()
                        hasSearched = false
                        message = null
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, SpotizerSearchType.entries.size),
                    label = { Text(type.label) },
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search ${searchType.label.lowercase()}") },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            trailingIcon = {
                IconButton(enabled = query.isNotBlank() && !loading, onClick = { search() }) {
                    Icon(Icons.Filled.Search, "Search Spotizer")
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!loading && message == null && hasSearched && results.isEmpty()) {
            Text("No ${searchType.label.lowercase()} found")
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(results, key = { "${searchType.apiValue}-${it.id}" }) { result ->
                SpotizerResultCard(result, downloads) { track ->
                    scope.launch {
                        downloads[track.id] = 0f
                        runCatching {
                            service.download(context.activity, context.symphony, track) {
                                downloads[track.id] = it
                            }
                        }.onSuccess { message = "$it downloaded to your device" }
                            .onFailure { message = it.message ?: "Download failed" }
                        downloads.remove(track.id)
                    }
                }
            }
        }
    }
}

private val SpotizerSearchType.label: String
    get() = when (this) {
        SpotizerSearchType.TRACK -> "Tracks"
        SpotizerSearchType.ARTIST -> "Artists"
        SpotizerSearchType.ALBUM -> "Albums"
    }

@Composable
private fun SpotizerResultCard(
    result: SpotizerResult,
    downloads: Map<String, Float?>,
    onDownload: (OnlineTrack) -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = result.artworkUrl,
                contentDescription = result.title,
                modifier = Modifier.size(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (result) {
                    is OnlineTrack -> {
                        result.artist?.let { ResultSubtitle(it) }
                        val details = listOfNotNull(
                            result.album,
                            result.durationSeconds?.let { "%d:%02d".format(it / 60, it % 60) },
                        ).joinToString(" • ")
                        if (details.isNotEmpty()) ResultDetails(details)
                        if (result.id in downloads) {
                            downloads[result.id]?.let { LinearProgressIndicator({ it }) }
                                ?: LinearProgressIndicator()
                            ResultDetails("Downloading to your device…")
                        }
                    }
                    is OnlineArtist -> {
                        ResultSubtitle("Artist")
                        val details = listOfNotNull(
                            result.albumCount?.let { "$it albums" },
                            result.fanCount?.let { "${it.formatCount()} fans" },
                        ).joinToString(" • ")
                        if (details.isNotEmpty()) ResultDetails(details)
                    }
                    is OnlineAlbum -> {
                        result.artist?.let { ResultSubtitle(it) }
                        ResultDetails(result.trackCount?.let { "$it tracks" } ?: "Album")
                    }
                }
            }
            if (result is OnlineTrack) {
                FilledIconButton(
                    enabled = result.id !in downloads,
                    onClick = { onDownload(result) },
                ) { Icon(Icons.Filled.Download, "Download ${result.title}") }
            }
        }
    }
}

@Composable
private fun ResultSubtitle(value: String) = Text(
    value,
    style = MaterialTheme.typography.bodyMedium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

@Composable
private fun ResultDetails(value: String) = Text(
    value,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

private fun Long.formatCount(): String = when {
    this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
    this >= 1_000 -> "%.1fK".format(this / 1_000.0)
    else -> toString()
}
