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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * رنگ accent استخراجشده از کاور — همهجای Now Playing از این استفاده میکنه.
 */
val LocalNowPlayingAccent = compositionLocalOf { Color.White }

/**
 * پسزمینهی داینامیک: کاور بلورشده + saturation بیشتر + گرادیان تیره.
 * روی اندروید < 12 بلور اعمال نمیشه (fallback: کاور بزرگشده + گرادیان تیرهتر).
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
            .background(Color(0xFF121212)) // fallback وقتی کاور نیست
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
                        // بزرگنمایی که لبههای بلور تمیز بمونن
                        scaleX = 1.25f
                        scaleY = 1.25f
                    }
                    .blur(70.dp)
            )
        }
        // گرادیان تیره: بالا روشنتر، پایین تیرهتر — برای خوانایی متن و کنترلها
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
 * رنگ غالب کاور رو با Palette درمیاره و برای خوانایی روی پسزمینهی تیره روشنش میکنه.
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
