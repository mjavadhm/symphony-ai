package io.github.zyrouge.symphony.services.spotizer

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Lightweight MediaPlayer wrapper used for Spotizer online streaming. */
class SpotizerStreamPlayer {
    enum class State { Idle, Buffering, Playing, Paused, Completed, Error }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaPlayer: MediaPlayer? = null

    private val _track = MutableStateFlow<SpotizerTrack?>(null)
    val track: StateFlow<SpotizerTrack?> = _track

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    init {
        scope.launch {
            while (true) {
                delay(500)
                val player = mediaPlayer
                if (player != null && _state.value == State.Playing) {
                    runCatching {
                        _positionMs.value = player.currentPosition.toLong()
                    }
                }
            }
        }
    }

    fun play(nTrack: SpotizerTrack, url: String) {
        releasePlayer()
        _track.value = nTrack
        _state.value = State.Buffering
        _positionMs.value = 0L
        _durationMs.value = nTrack.durationMs
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(url)
            player.setOnPreparedListener {
                it.start()
                if (it.duration > 0) {
                    _durationMs.value = it.duration.toLong()
                }
                _state.value = State.Playing
            }
            player.setOnCompletionListener {
                _state.value = State.Completed
            }
            player.setOnErrorListener { _, _, _ ->
                _state.value = State.Error
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            _state.value = State.Error
        }
    }

    fun toggle() {
        val player = mediaPlayer ?: return
        when (_state.value) {
            State.Playing -> {
                runCatching { player.pause() }
                _state.value = State.Paused
            }
            State.Paused -> {
                runCatching { player.start() }
                _state.value = State.Playing
            }
            State.Completed -> {
                runCatching {
                    player.seekTo(0)
                    player.start()
                    _state.value = State.Playing
                }
            }
            else -> {}
        }
    }

    fun seekTo(ms: Long) {
        val player = mediaPlayer ?: return
        when (_state.value) {
            State.Playing, State.Paused, State.Completed -> {
                runCatching {
                    player.seekTo(ms.toInt())
                    _positionMs.value = ms
                }
            }
            else -> {}
        }
    }

    fun stop() {
        releasePlayer()
        _track.value = null
        _state.value = State.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
    }
}
