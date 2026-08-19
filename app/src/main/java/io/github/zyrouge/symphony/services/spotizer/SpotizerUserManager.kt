package io.github.zyrouge.symphony.services.spotizer

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SpotizerUserManager(
    private val context: Context,
    private val settings: SpotizerSettings,
    private val client: SpotizerClient,
) {
    private val mutex = Mutex()

    /**
     * Returns the backend user id, resolving and caching it on first use.
     * Returns null when the backend is unreachable (callers treat the id as optional).
     */
    @SuppressLint("HardwareIds")
    suspend fun ensureUser(): String? = mutex.withLock {
        val cached = settings.userId.value
        if (cached.isNotBlank()) {
            return cached
        }
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
        if (androidId.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val user = client.resolveUser(
                platformUserId = "android_" + androidId,
                displayName = "Symphony Android",
            )
            user.userId?.toString()?.also { settings.setUserId(it) }
        }.getOrNull()
    }

    /** Pushes the currently selected download quality to the backend user settings. */
    suspend fun syncQualityToServer() {
        runCatching {
            val userId = ensureUser() ?: return
            client.updateUserSettings(userId, settings.downloadQuality.value)
        }
    }
}
