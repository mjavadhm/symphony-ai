package io.github.zyrouge.symphony.services.spotizer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Real SharedPreferences persistence/flow checks; never touches the user's normal settings. */
class SpotizerSettingsTest {
    private lateinit var context: Context
    private val stores = mutableListOf<SharedPreferences>()
    @Before fun setup() {
        val prefix = "spotizer-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode).also { stores.add(it) }
        }
    }
    @After fun cleanup() { stores.forEach { it.edit().clear().commit() } }

    @Test fun homeValueMigratesAndSubsequentRestartUsesCanonicalValue() {
        context.getSharedPreferences("settings", 0).edit().putString("online_service_base_url", "https://home.test/").commit()
        val settings = SpotizerSettings(context)
        assertEquals("https://home.test", settings.serverBaseUrl.value)
        settings.setServerBaseUrl("https://new.test/")
        assertEquals("https://new.test", SpotizerSettings(context).serverBaseUrl.value)
    }
    @Test fun serverChangeImmediatelyChangesRequestURLsAndClearsOldIdentity() {
        val settings = SpotizerSettings(context)
        val client = SpotizerClient(settings)
        settings.setUserId("42"); settings.setClientKey("old-test-key")
        settings.setServerBaseUrl("https://new.test/")
        assertEquals("https://new.test/v1/tracks/1/stream?quality=MP3_320", client.streamUrl(1, "MP3_320"))
        assertEquals("", settings.userId.value); assertEquals("", settings.clientKey.value)
    }
    @Test fun equivalentServerDoesNotDiscardIdentity() {
        val settings = SpotizerSettings(context)
        settings.setUserId("42")
        settings.setServerBaseUrl(settings.serverBaseUrl.value + "/")
        assertEquals("42", settings.userId.value)
    }
    @Test fun invalidInputDoesNotChangeSavedServer() {
        val settings = SpotizerSettings(context)
        try { settings.setServerBaseUrl("ftp://invalid.test"); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(SpotizerServer.DEFAULT, SpotizerSettings(context).serverBaseUrl.value)
    }
    @Test fun delayedIdentityResponseCannotPolluteAnotherServer() {
        val settings = SpotizerSettings(context)
        val original = settings.serverBaseUrl.value
        settings.setServerBaseUrl("https://new.test")
        assertFalse(settings.setUserIdForServer(original, "old-id"))
        assertEquals("", settings.userId.value)
    }
}
