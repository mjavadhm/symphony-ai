package io.github.zyrouge.symphony.ui.view.nowPlaying

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.launch

internal val defaultHorizontalPadding = 20.dp

// Smooth motion between the artwork and the lyrics — letting go snaps to the closest state
private val lyricsRevealSpec = spring<Float>(
    dampingRatio = 0.85f,
    stiffness = Spring.StiffnessMediumLow,
)

// A vertical drag has to cover at least this fraction of the height for the screen to close
private const val DISMISS_DRAG_FRACTION = 0.15f

// Distance required to travel from the artwork to the lyrics (relative to the container height)
private const val LYRICS_DRAG_FRACTION = 0.55f

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
                                    NowPlayingLyricsSwitcher(
                                        context,
                                        data = data,
                                        states = states,
                                        orientation = orientation,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                    )
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

/**
 * The artwork and the lyrics are stacked on top of each other and driven by a single progress
 * value between 0 and 1:
 * - dragging the artwork up → the lyrics come up
 * - dragging the lyrics down → the lyrics close
 * - dragging the artwork down (while the lyrics are closed) → the screen closes
 *
 * Because progress follows the finger directly, the motion is continuous and reversible, and
 * letting go springs it to the nearest state.
 */
@Composable
private fun NowPlayingLyricsSwitcher(
    context: ViewContext,
    data: NowPlayingData,
    states: NowPlayingStates,
    orientation: ScreenOrientation,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val showLyrics by states.showLyrics.collectAsState()
    val lyricsEnabled = data.lyricsLayout == NowPlayingLyricsLayout.ReplaceArtwork
    val progress = remember { Animatable(if (states.showLyrics.value) 1f else 0f) }
    var containerHeightPx by remember { mutableStateOf(1f) }

    // Stay in sync with the lyrics button in the bottom bar
    LaunchedEffect(showLyrics) {
        val target = if (showLyrics) 1f else 0f
        if (progress.value != target) {
            progress.animateTo(target, animationSpec = lyricsRevealSpec)
        }
    }

    val settle: (Float) -> Unit = { target ->
        coroutineScope.launch {
            progress.animateTo(target, animationSpec = lyricsRevealSpec)
        }
        val nShowLyrics = target > 0.5f
        if (states.showLyrics.value != nShowLyrics) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            states.showLyrics.value = nShowLyrics
            NowPlayingDefaults.showLyrics = nShowLyrics
        }
    }

    val revealProgress = progress.value

    Box(
        modifier = modifier
            .onSizeChanged {
                containerHeightPx = it.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(lyricsEnabled) {
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        totalDrag = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                        if (lyricsEnabled) {
                            coroutineScope.launch {
                                val next = progress.value -
                                        (dragAmount / (containerHeightPx * LYRICS_DRAG_FRACTION))
                                progress.snapTo(next.coerceIn(0f, 1f))
                            }
                        }
                    },
                    onDragEnd = {
                        val current = progress.value
                        val wasShowingLyrics = states.showLyrics.value
                        when {
                            // Dragging the artwork down → close the now playing screen
                            !wasShowingLyrics && current <= 0.02f &&
                                    totalDrag > containerHeightPx * DISMISS_DRAG_FRACTION -> {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                context.navController.popBackStack()
                            }

                            !lyricsEnabled -> Unit

                            // While the lyrics are open, closing them takes a longer downward drag
                            wasShowingLyrics -> settle(if (current > 0.65f) 1f else 0f)

                            else -> settle(if (current > 0.35f) 1f else 0f)
                        }
                    },
                    onDragCancel = {
                        if (lyricsEnabled) {
                            settle(if (states.showLyrics.value) 1f else 0f)
                        }
                    },
                )
            },
    ) {
        if (revealProgress < 0.999f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
                    .graphicsLayer {
                        alpha = 1f - revealProgress
                        translationY = -revealProgress * size.height * 0.12f
                        val coverScale = 1f - (0.12f * revealProgress)
                        scaleX = coverScale
                        scaleY = coverScale
                    },
                contentAlignment = Alignment.Center,
            ) {
                NowPlayingBodyCover(
                    context,
                    data,
                    states,
                    orientation,
                    verticalSwipeToDismiss = false,
                )
            }
        }
        if (revealProgress > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = revealProgress
                        translationY = (1f - revealProgress) * size.height * 0.1f
                    },
            ) {
                NowPlayingLyricsMode(context, data)
            }
        }
    }
}

// Lyrics mode: a small cover at the top left plus karaoke lyrics with no panel behind them
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingLyricsMode(
    context: ViewContext,
    data: NowPlayingData,
    coverModifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header: thumbnail + title + artist
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
                modifier = coverModifier
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
        // Lyrics without a panel — drawn straight onto the background, with faded edges
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
