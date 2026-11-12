package io.github.zyrouge.symphony.services.spotizer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpotizerServerTest {
    @Test fun `user URL is trimmed and trailing slash does not duplicate API paths`() {
        assertEquals(SpotizerServer.DEFAULT, SpotizerServer.normalize(" https://api.spotizer.room237.site/ "))
    }
    @Test fun `custom paths and local HTTP servers are supported`() {
        assertEquals("http://localhost:8137/spotizer", SpotizerServer.normalize("http://localhost:8137/spotizer/"))
    }
    @Test fun `invalid schemes credentials queries and fragments are rejected`() {
        listOf("", "ftp://server/music", "https://user:secret@server.test", "https://server.test/?token=abc", "https://server.test/#path").forEach {
            assertThrows(IllegalArgumentException::class.java) { SpotizerServer.normalize(it) }
        }
    }
    @Test fun `old Home setting overrides untouched dedicated default`() {
        assertEquals(SpotizerServer.DEFAULT, SpotizerServer.migrate(SpotizerServer.LEGACY_SPOTIZER_DEFAULT, "https://api.spotizer.room237.site/"))
    }
    @Test fun `old Home custom server migrates when dedicated value was never set`() {
        assertEquals("https://my-server.test/music", SpotizerServer.migrate(null, "https://my-server.test/music/"))
    }
    @Test fun `dedicated custom server is never overwritten by stale Home value`() {
        assertEquals("https://custom.test", SpotizerServer.migrate("https://custom.test/", "https://other.test"))
    }
    @Test fun `explicit current domain is not replaced by stale Home settings`() {
        assertEquals(SpotizerServer.DEFAULT, SpotizerServer.migrate(SpotizerServer.DEFAULT, "https://stale.test"))
    }
    @Test fun `unused legacy defaults move to current default`() {
        assertEquals(SpotizerServer.DEFAULT, SpotizerServer.migrate(SpotizerServer.LEGACY_SPOTIZER_DEFAULT, "http://localhost:8137"))
    }
    @Test fun `quoted numeric IDs from the API remain compatible with Android models`() {
        val response = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SpotizerTrackSearchResponse>(
            """{"type":"track","results":[{"id":"740969222","title":"Pneuma","artist":"TOOL","artist_id":"689","album_id":"108996972"}],"total":1}"""
        )
        assertEquals(740969222L, response.results.single().id)
    }
    @Test fun `invalid legacy URL cannot break startup`() {
        assertEquals(SpotizerServer.DEFAULT, SpotizerServer.migrate("broken", "also broken"))
    }
}
