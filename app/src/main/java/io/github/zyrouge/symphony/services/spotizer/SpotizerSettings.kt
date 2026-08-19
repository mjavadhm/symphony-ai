package io.github.zyrouge.symphony.services.spotizer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SpotizerSettings(context: Context) {
    companion object {
        const val DEFAULT_SERVER_BASE_URL = "https://api.spotizer.javadhm.online"

        private const val KEY_DOWNLOAD_QUALITY = "download_quality"
        private const val KEY_STREAM_QUALITY = "stream_quality"
        private const val KEY_SKIP_EXISTING = "skip_existing_tracks"
        private const val KEY_WIFI_ONLY = "wifi_only_downloads"
        private const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
        private const val KEY_DOWNLOAD_FOLDER = "download_folder_name"
        private const val KEY_SERVER_BASE_URL = "server_base_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_CLIENT_KEY = "client_key"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("spotizer", Context.MODE_PRIVATE)

    private val _downloadQuality = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD_QUALITY, SpotizerQuality.MP3_320) ?: SpotizerQuality.MP3_320
    )
    val downloadQuality: StateFlow<String> = _downloadQuality

    fun setDownloadQuality(value: String) {
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, value).apply()
        _downloadQuality.value = value
    }

    private val _streamQuality = MutableStateFlow(
        prefs.getString(KEY_STREAM_QUALITY, SpotizerQuality.MP3_320) ?: SpotizerQuality.MP3_320
    )
    val streamQuality: StateFlow<String> = _streamQuality

    fun setStreamQuality(value: String) {
        prefs.edit().putString(KEY_STREAM_QUALITY, value).apply()
        _streamQuality.value = value
    }

    private val _skipExistingTracks = MutableStateFlow(prefs.getBoolean(KEY_SKIP_EXISTING, true))
    val skipExistingTracks: StateFlow<Boolean> = _skipExistingTracks

    fun setSkipExistingTracks(value: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_EXISTING, value).apply()
        _skipExistingTracks.value = value
    }

    private val _wifiOnlyDownloads = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, false))
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnlyDownloads

    fun setWifiOnlyDownloads(value: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        _wifiOnlyDownloads.value = value
    }

    private val _maxConcurrentDownloads = MutableStateFlow(
        prefs.getInt(KEY_MAX_CONCURRENT, 2).coerceIn(1, 3)
    )
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads

    fun setMaxConcurrentDownloads(value: Int) {
        val coerced = value.coerceIn(1, 3)
        prefs.edit().putInt(KEY_MAX_CONCURRENT, coerced).apply()
        _maxConcurrentDownloads.value = coerced
    }

    private val _downloadFolderName = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD_FOLDER, "Spotizer") ?: "Spotizer"
    )
    val downloadFolderName: StateFlow<String> = _downloadFolderName

    fun setDownloadFolderName(value: String) {
        prefs.edit().putString(KEY_DOWNLOAD_FOLDER, value).apply()
        _downloadFolderName.value = value
    }

    private val _serverBaseUrl = MutableStateFlow(
        prefs.getString(KEY_SERVER_BASE_URL, DEFAULT_SERVER_BASE_URL) ?: DEFAULT_SERVER_BASE_URL
    )
    val serverBaseUrl: StateFlow<String> = _serverBaseUrl

    fun setServerBaseUrl(value: String) {
        prefs.edit().putString(KEY_SERVER_BASE_URL, value).apply()
        _serverBaseUrl.value = value
    }

    private val _userId = MutableStateFlow(prefs.getString(KEY_USER_ID, "") ?: "")
    val userId: StateFlow<String> = _userId

    fun setUserId(value: String) {
        prefs.edit().putString(KEY_USER_ID, value).apply()
        _userId.value = value
    }

    private val _clientKey = MutableStateFlow(prefs.getString(KEY_CLIENT_KEY, "") ?: "")
    val clientKey: StateFlow<String> = _clientKey

    fun setClientKey(value: String) {
        prefs.edit().putString(KEY_CLIENT_KEY, value).apply()
        _clientKey.value = value
    }
}
