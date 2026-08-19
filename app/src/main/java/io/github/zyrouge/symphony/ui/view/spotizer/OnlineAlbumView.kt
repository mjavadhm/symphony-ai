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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.spotizer.Spotizer
import io.github.zyrouge.symphony.services.spotizer.SpotizerAlbum
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack
import kotlinx.coroutines.launch

/**
 * Full-screen view for an online album: glass header with big cover,
 * track list, and a "Download album" button that enqueues every track
 * (skipping ones already on the device when the setting is on).
 */
@Composable
fun OnlineAlbumView(
    spotizer: Spotizer,
    albumId: String,
    onOpenTrack: (SpotizerTrack) -> Unit,
    onOpenArtist: (artistId: String) -> Unit,
    snackbar: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
) {
    var album by remember(albumId) { mutableStateOf<SpotizerAlbum?>(null) }
    var error by remember(albumId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(albumId) {
        error = null
        album = null
        try {
            album = spotizer.client.getAlbum(albumId)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    when {
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error)
        }
        album == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            val data = album!!
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
                            model = data.bestCover,
                            contentDescription = data.title,
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(24.dp)),
                        )
                        Text(
                            text = data.title ?: "Unknown",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = listOfNotNull(
                                data.artist,
                                data.releaseYear,
                                data.trackCount?.let { "$it tracks" },
                            ).joinToString(" - "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                data.artistId?.let(onOpenArtist)
                            },
                        )
                        FilledTonalButton(onClick = {
                            val (queued, skipped) = spotizer.downloads.enqueueAlbum(data)
                            scope.launch {
                                snackbar?.showSnackbar(
                                    buildString {
                                        append("${queued.size} track(s) queued")
                                        if (skipped.isNotEmpty()) append(", ${skipped.size} already on device")
                                    }
                                )
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Text("  Download album")
                        }
                    }
                }
                itemsIndexed(data.tracks, key = { i, t -> t.id ?: i.toString() }) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(cornerRadius = 14.dp)
                            .clickable { onOpenTrack(track) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title ?: "Unknown",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            track.artist?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
            }
        }
    }
}
