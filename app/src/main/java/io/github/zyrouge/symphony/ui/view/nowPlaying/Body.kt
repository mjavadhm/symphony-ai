package io.github.zyrouge.symphony.ui.view.nowPlaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.components.LocalNowPlayingAccent
import io.github.zyrouge.symphony.ui.components.NowPlayingDynamicBackground
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
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .padding(bottom = 20.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        NowPlayingBodyCover(context, data, states, orientation)
                                    }
                                    Column {
                                        NowPlayingBodyContent(context, data)
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
