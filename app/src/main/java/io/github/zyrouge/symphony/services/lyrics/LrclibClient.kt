package io.github.zyrouge.symphony.services.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LyricsApiException(val code: Int, val retryAfterMs: Long = 0) : IOException("Lyrics API HTTP $code")

/** A configurable LRCLIB-compatible root URL, not a universal lyrics-provider protocol. */
class LrclibClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).build()) {
    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net"
        fun normalizeBaseUrl(value: String): String {
            val url = value.trim().trimEnd('/').removeSuffix("/api").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid HTTPS URL")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null) { "Use an HTTPS root URL without credentials or query parameters" }
            return url.toString().trimEnd('/')
        }
        internal fun parseRecord(value: JsonObject): LyricsRecord = LyricsRecord(
            id = value["id"]?.jsonPrimitive?.longOrNull ?: 0,
            trackName = value["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            artistName = value["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            albumName = value["albumName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            duration = value["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            instrumental = value["instrumental"]?.jsonPrimitive?.booleanOrNull ?: false,
            syncedLyrics = value["syncedLyrics"]?.jsonPrimitive?.contentOrNull,
            plainLyrics = value["plainLyrics"]?.jsonPrimitive?.contentOrNull,
        )
    }
    @Volatile private var nextRequestAt = 0L

    suspend fun find(baseUrl: String, track: LyricsTrack): LyricsCandidate? {
        if (track.title.isBlank() || track.artists.none { it.isNotBlank() }) return null
        val root = normalizeBaseUrl(baseUrl).toHttpUrlOrNull()!!
        val exact = root.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artists.first())
            .apply {
                track.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) }
                if (track.durationSeconds in 1.0..3600.0) addQueryParameter("duration", track.durationSeconds.toString())
            }.build()
        val records = mutableListOf<LyricsRecord>()
        get(exact)?.let { records += parseRecord(Json.parseToJsonElement(it).jsonObject) }
        LyricsMatch.select(track, records)?.takeIf { it.synced }?.let { return it }
        // A plain exact result must not hide a synchronized result available in search.
        val search = root.newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artists.first()).build()
        get(search)?.let { body ->
            records += Json.parseToJsonElement(body).jsonArray.take(20).map { parseRecord(it.jsonObject) }
        }
        return LyricsMatch.select(track, records)
    }

    private suspend fun get(url: HttpUrl): String? {
        delay((nextRequestAt - System.currentTimeMillis()).coerceAtLeast(0))
        val request = Request.Builder().url(url)
            .header("User-Agent", "Symphony/2024.12.115 (https://github.com/mjavadhm/symphony-ai)")
            .header("Accept", "application/json").build()
        nextRequestAt = System.currentTimeMillis() + 500L
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            if (it.code == 404) return@use null
                            if (!it.isSuccessful) {
                                val retry = it.header("Retry-After")?.let { header ->
                                    header.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(Long.MAX_VALUE / 2000)?.times(1000)
                                        ?: runCatching {
                                            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                                                .toInstant().toEpochMilli() - System.currentTimeMillis()
                                        }.getOrNull()
                                } ?: 60_000L
                                if (it.code == 429 || it.code == 503) {
                                    // Stop the batch; a subsequent retry still respects the server's cooldown.
                                    nextRequestAt = System.currentTimeMillis() + retry.coerceAtLeast(1000) + 500
                                }
                                throw LyricsApiException(it.code, retry)
                            }
                            val body = it.body ?: throw IOException("Empty lyrics response")
                            if (!body.contentType().toString().contains("json", ignoreCase = true)) {
                                throw IOException("The server did not return JSON; check the LRCLIB-compatible Base URL")
                            }
                            val source = body.source()
                            source.request(2_000_001)
                            if (source.buffer.size > 2_000_000) throw IOException("Lyrics response is too large")
                            body.string()
                        }
                        nextRequestAt = maxOf(nextRequestAt, System.currentTimeMillis() + 300L)
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
