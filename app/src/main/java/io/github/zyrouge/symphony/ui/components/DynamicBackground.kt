package io.github.zyrouge.symphony.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The accent color extracted from the artwork — used all across Now Playing.
 */
val LocalNowPlayingAccent = compositionLocalOf { Color.White }

/**
 * Dynamic background: blurred artwork + extra saturation + a dark gradient.
 * On Android < 12 the blur is not applied (fallback: scaled-up artwork with a darker gradient).
 */
@Composable
fun NowPlayingDynamicBackground(
    context: ViewContext,
    song: Song,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // fallback for when there is no artwork
    ) {
        AnimatedContent(
            label = "now-playing-dynamic-background",
            targetState = song,
            transitionSpec = {
                fadeIn(tween(600)).togetherWith(fadeOut(tween(600)))
            },
        ) { targetSong ->
            AsyncImage(
                targetSong
                    .createArtworkImageRequest(context.symphony)
                    .build(),
                null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToSaturation(1.6f) }
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Scale up so the blurred edges stay clean
                        scaleX = 1.25f
                        scaleY = 1.25f
                    }
                    .blur(70.dp)
            )
        }
        // Dark gradient: lighter at the top, darker at the bottom — keeps text and controls readable
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.8f),
                        )
                    )
                )
        )
    }
}

/**
 * Pulls the dominant color out of the artwork with Palette and brightens it so it stays
 * readable against the dark background.
 */
@Composable
fun rememberArtworkAccent(context: ViewContext, song: Song): Color {
    var accent by remember { mutableStateOf(Color.White) }

    LaunchedEffect(song.id) {
        accent = try {
            val request = song
                .createArtworkImageRequest(context.symphony)
                .allowHardware(false)
                .build()
            val drawable = context.activity.imageLoader.execute(request).drawable
            val bitmap = (drawable as? BitmapDrawable)?.bitmap
            bitmap?.let {
                val palette = withContext(Dispatchers.Default) {
                    Palette.from(it).generate()
                }
                val raw = palette.getVibrantColor(
                    palette.getLightVibrantColor(
                        palette.getLightMutedColor(android.graphics.Color.WHITE)
                    )
                )
                lerp(Color(raw), Color.White, 0.25f)
            } ?: Color.White
        } catch (_: Exception) {
            Color.White
        }
    }
    return accent
}

/**
 * A "faded" dynamic background for the library screens —
 * the currently playing song's artwork only adds a subtle tint, so lists stay readable.
 */
@Composable
fun HomeDynamicBackground(
    context: ViewContext,
    modifier: Modifier = Modifier,
) {
    val queue by context.symphony.radio.observatory.queue.collectAsState()
    val queueIndex by context.symphony.radio.observatory.queueIndex.collectAsState()
    val currentSong by remember(queue, queueIndex) {
        derivedStateOf {
            queue.getOrNull(queueIndex)?.let { context.symphony.groove.song.get(it) }
        }
    }
    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surface)
            .hazeSource(state = LocalHazeState.current)
    ) {
        AnimatedContent(
            label = "home-dynamic-background",
            targetState = currentSong,
            transitionSpec = {
                fadeIn(tween(800)).togetherWith(fadeOut(tween(800)))
            },
        ) { targetSong ->
            targetSong?.let { song ->
                AsyncImage(
                    song.createArtworkImageRequest(context.symphony).build(),
                    null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(
                        ColorMatrix().apply { setToSaturation(1.4f) }
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.3f
                            scaleY = 1.3f
                            alpha = 0.5f
                        }
                        .blur(100.dp)
                )
            } ?: Box(modifier = Modifier.fillMaxSize())
        }
        // A strong scrim in the theme color — darker at the bottom so the pills read more like glass
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            surface.copy(alpha = 0.72f),
                            surface.copy(alpha = 0.8f),
                            surface.copy(alpha = 0.88f),
                        )
                    )
                )
        )
    }
}

/**
 * Dynamic background for the detail screens —
 * the album's or artist's own artwork gets blurred, not the currently playing song's.
 */
@Composable
fun ArtworkDynamicBackground(
    image: ImageRequest?,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surface)
            .hazeSource(state = LocalHazeState.current)
    ) {
        image?.let {
            AsyncImage(
                it,
                null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToSaturation(1.4f) }
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                        alpha = 0.5f
                    }
                    .blur(100.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            surface.copy(alpha = 0.72f),
                            surface.copy(alpha = 0.8f),
                            surface.copy(alpha = 0.88f),
                        )
                    )
                )
        )
    }
}
