package com.voiceforge.app.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single playback slot shared by every screen (result player, voice preview,
 * queue/saved playback): one ExoPlayer, one [currentFile].
 *
 * Position/duration are polled every 100 ms while playing — that drives the
 * waveform playhead without a Compose-side timer.
 */
class AppAudioPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var pollJob: Job? = null

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** Plays [file]; resumes it when it is already the loaded file. */
    fun play(file: File, startAtMs: Long = 0L) {
        val exo = ensurePlayer()
        if (_currentFile.value != file) {
            _currentFile.value = file
            _positionMs.value = startAtMs.coerceAtLeast(0L)
            _durationMs.value = 0L
            exo.stop()
            exo.setMediaItem(MediaItem.fromUri(file.absolutePath), startAtMs.coerceAtLeast(0L))
            exo.prepare()
        } else if (startAtMs > 0L) {
            seekTo(startAtMs)
        } else if (exo.playbackState == Player.STATE_ENDED) {
            seekTo(0L)                       // replay from the start
        }
        exo.playWhenReady = true
        exo.play()
    }

    /** Play/pause toggle for [file] (starts it when something else is loaded). */
    fun toggle(file: File) {
        if (_currentFile.value == file && _isPlaying.value) pause() else play(file)
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        player?.stop()
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        stopPolling()
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        player?.seekTo(target)
        _positionMs.value = target
    }

    fun release() {
        stopPolling()
        scope.coroutineContext[Job]?.cancel()
        player?.release()
        player = null
        _currentFile.value = null
        _isPlaying.value = false
    }

    // ------------------------------------------------------------- internals

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val exo = ExoPlayer.Builder(appContext).build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) startPolling() else {
                    stopPolling()
                    snapshot()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) snapshot()
            }
        })
        player = exo
        return exo
    }

    private fun snapshot() {
        val exo = player ?: return
        _positionMs.value = exo.currentPosition
        val d = exo.duration
        if (d > 0) _durationMs.value = d
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                snapshot()
                delay(100)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
