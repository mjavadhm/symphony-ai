package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.HomeDynamicBackground
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.components.NewPlaylistDialog
import io.github.zyrouge.symphony.ui.components.SongCard
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.nowPlaying.NothingPlayingBody
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object QueueViewRoute

@Composable
fun QueueView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val queue by context.symphony.radio.observatory.queue.collectAsState()
    val queueIndex by context.symphony.radio.observatory.queueIndex.collectAsState()
    val selectedSongIndices = remember { mutableStateListOf<Int>() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = queueIndex,
    )
    var showSaveDialog by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            HomeDynamicBackground(context)
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                        ) {
                            IconButton(
                                modifier = Modifier.size(44.dp),
                                onClick = { context.navController.popBackStack() },
                            ) {
                                Icon(Icons.Filled.ExpandMore, null)
                            }
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassSurface(
                                modifier = Modifier.height(44.dp),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    context.symphony.t.Queue,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                            }
                        }
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                        ) {
                            IconButton(
                                modifier = Modifier.size(44.dp),
                                onClick = {
                                    context.symphony.radio.queue.applyFlowOrder()
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(0)
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Waves, null)
                            }
                        }
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                        ) {
                            when {
                                selectedSongIndices.isNotEmpty() -> IconButton(
                                    modifier = Modifier.size(44.dp),
                                    onClick = {
                                        context.symphony.radio.queue.remove(selectedSongIndices.toList())
                                        selectedSongIndices.clear()
                                    }
                                ) {
                                    Icon(Icons.Filled.Delete, null)
                                }

                                else -> IconButton(
                                    modifier = Modifier.size(44.dp),
                                    onClick = {
                                        showSaveDialog = !showSaveDialog
                                    }
                                ) {
                                    Icon(Icons.Default.Save, null)
                                }
                            }
                        }
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                        ) {
                            IconButton(
                                modifier = Modifier.size(44.dp),
                                onClick = {
                                    context.symphony.radio.stop()
                                    selectedSongIndices.clear()
                                }
                            ) {
                                Icon(Icons.Filled.ClearAll, null)
                            }
                        }
                    }
                },
                content = { contentPadding ->
                    if (queue.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .padding(contentPadding)
                                .fillMaxSize()
                        ) {
                            NothingPlayingBody(context)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = contentPadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = LocalHazeState.current, zIndex = 1f),
                        ) {
                            itemsIndexed(
                                queue,
                                key = { i, id -> "$i-$id" },
                                contentType = { _, _ -> Groove.Kind.SONG },
                            ) { i, songId ->
                                context.symphony.groove.song.get(songId)?.let { song ->
                                    Box {
                                        SongCard(
                                            context,
                                            song,
                                            autoHighlight = false,
                                            highlighted = i == queueIndex,
                                            leading = {
                                                Checkbox(
                                                    checked = selectedSongIndices.contains(i),
                                                    onCheckedChange = {
                                                        if (selectedSongIndices.contains(i)) {
                                                            selectedSongIndices.remove(i)
                                                        } else {
                                                            selectedSongIndices.add(i)
                                                        }
                                                    },
                                                    modifier = Modifier.offset((-4).dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            },
                                            thumbnailLabel = {
                                                Text((i + 1).toString())
                                            },
                                            onClick = {
                                                context.symphony.radio.jumpTo(i)
                                                coroutineScope.launch {
                                                    listState.animateScrollToItem(i)
                                                }
                                            },
                                        )
                                        if (i < queueIndex) {
                                            Box(
                                                modifier = Modifier
                                                    .matchParentSize()
                                                    .background(
                                                        MaterialTheme.colorScheme.background.copy(alpha = 0.3f)
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    if (showSaveDialog) {
        NewPlaylistDialog(
            context,
            initialSongIds = queue.toList(),
            onDone = { playlist ->
                showSaveDialog = false
                context.symphony.groove.playlist.add(playlist)
            },
            onDismissRequest = {
                showSaveDialog = false
            }
        )
    }
}
