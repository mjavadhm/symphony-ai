package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.spotizer.SpotizerArtist
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class OnlineArtistViewRoute(val artistId: Long)

@Composable
fun OnlineArtistView(context: ViewContext, route: OnlineArtistViewRoute) {
    val spotizer = context.symphony.spotizer
    var artist by remember { mutableStateOf<SpotizerArtist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTrack by remember { mutableStateOf<SpotizerTrack?>(null) }

    LaunchedEffect(route.artistId) {
        runCatching {
            withContext(Dispatchers.IO) { spotizer.client.getArtist(route.artistId) }
        }.onSuccess { artist = it }.onFailure { error = it.message ?: "Could not load artist" }
    }

    SpotizerPageScaffold(
        context = context,
        title = artist?.name ?: "Artist",
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
                    Text(error ?: "Could not load artist")
                }
            }

            artist == null -> {
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
                val nArtist = artist!!
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
                                model = nArtist.picture,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(CircleShape),
                            )
                            Text(
                                nArtist.name ?: "Unknown artist",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            nArtist.nbFans?.let {
                                Text(
                                    it.toString() + " fans",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }

                    if (nArtist.topTracks.isNotEmpty()) {
                        item {
                            Text(
                                "Top tracks",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(nArtist.topTracks.size) { index ->
                            OnlineTrackRow(context, nArtist.topTracks[index]) {
                                selectedTrack = nArtist.topTracks[index]
                            }
                        }
                    }

                    if (nArtist.albums.isNotEmpty()) {
                        item {
                            Text(
                                "Discography",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(nArtist.albums.size) { index ->
                                    val album = nArtist.albums[index]
                                    Column(
                                        modifier = Modifier
                                            .width(120.dp)
                                            .clickable {
                                                album.id?.let {
                                                    context.navController.navigate(
                                                        OnlineAlbumViewRoute(it)
                                                    )
                                                }
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AsyncImage(
                                            model = album.smallCover,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(120.dp)
                                                .clip(RoundedCornerShape(20.dp)),
                                        )
                                        Text(
                                            album.title ?: "Unknown album",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (nArtist.relatedArtists.isNotEmpty()) {
                        item {
                            Text(
                                "Related artists",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(nArtist.relatedArtists.size) { index ->
                                    val related = nArtist.relatedArtists[index]
                                    Column(
                                        modifier = Modifier
                                            .width(100.dp)
                                            .clickable {
                                                related.id?.let {
                                                    context.navController.navigate(
                                                        OnlineArtistViewRoute(it)
                                                    )
                                                }
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AsyncImage(
                                            model = related.smallPicture,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(90.dp)
                                                .clip(CircleShape),
                                        )
                                        Text(
                                            related.name ?: "Unknown artist",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
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

    selectedTrack?.let { track ->
        OnlineTrackBottomSheet(context, track) { selectedTrack = null }
    }
}
