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
import androidx.compose.ui.draw.clip
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
    var limitMode by remember { mutableStateOf(0) } // 0 = Count, 1 = Similarity
    
    var isGenerating by remember { mutableStateOf(false) }
    var generatedSongs by remember { mutableStateOf<List<Song>?>(null) }
    val selectedGeneratedSongs = remember { mutableStateListOf<String>() }
    
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
                                        leadingContent = {
                                            coil.compose.AsyncImage(
                                                model = song.createArtworkImageRequest(context.symphony).build(),
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                                            )
                                        },
                                        headlineContent = { Text(song.title, maxLines = 1) },
                                        supportingContent = { Text(song.artists.joinToString(), maxLines = 1) },
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
                            leadingContent = {
                                coil.compose.AsyncImage(
                                    model = selectedReferenceSong!!.createArtworkImageRequest(context.symphony).build(),
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                                )
                            },
                            headlineContent = { Text(selectedReferenceSong!!.title, maxLines = 1) },
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

            // Limit Mode Switcher
            TabRow(selectedTabIndex = limitMode) {
                Tab(
                    selected = limitMode == 0,
                    onClick = { limitMode = 0 },
                    text = { Text("Limit by Count") }
                )
                Tab(
                    selected = limitMode == 1,
                    onClick = { limitMode = 1 },
                    text = { Text("Limit by Similarity") }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Sliders
            if (limitMode == 0) {
                Text("Track Count: ${trackCount.toInt()}")
                Slider(
                    value = trackCount,
                    onValueChange = { trackCount = it },
                    valueRange = 5f..100f,
                    steps = 19
                )
            } else {
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
                        val limit = if (limitMode == 0) trackCount.toInt() else 500
                        
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
                                if (limitMode == 1) {
                                    results.filter { it.hybridScore >= (similarityThreshold / 100f) }.mapNotNull { it.track.filePath }
                                } else {
                                    results.mapNotNull { it.track.filePath }
                                }
                            } else emptyList()
                        }
                        
                        val resolvedSongs = filePaths.mapNotNull { path ->
                            allSongs.find { it.path == path || it.path.endsWith(path, ignoreCase = true) }
                        }
                        generatedSongs = resolvedSongs
                        selectedGeneratedSongs.clear()
                        selectedGeneratedSongs.addAll(resolvedSongs.map { it.id })
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
                        Text("${selectedGeneratedSongs.size} selected", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = {
                                showAddToPlaylistDialog = true
                            }, enabled = selectedGeneratedSongs.isNotEmpty()) {
                                Text("Save")
                            }
                            TextButton(onClick = {
                                context.symphony.radio.shorty.playQueue(
                                    selectedGeneratedSongs.toList()
                                )
                            }, enabled = selectedGeneratedSongs.isNotEmpty()) {
                                Text("Play")
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(songs) { index, song ->
                            val isSelected = selectedGeneratedSongs.contains(song.id)
                            SongCard(
                                context = context,
                                song = song,
                                leading = {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedGeneratedSongs.add(song.id)
                                            else selectedGeneratedSongs.remove(song.id)
                                        }
                                    )
                                },
                                onClick = {
                                    if (isSelected) selectedGeneratedSongs.remove(song.id)
                                    else selectedGeneratedSongs.add(song.id)
                                }
                            )
                        }
                    }
                    
                    if (showAddToPlaylistDialog) {
                        AddToPlaylistDialog(
                            context = context,
                            songIds = selectedGeneratedSongs.toList(),
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
