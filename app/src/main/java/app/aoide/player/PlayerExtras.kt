package app.aoide.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.aoide.data.Catalog
import app.aoide.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything that needs the real ExoPlayer rather than a session command: fades, skip silence,
 * speed, the sleep timer, pause on mute, resume when headphones come back, autoplay when the queue
 * runs out, and the equaliser's audio session. Runs in the service, on the player's thread.
 */
@UnstableApi
class PlayerExtras(context: Context, private val exo: ExoPlayer) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fadeJob: Job? = null
    private var mutedPause = false
    private var noisyPause = false
    private var autoplayedFrom = ""
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (noisyPause && Prefs.resumeOnBluetooth.on && added.any { it.isSink && it.type in HEADSETS } && exo.mediaItemCount > 0 && !exo.playWhenReady) {
                noisyPause = false
                play()
            }
        }
    }

    fun start() {
        exo.addListener(this)
        scope.launch { Prefs.skipSilence.value.collect { exo.skipSilenceEnabled = it } }
        scope.launch { Prefs.speed.collect { exo.setPlaybackSpeed(it.coerceIn(0.5f, 2f)) } }
        runCatching { audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper())) }
        if (exo.audioSessionId != C.AUDIO_SESSION_ID_UNSET) AudioEffects.attach(exo.audioSessionId)
        scope.launch { while (isActive) { runCatching { tick() }; delay(250) } }
    }

    fun stop() {
        exo.removeListener(this)
        runCatching { audio.unregisterAudioDeviceCallback(devices) }
        scope.cancel()
        AudioEffects.release()
    }

    /* ---------- play / pause with a fade ---------- */

    fun play() {
        val fade = Prefs.fadeMs.value
        fadeJob?.cancel()
        if (fade <= 0 || exo.playbackState != Player.STATE_READY) {
            exo.volume = 1f
            exo.play()
            return
        }
        exo.volume = 0f
        exo.play()
        ramp(0f, 1f, minOf(fade, 600))
    }

    fun pause() {
        val fade = Prefs.fadeMs.value
        fadeJob?.cancel()
        if (fade <= 0 || !exo.isPlaying) {
            exo.pause()
            exo.volume = 1f
            return
        }
        ramp(exo.volume, 0f, minOf(fade, 600)) { exo.pause(); exo.volume = 1f }
    }

    private fun ramp(from: Float, to: Float, ms: Int, then: (() -> Unit)? = null) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = (ms / 40).coerceIn(4, 40)
            for (i in 1..steps) {
                exo.volume = (from + (to - from) * i / steps).coerceIn(0f, 1f)
                delay((ms / steps).toLong())
            }
            then?.invoke()
        }
    }

    /* ---------- the quarter-second tick ---------- */

    private fun tick() {
        SleepTimer.endAt.value?.let { end -> if (System.currentTimeMillis() >= end) { SleepTimer.cancel(); pause() } }
        if (Prefs.pauseOnMute.on) {
            val muted = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            if (muted && exo.isPlaying) { mutedPause = true; exo.pause() }
            else if (!muted && mutedPause) { mutedPause = false; if (!exo.playWhenReady && exo.mediaItemCount > 0) exo.play() }
        } else mutedPause = false
        // Fade the song out over its last moments when another one follows; the next fades in on the transition.
        val fade = Prefs.fadeMs.value
        if (fade > 0 && exo.isPlaying && fadeJob?.isActive != true) {
            val dur = exo.duration
            if (dur > 0) {
                val remaining = dur - exo.currentPosition
                val another = exo.hasNextMediaItem() || exo.repeatMode != Player.REPEAT_MODE_OFF
                if (another && remaining in 0..fade.toLong()) exo.volume = (remaining.toFloat() / fade).coerceIn(0.02f, 1f)
                else if (exo.volume < 1f) exo.volume = 1f
            }
        }
    }

    /* ---------- player events ---------- */

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            if (SleepTimer.endOfTrack.value) {
                SleepTimer.cancel()
                fadeJob?.cancel()
                exo.pause()
                exo.volume = 1f
                return
            }
            val fade = Prefs.fadeMs.value
            if (fade > 0) ramp(0f, 1f, fade) else exo.volume = 1f
        }
        maybeAutoplay()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) noisyPause = true
        if (playWhenReady) noisyPause = false
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) = AudioEffects.attach(audioSessionId)

    /** When the last song of the queue starts, line up songs like it, once per queue. */
    private fun maybeAutoplay() {
        if (!Prefs.autoplay.on || exo.repeatMode != Player.REPEAT_MODE_OFF) return
        if (exo.mediaItemCount == 0 || exo.currentMediaItemIndex != exo.mediaItemCount - 1) return
        val id = exo.currentMediaItem?.mediaId ?: return
        if (id.startsWith("local:") || id == autoplayedFrom) return
        autoplayedFrom = id
        scope.launch {
            val recs = withContext(Dispatchers.IO) { runCatching { Catalog.radio(id) }.getOrDefault(emptyList()) }
            val have = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }.toSet()
            val fresh = recs.filter { it.id !in have }.distinctBy { it.id }.take(15)
            if (fresh.isEmpty()) return@launch
            exo.addMediaItems(fresh.map { AoideMedia.toPlayable(AoideMedia.mediaItemFor(it, autoplay = true)) })
        }
    }

    private companion object {
        val HEADSETS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, 26 /* BLE headset */)
    }
}

/** The session's player: play and pause go through [PlayerExtras] so they can fade. */
@UnstableApi
class FadingPlayer(exo: ExoPlayer, private val extras: PlayerExtras) : ForwardingPlayer(exo) {
    override fun play() = extras.play()
    override fun pause() = extras.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) extras.play() else extras.pause() }
}
