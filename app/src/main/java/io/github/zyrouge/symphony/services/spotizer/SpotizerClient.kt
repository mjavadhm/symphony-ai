package io.github.zyrouge.symphony.services.spotizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SpotizerApiException(val statusCode: Int, message: String) : Exception(message)

class SpotizerClient(private val settings: SpotizerSettings) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl() = settings.serverBaseUrl.value.trimEnd('/')

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun buildRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        val clientKey = settings.clientKey.value
        if (clientKey.isNotBlank()) {
            builder.header("X-Client-Key", clientKey)
        }
        return builder
    }

    private fun getBody(url: String): String {
        http.newCall(buildRequest(url).get().build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw SpotizerApiException(
                    response.code,
                    "Spotizer API error " + response.code + " for " + url,
                )
            }
            return body
        }
    }

    suspend fun searchTracks(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): SpotizerTrackSearchResponse = withContext(Dispatchers.IO) {
        json.decodeFromString<SpotizerTrackSearchResponse>(
            getBody(
                baseUrl() + "/v1/search?q=" + encode(query) +
                        "&type=track&limit=" + limit + "&offset=" + offset
            )
        )
    }

    suspend fun searchAlbums(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): SpotizerAlbumSearchResponse = withContext(Dispatchers.IO) {
        json.decodeFromString<SpotizerAlbumSearchResponse>(
            getBody(
                baseUrl() + "/v1/search?q=" + encode(query) +
                        "&type=album&limit=" + limit + "&offset=" + offset
            )
        )
    }

    suspend fun searchArtists(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): SpotizerArtistSearchResponse = withContext(Dispatchers.IO) {
        json.decodeFromString<SpotizerArtistSearchResponse>(
            getBody(
                baseUrl() + "/v1/search?q=" + encode(query) +
                        "&type=artist&limit=" + limit + "&offset=" + offset
            )
        )
    }

    suspend fun getAlbum(id: Long): SpotizerAlbum = withContext(Dispatchers.IO) {
        json.decodeFromString<SpotizerAlbum>(getBody(baseUrl() + "/v1/albums/" + id))
    }

    suspend fun getArtist(id: Long): SpotizerArtist = withContext(Dispatchers.IO) {
        json.decodeFromString<SpotizerArtist>(getBody(baseUrl() + "/v1/artists/" + id))
    }

    suspend fun getTrackStatus(id: Long, quality: String): SpotizerTrackStatus =
        withContext(Dispatchers.IO) {
            json.decodeFromString<SpotizerTrackStatus>(
                getBody(baseUrl() + "/v1/tracks/" + id + "/status?quality=" + encode(quality))
            )
        }

    suspend fun resolveUser(platformUserId: String, displayName: String): SpotizerUser =
        withContext(Dispatchers.IO) {
            val payload = "{\"platform_user_id\":" + json.encodeToString(
                kotlinx.serialization.serializer<String>(), platformUserId
            ) + ",\"display_name\":" + json.encodeToString(
                kotlinx.serialization.serializer<String>(), displayName
            ) + "}"
            val request = buildRequest(baseUrl() + "/v1/users/resolve")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw SpotizerApiException(response.code, "Could not resolve Spotizer user")
                }
                json.decodeFromString<SpotizerUser>(body)
            }
        }

    suspend fun updateUserSettings(userId: String, quality: String) =
        withContext(Dispatchers.IO) {
            val payload = "{\"quality\":" + json.encodeToString(
                kotlinx.serialization.serializer<String>(), quality
            ) + "}"
            val request = buildRequest(baseUrl() + "/v1/users/" + encode(userId) + "/settings")
                .patch(payload.toRequestBody(jsonMediaType))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SpotizerApiException(response.code, "Could not update Spotizer settings")
                }
            }
        }

    fun streamUrl(id: Long, quality: String): String =
        baseUrl() + "/v1/tracks/" + id + "/stream?quality=" + encode(quality)

    fun downloadUrl(id: Long, quality: String, userId: String? = null): String {
        var url = baseUrl() + "/v1/tracks/" + id + "/download?quality=" + encode(quality)
        if (!userId.isNullOrBlank()) {
            url += "&user_id=" + encode(userId)
        }
        return url
    }
}
