package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.spotizer.Spotizer
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack

/**
 * Bottom sheet shown when tapping an online track: big cover, server cache
 * state badge (instant vs needs preparation), Play (stream) and Download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTrackBottomSheet(
    spotizer: Spotizer,
    track: SpotizerTrack,
    onDismiss: () -> Unit,
    /** Wire to the player: receives the stream URL (Range/seek supported). */
    onPlay: (streamUrl: String, track: SpotizerTrack) -> Unit,
    onOpenAlbum: ((albumId: String) -> Unit)? = null,
    onOpenArtist: ((artistId: String) -> Unit)? = null,
) {
    var cachedOnServer by remember(track.id) { mutableStateOf<Boolean?>(null) }
    var existsLocally by remember(track.id) { mutableStateOf(false) }

    LaunchedEffect(track.id) {
        val id = track.id ?: return@LaunchedEffect
        cachedOnServer = runCatching {
            spotizer.client.getTrackStatus(id, spotizer.settings.downloadQuality.value).cached
        }.getOrNull()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = track.bestCover,
                contentDescription = track.title,
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Text(
                text = track.title ?: "Unknown",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = listOfNotNull(track.artist, track.album).joinToString(" - "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // server cache badge: instant vs will-be-prepared
            when (cachedOnServer) {
                true -> Text(
                    text = "\u26A1 Ready on server - instant download",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                false -> Text(
                    text = "\u23F3 Will be prepared on server first",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                null -> {}
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        spotizer.streamUrl(track)?.let { onPlay(it, track) }
                        onDismiss()
                    },
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("  Play")
                }
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = !existsLocally,
                    onClick = {
                        spotizer.downloads.enqueueTrack(track)
                        existsLocally = true // reflect "queued" state on the button
                    },
                ) {
                    if (existsLocally) {
                        Icon(Icons.Default.DownloadDone, contentDescription = null)
                        Text("  Queued")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text("  Download")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                track.albumId?.let { albumId ->
                    if (onOpenAlbum != null) {
                        androidx.compose.material3.TextButton(onClick = {
                            onDismiss()
                            onOpenAlbum(albumId)
                        }) { Text("View album") }
                    }
                }
                track.artistId?.let { artistId ->
                    if (onOpenArtist != null) {
                        androidx.compose.material3.TextButton(onClick = {
                            onDismiss()
                            onOpenArtist(artistId)
                        }) { Text("View artist") }
                    }
                }
            }
        }
    }
}
