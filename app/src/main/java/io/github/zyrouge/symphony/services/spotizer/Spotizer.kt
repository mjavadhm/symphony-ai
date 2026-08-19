package io.github.zyrouge.symphony.services.spotizer

import android.content.Context

/**
 * Facade for the Spotizer online integration, exposed as `symphony.spotizer`.
 *
 * @param isTrackOnDevice used to skip downloading tracks that already exist
 *   in the local library (matched by title + artist and roughly by duration).
 */
class Spotizer(
    context: Context,
    isTrackOnDevice: (SpotizerTrack) -> Boolean,
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
    )

    fun streamUrl(track: SpotizerTrack): String? =
        track.id?.let { client.streamUrl(it, settings.streamQuality.value) }

    fun playStream(track: SpotizerTrack) {
        streamUrl(track)?.let { player.play(track, it) }
    }
}
