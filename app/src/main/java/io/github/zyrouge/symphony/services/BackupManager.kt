package io.github.zyrouge.symphony.services

import android.content.Context
import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.entities.PlaybackHistory
import io.github.zyrouge.symphony.services.groove.Playlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupSettingValue(
    val type: String,
    val value: String? = null,
    val values: List<String>? = null,
)

@Serializable
data class BackupPlaylist(
    val title: String,
    val songPaths: List<String>,
    val isFavorites: Boolean = false,
)

@Serializable
data class BackupHistoryEntry(
    val playedAt: Long,
    val durationPlayed: Long,
    val isShuffleMode: Boolean = false,
    val loopMode: String = "None",
    val songDurationMs: Long = 0,
    val completionRate: Float = 0f,
    val skipped: Boolean = false,
    val hourOfDay: Int = -1,
    val dayOfWeek: Int = -1,
    val title: String = "",
    val artist: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val songPath: String? = null,
)

@Serializable
data class BackupFile(
    val format: Int = 1,
    val app: String = "symphony-ai",
    val createdAt: Long = 0,
    val settings: Map<String, BackupSettingValue>? = null,
    val playlists: List<BackupPlaylist>? = null,
    val history: List<BackupHistoryEntry>? = null,
)

data class BackupImportResult(
    val settingsApplied: Int,
    val playlistsAdded: Int,
    val favoritesMerged: Int,
    val historyAdded: Int,
    val historySkipped: Int,
)

class BackupManager(private val symphony: Symphony) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportToUri(
        uri: Uri,
        includeSettings: Boolean,
        includePlaylists: Boolean,
        includeHistory: Boolean,
    ): BackupFile {
        val backup = BackupFile(
            createdAt = System.currentTimeMillis(),
            settings = if (includeSettings) exportSettings() else null,
            playlists = if (includePlaylists) exportPlaylists() else null,
            history = if (includeHistory) exportHistory() else null,
        )
        val content = json.encodeToString(BackupFile.serializer(), backup)
        symphony.applicationContext.contentResolver.openOutputStream(uri, "w")?.use {
            it.write(content.toByteArray())
        } ?: throw Exception("Unable to open output stream")
        return backup
    }

    suspend fun importFromUri(
        uri: Uri,
        includeSettings: Boolean,
        includePlaylists: Boolean,
        includeHistory: Boolean,
    ): BackupImportResult {
        val content = symphony.applicationContext.contentResolver.openInputStream(uri)
            ?.use { String(it.readBytes()) }
            ?: throw Exception("Unable to open input stream")
        val backup = json.decodeFromString(BackupFile.serializer(), content)
        var settingsApplied = 0
        var playlistsAdded = 0
        var favoritesMerged = 0
        var historyAdded = 0
        var historySkipped = 0
        if (includeSettings) {
            backup.settings?.let { settingsApplied = importSettings(it) }
        }
        if (includePlaylists) {
            backup.playlists?.let {
                val (added, merged) = importPlaylists(it)
                playlistsAdded = added
                favoritesMerged = merged
            }
        }
        if (includeHistory) {
            backup.history?.let {
                val (added, skipped) = importHistory(it)
                historyAdded = added
                historySkipped = skipped
            }
        }
        return BackupImportResult(
            settingsApplied = settingsApplied,
            playlistsAdded = playlistsAdded,
            favoritesMerged = favoritesMerged,
            historyAdded = historyAdded,
            historySkipped = historySkipped,
        )
    }

    // ---------- settings ----------

    // Device-specific keys that must not be carried over to another phone
    private val excludedSettingsKeys = setOf(
        "previous_song_queue",
        "media_folders",
        "last_used_folder_path",
    )

    private fun getSharedPreferences() = symphony.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun exportSettings(): Map<String, BackupSettingValue> {
        val out = mutableMapOf<String, BackupSettingValue>()
        for ((key, raw) in getSharedPreferences().all) {
            if (key in excludedSettingsKeys || raw == null) continue
            out[key] = when (raw) {
                is Boolean -> BackupSettingValue("boolean", raw.toString())
                is Int -> BackupSettingValue("int", raw.toString())
                is Long -> BackupSettingValue("long", raw.toString())
                is Float -> BackupSettingValue("float", raw.toString())
                is String -> BackupSettingValue("string", raw)
                is Set<*> -> BackupSettingValue(
                    "stringSet",
                    values = raw.mapNotNull { it?.toString() },
                )
                else -> continue
            }
        }
        return out
    }

    private fun importSettings(settings: Map<String, BackupSettingValue>): Int {
        var applied = 0
        val editor = getSharedPreferences().edit()
        for ((key, x) in settings) {
            if (key in excludedSettingsKeys) continue
            when (x.type) {
                "boolean" -> x.value?.toBooleanStrictOrNull()
                    ?.let { editor.putBoolean(key, it); applied++ }
                "int" -> x.value?.toIntOrNull()
                    ?.let { editor.putInt(key, it); applied++ }
                "long" -> x.value?.toLongOrNull()
                    ?.let { editor.putLong(key, it); applied++ }
                "float" -> x.value?.toFloatOrNull()
                    ?.let { editor.putFloat(key, it); applied++ }
                "string" -> x.value
                    ?.let { editor.putString(key, it); applied++ }
                "stringSet" -> x.values
                    ?.let { editor.putStringSet(key, it.toSet()); applied++ }
            }
        }
        editor.apply()
        return applied
    }

    // ---------- playlists ----------

    private fun exportPlaylists(): List<BackupPlaylist> {
        val repo = symphony.groove.playlist
        return repo.values().map { playlist ->
            BackupPlaylist(
                title = playlist.title,
                songPaths = playlist.songPaths,
                isFavorites = repo.isFavoritesPlaylist(playlist),
            )
        }
    }

    private fun resolveSongId(path: String): String? {
        val pathCache = symphony.groove.song.pathCache
        return pathCache[path]
            ?: path.takeIf { it.isNotEmpty() && it[0] == '/' }
                ?.let { pathCache[it.substring(1).replaceFirst("/", ":")] }
            ?: pathCache["primary:$path"]
    }

    private fun importPlaylists(playlists: List<BackupPlaylist>): Pair<Int, Int> {
        val repo = symphony.groove.playlist
        var added = 0
        var favoritesMerged = 0
        val existingTitles = repo.values().map { it.title.lowercase() }.toMutableSet()
        for (x in playlists) {
            if (x.isFavorites) {
                val favorites = repo.getFavorites()
                val current = favorites.getSongIds(symphony).toMutableList()
                var changed = false
                for (path in x.songPaths) {
                    val id = resolveSongId(path) ?: continue
                    if (!current.contains(id)) {
                        current.add(id)
                        changed = true
                        favoritesMerged++
                    }
                }
                if (changed) {
                    repo.update(favorites.id, current)
                }
                continue
            }
            if (x.title.lowercase() in existingTitles) continue
            repo.add(
                Playlist(
                    id = repo.idGenerator.next(),
                    title = x.title,
                    songPaths = x.songPaths,
                    uri = null,
                    path = null,
                )
            )
            existingTitles.add(x.title.lowercase())
            added++
        }
        return added to favoritesMerged
    }

    // ---------- history ----------

    private suspend fun exportHistory(): List<BackupHistoryEntry> {
        return symphony.database.playbackHistory.getAllHistory().map { h ->
            BackupHistoryEntry(
                playedAt = h.playedAt,
                durationPlayed = h.durationPlayed,
                isShuffleMode = h.isShuffleMode,
                loopMode = h.loopMode,
                songDurationMs = h.songDurationMs,
                completionRate = h.completionRate,
                skipped = h.skipped,
                hourOfDay = h.hourOfDay,
                dayOfWeek = h.dayOfWeek,
                title = h.title,
                artist = h.artist,
                deviceId = h.deviceId,
                deviceName = h.deviceName,
                songPath = symphony.groove.song.get(h.songId)?.path,
            )
        }
    }

    private suspend fun importHistory(entries: List<BackupHistoryEntry>): Pair<Int, Int> {
        val store = symphony.database.playbackHistory
        val existing = store.getAllHistory()
            .map { "${it.playedAt}|${it.title}|${it.artist}" }
            .toHashSet()
        // Title index, used to remap entries when the path doesn't match
        val byTitle = HashMap<String, String>()
        for (id in symphony.groove.song.pathCache.values) {
            val song = symphony.groove.song.get(id) ?: continue
            byTitle.putIfAbsent(song.title.lowercase(), id)
        }
        var added = 0
        var skipped = 0
        for (x in entries) {
            val key = "${x.playedAt}|${x.title}|${x.artist}"
            if (key in existing) {
                skipped++
                continue
            }
            val songId = x.songPath?.let { resolveSongId(it) }
                ?: byTitle[x.title.lowercase()]
                ?: ""
            store.insert(
                PlaybackHistory(
                    songId = songId,
                    playedAt = x.playedAt,
                    durationPlayed = x.durationPlayed,
                    isShuffleMode = x.isShuffleMode,
                    loopMode = x.loopMode,
                    songDurationMs = x.songDurationMs,
                    completionRate = x.completionRate,
                    skipped = x.skipped,
                    hourOfDay = x.hourOfDay,
                    dayOfWeek = x.dayOfWeek,
                    title = x.title,
                    artist = x.artist,
                    deviceId = x.deviceId,
                    deviceName = x.deviceName,
                )
            )
            existing.add(key)
            added++
        }
        return added to skipped
    }
}
