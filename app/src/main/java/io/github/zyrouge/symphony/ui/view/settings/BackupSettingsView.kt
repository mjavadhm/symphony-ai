package io.github.zyrouge.symphony.ui.view.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.github.zyrouge.symphony.services.BackupManager
import io.github.zyrouge.symphony.ui.components.GlassChip
import io.github.zyrouge.symphony.ui.components.GlassSurface
import io.github.zyrouge.symphony.ui.components.HomeDynamicBackground
import io.github.zyrouge.symphony.ui.components.LocalHazeState
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
object BackupSettingsViewRoute

@Composable
fun BackupSettingsView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val hazeState = remember { HazeState() }
    val backupManager = remember { BackupManager(context.symphony) }

    var includeSettings by remember { mutableStateOf(true) }
    var includePlaylists by remember { mutableStateOf(true) }
    var includeHistory by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            busy = true
            status = "Exporting…"
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val backup = backupManager.exportToUri(
                        it,
                        includeSettings = includeSettings,
                        includePlaylists = includePlaylists,
                        includeHistory = includeHistory,
                    )
                    status = buildString {
                        append("Backup saved ✓")
                        backup.settings?.let { x -> append("\nSettings: ${x.size}") }
                        backup.playlists?.let { x -> append("\nPlaylists: ${x.size}") }
                        backup.history?.let { x -> append("\nHistory entries: ${x.size}") }
                    }
                } catch (err: Exception) {
                    status = "Export failed: ${err.message}"
                }
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            busy = true
            status = "Importing…"
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val result = backupManager.importFromUri(
                        it,
                        includeSettings = includeSettings,
                        includePlaylists = includePlaylists,
                        includeHistory = includeHistory,
                    )
                    status = buildString {
                        append("Import done ✓")
                        append("\nSettings applied: ${result.settingsApplied}")
                        if (result.settingsApplied > 0) append(" (restart app to apply)")
                        append("\nPlaylists added: ${result.playlistsAdded}")
                        append("\nFavorites merged: ${result.favoritesMerged}")
                        append("\nHistory added: ${result.historyAdded}")
                        append(" (${result.historySkipped} duplicates skipped)")
                    }
                } catch (err: Exception) {
                    status = "Import failed: ${err.message}"
                }
                busy = false
            }
        }
    }

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
                                    "Backup & Restore",
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "What to include",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    GlassChip(
                                        selected = includeSettings,
                                        onClick = { includeSettings = !includeSettings },
                                        label = { Text("Settings") },
                                    )
                                    GlassChip(
                                        selected = includePlaylists,
                                        onClick = { includePlaylists = !includePlaylists },
                                        label = { Text("Playlists") },
                                    )
                                    GlassChip(
                                        selected = includeHistory,
                                        onClick = { includeHistory = !includeHistory },
                                        label = { Text("History") },
                                    )
                                }
                                Text(
                                    "Embeddings are not included — re-index on the new device or import them from the laptop file.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                        GlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy && (includeSettings || includePlaylists || includeHistory),
                                    onClick = {
                                        val date = SimpleDateFormat(
                                            "yyyy-MM-dd-HHmm",
                                            Locale.US,
                                        ).format(Date())
                                        exportLauncher.launch("symphony-backup-$date.json")
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Save,
                                        null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Export backup")
                                }
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy && (includeSettings || includePlaylists || includeHistory),
                                    onClick = {
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.SettingsBackupRestore,
                                        null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Import backup")
                                }
                            }
                        }
                        status?.let { x ->
                            GlassSurface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(28.dp),
                            ) {
                                Text(
                                    x,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 16.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                },
            )
        }
    }
}
