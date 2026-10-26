package io.github.zyrouge.symphony.ui.view.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.lyrics.EmbeddedLyricsStore
import io.github.zyrouge.symphony.services.lyrics.LrclibClient
import io.github.zyrouge.symphony.services.lyrics.LyricsManager
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
object LyricsSettingsViewRoute

@Composable
fun LyricsSettingsView(context: ViewContext) {
    val manager = context.symphony.lyricsManager
    val state by manager.state.collectAsState()
    var baseUrl by remember { mutableStateOf(manager.baseUrl) }
    var message by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var onlyActionable by remember { mutableStateOf(true) }
    var confirmIds by remember { mutableStateOf<Set<String>?>(null) }
    var preview by remember { mutableStateOf<LyricsManager.Item?>(null) }
    var recovery by remember { mutableStateOf<EmbeddedLyricsStore.Recovery?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            message = runCatching {
                context.activity.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                "Folder access saved. Retry embedding the matches in this folder."
            }.getOrElse { "Write access was not granted: ${it.message}" }
        }
    }
    val readyIds = state.items.filter { it.status == LyricsManager.Status.READY }.map { it.song.id }.toSet()
    GlassSettingsScaffold(context, title = "Lyrics Manager") { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Find & embed missing lyrics", style = MaterialTheme.typography.titleMedium)
                        Text("Synchronized lyrics first; plain text if unavailable. Existing embedded lyrics are never replaced. " +
                            "Supported: MP3, FLAC, M4A/M4B, OGG and Opus. No audio re-encoding.")
                        Text("Timed lyrics are stored as LRC text inside the lyrics tag, not as a separate file. " +
                            "Other players may display the timestamps instead of synchronizing them.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = baseUrl, onValueChange = { baseUrl = it },
                            label = { Text("LRCLIB-compatible Base URL") },
                            placeholder = { Text(LrclibClient.DEFAULT_BASE_URL) },
                            enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Use the service root (or /api). This is not compatible with arbitrary lyrics APIs. " +
                            "Searching sends song title, artist, album and duration to this server.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !state.busy, onClick = {
                                message = runCatching { manager.baseUrl = baseUrl; baseUrl = manager.baseUrl; "Provider saved" }
                                    .getOrElse { it.message ?: "Invalid URL" }
                            }) { Text("Save URL") }
                            TextButton(enabled = !state.busy, onClick = {
                                manager.baseUrl = LrclibClient.DEFAULT_BASE_URL
                                baseUrl = manager.baseUrl
                            }) { Text("Reset") }
                        }
                        OutlinedButton(enabled = !state.busy, onClick = {
                            folderPicker.launch(context.symphony.settings.mediaFolders.value.firstOrNull())
                        }) { Text("Grant music-folder write access") }
                        Text("Choose the same music folder used by your library. Stop the current song before embedding it. " +
                            "Keep an independent backup of your music before a large batch.", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !state.busy && state.recoveries.isEmpty(), onClick = {
                            message = runCatching {
                                manager.baseUrl = baseUrl
                                manager.scan()
                                ""
                            }.getOrElse { it.message ?: "Invalid provider URL" }
                        }) { Text("Scan & find missing lyrics") }
                        if (state.busy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = manager::stop) { Text("Stop after safe file operation") }
                        } else if (readyIds.isNotEmpty()) {
                            Button(onClick = { confirmIds = readyIds }) { Text("Embed ${readyIds.size} matches") }
                        }
                        if (state.progress.isNotBlank()) Text(state.progress)
                        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        Text("${state.items.count { it.status == LyricsManager.Status.EMBEDDED }} embedded · " +
                            "${readyIds.size} ready · ${state.items.count { it.status == LyricsManager.Status.NOT_FOUND }} not found")
                    }
                }
            }
            items(state.recoveries, key = { "recovery:${it.directory.name}" }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Recovery backup: ${entry.name}", color = MaterialTheme.colorScheme.error)
                        Text("A previous write may have been interrupted. Restore before continuing.")
                        TextButton(enabled = !state.busy, onClick = { recovery = entry }) { Text("Restore original") }
                    }
                }
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Filter songs") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = onlyActionable, onCheckedChange = { onlyActionable = it })
                    Text("Hide songs that already had lyrics", modifier = Modifier.padding(top = 14.dp))
                }
            }
            items(state.items.filter {
                (!onlyActionable || it.status != LyricsManager.Status.EXISTING) &&
                    (query.isBlank() || it.song.title.contains(query, true) || it.song.artists.any { a -> a.contains(query, true) })
            }, key = { it.song.id }) { item ->
                GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.song.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.song.artists.joinToString(), style = MaterialTheme.typography.bodySmall)
                        Text(when (item.status) {
                            LyricsManager.Status.READY -> if (item.candidate?.synced == true) "Ready · synchronized LRC" else "Ready · plain lyrics"
                            LyricsManager.Status.EXISTING -> "Already has embedded lyrics · preserved"
                            LyricsManager.Status.EMBEDDED -> "Embedded & verified"
                            LyricsManager.Status.NOT_FOUND -> "No confident match found"
                            else -> item.status.name.lowercase().replaceFirstChar { it.uppercase() }
                        })
                        if (item.message.isNotBlank()) Text(item.message, color = MaterialTheme.colorScheme.error)
                        if (item.status == LyricsManager.Status.READY) {
                            Row {
                                TextButton(onClick = { preview = item }) { Text("Review match") }
                                TextButton(enabled = !state.busy, onClick = { confirmIds = setOf(item.song.id) }) { Text("Embed") }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmIds?.let { ids ->
        AlertDialog(onDismissRequest = { confirmIds = null }, title = { Text("Modify ${ids.size} audio files?") },
            text = { Text("The matched lyrics will be written inside the original songs. Existing lyrics, artwork and other tags " +
                "will be preserved. A temporary backup is retained until each write is verified. Playback of the current song is skipped.") },
            confirmButton = { TextButton(onClick = { confirmIds = null; manager.embed(ids) }) { Text("Embed lyrics") } },
            dismissButton = { TextButton(onClick = { confirmIds = null }) { Text("Cancel") } })
    }
    preview?.let { item ->
        val candidate = item.candidate!!
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Matched recording") },
            text = { Text("${candidate.record.trackName}\n${candidate.record.artistName}\n${candidate.record.albumName}\n" +
                "Duration: ${candidate.record.duration}s\n" + if (candidate.synced) "Synchronized LRC" else "Plain lyrics") },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } })
    }
    recovery?.let { entry ->
        AlertDialog(onDismissRequest = { recovery = null }, title = { Text("Restore original file?") },
            text = { Text("Replace ${entry.name} with its verified backup. Stop playback first.") },
            confirmButton = { TextButton(onClick = { recovery = null; manager.restore(entry) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { recovery = null }) { Text("Cancel") } })
    }
}
