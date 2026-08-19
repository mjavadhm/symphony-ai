package io.github.zyrouge.symphony.ui.view.nowPlaying

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MotionPhotosPaused
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.components.DeleteSongFromDeviceDialog
import io.github.zyrouge.symphony.ui.components.LocalNowPlayingAccent
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.LyricsViewRoute
import io.github.zyrouge.symphony.ui.view.NowPlayingData
import io.github.zyrouge.symphony.ui.view.NowPlayingDefaults
import io.github.zyrouge.symphony.ui.view.NowPlayingLyricsLayout
import io.github.zyrouge.symphony.ui.view.NowPlayingStates
import io.github.zyrouge.symphony.ui.view.QueueViewRoute
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.launch

@Composable
fun NowPlayingBodyBottomBar(
    context: ViewContext,
    data: NowPlayingData,
    states: NowPlayingStates,
) {
    var showExtraOptions by remember { mutableStateOf(false) }

    data.run {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // صف پخش — چپ
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable {
                        context.navController.navigate(QueueViewRoute)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "${currentSongIndex + 1}/$queueSize",
                    style = MaterialTheme.typography.labelLarge
                        .copy(fontWeight = FontWeight.Bold),
                )
            }
            // تنطیمات پخش — وسط
            IconButton(
                modifier = Modifier.background(
                    Color.White.copy(alpha = 0.12f),
                    CircleShape,
                ),
                onClick = { showExtraOptions = true },
            ) {
                Icon(Icons.Filled.MoreHoriz, null)
            }
            // لیریک — راست
            states.showLyrics.let { showLyricsState ->
                val showLyrics by showLyricsState.collectAsState()

                IconButton(
                    modifier = Modifier.background(
                        Color.White.copy(alpha = if (showLyrics) 0.25f else 0.12f),
                        CircleShape,
                    ),
                    onClick = {
                        when (lyricsLayout) {
                            NowPlayingLyricsLayout.ReplaceArtwork -> {
                                val nShowLyrics = !showLyricsState.value
                                showLyricsState.value = nShowLyrics
                                NowPlayingDefaults.showLyrics = nShowLyrics
                            }

                            NowPlayingLyricsLayout.SeparatePage -> {
                                context.navController.navigate(LyricsViewRoute)
                            }
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Article,
                        null,
                        tint = when {
                            showLyrics -> LocalNowPlayingAccent.current
                            else -> LocalContentColor.current
                        }
                    )
                }
            }
        }
    }

    NowPlayingExtraOptions(
        context,
        data = data,
        visible = showExtraOptions,
        onDismissRequest = { showExtraOptions = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingExtraOptions(
    context: ViewContext,
    data: NowPlayingData,
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val equalizerActivity = rememberLauncherForActivityResult(
        context.symphony.radio.session.createEqualizerActivityContract()
    ) {}

    val sleepTimer by context.symphony.radio.observatory.sleepTimer.collectAsState()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showPitchDialog by remember { mutableStateOf(false) }
    var showDeleteFromDeviceDialog by remember { mutableStateOf(false) }

    data.run {
        if (visible) {
            val sheetState = rememberModalBottomSheetState()
            val closeBottomSheet = {
                onDismissRequest()
                coroutineScope.launch {
                    sheetState.hide()
                }
            }

            ModalBottomSheet(
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                onDismissRequest = onDismissRequest,
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        modifier = Modifier.clickable {
                            closeBottomSheet()
                            try {
                                equalizerActivity.launch()
                            } catch (err: Exception) {
                                Logger.error(
                                    "NowPlayingBottomBar",
                                    "launching equalizer failed",
                                    err
                                )
                                Toast.makeText(
                                    context.activity,
                                    context.symphony.t.LaunchingEqualizerFailedX(
                                        err.localizedMessage ?: err.toString()
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Filled.GraphicEq, null)
                        },
                        headlineContent = {
                            Text(context.symphony.t.Equalizer)
                        },
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            closeBottomSheet()
                            context.symphony.radio.setPauseOnCurrentSongEnd(!pauseOnCurrentSongEnd)
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.MotionPhotosPaused,
                                null,
                                tint = when {
                                    pauseOnCurrentSongEnd -> MaterialTheme.colorScheme.primary
                                    else -> LocalContentColor.current
                                }
                            )
                        },
                        headlineContent = {
                            Text(context.symphony.t.PauseOnCurrentSongEnd)
                        },
                        supportingContent = {
                            Text(
                                if (pauseOnCurrentSongEnd) context.symphony.t.Enabled
                                else context.symphony.t.Disabled
                            )
                        },
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            closeBottomSheet()
                            showSleepTimerDialog = !showSleepTimerDialog
                        },
                        leadingContent = {
                            Icon(
                                Icons.Outlined.Timer,
                                null,
                                tint = when {
                                    hasSleepTimer -> MaterialTheme.colorScheme.primary
                                    else -> LocalContentColor.current
                                }
                            )
                        },
                        headlineContent = {
                            Text(context.symphony.t.SleepTimer)
                        },
                        supportingContent = {
                            Text(
                                if (hasSleepTimer) context.symphony.t.Enabled
                                else context.symphony.t.Disabled
                            )
                        },
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            closeBottomSheet()
                            showSpeedDialog = !showSpeedDialog
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Speed, null)
                        },
                        headlineContent = {
                            Text(context.symphony.t.Speed)
                        },
                        supportingContent = {
                            Text("x${data.currentSpeed}")
                        },
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            closeBottomSheet()
                            showPitchDialog = !showPitchDialog
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Speed, null)
                        },
                        headlineContent = {
                            Text(context.symphony.t.Pitch)
                        },
                        supportingContent = {
                            Text("x${data.currentPitch}")
                        },
                    )
                    ListItem(
                        modifier = Modifier.clickable {
                            closeBottomSheet()
                            showDeleteFromDeviceDialog = true
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.DeleteForever,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        headlineContent = {
                            Text(
                                "Delete from device",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        supportingContent = {
                            Text("Permanently removes the file")
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (showSleepTimerDialog) {
            sleepTimer?.let {
                NowPlayingSleepTimerDialog(
                    context,
                    sleepTimer = it,
                    onDismissRequest = {
                        showSleepTimerDialog = false
                    }
                )
            } ?: run {
                NowPlayingSleepTimerSetDialog(
                    context,
                    onDismissRequest = {
                        showSleepTimerDialog = false
                    }
                )
            }
        }

        if (showSpeedDialog) {
            NowPlayingSpeedDialog(
                context,
                currentSpeed = data.currentSpeed,
                persistedSpeed = data.persistedSpeed,
                onDismissRequest = {
                    showSpeedDialog = false
                }
            )
        }

        if (showPitchDialog) {
            NowPlayingPitchDialog(
                context,
                currentPitch = data.currentPitch,
                persistedPitch = data.persistedPitch,
                onDismissRequest = {
                    showPitchDialog = false
                }
            )
        }

        if (showDeleteFromDeviceDialog) {
            DeleteSongFromDeviceDialog(
                context,
                song = data.song,
                onDismissRequest = {
                    showDeleteFromDeviceDialog = false
                }
            )
        }
    }
}
