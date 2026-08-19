package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.spotizer.SpotizerAlbum
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class OnlineAlbumViewRoute(val albumId: Long)

@Composable
fun OnlineAlbumView(context: ViewContext, route: OnlineAlbumViewRoute) {
    val spotizer = context.symphony.spotizer
    var album by remember { mutableStateOf<SpotizerAlbum?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTrack by remember { mutableStateOf<SpotizerTrack?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(route.albumId) {
        runCatching {
            withContext(Dispatchers.IO) { spotizer.client.getAlbum(route.albumId) }
        }.onSuccess { album = it }.onFailure { error = it.message ?: "Could not load album" }
    }

    SpotizerPageScaffold(
        context = context,
        title = album?.title ?: "Album",
        bottomBar = { SpotizerStreamBar(context) },
    ) { contentPadding ->
        when {
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(error ?: "Could not load album")
                }
            }

            album == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                val nAlbum = album!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = LocalHazeState.current, zIndex = 1f),
                    contentPadding = contentPadding,
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AsyncImage(
                                model = nAlbum.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(28.dp)),
                            )
                            Text(
                                nAlbum.title ?: "Unknown album",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                nAlbum.artist ?: "Unknown artist",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    nAlbum.artistId?.let {
                                        context.navController.navigate(OnlineArtistViewRoute(it))
                                    }
                                },
                            )
                            Text(
                                listOfNotNull(
                                    nAlbum.recordType?.replaceFirstChar { it.uppercase() },
                                    nAlbum.releaseDate,
                                    (nAlbum.nbTracks ?: nAlbum.tracks.size)
                                        .takeIf { it > 0 }
                                        ?.let { it.toString() + " tracks" },
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            Button(
                                onClick = {
                                    val (enqueued, skipped) =
                                        spotizer.downloads.enqueueAlbumTracks(nAlbum.tracks)
                                    banner = enqueued.toString() + " queued, " +
                                            skipped + " skipped"
                                },
                                enabled = nAlbum.tracks.isNotEmpty(),
                            ) {
                                Icon(Icons.Filled.CloudDownload, null)
                                Text(" Download album")
                            }
                            banner?.let {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = {
                                        context.navController.navigate(SpotizerDownloadsViewRoute)
                                    }) {
                                        Text("Open downloads")
                                    }
                                }
                            }
                        }
                    }
                    items(nAlbum.tracks.size) { index ->
                        val track = nAlbum.tracks[index]
                        GlassSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTrack = track }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    (index + 1).toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.width(28.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        track.title ?: "Unknown title",
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOfNotNull(
                                            track.artist,
                                            formatOnlineDuration(track.duration),
                                        ).joinToString(" • "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedTrack?.let { track ->
        OnlineTrackBottomSheet(context, track) { selectedTrack = null }
    }
}
