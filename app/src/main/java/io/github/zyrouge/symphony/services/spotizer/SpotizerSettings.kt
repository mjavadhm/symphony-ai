package io.github.zyrouge.symphony.services.spotizer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local (on-device) settings for the Spotizer online section.
 *
 * Deliberately self-contained (SharedPreferences) so it merges into the fork
 * without touching Symphony's own settings plumbing. Each value is exposed as
 * a StateFlow so Compose can observe changes with collectAsState().
 */
class SpotizerSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("spotizer", Context.MODE_PRIVATE)

    // ---------- backing flows ----------
    private val _downloadQuality = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD_QUALITY, SpotizerQuality.MP3_320)!!
    )
    private val _streamQuality = MutableStateFlow(
        prefs.getString(KEY_STREAM_QUALITY, SpotizerQuality.MP3_320)!!
    )
    private val _skipExistingTracks = MutableStateFlow(
        prefs.getBoolean(KEY_SKIP_EXISTING, true)
    )
    private val _wifiOnlyDownloads = MutableStateFlow(
        prefs.getBoolean(KEY_WIFI_ONLY, false)
    )
    private val _maxConcurrentDownloads = MutableStateFlow(
        prefs.getInt(KEY_MAX_CONCURRENT, 2)
    )
    private val _downloadFolderName = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD_FOLDER, "Spotizer")!!
    )
    private val _serverBaseUrl = MutableStateFlow(
        prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER)!!
    )
    private val _userId = MutableStateFlow(
        prefs.getLong(KEY_USER_ID, -1L).takeIf { it > 0 }
    )

    // ---------- public API ----------

    /** Quality used when saving files to the device. */
    val downloadQuality: StateFlow<String> = _downloadQuality

    /** Quality used for streaming playback (separate from download). */
    val streamQuality: StateFlow<String> = _streamQuality

    /** Skip downloading tracks that already exist in the local library. */
    val skipExistingTracks: StateFlow<Boolean> = _skipExistingTracks

    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnlyDownloads
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads

    /** Subfolder under the public Music directory. */
    val downloadFolderName: StateFlow<String> = _downloadFolderName

    val userIdFlow: StateFlow<Long?> = _userId

    val serverBaseUrl: String get() = _serverBaseUrl.value
    val serverBaseUrlFlow: StateFlow<String> = _serverBaseUrl
    val userId: Long? get() = _userId.value
    val clientKey: String? get() = prefs.getString(KEY_CLIENT_KEY, null)

    fun setDownloadQuality(value: String) {
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, value).apply()
        _downloadQuality.value = value
    }

    fun setStreamQuality(value: String) {
        prefs.edit().putString(KEY_STREAM_QUALITY, value).apply()
        _streamQuality.value = value
    }

    fun setSkipExistingTracks(value: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_EXISTING, value).apply()
        _skipExistingTracks.value = value
    }

    fun setWifiOnlyDownloads(value: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        _wifiOnlyDownloads.value = value
    }

    fun setMaxConcurrentDownloads(value: Int) {
        val clamped = value.coerceIn(1, 3)
        prefs.edit().putInt(KEY_MAX_CONCURRENT, clamped).apply()
        _maxConcurrentDownloads.value = clamped
    }

    fun setDownloadFolderName(value: String) {
        prefs.edit().putString(KEY_DOWNLOAD_FOLDER, value.ifBlank { "Spotizer" }).apply()
        _downloadFolderName.value = value.ifBlank { "Spotizer" }
    }

    fun setServerBaseUrl(value: String) {
        val normalized = value.trim().removeSuffix("/").ifBlank { DEFAULT_SERVER }
        prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
        _serverBaseUrl.value = normalized
    }

    fun setUserId(value: Long?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_USER_ID) else putLong(KEY_USER_ID, value)
        }.apply()
        _userId.value = value
    }

    fun setClientKey(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_CLIENT_KEY) else putString(KEY_CLIENT_KEY, value)
        }.apply()
    }

    companion object {
        const val DEFAULT_SERVER = "https://api.spotizer.javadhm.online"

        private const val KEY_DOWNLOAD_QUALITY = "download_quality"
        private const val KEY_STREAM_QUALITY = "stream_quality"
        private const val KEY_SKIP_EXISTING = "skip_existing_tracks"
        private const val KEY_WIFI_ONLY = "wifi_only_downloads"
        private const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
        private const val KEY_DOWNLOAD_FOLDER = "download_folder_name"
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_CLIENT_KEY = "client_key"
    }
}
