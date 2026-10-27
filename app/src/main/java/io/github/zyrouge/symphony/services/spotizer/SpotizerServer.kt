package io.github.zyrouge.symphony.services.spotizer

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One URL policy for Home, Spotizer settings and both online clients. */
object SpotizerServer {
    const val DEFAULT = "https://api.spotizer.room237.site"
    const val LEGACY_SPOTIZER_DEFAULT = "https://api.spotizer.javadhm.online"
    private const val LEGACY_HOME_DEFAULT = "http://localhost:8137"

    fun normalize(value: String): String {
        val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTP(S) server URL")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Use a server base URL without credentials, query parameters or a fragment"
        }
        return url.toString().trimEnd('/')
    }

    /** Run only once: preserve custom dedicated settings, otherwise import the old Home field. */
    fun migrate(dedicated: String?, home: String?): String {
        val saved = dedicated?.let { runCatching { normalize(it) }.getOrNull() }
        val legacy = home?.let { runCatching { normalize(it) }.getOrNull() }
        return saved?.takeUnless { it == LEGACY_SPOTIZER_DEFAULT }
            ?: legacy?.takeUnless { it == LEGACY_HOME_DEFAULT }
            ?: DEFAULT
    }
}
