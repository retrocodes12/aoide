package app.aoide.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.aoide.data.Catalog
import app.aoide.data.Library
import app.aoide.data.PlayContext
import app.aoide.data.Prefs
import app.aoide.data.Track
import app.aoide.data.json
import app.aoide.ui.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

enum class Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

data class PlayerUiState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val status: Status = Status.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val context: PlayContext? = null,
    val error: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    val isPlaying: Boolean get() = status == Status.PLAYING || status == Status.LOADING
    val upcoming: List<Track> get() = if (index < 0) emptyList() else queue.drop(index + 1)
}

/** App-side handle on the session. One per process; the UI observes [state]. */
object PlayerController {
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    private var context: PlayContext? = null
    private var pendingShuffle = false

    fun connect(appContext: Context) {
        if (controller != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            _state.value = _state.value.copy(shuffle = pendingShuffle)
            sync()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            trackOf(mediaItem)?.let { Library.recordPlay(it) }
            sync()
        }
        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            _state.value = _state.value.copy(status = Status.ERROR, error = friendly(error))
            Toasts.show("Couldn't play \"${_state.value.current?.title ?: "that"}\". Skipping.")
            scope.launch {
                delay(900)
                if (c.hasNextMediaItem()) {
                    c.seekToNextMediaItem()
                    c.prepare()
                    c.play()
                } else {
                    c.stop()
                }
            }
        }
    }

    private fun friendly(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The mirror refused this track"
        else -> "Playback failed"
    }

    private fun sync() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).mapNotNull { trackOf(c.getMediaItemAt(it)) }
        val status = when {
            c.playerError != null -> Status.ERROR
            c.mediaItemCount == 0 -> Status.IDLE
            c.playbackState == Player.STATE_BUFFERING && c.playWhenReady -> Status.LOADING
            c.isPlaying -> Status.PLAYING
            c.playWhenReady && c.playbackState == Player.STATE_READY -> Status.PLAYING
            else -> Status.PAUSED
        }
        val cur = items.getOrNull(c.currentMediaItemIndex)
        val dur = if (c.duration > 0) c.duration else (cur?.duration ?: 0) * 1000L
        _state.value = _state.value.copy(
            queue = items,
            index = if (items.isEmpty()) -1 else c.currentMediaItemIndex,
            status = status,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = dur,
            repeat = c.repeatMode,
            context = context,
            error = if (status == Status.ERROR) _state.value.error else null,
        )
        if (status == Status.PLAYING || status == Status.LOADING) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val c = controller ?: break
                val dur = if (c.duration > 0) c.duration else _state.value.durationMs
                _state.value = _state.value.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = dur)
                delay(250)
            }
        }
    }

    private fun trackOf(item: MediaItem?): Track? = item?.mediaMetadata?.extras?.getString("track")?.let { runCatching { json.decodeFromString<Track>(it) }.getOrNull() }

    private fun mediaItem(t: Track): MediaItem {
        val extras = Bundle().apply { putString("track", json.encodeToString(t)) }
        val meta = MediaMetadata.Builder()
            .setTitle(t.title + (t.version?.let { " - $it" } ?: ""))
            .setArtist(t.artistNames)
            .setAlbumTitle(t.album?.title)
            .setArtworkUri(Catalog.cover(t.album?.cover, 640)?.let(Uri::parse))
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(t.id.toString()).setMediaMetadata(meta).build()
    }

    /* ---------- commands ---------- */

    fun playTracks(tracks: List<Track>, start: Int, ctx: PlayContext?) {
        val c = controller ?: return
        val playable = tracks.filter { it.duration > 0 }
        if (playable.isEmpty()) return
        var list = playable
        var index = start.coerceIn(0, playable.lastIndex)
        if (_state.value.shuffle) {
            val first = playable[index]
            list = listOf(first) + playable.filterIndexed { i, _ -> i != index }.shuffled()
            index = 0
        }
        context = ctx
        c.setMediaItems(list.map(::mediaItem), index, 0)
        c.prepare()
        c.play()
        sync()
    }

    fun playTrack(t: Track, ctx: PlayContext? = PlayContext("track", t.title)) = playTracks(listOf(t), 0, ctx)

    fun toggle() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.playerError != null) {
            c.prepare()
            c.play()
            return
        }
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) c.pause() else c.play()
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else if (c.repeatMode == Player.REPEAT_MODE_ALL && c.mediaItemCount > 0) c.seekToDefaultPosition(0) else c.pause()
    }

    fun prev() {
        val c = controller ?: return
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
        _state.value = _state.value.copy(positionMs = ms.coerceAtLeast(0))
    }

    fun toggleShuffle() {
        val c = controller
        val on = !_state.value.shuffle
        pendingShuffle = on
        _state.value = _state.value.copy(shuffle = on)
        if (c == null || !on || c.mediaItemCount < 2) return
        // Reorder the real queue: current first, the rest shuffled.
        val cur = c.currentMediaItemIndex
        if (cur > 0) c.moveMediaItem(cur, 0)
        val n = c.mediaItemCount
        for (i in n - 1 downTo 2) {
            val j = 1 + (Math.random() * i).toInt()
            if (j != i) {
                c.moveMediaItem(i, j)
                c.moveMediaItem(j + 1, i)
            }
        }
        sync()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun enqueueNext(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTrack(t)
        c.addMediaItem(c.currentMediaItemIndex + 1, mediaItem(t))
        sync()
    }

    fun enqueueLast(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTrack(t)
        c.addMediaItem(mediaItem(t))
        sync()
    }

    fun removeAt(i: Int) {
        val c = controller ?: return
        if (i == c.currentMediaItemIndex || i !in 0 until c.mediaItemCount) return
        c.removeMediaItem(i)
        sync()
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        if (from == to || from <= cur || to <= cur || from >= c.mediaItemCount || to >= c.mediaItemCount) return
        c.moveMediaItem(from, to)
        sync()
    }

    fun clearUpcoming() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        if (c.mediaItemCount > cur + 1) c.removeMediaItems(cur + 1, c.mediaItemCount)
        sync()
    }

    fun jumpTo(i: Int) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(i)
        c.play()
    }

    /** Re-open the current track at the new quality, keeping the position. */
    fun reloadCurrent() {
        val c = controller ?: return
        val cur = _state.value.current ?: return
        StreamResolver.forget(cur.id)
        val pos = c.currentPosition
        val wasPlaying = c.isPlaying
        val idx = c.currentMediaItemIndex
        c.replaceMediaItem(idx, mediaItem(cur))
        c.seekTo(idx, pos)
        c.prepare()
        if (wasPlaying) c.play()
    }

    fun quality() = Prefs.quality.value
}
