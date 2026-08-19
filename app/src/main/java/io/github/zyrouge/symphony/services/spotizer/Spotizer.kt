package io.github.zyrouge.symphony.services.spotizer

import android.content.Context

/**
 * Facade for the Spotizer online integration, exposed as `symphony.spotizer`.
 *
 * @param isTrackOnDevice used to skip downloading tracks that already exist
 *   in the local library (matched by title + artist and roughly by duration).
 * @param onDownloadCompleted called after a download burst finishes saving files,
 *   so the local library can be re-indexed automatically.
 */
class Spotizer(
    context: Context,
    isTrackOnDevice: (SpotizerTrack) -> Boolean,
    onDownloadCompleted: (() -> Unit)? = null,
) {
    val settings = SpotizerSettings(context)
    val client = SpotizerClient(settings)
    val users = SpotizerUserManager(context, settings, client)
    val player = SpotizerStreamPlayer()
    val downloads = SpotizerDownloadManager(
        context = context,
        settings = settings,
        client = client,
        isTrackOnDevice = isTrackOnDevice,
        resolveUserId = { users.ensureUser() },
        onDownloadCompleted = onDownloadCompleted,
    )

    fun streamUrl(track: SpotizerTrack): String? =
        track.id?.let { client.streamUrl(it, settings.streamQuality.value) }

    fun playStream(track: SpotizerTrack) {
        streamUrl(track)?.let { player.play(track, it) }
    }
}
