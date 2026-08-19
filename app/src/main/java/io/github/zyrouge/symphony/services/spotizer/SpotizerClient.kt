package io.github.zyrouge.symphony.services.spotizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

class SpotizerApiException(
    val statusCode: Int,
    val body: String?,
) : Exception("Spotizer API error $statusCode: ${body?.take(200)}")

/**
 * Thin HTTP client for the Spotizer backend.
 *
 * All calls are suspend + Dispatchers.IO. Base URL and client key come from
 * [SpotizerSettings] so they can be changed at runtime (debug server, etc).
 */
class SpotizerClient(private val settings: SpotizerSettings) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // stream/download endpoints may block while server prepares
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun apiUrl(vararg segments: String): HttpUrl.Builder {
        val builder = settings.serverBaseUrl.toHttpUrl().newBuilder().addPathSegment("v1")
        segments.forEach { builder.addPathSegment(it) }
        return builder
    }

    /** Public because the download manager needs raw byte access with Range headers. */
    fun newRequest(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        settings.clientKey?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-Client-Key", it)
        }
        return builder
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        http.newCall(request).execute()
    }

    private suspend fun getText(url: HttpUrl): String {
        val response = execute(newRequest(url).get().build())
        response.use {
            val body = it.body?.string()
            if (!it.isSuccessful) throw SpotizerApiException(it.code, body)
            return body ?: ""
        }
    }

    private suspend fun postText(url: HttpUrl, jsonBody: String): String {
        val request = newRequest(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        val response = execute(request)
        response.use {
            val body = it.body?.string()
            if (!it.isSuccessful) throw SpotizerApiException(it.code, body)
            return body ?: ""
        }
    }

    // ---------- catalog ----------

    private fun searchUrl(query: String, type: SpotizerSearchType, limit: Int, offset: Int) =
        apiUrl("search")
            .addQueryParameter("q", query)
            .addQueryParameter("type", type.wire)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()

    suspend fun searchTracks(query: String, limit: Int = 25, offset: Int = 0): SpotizerTrackSearchResponse =
        json.decodeFromString(getText(searchUrl(query, SpotizerSearchType.Track, limit, offset)))

    suspend fun searchAlbums(query: String, limit: Int = 25, offset: Int = 0): SpotizerAlbumSearchResponse =
        json.decodeFromString(getText(searchUrl(query, SpotizerSearchType.Album, limit, offset)))

    suspend fun searchArtists(query: String, limit: Int = 25, offset: Int = 0): SpotizerArtistSearchResponse =
        json.decodeFromString(getText(searchUrl(query, SpotizerSearchType.Artist, limit, offset)))

    suspend fun getTrack(trackId: String): SpotizerTrack =
        json.decodeFromString(getText(apiUrl("tracks", trackId).build()))

    suspend fun getAlbum(albumId: String): SpotizerAlbum =
        json.decodeFromString(getText(apiUrl("albums", albumId).build()))

    suspend fun getArtist(artistId: String): SpotizerArtist =
        json.decodeFromString(getText(apiUrl("artists", artistId).build()))

    // ---------- tracks: status / stream / download / lyrics ----------

    suspend fun getTrackStatus(trackId: String, quality: String): TrackStatusResponse =
        json.decodeFromString(
            getText(
                apiUrl("tracks", trackId, "status")
                    .addQueryParameter("quality", quality)
                    .build()
            )
        )

    /** URL for ExoPlayer / Media3 — server supports Range so seeking works out of the box. */
    fun streamUrl(trackId: String, quality: String): String =
        apiUrl("tracks", trackId, "stream")
            .addQueryParameter("quality", quality)
            .build()
            .toString()

    /** URL for downloading the track file (Content-Disposition: attachment, Range supported). */
    fun downloadUrl(trackId: String, quality: String, userId: Long?): HttpUrl {
        val builder = apiUrl("tracks", trackId, "download")
            .addQueryParameter("quality", quality)
        userId?.let { builder.addQueryParameter("user_id", it.toString()) }
        return builder.build()
    }

    suspend fun getLyrics(trackId: String, quality: String): String? = try {
        getText(
            apiUrl("tracks", trackId, "lyrics")
                .addQueryParameter("quality", quality)
                .build()
        )
    } catch (e: SpotizerApiException) {
        if (e.statusCode == 404) null else throw e
    }

    // ---------- server-side download jobs (album/playlist zip flow) ----------

    suspend fun createDownloadJob(request: DownloadRequest): DownloadCreatedResponse =
        json.decodeFromString(
            postText(apiUrl("downloads").build(), json.encodeToString(DownloadRequest.serializer(), request))
        )

    suspend fun getDownloadJob(jobId: String): JobStatusResponse =
        json.decodeFromString(getText(apiUrl("downloads", jobId).build()))

    suspend fun cancelDownloadJob(jobId: String): JobStatusResponse {
        val request = newRequest(apiUrl("downloads", jobId).build()).delete().build()
        val response = execute(request)
        response.use {
            val body = it.body?.string()
            if (!it.isSuccessful) throw SpotizerApiException(it.code, body)
            return json.decodeFromString(body ?: "")
        }
    }

    /** Resolve a relative temp_url (e.g. /v1/files/{token}) against the server base. */
    fun resolveFileUrl(tempUrl: String): HttpUrl =
        settings.serverBaseUrl.toHttpUrl().newBuilder(tempUrl)?.build()
            ?: throw IllegalArgumentException("Bad temp_url: $tempUrl")

    // ---------- users ----------

    suspend fun resolveUser(request: ResolveRequest): ResolveResponse =
        json.decodeFromString(
            postText(apiUrl("users", "resolve").build(), json.encodeToString(ResolveRequest.serializer(), request))
        )

    suspend fun linkWithCode(request: LinkRequest): ResolveResponse =
        json.decodeFromString(
            postText(apiUrl("users", "link").build(), json.encodeToString(LinkRequest.serializer(), request))
        )

    suspend fun updateServerSettings(userId: Long, update: SettingsUpdate): SpotizerServerSettings {
        val url = apiUrl("users", userId.toString(), "settings").build()
        val request = newRequest(url)
            .patch(
                json.encodeToString(SettingsUpdate.serializer(), update)
                    .toRequestBody("application/json".toMediaType())
            )
            .build()
        val response = execute(request)
        response.use {
            val body = it.body?.string()
            if (!it.isSuccessful) throw SpotizerApiException(it.code, body)
            return json.decodeFromString(body ?: "")
        }
    }
}
