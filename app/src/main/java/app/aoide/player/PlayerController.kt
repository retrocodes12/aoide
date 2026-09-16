package app.aoide.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.aoide.data.Catalog
import app.aoide.data.HiRate
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
import kotlinx.coroutines.withContext
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
internal data class Session(val queue: List<Track>, val index: Int, val positionMs: Long, val context: PlayContext? = null, val shuffle: Boolean = false, val repeat: Int = 0)

/** Where the session lives; read by the app to restore its labels and by the service to restore the queue itself. */
internal object SessionStore {
    fun file(context: Context) = File(context.filesDir, "session.json")
    fun load(context: Context): Session? {
        val s = app.aoide.data.Store.read(file(context)) { json.decodeFromString<Session>(it) } ?: return null
        return if (s.queue.isEmpty() || s.index !in s.queue.indices) null else s
    }
}

/** App-side handle on the session. One per process; the UI observes [state]. */
object PlayerController {
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    /** The playhead, four times a second, on its own: only the transport, the capsule and the lyrics need it, and nothing else should wake with it. */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position
    private var context: PlayContext? = null
    private var pendingShuffle = false
    /** The queue as it was before shuffle, so turning shuffle off restores the order. */
    private var unshuffled: List<Track>? = null
    /** Consecutive songs that failed to load; after three we stop skipping ahead and ask the listener. */
    private var failStreak = 0
    private var sessionFile: File? = null
    private var lastSave = 0L
    private var lastTintId = ""
    /** Songs already swapped to the second source, so a queue is not rebuilt over and over. */
    private val upgraded = HashSet<String>()
    private var tick = 0
    /** The song whose play has been recorded, and how long the current one has been heard. */
    private var countedId: String? = null
    private var listenedMs = 0L
    private var lastTickAt = 0L

    private var appContext: Context? = null
    private var lastWidget: String? = null

    private var connecting = false
    private var upgradeJob: Job? = null

    fun connect(appContext: Context) {
        // A controller whose service went away (the task was swiped off while paused) is dead weight: drop it and bind again.
        controller?.let { c -> if (c.isConnected) return else { runCatching { c.release() }; controller = null } }
        if (connecting) return
        connecting = true
        this.appContext = appContext.applicationContext
        sessionFile = SessionStore.file(appContext)
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (PlayerController.controller === controller) {
                    PlayerController.controller = null
                    upgradeJob?.cancel()
                    ticker?.cancel()
                    _state.value = PlayerUiState(shuffle = _state.value.shuffle)
                }
            }
        }).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            _state.value = _state.value.copy(shuffle = pendingShuffle)
            if (c.mediaItemCount == 0 || c.playbackState == Player.STATE_IDLE) restoreSession(c)
            // Long recordings pick up where they were left; the position is looked up when the item is reached (see Positions).
            scope.launch { Library.state.collect { pushLikeButton() } }
            // A lookup that lands after the queue was built upgrades the songs still to come; a burst of answers costs one pass.
            upgradeJob?.cancel()
            upgradeJob = scope.launch { HiRate.known.collect { upgradeQueue(); delay(300) } }
            sync()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A play is recorded once the song has really been listened to (see the ticker), not on arrival:
            // skipping through ten songs is not ten plays, and an upgrade swap of the same song is not a second one.
            if (mediaItem?.mediaId != countedId) { countedId = null; listenedMs = 0L }
            sync()
            pushLikeButton()
            saveSession(force = true)
            // Songs further on are asked about as the queue advances, so a long album is not capped at its first thirty.
            controller?.let { c -> val i = c.currentMediaItemIndex; HiRate.requestAll((i until minOf(i + 30, c.mediaItemCount)).mapNotNull { trackOf(c.getMediaItemAt(it)) }) }
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
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The source refused this song"
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This song's stream couldn't be decoded"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This song's stream was unreadable"
        else -> "Couldn't play this song"
    }

    private fun sync() {
        val c = controller ?: return
        // One slot per item, so an item whose track cannot be read never shifts the ones after it.
        val items = (0 until c.mediaItemCount).map { i -> val m = c.getMediaItemAt(i); trackOf(m) ?: Track(id = m.mediaId, title = m.mediaMetadata.title?.toString() ?: "") }
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
        _position.value = c.currentPosition.coerceAtLeast(0)
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
        val widgetKey = "${cur?.id}:${status == Status.PLAYING || status == Status.LOADING}"
        if (widgetKey != lastWidget) {
            lastWidget = widgetKey
            appContext?.let { app.aoide.widget.AoideWidget.push(it, cur, status == Status.PLAYING || status == Status.LOADING) }
        }
        if (status == Status.PLAYING || status == Status.LOADING) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        lastTickAt = System.currentTimeMillis()
        ticker = scope.launch {
            while (isActive) {
                val c = controller ?: break
                val dur = if (c.duration > 0) c.duration else 0L
                val pos = c.currentPosition.coerceAtLeast(0)
                _position.value = pos
                // The shared state only moves when something other than the playhead changed, so lists stay asleep.
                if (dur != _state.value.durationMs) _state.value = _state.value.copy(durationMs = dur, positionMs = pos)
                saveSession()
                Positions.note(_state.value.current, pos, dur)
                // A play counts once 20 s (or 30 % of a short song) has actually been heard.
                val now = System.currentTimeMillis()
                if (c.isPlaying) listenedMs += (now - lastTickAt).coerceIn(0, 1000)
                lastTickAt = now
                val cur = _state.value.current
                if (cur != null && countedId != cur.id && (listenedMs >= 20_000 || (dur > 0 && listenedMs >= dur * 0.3))) {
                    countedId = cur.id
                    Library.recordPlay(cur)
                }
                delay(250)
            }
        }
    }

    /** The track behind a queue item: from the registry when it has been seen, else decoded once from the item's extras. */
    private fun trackOf(item: MediaItem?): Track? {
        if (item == null) return null
        TrackRegistry.get(item.mediaId)?.let { return it }
        return item.mediaMetadata.extras?.getString("track")?.let { runCatching { json.decodeFromString<Track>(it) }.getOrNull() }?.also(TrackRegistry::put)
    }

    private fun mediaItem(t: Track): MediaItem = AoideMedia.mediaItemFor(t)

    /** Songs the service appended by itself when the queue ran out; the queue screen labels them. */
    fun isAutoplayed(i: Int): Boolean = controller?.let { c -> i in 0 until c.mediaItemCount && c.getMediaItemAt(i).mediaMetadata.extras?.getBoolean("autoplay") == true } ?: false

    /* ---------- notification ---------- */

    /** Tell the session which way the Like button should point for the song playing. */
    private fun pushLikeButton() {
        val c = controller ?: return
        val cur = _state.value.current ?: return
        runCatching { c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_LIKE_STATE, android.os.Bundle.EMPTY), android.os.Bundle().apply { putBoolean("liked", Library.state.value.isLiked(cur.id)) }) }
    }

    /* ---------- session ---------- */

    private fun saveSession(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 4000) return
        lastSave = now
        val s = _state.value
        val f = sessionFile ?: return
        // Long queues are windowed around the current song, so the index kept always points inside what was kept.
        val from = (s.index - 100).coerceAtLeast(0)
        val kept = s.queue.drop(from).take(300)
        val session = Session(kept, s.index - from, s.positionMs, s.context, s.shuffle, s.repeat)
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (s.index < 0) f.delete()
                else app.aoide.data.Store.writeAtomic(f, json.encodeToString(session))
            }
        }
    }

    /**
     * Put the last queue back, paused at the saved position; nothing loads until the listener presses play.
     * The service may already have restored the queue itself (it does so when it starts cold, so a widget
     * press finds something to play); then only the labels are taken from the session.
     */
    private fun restoreSession(c: MediaController) {
        val s = appContext?.let(SessionStore::load) ?: return
        context = s.context
        pendingShuffle = s.shuffle
        upgraded.clear()
        // Answers kept from earlier are read as the items are built; the songs coming up are asked about now.
        HiRate.requestAll(s.queue.subList(s.index, minOf(s.index + 12, s.queue.size)))
        if (c.mediaItemCount == 0) {
            c.setMediaItems(s.queue.map(::mediaItem), s.index, s.positionMs.coerceAtLeast(0))
            c.repeatMode = s.repeat
            c.playWhenReady = false
        }
        _state.value = _state.value.copy(shuffle = s.shuffle, durationMs = 0)
    }

    /* ---------- commands ---------- */

    /**
     * Start a queue. [shuffled] is the caller's intent, not a sticky global: Play on a record plays it in
     * order even after Shuffle was pressed somewhere else, and Shuffle never leaves shuffle on behind it.
     */
    fun playTracks(tracks: List<Track>, start: Int, ctx: PlayContext?, shuffled: Boolean = false) {
        val c = controller ?: return
        val playable = tracks.filter { it.isLocal || it.id.isNotBlank() }
        if (playable.isEmpty()) return
        var list = playable
        var index = start.coerceIn(0, playable.lastIndex)
        unshuffled = null
        pendingShuffle = shuffled
        _state.value = _state.value.copy(shuffle = shuffled)
        if (shuffled) {
            unshuffled = playable
            val first = playable[index]
            list = listOf(first) + playable.filterIndexed { i, _ -> i != index }.shuffled()
            index = 0
        }
        context = ctx
        failStreak = 0
        upgraded.clear()
        val first = list[index]
        // Ask the second source about the whole queue, the first song first; anything it carries is swapped in as answers land.
        HiRate.requestAll(listOf(first) + list)
        val finalList = list
        val finalIndex = index
        // The first song waits a moment for its answer, so it starts on the 320 file rather than being swapped a beat later.
        if (HiRate.enabled && !first.isLocal && !HiRate.isAnswered(first.id)) {
            scope.launch { HiRate.await(first, FIRST_SONG_WAIT_MS); start(c, finalList, finalIndex) }
        } else start(c, finalList, finalIndex)
    }

    /** Hand a queue to the player and begin. */
    private fun start(c: MediaController, list: List<Track>, index: Int) {
        _state.value = _state.value.copy(error = null, status = Status.LOADING, durationMs = 0)
        c.setMediaItems(list.map(::mediaItem), index, Positions.resumeAt(list[index]))
        c.prepare()
        c.play()
        sync()
    }

    /** How long the first song of a queue waits for the second source's answer before starting anyway. */
    private const val FIRST_SONG_WAIT_MS = 2_000L

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
    private fun reorderTo(c: MediaController, ids: List<String>) {
        val present = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
        val target = ids.filter { it in present } + (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.filter { it !in ids }
        for ((to, id) in target.withIndex()) {
            val from = (0 until c.mediaItemCount).first { c.getMediaItemAt(it).mediaId == id }
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
        HiRate.request(t)
        c.addMediaItem(c.currentMediaItemIndex + 1, mediaItem(t))
        unshuffled = unshuffled?.let { list -> val i = list.indexOfFirst { it.id == _state.value.current?.id }; list.toMutableList().apply { add(i + 1, t) } }
        sync()
        saveSession(force = true)
    }

    fun enqueueLast(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return playTrack(t)
        HiRate.request(t)
        c.addMediaItem(mediaItem(t))
        unshuffled = unshuffled?.plus(t)
        sync()
        saveSession(force = true)
    }

    fun removeAt(i: Int) {
        val c = controller ?: return
        if (i == c.currentMediaItemIndex || i !in 0 until c.mediaItemCount) return
        val id = c.getMediaItemAt(i).mediaId
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

    /** A kept file is being deleted: if it is the one playing, move back onto the stream before the file goes. */
    fun onDownloadRemoved(trackId: String) {
        StreamResolver.forget(trackId)
        if (_state.value.current?.id == trackId) reloadCurrent()
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

    /**
     * Swap in the second source's file for every song it turned out to carry, the one playing
     * included: it keeps its place and carries on from the better file. Items already built on
     * that file are left alone, so nothing is reloaded twice.
     */
    private fun upgradeQueue() {
        val c = controller ?: return
        if (!HiRate.enabled) return
        val cur = c.currentMediaItemIndex
        for (i in cur.coerceAtLeast(0) until c.mediaItemCount) {
            val id = c.getMediaItemAt(i).mediaId
            if (id in upgraded || AoideMedia.isBuiltAs320(id) || AoideMedia.hiRateUri(id) == null) continue
            val t = TrackRegistry.get(id) ?: continue
            upgraded.add(id)
            if (i == cur) swapCurrent(t) else runCatching { c.replaceMediaItem(i, mediaItem(t)) }
        }
    }

    /** Move the song playing onto the second source's file at the same position. Not worth a blip when it is nearly over. */
    private fun swapCurrent(t: Track) {
        val c = controller ?: return
        val pos = c.currentPosition
        if (c.duration > 0 && c.duration - pos < 15_000) return
        val wasPlaying = c.playWhenReady
        val idx = c.currentMediaItemIndex
        _state.value = _state.value.copy(durationMs = 0)
        runCatching {
            c.replaceMediaItem(idx, mediaItem(t))
            c.seekTo(idx, pos)
            c.prepare()
            if (wasPlaying) c.play()
        }
    }

    /**
     * The song starts at once, alone; the service's own mix for it is fetched and queued behind it.
     * What a tap on a search result or a lone card should do: the rest of the queue is music that
     * belongs with the song, not the other things that matched the words.
     */
    fun playRadio(t: Track) {
        playTracks(listOf(t), 0, PlayContext("radio", "${t.title} Radio"))
        if (t.isLocal) return
        scope.launch {
            val mix = withContext(Dispatchers.IO) { runCatching { Catalog.radio(t.id) }.getOrDefault(emptyList()) }
            val c = controller ?: return@launch
            // The listener has moved on to something else meanwhile: leave their queue alone.
            if (_state.value.current?.id != t.id || _state.value.context?.kind != "radio") return@launch
            val have = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            val fresh = mix.filter { it.id !in have }.distinctBy { it.id }
            if (fresh.isEmpty()) { Toasts.show("Couldn't build a radio for this song"); return@launch }
            HiRate.requestAll(fresh)
            c.addMediaItems(fresh.map(::mediaItem))
            unshuffled = null
            sync()
            saveSession(force = true)
        }
    }

    /** Songs after [i] in the current queue, for "play from here" and "add the rest". */
    fun queueFrom(list: List<Track>, i: Int): List<Track> = list.drop(i + 1)

    /** Test seam: lets a screenshot test paint the player without audio. */
    fun setStateForTest(s: PlayerUiState) {
        _state.value = s
        _position.value = s.positionMs
        s.current?.let { AppUi.player = Tint.of(it.album?.vibrantColor) }
    }
}
