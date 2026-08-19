package io.github.zyrouge.symphony.ui.view

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.ui.components.KeepScreenAwake
import io.github.zyrouge.symphony.ui.components.LocalNowPlayingAccent
import io.github.zyrouge.symphony.ui.components.LyricsText
import io.github.zyrouge.symphony.ui.components.NowPlayingDynamicBackground
import io.github.zyrouge.symphony.ui.components.TimedContentTextStyle
import io.github.zyrouge.symphony.ui.components.rememberArtworkAccent
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.nowPlaying.NothingPlaying
import io.github.zyrouge.symphony.ui.view.nowPlaying.NowPlayingSeekBar
import io.github.zyrouge.symphony.ui.view.nowPlaying.defaultHorizontalPadding
import kotlinx.serialization.Serializable

@Serializable
object LyricsViewRoute

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsView(context: ViewContext) {
    val keepScreenAwake by context.symphony.settings.lyricsKeepScreenAwake.flow.collectAsState()

    if (keepScreenAwake) {
        KeepScreenAwake()
    }

    NowPlayingObserver(context) { data ->
        when {
            data != null -> {
                val accent = rememberArtworkAccent(context, data.song)

                Box(modifier = Modifier.fillMaxSize()) {
                    // Seamless continuity with the player: the very same blurred background
                    NowPlayingDynamicBackground(context, song = data.song)

                    CompositionLocalProvider(
                        LocalContentColor provides Color.White,
                        LocalNowPlayingAccent provides accent,
                    ) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            topBar = {
                                LyricsHeader(context, data)
                            },
                        ) { contentPadding ->
                            Column(
                                modifier = Modifier
                                    .padding(contentPadding)
                                    .fillMaxSize(),
                            ) {
                                // The lyrics area, with a gradient fade along the top and bottom edges
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    0f to Color.Transparent,
                                                    0.07f to Color.Black,
                                                    0.93f to Color.Black,
                                                    1f to Color.Transparent,
                                                ),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                ) {
                                    LyricsText(
                                        context,
                                        style = karaokeLyricsStyle(),
                                        padding = PaddingValues(
                                            horizontal = defaultHorizontalPadding,
                                            vertical = 24.dp,
                                        ),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                NowPlayingSeekBar(context)
                                Spacer(modifier = Modifier.height(12.dp))
                                LyricsPillControls(context, data)
                                Spacer(modifier = Modifier.height(defaultHorizontalPadding))
                            }
                        }
                    }
                }
            }

            else -> NothingPlaying(context)
        }
    }
}

/**
 * Apple-style karaoke look: every line is large and bold, the active line is pure white,
 * already-sung lines are slightly dimmed, and upcoming lines are semi-transparent with a
 * gentle blur.
 * TextDirection.Content means each line is aligned right-to-left or left-to-right based on
 * its own language.
 */
@Composable
private fun karaokeLyricsStyle(): TimedContentTextStyle {
    val base = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        lineHeight = 34.sp,
        textDirection = TextDirection.Content,
    )
    return TimedContentTextStyle(
        active = base.copy(color = Color.White),
        highlighted = base.copy(color = Color.White.copy(alpha = 0.75f)),
        inactive = base.copy(color = Color.White.copy(alpha = 0.35f)),
        spacing = 18.dp,
        inactiveBlur = 1.5.dp,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricsHeader(context: ViewContext, data: NowPlayingData) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Entry transition: the header (the small cover) slides in from the top
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(350)) + slideInVertically(
            initialOffsetY = { -it / 2 },
            animationSpec = tween(350),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(defaultHorizontalPadding, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                data.song
                    .createArtworkImageRequest(context.symphony)
                    .build(),
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    data.song.title,
                    style = MaterialTheme.typography.titleMedium
                        .copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                if (data.song.artists.isNotEmpty()) {
                    Text(
                        data.song.artists.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = LocalContentColor.current.copy(alpha = 0.7f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                modifier = Modifier.background(Color.White.copy(alpha = 0.12f), CircleShape),
                onClick = {
                    context.navController.popBackStack()
                }
            ) {
                Icon(Icons.Filled.ExpandMore, null)
            }
        }
    }
}

@Composable
private fun LyricsPillControls(context: ViewContext, data: NowPlayingData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    context.symphony.radio.shorty.previous()
                }
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    null,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White, CircleShape),
                onClick = {
                    context.symphony.radio.shorty.playPause()
                }
            ) {
                AnimatedContent(
                    label = "lyrics-play-pause",
                    targetState = data.isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(initialScale = 0.6f, animationSpec = tween(150)))
                            .togetherWith(
                                fadeOut(tween(100)) + scaleOut(targetScale = 0.6f, animationSpec = tween(100))
                            )
                    },
                ) { playing ->
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null,
                        tint = Color.Black.copy(alpha = 0.85f),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    context.symphony.radio.shorty.skip()
                }
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    null,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}
