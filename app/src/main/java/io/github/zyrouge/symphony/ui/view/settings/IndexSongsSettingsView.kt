package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.components.*
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import kotlinx.serialization.Serializable

@Serializable
object IndexSongsSettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexSongsSettingsView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val allSongIds by context.symphony.groove.song.all.collectAsState()
    val repository = context.symphony.semanticSearch.repository
    val isReady by context.symphony.semanticSearch.isReady.collectAsState()

    var unembeddedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedSongs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var progressCount by remember { mutableStateOf(0) }

    LaunchedEffect(allSongIds, isReady, isProcessing) {
        if (!isReady || repository == null || isProcessing) return@LaunchedEffect
        
        // This is safe since it's just checking cache
        val filtered = allSongIds.mapNotNull { id -> context.symphony.groove.song.get(id) }.filter { song ->
            !repository.isTrackEmbedded(
                title = song.title,
                artist = song.artists.joinToString(),
                durationMs = song.duration
            )
        }
        unembeddedSongs = filtered
        // Don't clear selection unless needed, so we don't lose it on recomposition
        selectedSongs = selectedSongs.intersect(filtered.map { it.id }.toSet())
    }

    if (isProcessing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("Indexing Songs") },
            text = {
                Column {
                    androidx.compose.material3.CircularProgressIndicator()
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))
                    Text("Processed: $progressCount / ${selectedSongs.size}")
                    Text(
                        progressText,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                AdaptiveSnackbar(it)
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("Manage AI Index")
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            context.navController.popBackStack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (selectedSongs.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val songsToProcess = unembeddedSongs.filter { selectedSongs.contains(it.id) }
                                if (songsToProcess.isEmpty()) return@IconButton
                                
                                isProcessing = true
                                progressCount = 0
                                
                                coroutineScope.launch {
                                    for (song in songsToProcess) {
                                        progressText = "Embedding: ${song.title}"
                                        try {
                                            context.symphony.semanticSearch.embedSongLocal(song)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                        progressCount++
                                    }
                                    
                                    isProcessing = false
                                    selectedSongs = emptySet()
                                    snackbarHostState.showSnackbar("Finished indexing!")
                                }
                            }
                        ) {
                            Icon(Icons.Filled.AutoAwesome, "Index Selected")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.padding(contentPadding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${unembeddedSongs.size} un-indexed songs",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = {
                            if (selectedSongs.size == unembeddedSongs.size) {
                                selectedSongs = emptySet()
                            } else {
                                selectedSongs = unembeddedSongs.map { it.id }.toSet()
                            }
                        }
                    ) {
                        Text(
                            if (selectedSongs.size == unembeddedSongs.size) "Deselect All" else "Select All"
                        )
                    }
                }
            }

            items(unembeddedSongs, key = { it.id }) { song ->
                val isSelected = selectedSongs.contains(song.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isSelected,
                            onValueChange = { checked ->
                                selectedSongs = if (checked) {
                                    selectedSongs + song.id
                                } else {
                                    selectedSongs - song.id
                                }
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        SongCard(
                            context = context,
                            song = song,
                            disableHeartIcon = true,
                            onClick = {
                                selectedSongs = if (isSelected) {
                                    selectedSongs - song.id
                                } else {
                                    selectedSongs + song.id
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
