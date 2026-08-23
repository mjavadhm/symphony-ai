package io.github.zyrouge.symphony.services.radio

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.launch
import kotlin.random.Random

class RadioShorty(private val symphony: Symphony) {
    fun playPause() {
        if (!symphony.radio.hasPlayer) {
            return
        }
        when {
            symphony.radio.isPlaying -> symphony.radio.pause()
            else -> symphony.radio.resume()
        }
    }

    fun seekFromCurrent(offsetSecs: Int) {
        if (!symphony.radio.hasPlayer) {
            return
        }
        symphony.radio.currentPlaybackPosition?.run {
            val to = (played + (offsetSecs * 1000)).coerceIn(0..total)
            symphony.radio.seek(to)
        }
    }

    fun previous(): Boolean {
        return when {
            !symphony.radio.hasPlayer -> false
            symphony.radio.currentPlaybackPosition!!.played <= 3000 && symphony.radio.canJumpToPrevious() -> {
                symphony.radio.jumpToPrevious()
                true
            }

            else -> {
                symphony.radio.seek(0)
                false
            }
        }
    }

    fun skip(): Boolean {
        return when {
            !symphony.radio.hasPlayer -> false
            symphony.radio.canJumpToNext() -> {
                symphony.radio.jumpToNext()
                true
            }

            symphony.radio.queue.currentLoopMode == RadioQueue.LoopMode.Autoplay -> {
                symphony.radio.extendQueueForAutoplay()
                true
            }

            else -> {
                symphony.radio.play(Radio.PlayOptions(index = 0, autostart = false))
                false
            }
        }
    }

    /**
     * Song radio: start an endless station seeded from one song.
     * Clears the queue, plays the seed song, switches to Autoplay loop mode and
     * prefetches a first batch of similar songs so the upcoming queue is visible.
     */
    fun startRadio(songId: String) {
        symphony.radio.stop(ended = false)
        symphony.radio.playbackSource = "radio:$songId"
        symphony.radio.queue.add(songId)
        symphony.radio.queue.setLoopMode(RadioQueue.LoopMode.Autoplay)
        symphony.groove.coroutineScope.launch {
            val next = try {
                symphony.recommendation.getAutoplaySongs(
                    seedSongIds = listOf(songId),
                    excludeSongIds = setOf(songId),
                    anchorSongId = songId,
                )
            } catch (err: Exception) {
                Logger.error("RadioShorty", "start radio prefetch failed", err)
                emptyList()
            }
            if (next.isNotEmpty()) {
                // Appends without touching playback — the seed song keeps playing
                symphony.radio.queue.add(next)
            }
        }
    }

    fun playQueue(
        songIds: List<String>,
        options: Radio.PlayOptions = Radio.PlayOptions(),
        shuffle: Boolean = false,
    ) {
        symphony.radio.stop(ended = false)
        if (songIds.isEmpty()) {
            return
        }
        symphony.radio.queue.add(
            songIds,
            options = options.run {
                copy(index = if (shuffle) Random.nextInt(songIds.size) else options.index)
            }
        )
        symphony.radio.queue.setShuffleMode(shuffle)
    }

    fun playQueue(
        songId: String,
        options: Radio.PlayOptions = Radio.PlayOptions(),
        shuffle: Boolean = false,
    ) = playQueue(listOf(songId), options = options, shuffle = shuffle)

    fun playQueueSmart(songIds: List<String>) {
        symphony.radio.stop(ended = false)
        if (songIds.isEmpty()) {
            return
        }
        symphony.radio.queue.add(
            songIds,
            options = Radio.PlayOptions(index = Random.nextInt(songIds.size)),
        )
        symphony.radio.queue.enableSmartShuffle()
    }
}
