package io.github.zyrouge.symphony.ui.view.spotizer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.spotizer.SpotizerStreamPlayer
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.helpers.ViewContext

/**
 * Glass mini-player bar for Spotizer streaming. Renders nothing when no
 * online track is loaded, so it can always sit above the local now-playing bar.
 */
@Composable
fun SpotizerStreamBar(context: ViewContext) {
    val player = context.symphony.spotizer.player
    val track by player.track.collectAsState()
    val state by player.state.collectAsState()
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()

    val nTrack = track ?: return

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = nTrack.smallCover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        nTrack.title ?: "Unknown title",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(nTrack.artist, "Spotizer").joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (state) {
                    SpotizerStreamPlayer.State.Buffering -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(28.dp)
                                .padding(2.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    else -> {
                        IconButton(onClick = { player.toggle() }) {
                            Icon(
                                if (state == SpotizerStreamPlayer.State.Playing) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                if (state == SpotizerStreamPlayer.State.Playing) "Pause" else "Play",
                            )
                        }
                    }
                }
                IconButton(onClick = { player.stop() }) {
                    Icon(Icons.Filled.Close, "Stop")
                }
            }
            if (durationMs > 0L) {
                Slider(
                    value = if (isDragging) dragPosition
                    else positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = {
                        isDragging = true
                        dragPosition = it
                    },
                    onValueChangeFinished = {
                        player.seekTo(dragPosition.toLong())
                        isDragging = false
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                )
            }
        }
    }
}
