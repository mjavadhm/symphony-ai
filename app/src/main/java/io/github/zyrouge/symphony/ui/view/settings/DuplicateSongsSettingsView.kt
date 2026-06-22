package io.github.zyrouge.symphony.ui.view.settings

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.DocumentFileX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object DuplicateSongsSettingsViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateSongsSettingsView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    var matchByTitle by remember { mutableStateOf(true) }
    var matchByArtist by remember { mutableStateOf(true) }
    var matchByDuration by remember { mutableStateOf(false) }

    val allSongs = context.symphony.groove.song.values()
    val duplicateGroups = remember(matchByTitle, matchByArtist, matchByDuration, allSongs) {
        val groups = mutableMapOf<String, MutableList<Song>>()
        for (song in allSongs) {
            val titlePart = if (matchByTitle) song.title.lowercase() else ""
            val artistPart = if (matchByArtist) song.artists.joinToString(", ").lowercase() else ""
            val durationPart = if (matchByDuration) (song.duration / 1000).toString() else ""
            
            val key = "$titlePart|$artistPart|$durationPart"
            if (key != "||") {
                groups.getOrPut(key) { mutableListOf() }.add(song)
            }
        }
        groups.values.filter { it.size > 1 }.toList()
    }

    val selectedSongs = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("${context.symphony.t.Settings} - Find Duplicates")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
                navigationIcon = {
                    IconButton(onClick = { context.navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        },
        floatingActionButton = {
            val selectedCount = selectedSongs.values.count { it }
            if (selectedCount > 0) {
                ExtendedFloatingActionButton(
                    text = { Text("Delete $selectedCount selected") },
                    icon = { Icon(Icons.Filled.Delete, null) },
                    onClick = {
                        coroutineScope.launch {
                            var deletedCount = 0
                            val selectedIds = selectedSongs.filterValues { it }.keys.toList()
                            
                            withContext(Dispatchers.IO) {
                                for (id in selectedIds) {
                                    val song = allSongs.find { it.id == id }
                                    if (song != null) {
                                        val uri = context.symphony.groove.exposer.uris[song.path]
                                        if (uri != null) {
                                            val docFile = DocumentFileX.fromSingleUri(context.symphony.applicationContext, uri)
                                            if (docFile?.delete() == true) {
                                                deletedCount++
                                            }
                                        }
                                    }
                                }
                            }
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context.activity, "Deleted $deletedCount files", Toast.LENGTH_SHORT).show()
                                selectedSongs.clear()
                                // Force library rescan
                                context.symphony.radio.stop()
                                val options = io.github.zyrouge.symphony.services.groove.Groove.FetchOptions(
                                    resetInMemoryCache = true,
                                    resetPersistentCache = true,
                                )
                                context.symphony.groove.fetch(options)
                            }
                        }
                    }
                )
            }
        }
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            // Filters
            Text(
                text = "Match by:",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = matchByTitle,
                    onClick = { matchByTitle = !matchByTitle },
                    label = { Text("Title") }
                )
                FilterChip(
                    selected = matchByArtist,
                    onClick = { matchByArtist = !matchByArtist },
                    label = { Text("Artist") }
                )
                FilterChip(
                    selected = matchByDuration,
                    onClick = { matchByDuration = !matchByDuration },
                    label = { Text("Duration") }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (duplicateGroups.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No duplicates found")
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(duplicateGroups) { group ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Title: ${group.first().title}\nArtist: ${group.first().artists.joinToString(", ")}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                group.forEach { song ->
                                    val isSelected = selectedSongs[song.id] ?: false
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedSongs[song.id] = !isSelected
                                        }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { selectedSongs[song.id] = it }
                                        )
                                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                            Text(
                                                text = song.filename,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = song.path,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
