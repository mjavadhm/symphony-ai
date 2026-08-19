package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import io.github.zyrouge.symphony.ui.helpers.ViewContext

/**
 * Shared skeleton for the detail screens (album/artist/playlist/genre):
 * a blurred background taken from the artwork, a Telegram-style top bar, and full-screen content.
 */
@Composable
fun GlassDetailScaffold(
    context: ViewContext,
    title: String,
    backgroundImage: ImageRequest?,
    topBarActions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            ArtworkDynamicBackground(backgroundImage)
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                        ) {
                            IconButton(
                                modifier = Modifier.size(44.dp),
                                onClick = { context.navController.popBackStack() },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassSurface(
                                modifier = Modifier.height(44.dp),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                            }
                        }
                        when {
                            topBarActions != null -> GlassSurface(
                                modifier = Modifier.size(44.dp),
                                shape = CircleShape,
                            ) {
                                topBarActions()
                            }

                            else -> Spacer(modifier = Modifier.size(44.dp))
                        }
                    }
                },
                content = { contentPadding ->
                    CompositionLocalProvider(LocalHomeContentPadding provides contentPadding) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            content()
                        }
                    }
                },
                bottomBar = {
                    AnimatedNowPlayingBottomBar(context)
                },
            )
        }
    }
}
