package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.R
import io.github.zyrouge.symphony.services.AppMeta
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.HomeDynamicBackground
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.components.settings.SettingsSimpleTile
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.settings.AppearanceSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.DuplicateSongsSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.GrooveSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.HomePageSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.MiniPlayerSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.NowPlayingSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.PlayerSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.SemanticSearchSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.BackupSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.RecommendationSettingsRoute
import kotlinx.serialization.Serializable

@Serializable
data class SettingsViewRoute(val initialElement: String? = null) {
    companion object {
        const val ELEMENT_MEDIA_FOLDERS = "media_folders"
    }
}

@Composable
fun SettingsView(context: ViewContext, route: SettingsViewRoute) {
    val scrollState = rememberScrollState()
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier = Modifier.fillMaxSize()) {
            HomeDynamicBackground(context)
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {
                    Row(
                        modifier = Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassSurface(
                                modifier = Modifier.height(44.dp),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    context.symphony.t.Settings,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(44.dp))
                    }
                },
                content = { contentPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = LocalHazeState.current, zIndex = 1f)
                            .verticalScroll(scrollState)
                            .padding(contentPadding)
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        GlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(modifier = Modifier.size(64.dp)) {
                                    AsyncImage(R.drawable.ic_launcher_foreground, null)
                                }
                                Column {
                                    Text(AppMeta.appName, style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(AppMeta.version, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        SettingsGlassGroup {
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.LibraryMusic, null) },
                                title = { Text(context.symphony.t.Groove) },
                                onClick = {
                                    context.navController.navigate(
                                        GrooveSettingsViewRoute(route.initialElement)
                                    )
                                },
                            )
                            SettingsGlassDivider()
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.FindInPage, null) },
                                title = { Text("Find Duplicates") },
                                onClick = {
                                    context.navController.navigate(DuplicateSongsSettingsViewRoute)
                                },
                            )
                        }
                        SettingsGlassGroup {
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.Radio, null) },
                                title = { Text(context.symphony.t.Player) },
                                onClick = {
                                    context.navController.navigate(PlayerSettingsViewRoute)
                                },
                            )
                            SettingsGlassDivider()
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.MusicNote, null) },
                                title = { Text(context.symphony.t.MiniPlayer) },
                                onClick = {
                                    context.navController.navigate(MiniPlayerSettingsViewRoute)
                                },
                            )
                            SettingsGlassDivider()
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.MusicNote, null) },
                                title = { Text(context.symphony.t.NowPlaying) },
                                onClick = {
                                    context.navController.navigate(NowPlayingSettingsViewRoute)
                                },
                            )
                        }
                        SettingsGlassGroup {
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.Palette, null) },
                                title = { Text(context.symphony.t.Appearance) },
                                onClick = {
                                    context.navController.navigate(AppearanceSettingsViewRoute)
                                },
                            )
                            SettingsGlassDivider()
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.Home, null) },
                                title = { Text(context.symphony.t.Home) },
                                onClick = {
                                    context.navController.navigate(HomePageSettingsViewRoute)
                                },
                            )
                        }
                        SettingsGlassGroup {
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.Search, null) },
                                title = { Text("AI Search") },
                                onClick = {
                                    context.navController.navigate(SemanticSearchSettingsViewRoute)
                                },
                            )
                            SettingsGlassDivider()
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.AutoAwesome, null) },
                                title = { Text("AI Recommendations") },
                                onClick = {
                                    context.navController.navigate(RecommendationSettingsRoute)
                                },
                            )
                            SettingsGlassDivider()
                            SettingsSimpleTile(
                                icon = { Icon(Icons.Filled.SettingsBackupRestore, null) },
                                title = { Text("Backup & Restore") },
                                onClick = {
                                    context.navController.navigate(BackupSettingsViewRoute)
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsGlassGroup(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsGlassDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
}
