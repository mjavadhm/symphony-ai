package io.github.zyrouge.symphony.ui.view.nowPlaying

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.radio.RadioQueue
import io.github.zyrouge.symphony.ui.components.LocalNowPlayingAccent
import io.github.zyrouge.symphony.ui.components.SongDropdownMenu
import io.github.zyrouge.symphony.ui.helpers.FadeTransition
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.ArtistViewRoute
import io.github.zyrouge.symphony.ui.view.NowPlayingControlsLayout
import io.github.zyrouge.symphony.ui.view.NowPlayingData
import io.github.zyrouge.symphony.utils.DurationUtils

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingBodyContent(
    context: ViewContext,
    data: NowPlayingData,
    showSongInfo: Boolean = true,
) {
    val favoriteSongIds by context.symphony.groove.playlist.favorites.collectAsState()
    val isFavorite by remember(data) {
        derivedStateOf { favoriteSongIds.contains(data.song.id) }
    }

    data.run {
        Column {
            if (showSongInfo) Row {
                AnimatedContent(
                    label = "now-playing-body-content",
                    modifier = Modifier.weight(1f),
                    targetState = song,
                    transitionSpec = {
                        FadeTransition.enterTransition()
                            .togetherWith(FadeTransition.exitTransition())
                    },
                ) { targetStateSong ->
                    Column(modifier = Modifier.padding(defaultHorizontalPadding, 0.dp)) {
                        Text(
                            targetStateSong.title,
                            style = MaterialTheme.typography.headlineSmall
                                .copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        )
                        if (targetStateSong.artists.isNotEmpty()) {
                            val artistColor = LocalContentColor.current.copy(alpha = 0.7f)
                            FlowRow {
                                targetStateSong.artists.forEachIndexed { i, it ->
                                    Text(
                                        it,
                                        color = artistColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.pointerInput(Unit) {
                                            detectTapGestures { _ ->
                                                context.navController.navigate(ArtistViewRoute(it))
                                            }
                                        },
                                    )
                                    if (i != targetStateSong.artists.size - 1) {
                                        Text(", ", color = artistColor)
                                    }
                                }
                            }
                        }
                        if (data.showSongAdditionalInfo) {
                            targetStateSong.toSamplingInfoString(context.symphony)?.let {
                                val localContentColor = LocalContentColor.current
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall
                                        .copy(color = localContentColor.copy(alpha = 0.7f)),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                Row {
                    val view = LocalView.current
                    IconButton(
                        modifier = Modifier.offset(4.dp),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            context.symphony.groove.playlist.run {
                                when {
                                    isFavorite -> unfavorite(song.id)
                                    else -> favorite(song.id)
                                }
                            }
                        }
                    ) {
                        when {
                            isFavorite -> Icon(
                                Icons.Filled.Favorite,
                                null,
                                tint = LocalNowPlayingAccent.current,
                            )

                            else -> Icon(Icons.Filled.FavoriteBorder, null)
                        }
                    }

                    var showOptionsMenu by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            showOptionsMenu = !showOptionsMenu
                        }
                    ) {
                        Icon(Icons.Filled.MoreVert, null)
                        SongDropdownMenu(
                            context,
                            song,
                            isFavorite = isFavorite,
                            expanded = showOptionsMenu,
                            onDismissRequest = {
                                showOptionsMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(defaultHorizontalPadding))
            NowPlayingSeekBar(context)
            Spacer(modifier = Modifier.height(defaultHorizontalPadding + 8.dp))
            when (controlsLayout) {
                NowPlayingControlsLayout.CompactLeft -> NowPlayingCompactControls(
                    context,
                    data = data
                )

                NowPlayingControlsLayout.CompactRight -> NowPlayingCompactControls(
                    context,
                    data = data,
                    modifier = Modifier.align(Alignment.End)
                )

                NowPlayingControlsLayout.Traditional -> NowPlayingTraditionalControls(
                    context,
                    data = data,
                )
            }
            Spacer(modifier = Modifier.height(defaultHorizontalPadding))
        }
    }
}

@Composable
fun NowPlayingCompactControls(
    context: ViewContext,
    data: NowPlayingData,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(defaultHorizontalPadding, 0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NowPlayingPlayPauseButton(
            context,
            data = data,
            style = NowPlayingControlButtonStyle(
                color = NowPlayingControlButtonColor.Primary,
            ),
        )
        NowPlayingSkipPreviousButton(
            context,
            data = data,
            style = NowPlayingControlButtonStyle(
                color = NowPlayingControlButtonColor.Surface,
            ),
        )
        if (data.enableSeekControls) {
            NowPlayingFastRewindButton(
                context,
                data = data,
                style = NowPlayingControlButtonStyle(
                    color = NowPlayingControlButtonColor.Surface,
                ),
            )
            NowPlayingFastForwardButton(
                context,
                data = data,
                style = NowPlayingControlButtonStyle(
                    color = NowPlayingControlButtonColor.Surface,
                ),
            )
        }
        NowPlayingSkipNextButton(
            context,
            data = data,
            style = NowPlayingControlButtonStyle(
                color = NowPlayingControlButtonColor.Surface,
            ),
        )
    }
}

@Composable
fun NowPlayingTraditionalControls(context: ViewContext, data: NowPlayingData) {
    Row(
        modifier = Modifier
            .padding(defaultHorizontalPadding, 0.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NowPlayingShuffleButton(context, data = data)
        NowPlayingSkipPreviousButton(
            context,
            data = data,
            style = NowPlayingControlButtonStyle(
                color = NowPlayingControlButtonColor.Transparent,
                size = NowPlayingControlButtonSize.Medium,
            ),
        )
        if (data.enableSeekControls) {
            NowPlayingFastRewindButton(
                context,
                data = data,
                style = NowPlayingControlButtonStyle(
                    color = NowPlayingControlButtonColor.Transparent,
                ),
            )
        }
        NowPlayingPlayPauseButton(
            context,
            data = data,
            style = NowPlayingControlButtonStyle(
                color = NowPlayingControlButtonColor.Primary,
                size = NowPlayingControlButtonSize.Large,
            ),
        )
        if (data.enableSeekControls) {
            NowPlayingFastForwardButton(
                context,
                data = data,
                style = NowPlayingControlButtonStyle(
                    color = NowPlayingControlButtonColor.Transparent,
                ),
            )
        }
        NowPlayingSkipNextButton(
            context,
            data = data,
            style = NowPlayingControlButtonStyle(
                color = NowPlayingControlButtonColor.Transparent,
                size = NowPlayingControlButtonSize.Medium,
            ),
        )
        NowPlayingLoopButton(context, data = data)
    }
}

@Composable
private fun NowPlayingShuffleButton(context: ViewContext, data: NowPlayingData) {
    val view = LocalView.current
    val smartShuffle by context.symphony.radio.queue.smartShuffleMode.collectAsState()
    IconButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            context.symphony.radio.queue.cycleShuffleMode()
        }
    ) {
        Icon(
            when {
                data.currentShuffleMode && smartShuffle -> Icons.Filled.AutoAwesome
                else -> Icons.Filled.Shuffle
            },
            null,
            tint = when {
                data.currentShuffleMode -> LocalNowPlayingAccent.current
                else -> LocalContentColor.current.copy(alpha = 0.6f)
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun NowPlayingLoopButton(context: ViewContext, data: NowPlayingData) {
    val view = LocalView.current
    IconButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            context.symphony.radio.queue.toggleLoopMode()
        }
    ) {
        Icon(
            when (data.currentLoopMode) {
                RadioQueue.LoopMode.Song -> Icons.Filled.RepeatOne
                RadioQueue.LoopMode.Autoplay -> Icons.Filled.AllInclusive
                else -> Icons.Filled.Repeat
            },
            null,
            tint = when (data.currentLoopMode) {
                RadioQueue.LoopMode.None -> LocalContentColor.current.copy(alpha = 0.6f)
                else -> LocalNowPlayingAccent.current
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun NowPlayingSeekBar(context: ViewContext) {
    val playbackPosition by context.symphony.radio.observatory.playbackPosition.collectAsState()

    Row(
        modifier = Modifier.padding(defaultHorizontalPadding, 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var seekRatio by remember { mutableStateOf<Float?>(null) }

        NowPlayingPlaybackPositionText(
            seekRatio?.let { it * playbackPosition.total }?.toLong()
                ?: playbackPosition.played,
            Alignment.CenterStart,
        )
        Box(modifier = Modifier.weight(1f)) {
            NowPlayingSeekBar(
                ratio = playbackPosition.ratio,
                onSeekStart = {
                    seekRatio = 0f
                },
                onSeek = {
                    seekRatio = it
                },
                onSeekEnd = {
                    context.symphony.radio.seek((it * playbackPosition.total).toLong())
                    seekRatio = null
                },
                onSeekCancel = {
                    seekRatio = null
                },
            )
        }
        NowPlayingPlaybackPositionText(
            playbackPosition.total,
            Alignment.CenterEnd,
        )
    }
}

@Composable
private fun NowPlayingSeekBar(
    ratio: Float,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: (Float) -> Unit,
    onSeekCancel: () -> Unit,
) {
    val accent = LocalNowPlayingAccent.current
    val sliderHeight = 24.dp
    val thumbSize = 14.dp
    val thumbSizeHalf = thumbSize.div(2)

    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }

    // سبک اپل موزیک: track موقع لمس قطور میشه، thumb فقط موقع درگ ظاهر میشه
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) 8.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "seekbar-track-height",
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        label = "seekbar-thumb-alpha",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(sliderHeight),
        contentAlignment = Alignment.Center,
    ) {
        val sliderWidth = this@BoxWithConstraints.maxWidth

        Box(
            modifier = Modifier
                .height(sliderHeight)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val tapRatio = (offset.x / sliderWidth.toPx()).coerceIn(0f..1f)
                            onSeekEnd(tapRatio)
                        }
                    )
                }
                .pointerInput(Unit) {
                    var offsetX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            offsetX = offset.x
                            dragging = true
                            onSeekStart()
                        },
                        onDragEnd = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSeekEnd(dragRatio)
                            offsetX = 0f
                            dragging = false
                            dragRatio = 0f
                        },
                        onDragCancel = {
                            onSeekCancel()
                            offsetX = 0f
                            dragging = false
                            dragRatio = 0f
                        },
                        onHorizontalDrag = { pointer, dragAmount ->
                            pointer.consume()
                            offsetX += dragAmount
                            dragRatio = (offsetX / sliderWidth.toPx()).coerceIn(0f..1f)
                            onSeek(dragRatio)
                        },
                    )
                }
        )
        Box(
            modifier = Modifier
                .padding(thumbSizeHalf, 0.dp)
                .height(trackHeight)
                .fillMaxWidth()
                .background(
                    Color.White.copy(alpha = 0.25f),
                    RoundedCornerShape(thumbSizeHalf)
                )
        ) {
            Box(
                modifier = Modifier
                    .height(trackHeight)
                    .fillMaxWidth(if (dragging) dragRatio else ratio)
                    .background(
                        accent,
                        RoundedCornerShape(thumbSizeHalf)
                    )
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .offset(
                        sliderWidth
                            .minus(thumbSizeHalf.times(2))
                            .times(if (dragging) dragRatio else ratio),
                        0.dp
                    )
                    .graphicsLayer { alpha = thumbAlpha }
                    .background(Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun NowPlayingPlaybackPositionText(
    duration: Long,
    alignment: Alignment,
) {
    val textStyle = MaterialTheme.typography.labelMedium
    val durationFormatted = DurationUtils.formatMs(duration)

    Box(contentAlignment = alignment) {
        Text(
            "0".repeat(durationFormatted.length),
            style = textStyle.copy(color = Color.Transparent),
        )
        Text(
            durationFormatted,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun NowPlayingPlayPauseButton(
    context: ViewContext,
    data: NowPlayingData,
    style: NowPlayingControlButtonStyle,
) {
    data.run {
        NowPlayingControlButton(
            style = style,
            icon = when {
                !isPlaying -> Icons.Filled.PlayArrow
                else -> Icons.Filled.Pause
            },
            onClick = {
                context.symphony.radio.shorty.playPause()
            }
        )
    }
}

@Composable
private fun NowPlayingSkipPreviousButton(
    context: ViewContext,
    data: NowPlayingData,
    style: NowPlayingControlButtonStyle,
) {
    data.run {
        NowPlayingControlButton(
            style = style,
            icon = Icons.Filled.SkipPrevious,
            onClick = {
                context.symphony.radio.shorty.previous()
            }
        )
    }
}

@Composable
private fun NowPlayingSkipNextButton(
    context: ViewContext,
    data: NowPlayingData,
    style: NowPlayingControlButtonStyle,
) {
    data.run {
        NowPlayingControlButton(
            style = style,
            icon = Icons.Filled.SkipNext,
            onClick = {
                context.symphony.radio.shorty.skip()
            }
        )
    }
}

@Composable
private fun NowPlayingFastRewindButton(
    context: ViewContext,
    data: NowPlayingData,
    style: NowPlayingControlButtonStyle,
) {
    data.run {
        NowPlayingControlButton(
            style = style,
            icon = Icons.Filled.FastRewind,
            onClick = {
                context.symphony.radio.shorty
                    .seekFromCurrent(-seekBackDuration)
            }
        )
    }
}

@Composable
private fun NowPlayingFastForwardButton(
    context: ViewContext,
    data: NowPlayingData,
    style: NowPlayingControlButtonStyle,
) {
    data.run {
        NowPlayingControlButton(
            style = style,
            icon = Icons.Filled.FastForward,
            onClick = {
                context.symphony.radio.shorty
                    .seekFromCurrent(seekForwardDuration)
            }
        )
    }
}

private enum class NowPlayingControlButtonColor {
    Primary,
    Surface,
    Transparent,
}

private enum class NowPlayingControlButtonSize {
    Default,
    Medium,
    Large,
}

private data class NowPlayingControlButtonStyle(
    val color: NowPlayingControlButtonColor,
    val size: NowPlayingControlButtonSize = NowPlayingControlButtonSize.Default,
)

@Composable
private fun NowPlayingControlButton(
    style: NowPlayingControlButtonStyle,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    // spring scale موقع لمس — مثل تلگرام
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "control-press-scale",
    )

    val backgroundColor = when (style.color) {
        NowPlayingControlButtonColor.Primary -> Color.White
        NowPlayingControlButtonColor.Surface -> Color.White.copy(alpha = 0.15f)
        NowPlayingControlButtonColor.Transparent -> Color.Transparent
    }
    val contentColor = when (style.color) {
        NowPlayingControlButtonColor.Primary -> Color.Black.copy(alpha = 0.85f)
        else -> LocalContentColor.current
    }
    val buttonSize = when (style.size) {
        NowPlayingControlButtonSize.Default -> 48.dp
        NowPlayingControlButtonSize.Medium -> 60.dp
        NowPlayingControlButtonSize.Large -> 72.dp
    }
    val iconSize = when (style.size) {
        NowPlayingControlButtonSize.Default -> 26.dp
        NowPlayingControlButtonSize.Medium -> 36.dp
        NowPlayingControlButtonSize.Large -> 38.dp
    }

    val view = LocalView.current

    IconButton(
        modifier = Modifier
            .scale(pressScale)
            .size(buttonSize)
            .background(backgroundColor, CircleShape),
        interactionSource = interactionSource,
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
    ) {
        // morph بین play و pause
        AnimatedContent(
            label = "control-button-icon",
            targetState = icon,
            transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(initialScale = 0.6f, animationSpec = tween(150)))
                    .togetherWith(
                        fadeOut(tween(100)) + scaleOut(targetScale = 0.6f, animationSpec = tween(100))
                    )
            },
        ) { targetIcon ->
            Icon(
                targetIcon,
                null,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
