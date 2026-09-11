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
import app.aoide.ui.AppUi
import app.aoide.ui.Toasts
import app.aoide.ui.theme.Tint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

enum class Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

data class PlayerUiState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val status: Status = Status.IDLE,
    val positionMs: Long = 0,
    /** The media's own length, 0 until it is known. Never the catalogue's metadata: a preview is 30 s, whatever the song is. */
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

/** The last queue, paused where it was, so a relaunch comes back on the same song. */
@Serializable
private data class Session(val queue: List<Track>, val index: Int, val positionMs: Long, val context: PlayContext? = null, val shuffle: Boolean = false, val repeat: Int = 0)

/** App-side handle on the session. One per process; the UI observes [state]. */
object PlayerController {
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    private var context: PlayContext? = null
    private var pendingShuffle = false
    /** The queue as it was before shuffle, so turning shuffle off restores the order. */
    private var unshuffled: List<Track>? = null
    /** Consecutive songs that failed to load; after three we stop skipping ahead and ask the listener. */
    private var failStreak = 0
    private var sessionFile: File? = null
    private var lastSave = 0L
    private var lastTintId = -1L
    private var tick = 0

    fun connect(appContext: Context) {
        if (controller != null) return
        sessionFile = File(appContext.filesDir, "session.json")
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            _state.value = _state.value.copy(shuffle = pendingShuffle)
            if (c.mediaItemCount == 0) restoreSession(c)
            sync()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            trackOf(mediaItem)?.let { Library.recordPlay(it) }
            sync()
            saveSession(force = true)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) failStreak = 0
            else saveSession(force = true)
        }
        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            failStreak++
            val cur = _state.value.current
            if (failStreak >= 3 || !c.hasNextMediaItem()) {
                // Three dead songs in a row is not a bad song, it is a dead connection or a dead mirror. Stop and say so.
                _state.value = _state.value.copy(status = Status.ERROR, error = "Playback isn't working right now. Check your connection, then try again.")
                return
            }
            _state.value = _state.value.copy(status = Status.ERROR, error = friendly(error))
            Toasts.show("Couldn't play \"${cur?.title ?: "that"}\". Trying the next one.")
            scope.launch {
                delay(1200)
                if (_state.value.status == Status.ERROR && c.hasNextMediaItem()) {
                    c.seekToNextMediaItem()
                    c.prepare()
                    c.play()
                }
            }
        }
    }

    /** Media3's codes, in words a listener can act on. Never show the raw number. */
    private fun friendly(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network error while loading this song"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The mirror refused this song"
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This song's stream couldn't be decoded"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This song's stream was unreadable"
        else -> "Couldn't play this song"
    }

    private fun sync() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).mapNotNull { trackOf(c.getMediaItemAt(it)) }
        val status = when {
            c.playerError != null || _state.value.status == Status.ERROR && c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0 && !c.playWhenReady -> Status.ERROR
            c.mediaItemCount == 0 -> Status.IDLE
            c.playbackState == Player.STATE_BUFFERING && c.playWhenReady -> Status.LOADING
            c.isPlaying -> Status.PLAYING
            c.playWhenReady && c.playbackState == Player.STATE_READY -> Status.PLAYING
            else -> Status.PAUSED
        }
        val cur = items.getOrNull(c.currentMediaItemIndex)
        val dur = if (c.duration > 0 && c.playbackState != Player.STATE_IDLE) c.duration else if (c.playbackState == Player.STATE_IDLE) _state.value.durationMs else 0L
        _state.value = _state.value.copy(
            queue = items,
            index = if (items.isEmpty()) -1 else c.currentMediaItemIndex,
            status = status,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = dur,
            repeat = c.repeatMode,
            context = context,
            error = if (status == Status.ERROR) _state.value.error ?: "Couldn't play this song" else null,
        )
        if (cur != null && cur.id != lastTintId) {
            lastTintId = cur.id
            AppUi.player = Tint.of(cur.album?.vibrantColor)
        }
        if (status == Status.PLAYING || status == Status.LOADING) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val c = controller ?: break
                val dur = if (c.duration > 0) c.duration else 0L
                _state.value = _state.value.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = dur)
                saveSession()
                // The full session is throttled to every 4 s; the position alone is cheap enough to note every second.
                if (tick++ % 4 == 0) _state.value.current?.let { Prefs.putLong("pos:${it.id}", _state.value.positionMs) }
                delay(250)
            }
        }
    }

    private fun trackOf(item: MediaItem?): Track? = item?.mediaMetadata?.extras?.getString("track")?.let { runCatching { json.decodeFromString<Track>(it) }.getOrNull() }

    private fun mediaItem(t: Track): MediaItem {
        TrackRegistry.put(t)
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

    /* ---------- session ---------- */

    private fun saveSession(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 4000) return
        lastSave = now
        val s = _state.value
        val f = sessionFile ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (s.index < 0) f.delete()
                else f.writeText(json.encodeToString(Session(s.queue.take(300), s.index, s.positionMs, s.context, s.shuffle, s.repeat)))
            }
        }
    }

    /** Put the last queue back, paused at the saved position; nothing loads until the listener presses play. */
    private fun restoreSession(c: MediaController) {
        val f = sessionFile ?: return
        val s = runCatching { json.decodeFromString<Session>(f.readText()) }.getOrNull() ?: return
        if (s.queue.isEmpty() || s.index !in s.queue.indices) return
        context = s.context
        pendingShuffle = s.shuffle
        val latest = Prefs.getLong("pos:${s.queue[s.index].id}").takeIf { it >= 0 } ?: s.positionMs
        c.setMediaItems(s.queue.map(::mediaItem), s.index, maxOf(s.positionMs, latest))
        c.repeatMode = s.repeat
        c.playWhenReady = false
        _state.value = _state.value.copy(shuffle = s.shuffle, durationMs = 0)
    }

    /* ---------- commands ---------- */

    fun playTracks(tracks: List<Track>, start: Int, ctx: PlayContext?) {
        val c = controller ?: return
        val playable = tracks.filter { it.duration > 0 }
        if (playable.isEmpty()) return
        var list = playable
        var index = start.coerceIn(0, playable.lastIndex)
        unshuffled = null
        if (_state.value.shuffle) {
            unshuffled = playable
            val first = playable[index]
            list = listOf(first) + playable.filterIndexed { i, _ -> i != index }.shuffled()
            index = 0
        }
        context = ctx
        failStreak = 0
        _state.value = _state.value.copy(error = null, status = Status.LOADING, durationMs = 0)
        c.setMediaItems(list.map(::mediaItem), index, 0)
        c.prepare()
        c.play()
        sync()
    }

    fun playTrack(t: Track, ctx: PlayContext? = PlayContext("track", t.title)) = playTracks(listOf(t), 0, ctx)

    fun toggle() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.playerError != null || _state.value.status == Status.ERROR) {
            failStreak = 0
            _state.value = _state.value.copy(error = null, status = Status.LOADING)
            c.prepare()
            c.play()
            return
        }
        if (c.playbackState == Player.STATE_IDLE) {
            // A restored session: nothing is loaded yet, so the first press loads and plays.
            c.prepare()
            c.play()
            return
        }
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) c.pause() else c.play()
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else if (c.repeatMode == Player.REPEAT_MODE_ALL && c.mediaItemCount > 0) c.seekToDefaultPosition(0) else c.pause()
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    fun prev() {
        val c = controller ?: return
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    fun seekTo(ms: Long) {
        val c = controller ?: return
        // Never seek past what the media actually holds (previews end at 30 s).
        val max = if (c.duration > 0) c.duration - 1000 else Long.MAX_VALUE
        val target = ms.coerceIn(0, max.coerceAtLeast(0))
        c.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    fun toggleShuffle() {
        val c = controller
        val on = !_state.value.shuffle
        pendingShuffle = on
        _state.value = _state.value.copy(shuffle = on)
        if (c == null) return
        if (on) {
            if (c.mediaItemCount < 2) return
            unshuffled = _state.value.queue
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
        } else {
            // Back to the order the listener started with, keeping the current song where it is.
            val original = unshuffled
            unshuffled = null
            if (original != null) reorderTo(c, original.map { it.id })
        }
        sync()
        saveSession(force = true)
    }

    /** Move items one by one until the queue matches [ids] (items not in [ids] keep their relative order at the end). */
    private fun reorderTo(c: MediaController, ids: List<Long>) {
        val present = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId.toLong() }.toSet()
        val target = ids.filter { it in present } + (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId.toLong() }.filter { it !in ids }
        for ((to, id) in target.withIndex()) {
            val from = (0 until c.mediaItemCount).first { c.getMediaItemAt(it).mediaId.toLong() == id }
            if (from != to) c.moveMediaItem(from, to)
        }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        saveSession(force = true)
    }

    fun enqueueNext(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTrack(t)
        c.addMediaItem(c.currentMediaItemIndex + 1, mediaItem(t))
        unshuffled = unshuffled?.let { list -> val i = list.indexOfFirst { it.id == _state.value.current?.id }; list.toMutableList().apply { add(i + 1, t) } }
        sync()
        saveSession(force = true)
    }

    fun enqueueLast(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTrack(t)
        c.addMediaItem(mediaItem(t))
        unshuffled = unshuffled?.plus(t)
        sync()
        saveSession(force = true)
    }

    fun removeAt(i: Int) {
        val c = controller ?: return
        if (i == c.currentMediaItemIndex || i !in 0 until c.mediaItemCount) return
        val id = c.getMediaItemAt(i).mediaId.toLong()
        c.removeMediaItem(i)
        unshuffled = unshuffled?.filter { it.id != id }
        sync()
        saveSession(force = true)
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        if (from == to || from <= cur || to <= cur || from >= c.mediaItemCount || to >= c.mediaItemCount) return
        c.moveMediaItem(from, to)
        sync()
        saveSession(force = true)
    }

    fun clearUpcoming() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        if (c.mediaItemCount > cur + 1) c.removeMediaItems(cur + 1, c.mediaItemCount)
        unshuffled = null
        sync()
        saveSession(force = true)
    }

    fun jumpTo(i: Int) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(i)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
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
        _state.value = _state.value.copy(durationMs = 0)
        c.replaceMediaItem(idx, mediaItem(cur))
        c.seekTo(idx, pos)
        c.prepare()
        if (wasPlaying) c.play()
    }

    fun quality() = Prefs.quality.value

    /** Test seam: lets a screenshot test paint the player without audio. */
    fun setStateForTest(s: PlayerUiState) {
        _state.value = s
        s.current?.let { AppUi.player = Tint.of(it.album?.vibrantColor) }
    }
}
