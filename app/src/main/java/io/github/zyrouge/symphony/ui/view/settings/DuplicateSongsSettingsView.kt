package io.github.zyrouge.symphony.ui.view.settings

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.components.GlassSettingsScaffold
import io.github.zyrouge.symphony.ui.components.ScaffoldDialog
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.DocumentFileX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object DuplicateSongsSettingsViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateSongsSettingsView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    var matchByTitle by remember { mutableStateOf(true) }
    var matchByArtist by remember { mutableStateOf(true) }
    var matchByDuration by remember { mutableStateOf(false) }

    val allSongs = context.symphony.groove.song.values()
    val duplicateGroups = remember(matchByTitle, matchByArtist, matchByDuration, allSongs) {
        val groups = mutableMapOf<String, MutableList<Song>>()
        for (song in allSongs) {
            val titlePart = if (matchByTitle) song.title.lowercase() else ""
            val artistPart = if (matchByArtist) song.artists.joinToString(", ").lowercase() else ""
            val durationPart = if (matchByDuration) (song.duration / 1000).toString() else ""

            val key = "$titlePart|$artistPart|$durationPart"
            if (key != "||") {
                groups.getOrPut(key) { mutableListOf() }.add(song)
            }
        }
        groups.values.filter { it.size > 1 }.toList()
    }

    val selectedSongs = remember { mutableStateMapOf<String, Boolean>() }

    var preferProperNames by remember { mutableStateOf(true) }
    val folderPriority = remember { mutableStateListOf<String>() }
    var showFolderPriorityDialog by remember { mutableStateOf(false) }

    val allFolders = remember(duplicateGroups) {
        duplicateGroups.flatten()
            .map { it.path.substringBeforeLast('/', "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    fun rescanLibrary() {
        context.symphony.radio.stop()
        val options = io.github.zyrouge.symphony.services.groove.Groove.FetchOptions(
            resetInMemoryCache = true,
            resetPersistentCache = true,
        )
        context.symphony.groove.fetch(options)
    }

    fun autoSelect() {
        selectedSongs.clear()
        var count = 0
        for (group in duplicateGroups) {
            val keep = group.minWithOrNull(
                compareBy<Song>(
                    { song ->
                        val folder = song.path.substringBeforeLast('/', "")
                        val rank = folderPriority.indexOf(folder)
                        if (rank == -1) Int.MAX_VALUE else rank
                    },
                    { song ->
                        if (preferProperNames && isJunkFilename(song.filename)) 1 else 0
                    },
                )
            ) ?: continue
            group.forEach { song ->
                if (song.id != keep.id) {
                    selectedSongs[song.id] = true
                    count++
                }
            }
        }
        Toast.makeText(context.activity, "Selected $count files to delete", Toast.LENGTH_SHORT).show()
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context.activity, "Deleted from device", Toast.LENGTH_SHORT).show()
            selectedSongs.clear()
            rescanLibrary()
        }
    }

    GlassSettingsScaffold(
        context = context,
        title = "${context.symphony.t.Settings} - Find Duplicates",
        floatingActionButton = {
            val selectedCount = selectedSongs.values.count { it }
            if (selectedCount > 0) {
                ExtendedFloatingActionButton(
                    text = { Text("Delete $selectedCount selected") },
                    icon = { Icon(Icons.Filled.Delete, null) },
                    onClick = {
                        coroutineScope.launch {
                            val selectedIds = selectedSongs.filterValues { it }.keys.toList()
                            val songs = selectedIds.mapNotNull { id -> allSongs.find { it.id == id } }
                            val resolver = context.symphony.applicationContext.contentResolver
                            val mediaUris = withContext(Dispatchers.IO) {
                                songs.mapNotNull { resolveMediaStoreUri(resolver, it.path) }
                            }
                            when {
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaUris.isNotEmpty() -> {
                                    val pendingIntent = MediaStore.createDeleteRequest(resolver, mediaUris)
                                    deleteLauncher.launch(
                                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                    )
                                }

                                else -> {
                                    var deletedCount = 0
                                    withContext(Dispatchers.IO) {
                                        for (song in songs) {
                                            val uri = context.symphony.groove.exposer.uris[song.path]
                                            if (uri != null) {
                                                val docFile = DocumentFileX.fromSingleUri(
                                                    context.symphony.applicationContext,
                                                    uri,
                                                )
                                                if (docFile?.delete() == true) deletedCount++
                                            }
                                        }
                                    }
                                    Toast.makeText(
                                        context.activity,
                                        "Deleted $deletedCount files",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    selectedSongs.clear()
                                    rescanLibrary()
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            Text(
                text = "Match by:",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = matchByTitle,
                    onClick = { matchByTitle = !matchByTitle },
                    label = { Text("Title") }
                )
                FilterChip(
                    selected = matchByArtist,
                    onClick = { matchByArtist = !matchByArtist },
                    label = { Text("Artist") }
                )
                FilterChip(
                    selected = matchByDuration,
                    onClick = { matchByDuration = !matchByDuration },
                    label = { Text("Duration") }
                )
            }
            Text(
                text = "Auto select:",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = preferProperNames,
                    onClick = { preferProperNames = !preferProperNames },
                    label = { Text("Skip junk names") }
                )
                FilterChip(
                    selected = folderPriority.isNotEmpty(),
                    onClick = { showFolderPriorityDialog = true },
                    label = {
                        Text(
                            when {
                                folderPriority.isEmpty() -> "Folder priority"
                                else -> "Folder priority (${folderPriority.size})"
                            }
                        )
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { autoSelect() },
                    enabled = duplicateGroups.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auto Select")
                }
                TextButton(
                    onClick = { selectedSongs.clear() },
                    enabled = selectedSongs.values.any { it },
                ) {
                    Text("Clear")
                }
            }
            Text(
                text = "Checked files get deleted, unchecked ones are kept.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (duplicateGroups.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No duplicates found")
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(duplicateGroups) { group ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Title: ${group.first().title}\nArtist: ${
                                        group.first().artists.joinToString(", ")
                                    }",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                group.forEach { song ->
                                    val isSelected = selectedSongs[song.id] ?: false
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedSongs[song.id] = !isSelected
                                        }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { selectedSongs[song.id] = it }
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = song.filename,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = song.path,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFolderPriorityDialog) {
        ScaffoldDialog(
            title = { Text("Folder Priority") },
            content = {
                val ranked = folderPriority.toList()
                val unranked = allFolders.filter { !folderPriority.contains(it) }
                LazyColumn(modifier = Modifier.padding(vertical = 8.dp)) {
                    item {
                        Text(
                            "Tap a folder to rank it. In each group, the song from the highest-ranked folder is kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(ranked + unranked) { folder ->
                        val rankIndex = folderPriority.indexOf(folder)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (rankIndex) {
                                        -1 -> folderPriority.add(folder)
                                        else -> folderPriority.remove(folder)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = when (rankIndex) {
                                    -1 -> "–"
                                    else -> "${rankIndex + 1}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = when (rankIndex) {
                                    -1 -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.width(28.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    folder.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    folder,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (rankIndex != -1) {
                                IconButton(
                                    enabled = rankIndex > 0,
                                    onClick = {
                                        folderPriority.removeAt(rankIndex)
                                        folderPriority.add(rankIndex - 1, folder)
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    enabled = rankIndex < folderPriority.size - 1,
                                    onClick = {
                                        folderPriority.removeAt(rankIndex)
                                        folderPriority.add(rankIndex + 1, folder)
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowDownward,
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            actions = {
                TextButton(onClick = { folderPriority.clear() }) { Text("Reset") }
                TextButton(onClick = { showFolderPriorityDialog = false }) { Text("Done") }
            },
            onDismissRequest = { showFolderPriorityDialog = false },
        )
    }
}

private fun isJunkFilename(filename: String): Boolean {
    val name = filename.substringBeforeLast('.').trim()
    if (name.isEmpty()) return true
    return name.count { it.isLetter() } < 2
}

private fun safPathToAbsolutePath(path: String): String {
    if (path.startsWith("/")) return path
    val colonIndex = path.indexOf(':')
    if (colonIndex == -1) return path
    val volume = path.substring(0, colonIndex)
    val rest = path.substring(colonIndex + 1)
    return when (volume) {
        "primary" -> "/storage/emulated/0/$rest"
        else -> "/storage/$volume/$rest"
    }
}

private fun resolveMediaStoreUri(resolver: ContentResolver, songPath: String): Uri? {
    val absolutePath = safPathToAbsolutePath(songPath)
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Audio.Media._ID)
    resolver.query(
        collection,
        projection,
        "${MediaStore.Audio.Media.DATA} = ?",
        arrayOf(absolutePath),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
    }
    val filename = absolutePath.substringAfterLast('/')
    if (filename.isNotEmpty()) {
        resolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.DATA} LIKE ?",
            arrayOf("%/$filename"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
    }
    return null
}