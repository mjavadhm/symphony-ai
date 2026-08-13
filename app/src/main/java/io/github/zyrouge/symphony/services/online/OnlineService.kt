package io.github.zyrouge.symphony.services.online

import android.content.Context
import android.provider.DocumentsContract
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.utils.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.net.URI

data class OnlineTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSeconds: Long?,
    val artworkUrl: String?,
    val downloadUrl: String,
)

class OnlineService(private val baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val url = resolve(apiPath("search")) + "?q=" +
            java.net.URLEncoder.encode(query, Charsets.UTF_8.name()) + "&type=track&limit=30"
        execute(Request.Builder().url(url).get().build()).use { response ->
            if (!response.isSuccessful) error("Service returned HTTP ${response.code}")
            parseTracks(json.parseToJsonElement(response.body?.string().orEmpty()))
        }
    }

    suspend fun download(
        context: Context,
        symphony: Symphony,
        track: OnlineTrack,
        onProgress: (Float?) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val treeUri = symphony.settings.mediaFolders.value.firstOrNull()
            ?: error("Choose a music folder in Settings first")
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val extension = if (track.downloadUrl.contains("quality=FLAC", ignoreCase = true)) "flac" else "mp3"
        val baseName = safeName(listOfNotNull(track.artist, track.title).joinToString(" - "))
        val existing = context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent)),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } } ?: emptySet()
        var fileName = "$baseName.$extension"
        var suffix = 2
        while (fileName in existing) fileName = "$baseName ($suffix).$extension".also { suffix++ }
        val mime = if (extension.equals("mp3", true)) "audio/mpeg" else "audio/$extension"
        val document = DocumentsContract.createDocument(context.contentResolver, parent, mime, fileName)
            ?: error("Could not create the destination file")
        try {
            execute(Request.Builder().url(resolve(track.downloadUrl)).get().build()).use { response ->
                if (!response.isSuccessful) error("Download returned HTTP ${response.code}")
                val body = response.body ?: error("Download response was empty")
                val total = body.contentLength()
                context.contentResolver.openOutputStream(document, "w")!!.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(if (total > 0) copied.toFloat() / total else null)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            DocumentsContract.deleteDocument(context.contentResolver, document)
            throw error
        }
        symphony.groove.fetch(Groove.FetchOptions(resetInMemoryCache = true))
        fileName
    }

    private fun parseTracks(root: JsonElement): List<OnlineTrack> {
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> sequenceOf("results", "items", "tracks", "songs", "data")
                .mapNotNull { root[it] }.mapNotNull { it as? JsonArray }.firstOrNull() ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
                (item[key] as? JsonPrimitive)?.contentOrNull
            }
            val title = text("title", "name", "track_name") ?: return@mapNotNull null
            val id = text("id", "track_id", "uuid") ?: return@mapNotNull null
            OnlineTrack(
                id = id,
                title = title,
                artist = text("artist", "artist_name", "author"),
                album = text("album", "album_name"),
                durationSeconds = text("duration", "duration_seconds")?.toDoubleOrNull()?.toLong(),
                artworkUrl = text(
                    "cover_medium", "cover_big", "cover_xl", "cover_small",
                    "artwork_url", "artworkUrl", "cover_url", "thumbnail"
                ),
                downloadUrl = apiPath("tracks/$id/download") + "?quality=MP3_320",
            )
        }
    }

    private fun resolve(path: String): String = URI(baseUrl.trimEnd('/') + "/").resolve(path).toString()
    private fun apiPath(path: String): String {
        val configuredPath = runCatching { URI(baseUrl).path.trimEnd('/') }.getOrDefault("")
        return if (configuredPath.endsWith("/v1")) path else "v1/$path"
    }
    private fun execute(request: Request): Response = HttpClient.newCall(request).execute()
    private fun safeName(value: String) = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().trim('.').take(120).ifBlank { "Online track" }
}
