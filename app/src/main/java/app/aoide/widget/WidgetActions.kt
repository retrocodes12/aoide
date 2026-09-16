package app.aoide.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.aoide.player.PlaybackService

/**
 * The widget's transport buttons. Not exported, so only Aoide's own pending intents reach it. It
 * binds a controller to the playback service rather than starting the service: a receiver in the
 * background may not start a foreground service on Android 12+, and a session bound this way
 * promotes itself the moment it plays, which is the one case a home-screen button exists for.
 */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(TOGGLE, NEXT, PREV)) return
        val pending = goAsync()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) { pending.finish(); return@addListener }
            runCatching {
                when (action) {
                    TOGGLE -> if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) c.pause() else {
                        if (c.playbackState == Player.STATE_IDLE) c.prepare()
                        c.play()
                    }
                    NEXT -> if (c.hasNextMediaItem()) c.seekToNextMediaItem()
                    PREV -> if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
                }
            }
            // The command has left over the binder; a beat later the controller can go.
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { c.release() }; pending.finish() }, 400)
        }, ContextCompat.getMainExecutor(context))
    }

    companion object {
        const val TOGGLE = "app.aoide.widget.TOGGLE"
        const val NEXT = "app.aoide.widget.NEXT"
        const val PREV = "app.aoide.widget.PREV"
    }
}
