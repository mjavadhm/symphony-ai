package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.components.*
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import io.github.zyrouge.symphony.services.groove.Song
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.clickable
import androidx.compose.material3.ElevatedCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val isReady by context.symphony.semanticSearch.isReady.collectAsState()
    val hasIndex = remember { context.symphony.semanticSearch.indexedTrackCount() > 0 }

    var searchMode by remember { mutableStateOf(0) } // 0 = Text, 1 = Song
    var textQuery by remember { mutableStateOf("") }
    
    var trackCount by remember { mutableStateOf(20f) }
    var similarityThreshold by remember { mutableStateOf(50f) }
    var limitMode by remember { mutableStateOf(0) } // 0 = Count, 1 = Similarity
    
    var isGenerating by remember { mutableStateOf(false) }
    var generatedSongs by remember { mutableStateOf<List<Song>?>(null) }
    val selectedGeneratedSongs = remember { mutableStateListOf<String>() }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(state = LocalHazeState.current, zIndex = 1f)
            .padding(horizontal = 16.dp),
        contentPadding = LocalHomeContentPadding.current,
    ) {
            item {
                if (context.symphony.llm.isConfigured &&
                    context.symphony.llm.usageMode != io.github.zyrouge.symphony.services.llm.LlmClient.UsageMode.Off
                ) {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { context.navController.navigate(io.github.zyrouge.symphony.ui.view.DiscoverChatRoute()) }
                                .padding(14.dp),
                        ) {
                            Text("💬", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Chat with AI", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Describe what you want, give feedback, refine",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            item {
                IndexingStatusBanner(context)
            }
            
            if (!isReady || !hasIndex) {
                item { AiSetupChecklist(context) }
            } else {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    // Mode Switcher
                    GlassSegmentedTabs(
                        options = listOf("Prompt", "Reference Song"),
                        selectedIndex = searchMode,
                        onSelect = { searchMode = it },
                    )
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
                    GlassSegmentedTabs(
                        options = listOf("By Count", "By Similarity"),
                        selectedIndex = limitMode,
                        onSelect = { limitMode = it },
                    )
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
                                    val results = context.symphony.semanticSearch.searchDetailed(textQuery, limit)
                                    if (limitMode == 1 && results.isNotEmpty()) {
                                        // آستانه نسبی: نسبت به بهترین نتیجه سنجیده میشه
                                        val topScore = results.first().hybridScore
                                        results.filter { it.hybridScore >= (similarityThreshold / 100f) * topScore }
                                            .mapNotNull { it.track.filePath }
                                    } else {
                                        results.mapNotNull { it.track.filePath }
                                    }
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
                } // End of Column
            } // End of item

            val songs = generatedSongs
            if (songs != null) {
                if (songs.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                                    if (searchMode == 0) {
                                        context.symphony.radio.playbackSource = "discover_prompt"
                                    } else {
                                        context.symphony.radio.playbackSource = "discover_similar"
                                    }
                                    context.symphony.radio.shorty.playQueue(
                                        selectedGeneratedSongs.toList()
                                    )
                                }, enabled = selectedGeneratedSongs.isNotEmpty()) {
                                    Text("Play")
                                }
                            }
                        }
                    }
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
                } else {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("No matches found", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Try describing mood or genre in English — e.g. \"sad piano\", \"energetic rock for gym\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            } // End of else block
        } // End of LazyColumn
        
        if (showAddToPlaylistDialog) {
            AddToPlaylistDialog(
                context = context,
                songIds = selectedGeneratedSongs.toList(),
                onDismissRequest = {
                    showAddToPlaylistDialog = false
                }
            )
        }
}

@Composable
private fun GlassSegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val bgColor by animateColorAsState(
                    targetValue = when {
                        selected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                        else -> Color.Transparent
                    },
                    label = "segment-bg",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(bgColor)
                        .clickable { onSelect(index) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            selected -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}