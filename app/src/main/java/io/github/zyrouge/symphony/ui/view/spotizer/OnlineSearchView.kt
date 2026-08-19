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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.spotizer.SpotizerAlbum
import io.github.zyrouge.symphony.services.spotizer.SpotizerArtist
import io.github.zyrouge.symphony.services.spotizer.SpotizerSearchType
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack

/**
 * The Online tab body for Symphony's SearchView.
 *
 * Symphony's existing search bar stays as-is; add a Local/Online source switch
 * next to it and render this composable when Online is selected (see
 * INTEGRATION.md for the exact SearchView.kt hook).
 */
@Composable
fun OnlineSearchView(
    state: OnlineSearchState,
    onOpenTrack: (SpotizerTrack) -> Unit,
    onOpenAlbum: (SpotizerAlbum) -> Unit,
    onOpenArtist: (SpotizerArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchType by state.searchType.collectAsState()
    val results by state.results.collectAsState()
    val loading by state.loading.collectAsState()
    val loadingMore by state.loadingMore.collectAsState()
    val error by state.error.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // --- Track / Album / Artist filter chips ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpotizerSearchType.entries.forEach { type ->
                val selected = type == searchType
                Text(
                    text = when (type) {
                        SpotizerSearchType.Track -> "Tracks"
                        SpotizerSearchType.Album -> "Albums"
                        SpotizerSearchType.Artist -> "Artists"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .glassChip(selected)
                        .clickable { state.onTypeChanged(type) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { state.retry() }) { Text("Retry") }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (searchType) {
                    SpotizerSearchType.Track -> items(results.tracks, key = { it.id ?: it.hashCode().toString() }) { track ->
                        OnlineTrackRow(track = track, onClick = { onOpenTrack(track) })
                    }
                    SpotizerSearchType.Album -> items(results.albums, key = { it.id ?: it.hashCode().toString() }) { album ->
                        OnlineAlbumRow(album = album, onClick = { onOpenAlbum(album) })
                    }
                    SpotizerSearchType.Artist -> items(results.artists, key = { it.id ?: it.hashCode().toString() }) { artist ->
                        OnlineArtistRow(artist = artist, onClick = { onOpenArtist(artist) })
                    }
                }
                if (results.canLoadMore) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
                            else TextButton(onClick = { state.loadMore() }) { Text("Load more") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineTrackRow(
    track: SpotizerTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass()
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = track.listCover,
            contentDescription = track.title,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title ?: "Unknown",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(track.artist, track.album).joinToString(" - "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        track.duration?.let {
            Text(
                text = formatDuration(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun OnlineAlbumRow(
    album: SpotizerAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass()
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = album.listCover,
            contentDescription = album.title,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = album.title ?: "Unknown",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    album.artist,
                    album.releaseYear,
                    album.recordType?.uppercase(),
                    album.trackCount?.let { "$it tracks" },
                ).joinToString(" - "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun OnlineArtistRow(
    artist: SpotizerArtist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass()
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = artist.listPicture,
            contentDescription = artist.name,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = artist.name ?: "Unknown",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            artist.fanCount?.let {
                Text(
                    text = "$it fans",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
