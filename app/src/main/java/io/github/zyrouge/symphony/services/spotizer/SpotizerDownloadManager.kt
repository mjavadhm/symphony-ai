package io.github.zyrouge.symphony.services.spotizer

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-phase Spotizer download pipeline:
 * 1. Check the local library (optional skip) and the server cache.
 * 2. If the server has not prepared the file yet, the download endpoint answers 504
 *    while preparing; we poll every 3 seconds. Downloads resume via HTTP Range.
 * Finished files are saved to Music/<folder> via MediaStore.
 *
 * @param onDownloadCompleted invoked once the queue goes idle after at least one
 *   successful save, so the host can re-index the local library without the user
 *   having to trigger a manual re-scan.
 */
class SpotizerDownloadManager(
    private val context: Context,
    private val settings: SpotizerSettings,
    private val client: SpotizerClient,
    private val isTrackOnDevice: (SpotizerTrack) -> Boolean,
    private val resolveUserId: suspend () -> String?,
    private val onDownloadCompleted: (() -> Unit)? = null,
) {
    enum class Phase {
        Queued,
        CheckingLocal,
        SkippedExists,
        CheckingServer,
        PreparingOnServer,
        Downloading,
        Saving,
        Done,
        Failed,
        Cancelled,
    }

    data class Item(
        val id: Long,
        val track: SpotizerTrack,
        val quality: String,
        val phase: Phase = Phase.Queued,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val wasCachedOnServer: Boolean = false,
        val error: String? = null,
        val savedFileName: String? = null,
    ) {
        val isActive: Boolean
            get() = phase == Phase.Queued ||
                    phase == Phase.CheckingLocal ||
                    phase == Phase.CheckingServer ||
                    phase == Phase.PreparingOnServer ||
                    phase == Phase.Downloading ||
                    phase == Phase.Saving
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nextId = AtomicLong(1L)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private var semaphore = Semaphore(settings.maxConcurrentDownloads.value)
    private var semaphoreSize = settings.maxConcurrentDownloads.value
    private val libraryRefreshPending = AtomicBoolean(false)

    private val state = SpotizerDownloadState()
    val items: StateFlow<List<Item>> = state.items

    /** Returns false when the track is already queued or running. */
    fun enqueueTrack(track: SpotizerTrack): Boolean {
        if (track.id == null) return false
        val item = Item(
            id = nextId.getAndIncrement(),
            track = track,
            quality = settings.downloadQuality.value,
        )
        if (!state.enqueue(item)) return false
        startJob(item.id)
        return true
    }

    /** Returns (enqueued, skipped). Skips tracks already on device when enabled. */
    fun enqueueAlbumTracks(tracks: List<SpotizerTrack>): Pair<Int, Int> {
        var enqueued = 0
        var skipped = 0
        val skipExisting = settings.skipExistingTracks.value
        for (track in tracks) {
            if (skipExisting && runCatching { isTrackOnDevice(track) }.getOrDefault(false)) {
                skipped += 1
                continue
            }
            if (enqueueTrack(track)) {
                enqueued += 1
            } else {
                skipped += 1
            }
        }
        return enqueued to skipped
    }

    fun cancel(itemId: Long) {
        update(itemId) { it.copy(phase = Phase.Cancelled) }
        jobs.remove(itemId)?.cancel()
    }

    fun retry(itemId: Long) {
        if (!state.retry(itemId)) return
        startJob(itemId)
    }

    fun clearFinished() {
        state.clearFinished()
    }

    private fun update(itemId: Long, transform: (Item) -> Item) {
        state.update(itemId, transform)
    }

    private fun currentItem(itemId: Long) = items.value.find { it.id == itemId }

    /**
     * Schedules a single library refresh for the current download burst. Waits for
     * the queue to drain first so a 20 track album triggers one re-index, not 20.
     */
    private fun scheduleLibraryRefresh() {
        val callback = onDownloadCompleted ?: return
        if (!libraryRefreshPending.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                while (items.value.any { it.isActive }) {
                    delay(1000)
                }
                // Give MediaStore a moment to publish the freshly written files.
                delay(750)
                runCatching { callback() }
            } finally {
                libraryRefreshPending.set(false)
            }
        }
    }

    private fun refreshSemaphore() {
        val wanted = settings.maxConcurrentDownloads.value
        if (wanted != semaphoreSize) {
            semaphore = Semaphore(wanted)
            semaphoreSize = wanted
        }
    }

    private fun startJob(itemId: Long) {
        refreshSemaphore()
        val gate = semaphore
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                gate.withPermit {
                    runCatching { process(itemId) }.onFailure { failure ->
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        io.github.zyrouge.symphony.services.diagnostics.Diagnostics.event(
                            "ERROR", "spotizer", "download_or_save_failed", failure,
                        )
                        if (currentItem(itemId)?.isActive == true) {
                            update(itemId) {
                                it.copy(
                                    phase = Phase.Failed,
                                    error = failure.message ?: "Unexpected error",
                                )
                            }
                        }
                    }
                }
            } finally {
                jobs.remove(itemId, currentCoroutineContext()[Job])
            }
        }
        jobs[itemId] = job
        job.start()
    }

    private fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun process(itemId: Long) {
        val item = currentItem(itemId) ?: return
        val trackId = item.track.id
            ?: run {
                update(itemId) { it.copy(phase = Phase.Failed, error = "Track has no id") }
                return
            }

        if (settings.wifiOnlyDownloads.value && !isWifiConnected()) {
            update(itemId) { it.copy(phase = Phase.Failed, error = "Wi-Fi is not connected") }
            return
        }

        update(itemId) { it.copy(phase = Phase.CheckingLocal) }
        if (settings.skipExistingTracks.value &&
            runCatching { isTrackOnDevice(item.track) }.getOrDefault(false)
        ) {
            update(itemId) { it.copy(phase = Phase.SkippedExists) }
            return
        }

        update(itemId) { it.copy(phase = Phase.CheckingServer) }
        val status = runCatching { client.getTrackStatus(trackId, item.quality) }.getOrNull()
        val cachedOnServer = status?.cached == true
        update(itemId) { it.copy(wasCachedOnServer = cachedOnServer) }

        val userId = runCatching { resolveUserId() }.getOrNull()
        val url = client.downloadUrl(trackId, item.quality, userId)
        val tempFile = File(
            context.cacheDir,
            "spotizer_" + trackId + "_" + item.quality + ".part",
        )

        var totalBytes = 0L
        var attempts = 0
        val maxPrepareAttempts = 40

        while (true) {
            currentCoroutineContext().ensureActive()
            if (currentItem(itemId)?.phase == Phase.Cancelled) {
                return
            }
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
            val requestBuilder = client.buildRequest(url).get()
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=" + existingBytes + "-")
            }
            val response = client.http.newCall(requestBuilder.build()).execute()
            if (!currentCoroutineContext().isActive) {
                response.close()
                currentCoroutineContext().ensureActive()
            }
            when {
                response.code == 504 -> {
                    response.close()
                    attempts += 1
                    if (attempts > maxPrepareAttempts) {
                        update(itemId) {
                            it.copy(
                                phase = Phase.Failed,
                                error = "Server took too long to prepare the track",
                            )
                        }
                        return
                    }
                    update(itemId) { it.copy(phase = Phase.PreparingOnServer) }
                    delay(3000)
                }

                response.code == 200 || response.code == 206 -> {
                    val body = response.body
                    if (body == null) {
                        response.close()
                        update(itemId) { it.copy(phase = Phase.Failed, error = "Empty response") }
                        return
                    }
                    val resuming = response.code == 206 && existingBytes > 0L
                    val contentLength = body.contentLength().takeIf { it > 0L } ?: 0L
                    totalBytes = if (resuming) existingBytes + contentLength else contentLength
                    update(itemId) {
                        it.copy(
                            phase = Phase.Downloading,
                            downloadedBytes = if (resuming) existingBytes else 0L,
                            totalBytes = totalBytes,
                        )
                    }
                    var downloaded = if (resuming) existingBytes else 0L
                    var lastEmit = 0L
                    response.use {
                        body.byteStream().use { input ->
                            FileOutputStream(tempFile, resuming).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    if (currentItem(itemId)?.phase == Phase.Cancelled) {
                                        return
                                    }
                                    val read = input.read(buffer)
                                    if (read < 0) {
                                        break
                                    }
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= 300) {
                                        lastEmit = now
                                        update(itemId) {
                                            it.copy(downloadedBytes = downloaded)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    update(itemId) { it.copy(downloadedBytes = downloaded, phase = Phase.Saving) }
                    saveToMediaStore(itemId, tempFile, status)
                    return
                }

                else -> {
                    val code = response.code
                    val requestId = io.github.zyrouge.symphony.services.diagnostics.DiagnosticPrivacy.requestId(response.header("X-Request-ID"))
                        ?: io.github.zyrouge.symphony.services.diagnostics.DiagnosticPrivacy.requestId(response.request.header("X-Request-ID"))
                    response.close()
                    update(itemId) {
                        it.copy(phase = Phase.Failed, error = "Download failed with HTTP " + code)
                    }
                    return
                }
            }
        }
    }

    private fun fileExtension(item: Item, status: SpotizerTrackStatus?): String {
        status?.format?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        return if (item.quality == SpotizerQuality.FLAC) "flac" else "mp3"
    }

    private fun mimeType(extension: String): String = when (extension) {
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    private fun sanitizeFileName(name: String): String {
        val illegal = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val cleaned = name.map { if (it in illegal) '_' else it }.joinToString("")
        return cleaned.trim().take(200).ifBlank { "track" }
    }

    private fun saveToMediaStore(itemId: Long, tempFile: File, status: SpotizerTrackStatus?) {
        val item = currentItem(itemId) ?: return
        val extension = fileExtension(item, status)
        val artist = item.track.artist ?: "Unknown artist"
        val title = item.track.title ?: ("Track " + item.track.id)
        val fileName = sanitizeFileName(artist + " - " + title) + "." + extension
        val folderName = settings.downloadFolderName.value.ifBlank { "Spotizer" }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType(extension))
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/" + folderName)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                    put(MediaStore.Audio.Media.TITLE, title)
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    item.track.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                ) ?: throw Exception("Could not create media store entry")
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Could not open output stream")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val musicDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC,
                )
                val targetDir = File(musicDir, folderName)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val targetFile = File(targetDir, fileName)
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            tempFile.delete()
            update(itemId) { it.copy(phase = Phase.Done, savedFileName = fileName) }
            scheduleLibraryRefresh()
        } catch (failure: Exception) {
            update(itemId) {
                it.copy(phase = Phase.Failed, error = failure.message ?: "Could not save file")
            }
        }
    }
}
