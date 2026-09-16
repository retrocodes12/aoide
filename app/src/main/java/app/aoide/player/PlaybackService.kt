package app.aoide.player

import android.app.PendingIntent
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.aoide.R
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.aoide.MainActivity
import app.aoide.data.Catalog
import app.aoide.data.Downloads
import app.aoide.data.Library
import app.aoide.data.Track
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer and the media session. Queue items arrive from the app as
 * `aoide://track/{id}`; [AoideMedia] turns each into the real manifest on the loader thread, right
 * before ExoPlayer opens it, so a 100-track queue costs nothing until each track is reached.
 * Notification, lock screen and headset buttons come from Media3 for free. As a library service
 * it also answers Android Auto's browser with the listener's library.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var extras: PlayerExtras? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(AoideMedia.mediaSourceFactory(this))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_500, 3_000).build())
            .build()
        val ex = PlayerExtras(this, exo).also { it.start() }
        extras = ex
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { showLiked(mediaItem?.mediaId?.let { Library.state.value.isLiked(it) } ?: false) }
        })
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, FadingPlayer(exo, ex), LibraryCallback()).setSessionActivity(pending).setCustomLayout(listOf(likeButton(false))).build()
        // Started cold by a widget or a headset button: the last queue comes back paused where it was,
        // so that press has something to play even when the app itself is not running.
        SessionStore.load(this)?.let { s ->
            s.queue.forEach(TrackRegistry::put)
            exo.setMediaItems(s.queue.map { AoideMedia.toPlayable(AoideMedia.mediaItemFor(it)) }, s.index, s.positionMs.coerceAtLeast(0))
            exo.repeatMode = s.repeat
            exo.playWhenReady = false
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** The Like button on the notification and the lock screen, drawn to match whether the song is liked. */
    private fun likeButton(liked: Boolean): CommandButton = CommandButton.Builder()
        .setDisplayName(if (liked) "Remove from Liked Songs" else "Add to Liked Songs")
        .setIconResId(if (liked) R.drawable.ic_liked else R.drawable.ic_like)
        .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
        .build()

    private fun showLiked(liked: Boolean) { session?.setCustomLayout(listOf(likeButton(liked))) }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        extras?.stop()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /** MediaItems lose their URI crossing the IPC boundary; put it back from the media id. The rest is the browse tree for Android Auto. */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_LIKE, Bundle.EMPTY)).add(SessionCommand(CMD_LIKE_STATE, Bundle.EMPTY)).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_LIKE -> {
                    val t = session.player.currentMediaItem?.mediaId?.let(TrackRegistry::get)
                    if (t != null) showLiked(Library.toggleLike(t))
                }
                CMD_LIKE_STATE -> showLiked(args.getBoolean("liked"))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(AoideMedia::toPlayable).toMutableList())

        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(mediaItems.map(AoideMedia::toPlayable)), startIndex, startPositionMs))

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Aoide", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val t = TrackRegistry.get(mediaId) ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(AoideMedia.mediaItemFor(t), null))
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val lib = Library.state.value
            val items: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    folder(LIKED, "Liked Songs", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    folder(RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    folder(DOWNLOADS, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                )
                parentId == LIKED -> lib.liked.map { AoideMedia.mediaItemFor(it) }
                parentId == RECENT -> lib.recentTracks.map { AoideMedia.mediaItemFor(it) }
                parentId == DOWNLOADS -> Downloads.all.value.values.sortedByDescending { it.savedAt }.map { AoideMedia.mediaItemFor(it.track) }
                parentId == PLAYLISTS -> lib.playlists.map { folder("pl:${it.id}", it.title, MediaMetadata.MEDIA_TYPE_PLAYLIST, it.tracks.firstOrNull()?.album?.cover?.let { c -> Catalog.cover(c, 320) }) }
                parentId.startsWith("pl:") -> lib.playlists.find { it.id == parentId.removePrefix("pl:") }?.tracks?.map { AoideMedia.mediaItemFor(it) } ?: emptyList()
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val n = runCatching { Catalog.searchTracks(query, 20) }.getOrDefault(emptyList()).also { list -> searches[query] = list; list.forEach(TrackRegistry::put) }.size
                session.notifySearchResultChanged(browser, query, n, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val cached = searches[query]
            if (cached != null) return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(cached.map { AoideMedia.mediaItemFor(it) }), params))
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val list = runCatching { Catalog.searchTracks(query, 20) }.getOrDefault(emptyList())
                searches[query] = list
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(list.map { AoideMedia.mediaItemFor(it) }), params))
            }
            return future
        }
    }

    private fun folder(id: String, title: String, type: Int, art: String? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(type).setArtworkUri(art?.let(android.net.Uri::parse)).build(),
        ).build()

    private val searches = object : LinkedHashMap<String, List<Track>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Track>>?) = size > 20
    }

    companion object {
        /** Toggle the like on the song playing (from the notification). */
        const val CMD_LIKE = "app.aoide.LIKE"
        /** The app telling the session which way the button should point. */
        const val CMD_LIKE_STATE = "app.aoide.LIKE_STATE"
        private const val ROOT = "root"
        private const val LIKED = "liked"
        private const val RECENT = "recent"
        private const val DOWNLOADS = "downloads"
        private const val PLAYLISTS = "playlists"
    }
}
