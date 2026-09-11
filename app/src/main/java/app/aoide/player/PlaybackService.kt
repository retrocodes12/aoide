package app.aoide.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.aoide.MainActivity
import app.aoide.data.ApiClient
import app.aoide.data.Prefs
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking

/**
 * Owns the ExoPlayer and the MediaSession. Queue items arrive from the app as
 * `aoide://track/{id}`; [AoideMedia] turns each into the real manifest on the loader thread, right
 * before ExoPlayer opens it, so a 100-track queue costs nothing until each track is reached.
 * Notification, lock screen and headset buttons come from Media3 for free.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(AoideMedia.mediaSourceFactory(this))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_500, 3_000).build())
            .build()
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setCallback(Callback()).setSessionActivity(pending).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** MediaItems lose their URI crossing the IPC boundary; put it back from the media id. */
    private class Callback : MediaSession.Callback {
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val fixed = mediaItems.map { item ->
                AoideMedia.toPlayable(item)
            }.toMutableList()
            return Futures.immediateFuture(fixed)
        }

        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val fixed = mediaItems.map { item ->
                AoideMedia.toPlayable(item)
            }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(fixed), startIndex, startPositionMs))
        }
    }
}
