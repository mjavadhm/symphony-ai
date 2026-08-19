package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.spotizer.SpotizerAlbum
import io.github.zyrouge.symphony.services.spotizer.SpotizerArtist
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.helpers.ViewContext

/** "3:45" style duration, or null when unknown. */
internal fun formatOnlineDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) {
        return null
    }
    val minutes = seconds / 60
    val rest = seconds % 60
    return minutes.toString() + ":" + rest.toString().padStart(2, '0')
}

@Composable
internal fun OnlineGlassChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    GlassSurface(shape = RoundedCornerShape(50)) {
        FilterChip(
            selected = selected,
            label = { Text(label) },
            onClick = onClick,
            shape = RoundedCornerShape(50),
            border = null,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
            ),
        )
    }
}

/** Track / Album / Artist selector chips for the online search mode. */
@Composable
fun OnlineSearchKindChips(state: OnlineSearchState) {
    val kind by state.kind.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OnlineGlassChip(
            selected = kind == OnlineSearchState.Kind.Track,
            label = "Tracks",
        ) { state.setKind(OnlineSearchState.Kind.Track) }
        OnlineGlassChip(
            selected = kind == OnlineSearchState.Kind.Album,
            label = "Albums",
        ) { state.setKind(OnlineSearchState.Kind.Album) }
        OnlineGlassChip(
            selected = kind == OnlineSearchState.Kind.Artist,
            label = "Artists",
        ) { state.setKind(OnlineSearchState.Kind.Artist) }
    }
}

@Composable
fun OnlineTrackRow(
    context: ViewContext,
    track: SpotizerTrack,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = track.smallCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
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
                        if (track.explicit == true) "E" else null,
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { context.symphony.spotizer.downloads.enqueueTrack(track) },
            ) {
                Icon(
                    Icons.Filled.CloudDownload,
                    "Download",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OnlineAlbumRow(context: ViewContext, album: SpotizerAlbum) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    album.id?.let {
                        context.navController.navigate(OnlineAlbumViewRoute(it))
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = album.smallCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    album.title ?: "Unknown album",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        album.artist,
                        album.nbTracks?.let { it.toString() + " tracks" },
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

@Composable
private fun OnlineArtistRow(context: ViewContext, artist: SpotizerArtist) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    artist.id?.let {
                        context.navController.navigate(OnlineArtistViewRoute(it))
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = artist.smallPicture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    artist.name ?: "Unknown artist",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                artist.nbFans?.let {
                    Text(
                        it.toString() + " fans",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** Online search results body, rendered instead of the local results. */
@Composable
fun OnlineSearchBody(
    context: ViewContext,
    state: OnlineSearchState,
    contentPadding: PaddingValues,
    onOpenTrack: (SpotizerTrack) -> Unit,
) {
    val kind by state.kind.collectAsState()
    val tracks by state.tracks.collectAsState()
    val albums by state.albums.collectAsState()
    val artists by state.artists.collectAsState()
    val isLoading by state.isLoading.collectAsState()
    val error by state.error.collectAsState()
    val canLoadMore by state.canLoadMore.collectAsState()

    val isEmpty = when (kind) {
        OnlineSearchState.Kind.Track -> tracks.isEmpty()
        OnlineSearchState.Kind.Album -> albums.isEmpty()
        OnlineSearchState.Kind.Artist -> artists.isEmpty()
    }

    when {
        error != null && isEmpty -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTextBody(
                        icon = { modifier ->
                            Icon(Icons.Filled.CloudOff, null, modifier = modifier)
                        },
                        content = { Text(error ?: "Search failed") },
                    )
                    TextButton(onClick = { state.retry() }) {
                        Text("Retry")
                    }
                }
            }
        }

        isLoading && isEmpty -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        isEmpty -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                IconTextBody(
                    icon = { modifier ->
                        Icon(Icons.Filled.Public, null, modifier = modifier)
                    },
                    content = {
                        Text(
                            if (state.currentQuery.isBlank()) "Search Spotizer for tracks, albums, and artists"
                            else "No results found"
                        )
                    },
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = LocalHazeState.current, zIndex = 1f),
                contentPadding = contentPadding,
            ) {
                when (kind) {
                    OnlineSearchState.Kind.Track -> items(tracks.size) { index ->
                        val track = tracks[index]
                        OnlineTrackRow(context, track) { onOpenTrack(track) }
                    }

                    OnlineSearchState.Kind.Album -> items(albums.size) { index ->
                        OnlineAlbumRow(context, albums[index])
                    }

                    OnlineSearchState.Kind.Artist -> items(artists.size) { index ->
                        OnlineArtistRow(context, artists[index])
                    }
                }
                if (canLoadMore || isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            } else {
                                TextButton(onClick = { state.loadMore() }) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
