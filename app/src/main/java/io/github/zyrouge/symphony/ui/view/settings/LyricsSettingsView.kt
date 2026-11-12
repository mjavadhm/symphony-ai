package io.github.zyrouge.symphony.ui.view.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.lyrics.*
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.ReadableText
import io.github.zyrouge.symphony.ui.components.GlassSurface
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
data class LyricsSettingsViewRoute(val songId: String = "", val albumId: String = "")

@Composable
fun LyricsSettingsView(context: ViewContext, route: LyricsSettingsViewRoute = LyricsSettingsViewRoute()) {
    val manager = context.symphony.lyricsManager
    val state by manager.state.collectAsState()
    val scopeKey = if (route.songId.isNotEmpty()) "song:${route.songId}" else if (route.albumId.isNotEmpty()) "album:${route.albumId}" else "library"
    val scopedIds = remember(route) {
        when {
            route.songId.isNotEmpty() -> setOf(route.songId)
            route.albumId.isNotEmpty() -> context.symphony.groove.album.get(route.albumId)?.getSongIds(context.symphony)?.toSet().orEmpty()
            else -> null
        }
    }
    var query by rememberSaveable(route) { mutableStateOf("") }
    var albumQuery by rememberSaveable(route) { mutableStateOf("") }
    var filter by rememberSaveable(route) { mutableStateOf("Missing") }
    var selected by rememberSaveable(route) { mutableStateOf(listOf<String>()) }
    var provider by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmIds by remember { mutableStateOf<Set<String>?>(null) }
    var preview by remember { mutableStateOf<LyricsManager.Item?>(null) }
    var recovery by remember { mutableStateOf<EmbeddedLyricsStore.Recovery?>(null) }
    val inventory = if (state.scopeKey == scopeKey) state.items else emptyList()
    val visible = inventory.filter { item ->
        (when (filter) {
            "Missing" -> item.missing
            "Ready" -> item.status == LyricsManager.Status.READY
            "Errors" -> item.status == LyricsManager.Status.FAILED || item.writeError
            else -> true
        }) && (query.isBlank() || item.song.title.contains(query, true) || item.song.artists.any { it.contains(query, true) } || item.song.album.orEmpty().contains(query, true)) &&
            (albumQuery.isBlank() || item.song.album.orEmpty().contains(albumQuery, true))
    }
    val selectedItems = inventory.filter { it.song.id in selected }
    val searchIds = selectedItems.filter { it.missing && it.status != LyricsManager.Status.READY }.map { it.song.id }.toSet()
    val readyIds = selectedItems.filter { it.status == LyricsManager.Status.READY }.map { it.song.id }.toSet()
    fun scan() { selected = emptyList(); manager.scan(scopedIds, scopeKey) }
    LaunchedEffect(scopeKey, state.busy) {
        if (!state.busy && state.scopeKey != scopeKey) manager.prepare(scopedIds, scopeKey)
    }
    LaunchedEffect(scopeKey, state.scopeKey, state.scanned) {
        if (state.scopeKey == scopeKey && state.scanned && scopedIds != null) selected = inventory.filter { it.missing }.map { it.song.id }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) message = runCatching {
            context.activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            "Folder access saved. Retry embedding."
        }.getOrElse { "Write access was not granted: ${it.message}" }
    }
    GlassSettingsScaffold(context, title = if (route.songId.isNotEmpty()) "Lyrics · this song" else if (route.albumId.isNotEmpty()) "Lyrics · this album" else "Missing lyrics") { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        // Outside the scrolling list: cancellation remains reachable during a long queue.
        if (state.busy) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(state.progress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    TextButton(onClick = manager::stop) { Text("Cancel") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("1. Scan locally → 2. Select & search → 3. Review & embed", style = MaterialTheme.typography.titleSmall)
                Text("Your saved library check opens instantly. Check files only when you choose; online search starts only for selected songs.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !state.busy && state.recoveries.isEmpty(), onClick = ::scan) { Text(if (state.scanned) "Check changes" else "Check local files") }
                    TextButton(enabled = !state.busy, onClick = { provider = true }) { Text("Provider & access") }
                }
                Row {
                    TextButton(enabled = !state.busy && state.recoveries.isEmpty(), onClick = { selected = emptyList(); manager.scan(scopedIds, scopeKey, force = true) }) { Text("Recheck all files") }
                }
                if (state.progress.isNotBlank()) Text(state.progress)
                if (state.cooldownUntil > System.currentTimeMillis()) Text("Provider cooldown until ${java.text.DateFormat.getTimeInstance().format(java.util.Date(state.cooldownUntil))}. Local scan and filtering still work.")
                if (state.scopeKey != scopeKey && state.busy) Text("Another scope is busy. Cancel it or wait; your saved list will then open.")
                if (message.isNotBlank()) Text(message)
                Text("${inventory.count { it.missing }} missing · ${inventory.count { it.status == LyricsManager.Status.READY }} ready · ${inventory.count { it.status == LyricsManager.Status.EMBEDDED }} embedded")
            }
            items(state.recoveries, key = { "recovery:${it.directory.name}" }) { entry ->
                Card { Column(Modifier.padding(12.dp)) {
                    Text("Interrupted write: ${entry.name}", color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !state.busy, onClick = { recovery = entry }) { Text("Restore original before continuing") }
                } }
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Filter title / artist / album") }, singleLine = true)
                OutlinedTextField(albumQuery, { albumQuery = it }, Modifier.fillMaxWidth(), label = { Text("Album filter (optional)") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Missing", "Ready", "Errors", "All").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
                }
                Row {
                    TextButton(enabled = !state.busy, onClick = { selected = (selected + visible.filter { it.missing }.map { it.song.id }).distinct() }) { Text("Select visible") }
                    TextButton(enabled = !state.busy, onClick = { selected = selected.filterNot { id -> visible.any { it.song.id == id } } }) { Text("Deselect visible") }
                }
                Text("${selectedItems.size} selected (including hidden rows)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !state.busy && searchIds.isNotEmpty() && state.recoveries.isEmpty(), onClick = { manager.search(searchIds) }) { Text("Search ${searchIds.size} selected") }
                    OutlinedButton(enabled = !state.busy && readyIds.isNotEmpty() && state.recoveries.isEmpty(), onClick = { confirmIds = readyIds }) { Text("Embed ${readyIds.size}") }
                }
                TextButton(enabled = !state.busy && selectedItems.any { it.missing } && state.recoveries.isEmpty(), onClick = {
                    manager.search(selectedItems.filter { it.missing }.map { it.song.id }.toSet(), refresh = true)
                }) { Text("Refresh selected online (ignore result cache)") }
                if (state.scanned && visible.isEmpty()) Text("No songs match this filter. Try All or clear the filters.")
            }
            items(visible, key = { it.song.id }) { item ->
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Checkbox(checked = item.song.id in selected, enabled = !state.busy && item.missing,
                            onCheckedChange = { checked -> selected = if (checked) (selected + item.song.id).distinct() else selected - item.song.id })
                        Column(Modifier.weight(1f)) {
                            ReadableText(item.song.title, style = MaterialTheme.typography.titleSmall)
                            ReadableText(item.song.artists.joinToString(), style = MaterialTheme.typography.bodySmall)
                            ReadableText(item.song.album.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(when (item.status) {
                        LyricsManager.Status.UNKNOWN -> "Not checked yet · choose Check local files"
                        LyricsManager.Status.MISSING -> "Missing lyrics · ready to search"
                        LyricsManager.Status.EXISTING -> "Embedded lyrics already present · preserved"
                        LyricsManager.Status.SIDECAR -> "Local .lrc already present · preserved"
                        LyricsManager.Status.READY -> if (item.candidate?.synced == true) "Match ready · synchronized" else "Match ready · plain text"
                        LyricsManager.Status.NOT_FOUND -> "No confident match found"
                        LyricsManager.Status.EMBEDDED -> "Embedded & verified"
                        else -> item.status.name.lowercase().replaceFirstChar { it.uppercase() }
                    })
                    if (item.message.isNotBlank()) ReadableText(item.message, style = MaterialTheme.typography.bodyMedium.copy(color = if (item.writeError) MaterialTheme.colorScheme.error else LocalContentColor.current))
                    if (item.accessRequired) TextButton(enabled = !state.busy, onClick = { folderPicker.launch(context.symphony.settings.mediaFolders.value.firstOrNull()) }) { Text("Grant folder access") }
                    Row {
                        if (item.missing && item.status != LyricsManager.Status.READY) TextButton(enabled = !state.busy && state.recoveries.isEmpty(), onClick = { manager.search(setOf(item.song.id)) }) { Text("Search this song") }
                        if (item.status == LyricsManager.Status.READY) {
                            TextButton(onClick = { preview = item }) { Text("Review lyrics") }
                            TextButton(enabled = !state.busy && state.recoveries.isEmpty(), onClick = { confirmIds = setOf(item.song.id) }) { Text(if (item.writeError) "Retry saving" else "Embed this song") }
                        }
                    }
                } }
            }
        }
    }
    }
    if (provider) LyricsProviderDialog(manager, onFolder = { folderPicker.launch(context.symphony.settings.mediaFolders.value.firstOrNull()) }, dismiss = { provider = false })
    confirmIds?.let { ids -> AlertDialog(onDismissRequest = { confirmIds = null }, title = { Text("Embed lyrics in ${ids.size} selected files?") },
        text = { Text("Writes lyrics inside the original audio without re-encoding. Existing tags/artwork/lyrics are preserved and each write is backed up and verified. The currently loaded song is skipped; play a different song before retrying it. Keep an independent backup before a large batch.") },
        confirmButton = { TextButton(onClick = { confirmIds = null; manager.embed(ids) }) { Text("Embed selected") } }, dismissButton = { TextButton(onClick = { confirmIds = null }) { Text("Cancel") } }) }
    preview?.let { item -> val candidate = item.candidate!!
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Review match") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("${candidate.record.trackName}\n${candidate.record.artistName}\n${candidate.record.albumName}\n${candidate.record.duration}s · ${if (candidate.synced) "Synchronized LRC" else "Plain lyrics"}\n")
                ReadableText(candidate.text.take(16000))
                if (candidate.text.length > 16000) Text("Preview shortened; full matched text is used when embedding.")
            }
        }, confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } })
    }
    recovery?.let { entry -> AlertDialog(onDismissRequest = { recovery = null }, title = { Text("Restore original file?") }, text = { Text("Replace ${entry.name} with its verified backup. Stop playback first.") },
        confirmButton = { TextButton(onClick = { recovery = null; manager.restore(entry) }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { recovery = null }) { Text("Cancel") } }) }
}

@Composable
private fun LyricsProviderDialog(manager: LyricsManager, onFolder: () -> Unit, dismiss: () -> Unit) {
    var base by remember { mutableStateOf(manager.baseUrl) }; var appId by remember { mutableStateOf(manager.publicAppId) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Lyrics provider & access") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default: LRCLIB — free, no API key required, synchronized lyrics first, plain lyrics as fallback. Coverage varies; no free provider covers every recording.")
            OutlinedTextField(base, { base = it }, label = { Text("LRCLIB-compatible Base URL") })
            TextButton(onClick = { base = LrclibClient.DEFAULT_BASE_URL }) { Text("Use official LRCLIB") }
            OutlinedTextField(appId, { appId = it }, label = { Text("Registered public LRCLIB app ID (optional)") })
            Text("Leave blank for anonymous access. Only use a real public app ID registered with LRCLIB. Never enter a server API key here. Custom services must implement the LRCLIB API; this is not a universal lyrics API protocol.", style = MaterialTheme.typography.bodySmall)
            Text("Search sends title, artist, album and duration. Requests are serial, spaced at least 1.5s after completion, cached, and paused for Retry-After. Shared IP/VPN limits can still apply.", style = MaterialTheme.typography.bodySmall)
            Text("Timed lyrics are embedded as LRC text in the lyrics tag. Other players may show timestamps instead of synchronizing them.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onFolder) { Text("Grant music-folder write access") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val normalized = LrclibClient.normalizeBaseUrl(base)
            val id = appId.trim(); require(id.length <= 200 && id.all { it.code in 33..126 }) { "Invalid public app ID" }
            manager.baseUrl = normalized; manager.publicAppId = id; dismiss()
        } catch (e: Exception) { error = e.message.orEmpty() }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
