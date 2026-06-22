package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.ui.helpers.ViewContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouSongCard(
    context: ViewContext,
    song: Song,
    tileWidth: Dp,
    queue: List<String>,
    index: Int
) {
    val tileHeight = 96.dp
    val backgroundColor = MaterialTheme.colorScheme.surface

    ElevatedCard(
        modifier = Modifier
            .width(tileWidth)
            .height(tileHeight),
        onClick = {
            context.symphony.radio.shorty.playQueue(
                queue,
                options = Radio.PlayOptions(index = index),
            )
        }
    ) {
        Box {
            AsyncImage(
                song.createArtworkImageRequest(context.symphony)
                    .build(),
                null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                backgroundColor.copy(alpha = 0.2f),
                                backgroundColor.copy(alpha = 0.7f),
                                backgroundColor.copy(alpha = 0.8f),
                            ),
                        )
                    )
            )
            Row(modifier = Modifier.padding(8.dp)) {
                Box {
                    AsyncImage(
                        song.createArtworkImageRequest(context.symphony)
                            .build(),
                        null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Box(
                        modifier = Modifier.matchParentSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    backgroundColor.copy(alpha = 0.25f),
                                    CircleShape,
                                )
                                .padding(1.dp)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.artists.isNotEmpty()) {
                        Text(
                            song.artists.joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
