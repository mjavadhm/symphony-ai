package io.github.zyrouge.symphony.services.spotizer

import android.content.Context

/**
 * Facade / composition root for the Spotizer online section.
 *
 * Create one instance and keep it on the Symphony application class:
 *
 *   class Symphony : Application() {
 *       lateinit var spotizer: Spotizer
 *       override fun onCreate() {
 *           super.onCreate()
 *           spotizer = Spotizer(
 *               context = this,
 *               isTrackOnDevice = { track -> /* match against groove.song repository */ false },
 *           )
 *       }
 *   }
 */
class Spotizer(
    context: Context,
    isTrackOnDevice: (SpotizerTrack) -> Boolean = { false },
) {
    val settings = SpotizerSettings(context)
    val client = SpotizerClient(settings)
    val users = SpotizerUserManager(context, client, settings)
    val downloads = SpotizerDownloadManager(
        context = context,
        client = client,
        settings = settings,
        isTrackOnDevice = isTrackOnDevice,
        resolveUserId = { runCatching { users.ensureUser() }.getOrNull() },
    )

    /**
     * Streaming URL for the player. Point Media3 / ExoPlayer (or Symphony's
     * RadioPlayer via Uri) at this URL; the server supports HTTP Range so
     * seeking works out of the box. A cache miss triggers an on-demand server
     * download, so playback may take a few seconds to start the first time.
     */
    fun streamUrl(track: SpotizerTrack): String? =
        track.id?.let { client.streamUrl(it, settings.streamQuality.value) }
}
