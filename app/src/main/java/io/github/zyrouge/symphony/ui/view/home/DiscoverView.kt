package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.components.*
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import io.github.zyrouge.symphony.services.search.data.SearchResult
import io.github.zyrouge.symphony.services.groove.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    var searchMode by remember { mutableStateOf(0) } // 0 = Text, 1 = Song
    var textQuery by remember { mutableStateOf("") }
    
    var trackCount by remember { mutableStateOf(20f) }
    var similarityThreshold by remember { mutableStateOf(50f) }
    
    var isGenerating by remember { mutableStateOf(false) }
    var generatedSongs by remember { mutableStateOf<List<Song>?>(null) }
    
    // For song search mode
    var songSearchQuery by remember { mutableStateOf("") }
    var selectedReferenceSong by remember { mutableStateOf<Song?>(null) }
    val allSongIds by context.symphony.groove.song.all.collectAsState()
    val allSongs = remember(allSongIds) {
        allSongIds.mapNotNull { context.symphony.groove.song.get(it) }
    }
    val filteredSongs = remember(songSearchQuery, allSongs) {
        if (songSearchQuery.isBlank()) emptyList<Song>()
        else allSongs.filter { it.title.contains(songSearchQuery, ignoreCase = true) || it.artists.any { a -> a.contains(songSearchQuery, ignoreCase = true) } }.take(5)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("AI Discover")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Mode Switcher
            TabRow(selectedTabIndex = searchMode) {
                Tab(
                    selected = searchMode == 0,
                    onClick = { searchMode = 0 },
                    text = { Text("Prompt") }
                )
                Tab(
                    selected = searchMode == 1,
                    onClick = { searchMode = 1 },
                    text = { Text("Reference Song") }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Inputs
            if (searchMode == 0) {
                OutlinedTextField(
                    value = textQuery,
                    onValueChange = { textQuery = it },
                    label = { Text("Describe the vibe...") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                    singleLine = true
                )
            } else {
                if (selectedReferenceSong == null) {
                    OutlinedTextField(
                        value = songSearchQuery,
                        onValueChange = { songSearchQuery = it },
                        label = { Text("Search for a song...") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true
                    )
                    if (filteredSongs.isNotEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                filteredSongs.forEach { song ->
                                    ListItem(
                                        headlineContent = { Text(song.title) },
                                        supportingContent = { Text(song.artists.joinToString()) },
                                        modifier = Modifier.clickable {
                                            selectedReferenceSong = song
                                            songSearchQuery = ""
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.MusicNote, null) },
                            headlineContent = { Text(selectedReferenceSong!!.title) },
                            supportingContent = { Text("Reference Song") },
                            trailingContent = {
                                TextButton(onClick = { selectedReferenceSong = null }) {
                                    Text("Change")
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sliders
            Text("Track Count: ${trackCount.toInt()}")
            Slider(
                value = trackCount,
                onValueChange = { trackCount = it },
                valueRange = 5f..100f,
                steps = 19
            )

            if (searchMode == 1) {
                Text("Similarity: ${similarityThreshold.toInt()}%")
                Slider(
                    value = similarityThreshold,
                    onValueChange = { similarityThreshold = it },
                    valueRange = 0f..100f
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Generate Button
            Button(
                onClick = {
                    coroutineScope.launch {
                        isGenerating = true
                        generatedSongs = null
                        val limit = trackCount.toInt()
                        
                        val filePaths = if (searchMode == 0) {
                            context.symphony.semanticSearch.search(textQuery, limit)
                        } else {
                            if (selectedReferenceSong != null) {
                                val results = context.symphony.semanticSearch.findSimilarSongs(
                                    selectedReferenceSong!!.title,
                                    selectedReferenceSong!!.artists.joinToString(),
                                    (selectedReferenceSong!!.duration / 1000).toInt(),
                                    limit
                                )
                                // Convert similarity to distance roughly: 100% = 0.0, 0% = 2.0
                                val maxDistance = 2.0f - (similarityThreshold / 100f * 2.0f)
                                results.filter { it.hybridScore >= (similarityThreshold / 100f) }.mapNotNull { it.track.filePath }
                            } else emptyList()
                        }
                        
                        val resolvedSongs = filePaths.mapNotNull { path ->
                            allSongs.find { it.path == path }
                        }
                        generatedSongs = resolvedSongs
                        isGenerating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating && (searchMode == 0 && textQuery.isNotBlank() || searchMode == 1 && selectedReferenceSong != null)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Generate Playlist")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            var showAddToPlaylistDialog by remember { mutableStateOf(false) }
            
            // Results
            generatedSongs?.let { songs ->
                if (songs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Found ${songs.size} songs", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = {
                                showAddToPlaylistDialog = true
                            }) {
                                Text("Save to Playlists")
                            }
                            TextButton(onClick = {
                                context.symphony.radio.shorty.playQueue(
                                    songs.map { it.id }
                                )
                            }) {
                                Text("Play Now")
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(songs) { index, song ->
                            SongCard(
                                context = context,
                                song = song,
                                onClick = {
                                    context.symphony.radio.shorty.playQueue(
                                        songs.map { it.id },
                                        options = io.github.zyrouge.symphony.services.radio.Radio.PlayOptions(index = index)
                                    )
                                }
                            )
                        }
                    }
                    
                    if (showAddToPlaylistDialog) {
                        AddToPlaylistDialog(
                            context = context,
                            songIds = songs.map { it.id },
                            onDismissRequest = {
                                showAddToPlaylistDialog = false
                            }
                        )
                    }
                } else {
                    Text("No songs found. Try adjusting your parameters.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
