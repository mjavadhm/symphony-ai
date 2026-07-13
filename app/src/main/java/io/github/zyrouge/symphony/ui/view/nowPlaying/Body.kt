package io.github.zyrouge.symphony.ui.view.nowPlaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.zyrouge.symphony.ui.components.LocalNowPlayingAccent
import io.github.zyrouge.symphony.ui.components.LyricsText
import io.github.zyrouge.symphony.ui.components.NowPlayingDynamicBackground
import io.github.zyrouge.symphony.ui.components.TimedContentTextStyle
import io.github.zyrouge.symphony.ui.components.rememberArtworkAccent
import io.github.zyrouge.symphony.ui.helpers.ScreenOrientation
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.NowPlayingData
import io.github.zyrouge.symphony.ui.view.NowPlayingDefaults
import io.github.zyrouge.symphony.ui.view.NowPlayingLyricsLayout
import io.github.zyrouge.symphony.ui.view.NowPlayingStates
import kotlinx.coroutines.flow.MutableStateFlow

internal val defaultHorizontalPadding = 20.dp

@Composable
fun NowPlayingBody(context: ViewContext, data: NowPlayingData) {
    val states = remember {
        NowPlayingStates(
            showLyrics = MutableStateFlow(
                data.lyricsLayout == NowPlayingLyricsLayout.ReplaceArtwork && NowPlayingDefaults.showLyrics
            ),
        )
    }
    val accent = rememberArtworkAccent(context, data.song)
    val showLyrics by states.showLyrics.collectAsState()

    data.run {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val orientation = ScreenOrientation.fromConstraints(this@BoxWithConstraints)

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
                        if (orientation.isPortrait) {
                            NowPlayingAppBar(context)
                        }
                    },
                    content = { contentPadding ->
                        Box(modifier = Modifier.padding(contentPadding)) {
                            when (orientation) {
                                ScreenOrientation.PORTRAIT -> Column(modifier = Modifier.fillMaxSize()) {
                                    AnimatedContent(
                                        label = "now-playing-lyrics-mode",
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        targetState = showLyrics,
                                        transitionSpec = {
                                            (fadeIn(tween(350)) + scaleIn(
                                                initialScale = 0.96f,
                                                animationSpec = tween(350)
                                            )).togetherWith(fadeOut(tween(200)))
                                        },
                                    ) { targetShowLyrics ->
                                        when {
                                            targetShowLyrics -> NowPlayingLyricsMode(context, data)

                                            else -> Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(bottom = 20.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                NowPlayingBodyCover(context, data, states, orientation)
                                            }
                                        }
                                    }
                                    Column {
                                        NowPlayingBodyContent(context, data, showSongInfo = !showLyrics)
                                        NowPlayingBodyBottomBar(context, data, states)
                                    }
                                }

                                ScreenOrientation.LANDSCAPE -> Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .padding(top = 12.dp, bottom = 20.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        NowPlayingBodyCover(context, data, states, orientation)
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        Column {
                                            NowPlayingLandscapeAppBar(context)
                                            Box(modifier = Modifier.weight(1f))
                                            NowPlayingBodyContent(context, data)
                                            NowPlayingBodyBottomBar(context, data, states)
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

// حالت لیریک: کاور کوچیک بالا چپ + لیریک karaoke بدون پسزمینه
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingLyricsMode(context: ViewContext, data: NowPlayingData) {
    Column(modifier = Modifier.fillMaxSize()) {
        // هدر: thumbnail + تایتل + خواننده
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(defaultHorizontalPadding, 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                data.song.createArtworkImageRequest(context.symphony).build(),
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
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
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // لیریک بدون پنل — مستقیم روی پسزمینه، با fade لبهها
        val base = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            lineHeight = 34.sp,
            textDirection = TextDirection.Content,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.08f to Color.Black,
                            0.92f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            LyricsText(
                context,
                padding = PaddingValues(
                    horizontal = defaultHorizontalPadding,
                    vertical = 20.dp,
                ),
                style = TimedContentTextStyle(
                    highlighted = base.copy(color = Color.White.copy(alpha = 0.75f)),
                    active = base.copy(color = Color.White),
                    inactive = base.copy(color = Color.White.copy(alpha = 0.35f)),
                    spacing = 18.dp,
                    inactiveBlur = 1.5.dp,
                ),
            )
        }
    }
}
