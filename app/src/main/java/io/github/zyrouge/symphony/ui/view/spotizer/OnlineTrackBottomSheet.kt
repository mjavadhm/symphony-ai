package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrack
import io.github.zyrouge.symphony.services.spotizer.SpotizerTrackStatus
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTrackBottomSheet(
    context: ViewContext,
    track: SpotizerTrack,
    onDismiss: () -> Unit,
) {
    val spotizer = context.symphony.spotizer
    var status by remember { mutableStateOf<SpotizerTrackStatus?>(null) }
    var statusFailed by remember { mutableStateOf(false) }
    var queuedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(track.id) {
        status = null
        statusFailed = false
        val trackId = track.id ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                spotizer.client.getTrackStatus(trackId, spotizer.settings.downloadQuality.value)
            }
        }.onSuccess { status = it }.onFailure { statusFailed = true }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AsyncImage(
                model = track.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Text(
                track.title ?: "Unknown title",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    track.artist,
                    track.album,
                    formatOnlineDuration(track.duration),
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            val badges = mutableListOf<String>()
            status?.let { nStatus ->
                badges += if (nStatus.cached) "\u26a1 Ready on server"
                else "\u23f3 Will be prepared on first request"
                nStatus.format?.let { badges += it.uppercase() }
                nStatus.size?.takeIf { it > 0 }?.let {
                    badges += String.format("%.1f MB", it / (1024f * 1024f))
                }
            }
            if (track.explicit == true) {
                badges += "Explicit"
            }
            if (badges.isNotEmpty()) {
                Text(
                    badges.joinToString("  \u00b7  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            if (statusFailed) {
                Text(
                    "Could not check server status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        spotizer.playStream(track)
                        onDismiss()
                    },
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(" Stream")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val queued = spotizer.downloads.enqueueTrack(track)
                        queuedMessage =
                            if (queued) "Added to downloads" else "Already in downloads"
                    },
                ) {
                    Icon(Icons.Filled.CloudDownload, null)
                    Text(" Download")
                }
            }
            queuedMessage?.let { message ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        onDismiss()
                        context.navController.navigate(SpotizerDownloadsViewRoute)
                    }) {
                        Text("Open downloads")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                track.albumId?.let { albumId ->
                    TextButton(onClick = {
                        onDismiss()
                        context.navController.navigate(OnlineAlbumViewRoute(albumId))
                    }) {
                        Text("View album")
                    }
                }
                track.artistId?.let { artistId ->
                    TextButton(onClick = {
                        onDismiss()
                        context.navController.navigate(OnlineArtistViewRoute(artistId))
                    }) {
                        Text("View artist")
                    }
                }
            }
        }
    }
}
