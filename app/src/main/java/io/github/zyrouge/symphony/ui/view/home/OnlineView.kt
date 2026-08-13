package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.online.OnlineService
import io.github.zyrouge.symphony.services.online.OnlineTrack
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch

@Composable
fun OnlineView(context: ViewContext) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OnlineTrack>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val downloads = remember { mutableStateMapOf<String, Float?>() }
    val scope = rememberCoroutineScope()
    val service = remember(context.symphony.settings.onlineServiceBaseUrl.value) {
        OnlineService(context.symphony.settings.onlineServiceBaseUrl.value)
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Search online") }, trailingIcon = {
                IconButton(enabled = query.isNotBlank() && !loading, onClick = {
                    scope.launch {
                        loading = true; message = null
                        runCatching { service.search(query.trim()) }.onSuccess { results = it }
                            .onFailure { message = it.message ?: "Service unavailable" }
                        loading = false
                    }
                }) { Icon(Icons.Filled.Search, null) }
            })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
        if (!loading && message == null && query.isNotBlank() && results.isEmpty()) Text("No results")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(results, key = { it.id }) { track ->
                GlassSurface(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        track.artworkUrl?.let { AsyncImage(it, track.title, Modifier.padding(0.dp).weight(0.25f)) }
                        Column(Modifier.weight(1f)) {
                            Text(track.title); track.artist?.let { Text(it) }; track.album?.let { Text(it) }
                            track.durationSeconds?.let { Text("%d:%02d".format(it / 60, it % 60)) }
                            track.source?.let { Text(it) }
                            if (track.id in downloads) {
                                downloads[track.id]?.let { LinearProgressIndicator(progress = { it }) }
                                    ?: LinearProgressIndicator()
                            }
                        }
                        Button(enabled = track.id !in downloads, onClick = {
                            scope.launch {
                                downloads[track.id] = 0f
                                runCatching { service.download(context.activity, context.symphony, track) { downloads[track.id] = it } }
                                    .onFailure { message = it.message ?: "Download failed" }
                                downloads.remove(track.id)
                            }
                        }) { Text(if (track.id in downloads) "Downloading" else "Download") }
                    }
                }
            }
        }
    }
}
