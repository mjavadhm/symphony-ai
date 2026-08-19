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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.spotizer.Spotizer
import io.github.zyrouge.symphony.services.spotizer.SpotizerArtist
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack

/**
 * Full-screen view for an online artist: glass header with round picture,
 * top tracks, horizontally scrolling discography, and related artists.
 * GET /v1/artists/{id} already returns top_tracks + albums + related_artists.
 */
@Composable
fun OnlineArtistView(
    spotizer: Spotizer,
    artistId: String,
    onOpenTrack: (SpotizerTrack) -> Unit,
    onOpenAlbum: (albumId: String) -> Unit,
    onOpenArtist: (artistId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var artist by remember(artistId) { mutableStateOf<SpotizerArtist?>(null) }
    var error by remember(artistId) { mutableStateOf<String?>(null) }

    LaunchedEffect(artistId) {
        error = null
        artist = null
        try {
            artist = spotizer.client.getArtist(artistId)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    when {
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error)
        }
        artist == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            val data = artist!!
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AsyncImage(
                            model = data.bestPicture,
                            contentDescription = data.name,
                            modifier = Modifier
                                .size(160.dp)
                                .clip(CircleShape),
                        )
                        Text(
                            text = data.name ?: "Unknown",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        data.fanCount?.let {
                            Text(
                                text = "$it fans",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (data.topTracks.isNotEmpty()) {
                    item {
                        Text(
                            "Top tracks",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(data.topTracks, key = { it.id ?: it.hashCode().toString() }) { track ->
                        OnlineTrackRow(track = track, onClick = { onOpenTrack(track) })
                    }
                }

                if (data.albums.isNotEmpty()) {
                    item {
                        Text(
                            "Discography",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(data.albums, key = { it.id ?: it.hashCode().toString() }) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .glass(cornerRadius = 16.dp)
                                        .clickable { album.id?.let(onOpenAlbum) }
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AsyncImage(
                                        model = album.listCover,
                                        contentDescription = album.title,
                                        modifier = Modifier
                                            .size(114.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                    )
                                    Text(
                                        text = album.title ?: "Unknown",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            album.releaseYear,
                                            album.recordType?.uppercase(),
                                        ).joinToString(" - "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (data.relatedArtists.isNotEmpty()) {
                    item {
                        Text(
                            "Related artists",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(data.relatedArtists, key = { it.id ?: it.hashCode().toString() }) { related ->
                                Column(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .clickable { related.id?.let(onOpenArtist) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AsyncImage(
                                        model = related.listPicture,
                                        contentDescription = related.name,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape),
                                    )
                                    Text(
                                        text = related.name ?: "Unknown",
                                        style = MaterialTheme.typography.labelMedium,
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
}
