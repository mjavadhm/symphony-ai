package io.github.zyrouge.symphony.services.companion

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reference excerpts saved by earlier versions (Wikipedia or typed in by hand). New stories come from the
 * model's own knowledge, so nothing searches for references any more; old ones stay readable and in backups.
 */
object CompanionReferences {
    fun validUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty()
    } == true
}
