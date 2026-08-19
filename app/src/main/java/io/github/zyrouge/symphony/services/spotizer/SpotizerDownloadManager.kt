package io.github.zyrouge.symphony.services.spotizer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-phase download pipeline, matching the backend behaviour in
 * app/routers/tracks.py:
 *
 *   Phase 1 (server): GET /v1/tracks/{id}/status -> cached?
 *     - If not cached, GET /v1/tracks/{id}/download triggers the on-demand
 *       server download (library.ensure_track). While the server is still
 *       preparing, it answers 504 ("retry in a few seconds") - we poll status
 *       and retry. UI shows "preparing on server" (indeterminate).
 *   Phase 2 (device): stream the response body to a temp file with progress,
 *     resuming with a Range header on retries, then publish to MediaStore
 *     (Music/<folder>) so the local library picks it up immediately.
 *
 * Album downloads enqueue each track as its own item sharing an albumGroup.
 */
class SpotizerDownloadManager(
    private val context: Context,
    private val client: SpotizerClient,
    private val settings: SpotizerSettings,
    /**
     * Hook into Symphony's local library: return true when the track already
     * exists on the device. Wire it up with title/artist/duration matching
     * against the Groove song repository (see INTEGRATION.md).
     */
    private val isTrackOnDevice: (track: SpotizerTrack) -> Boolean = { false },
    /** Optional: resolve user id for history logging on the server. */
    private val resolveUserId: suspend () -> Long? = { null },
) {
    enum class Phase {
        Queued,
        CheckingLocal,
        CheckingServer,
        PreparingOnServer,
        Downloading,
        Saving,
        Done,
        SkippedExists,
        Failed,
        Cancelled,
    }

    data class Item(
        val id: String,
        val track: SpotizerTrack,
        val quality: String,
        /** album title used for grouping album downloads in the queue UI */
        val albumGroup: String? = null,
        val phase: Phase = Phase.Queued,
        /** 0f..1f device download progress; negative = unknown */
        val progress: Float = -1f,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val error: String? = null,
        val savedUri: Uri? = null,
    ) {
        val isActive: Boolean
            get() = phase in listOf(
                Phase.Queued,
                Phase.CheckingLocal,
                Phase.CheckingServer,
                Phase.PreparingOnServer,
                Phase.Downloading,
                Phase.Saving,
            )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    /** number of items currently in an active phase */
    val activeCount: Int get() = _items.value.count { it.isActive }

    private var semaphore = Semaphore(settings.maxConcurrentDownloads.value)

    // ---------- public API ----------

    fun enqueueTrack(track: SpotizerTrack, albumGroup: String? = null): String {
        val item = Item(
            id = UUID.randomUUID().toString(),
            track = track,
            quality = settings.downloadQuality.value,
            albumGroup = albumGroup,
        )
        upsert(item)
        jobs[item.id] = scope.launch { runItem(item.id) }
        return item.id
    }

    /**
     * Enqueue all album tracks. Returns the ids of enqueued items and the
     * tracks that were skipped because they already exist locally.
     */
    fun enqueueAlbum(album: SpotizerAlbum): Pair<List<String>, List<SpotizerTrack>> {
        val skipEnabled = settings.skipExistingTracks.value
        val (existing, missing) = album.tracks.partition { skipEnabled && isTrackOnDevice(it) }
        val ids = missing.map { enqueueTrack(it, albumGroup = album.title) }
        return ids to existing
    }

    fun cancel(itemId: String) {
        cancelled.add(itemId)
        jobs[itemId]?.cancel()
        update(itemId) { it.copy(phase = Phase.Cancelled) }
    }

    fun retry(itemId: String) {
        val item = _items.value.find { it.id == itemId } ?: return
        if (item.isActive) return
        cancelled.remove(itemId)
        update(itemId) { it.copy(phase = Phase.Queued, error = null, progress = -1f) }
        jobs[itemId] = scope.launch { runItem(itemId) }
    }

    fun clearFinished() {
        _items.value = _items.value.filter { it.isActive }
    }

    // ---------- pipeline ----------

    private suspend fun runItem(itemId: String) {
        semaphore.withPermit {
            try {
                if (itemId in cancelled) return
                val item = _items.value.find { it.id == itemId } ?: return
                val track = item.track
                val trackId = track.id ?: run {
                    update(itemId) { it.copy(phase = Phase.Failed, error = "Track id missing") }
                    return
                }

                // -- wifi-only guard --
                if (settings.wifiOnlyDownloads.value && !isOnWifi()) {
                    update(itemId) { it.copy(phase = Phase.Failed, error = "Waiting for Wi-Fi (wifi-only downloads is enabled)") }
                    return
                }

                // -- dedupe --
                update(itemId) { it.copy(phase = Phase.CheckingLocal) }
                if (settings.skipExistingTracks.value && isTrackOnDevice(track)) {
                    update(itemId) { it.copy(phase = Phase.SkippedExists) }
                    return
                }

                // -- phase 1: server readiness --
                update(itemId) { it.copy(phase = Phase.CheckingServer) }
                val status = runCatching { client.getTrackStatus(trackId, item.quality) }.getOrNull()
                if (status?.cached != true) {
                    update(itemId) { it.copy(phase = Phase.PreparingOnServer) }
                }

                // -- phase 2: download with 504-retry + Range resume --
                downloadWithRetries(itemId, trackId, item.quality)
            } catch (e: kotlinx.coroutines.CancellationException) {
                update(itemId) { it.copy(phase = Phase.Cancelled) }
            } catch (e: Exception) {
                update(itemId) { it.copy(phase = Phase.Failed, error = e.message ?: e.toString()) }
            } finally {
                jobs.remove(itemId)
            }
        }
    }

    private suspend fun downloadWithRetries(itemId: String, trackId: String, quality: String) {
        val tempFile = File(context.cacheDir, "spotizer_dl_$itemId.part")
        var attempt = 0
        val maxAttempts = 40 // server prep can take a while; 504s are cheap retries

        while (true) {
            if (itemId in cancelled) throw kotlinx.coroutines.CancellationException()
            attempt += 1
            val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
            val url = client.downloadUrl(trackId, quality, resolveUserId())
            val requestBuilder = client.newRequest(url).get()
            if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")

            val response = try {
                client.http.newCall(requestBuilder.build()).execute()
            } catch (e: java.io.IOException) {
                if (attempt >= maxAttempts) throw e
                update(itemId) { it.copy(phase = Phase.PreparingOnServer) }
                delay(3000)
                continue
            }

            response.use { resp ->
                when {
                    resp.code == 504 -> {
                        // server is still preparing the track (library.ensure_track timeout)
                        if (attempt >= maxAttempts) {
                            throw SpotizerApiException(504, "Server took too long preparing the track")
                        }
                        update(itemId) { it.copy(phase = Phase.PreparingOnServer) }
                        delay(3000)
                        return@use
                    }
                    !resp.isSuccessful -> throw SpotizerApiException(resp.code, resp.body?.string())
                    else -> {
                        val body = resp.body ?: throw SpotizerApiException(resp.code, "Empty body")
                        val isPartial = resp.code == 206
                        val contentLength = body.contentLength()
                        val total = if (isPartial) resumeFrom + contentLength else contentLength
                        if (!isPartial && tempFile.exists()) tempFile.delete()

                        update(itemId) {
                            it.copy(phase = Phase.Downloading, totalBytes = total, downloadedBytes = tempFile.length())
                        }

                        val fileName = fileNameFromResponse(resp.header("Content-Disposition"), trackId, quality)
                        val mimeType = resp.header("Content-Type") ?: "audio/mpeg"

                        body.byteStream().use { input ->
                            java.io.FileOutputStream(tempFile, isPartial).use { output ->
                                val buffer = ByteArray(256 * 1024)
                                var written = tempFile.length()
                                var lastEmit = 0L
                                while (true) {
                                    if (itemId in cancelled) throw kotlinx.coroutines.CancellationException()
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit > 200) {
                                        lastEmit = now
                                        val progress = if (total > 0) written.toFloat() / total else -1f
                                        update(itemId) {
                                            it.copy(downloadedBytes = written, progress = progress)
                                        }
                                    }
                                }
                            }
                        }

                        // -- save into the public music library --
                        update(itemId) { it.copy(phase = Phase.Saving, progress = 1f) }
                        val savedUri = saveToMusicLibrary(tempFile, fileName, mimeType)
                        tempFile.delete()
                        update(itemId) { it.copy(phase = Phase.Done, savedUri = savedUri) }
                        return
                    }
                }
            }
        }
    }

    // ---------- helpers ----------

    private fun fileNameFromResponse(contentDisposition: String?, trackId: String, quality: String): String {
        // filename*=UTF-8''... takes priority, then filename="..."
        if (contentDisposition != null) {
            Regex("filename\\*=UTF-8''([^;]+)").find(contentDisposition)?.let {
                return java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8")
            }
            Regex("filename=\"([^\"]+)\"").find(contentDisposition)?.let {
                return it.groupValues[1]
            }
        }
        val ext = if (quality == SpotizerQuality.FLAC) ".flac" else ".mp3"
        return "track_$trackId$ext"
    }

    private fun saveToMusicLibrary(source: File, fileName: String, mimeType: String): Uri? {
        val folder = settings.downloadFolderName.value
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + folder)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            // Android 9 (P): legacy external storage path + media scanner
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val targetDir = File(musicDir, folder).apply { mkdirs() }
            val target = File(targetDir, fileName)
            source.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            Uri.fromFile(target)
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun upsert(item: Item) {
        _items.value = _items.value.filter { it.id != item.id } + item
    }

    private fun update(itemId: String, transform: (Item) -> Item) {
        _items.value = _items.value.map { if (it.id == itemId) transform(it) else it }
    }
}
