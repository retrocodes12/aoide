package app.aoide.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.aoide.MainActivity
import app.aoide.R
import app.aoide.data.Catalog
import app.aoide.data.Track
import app.aoide.player.PlaybackService
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A home-screen strip with the song playing and transport buttons. Buttons come back to this
 * receiver, which forwards them to the playback service as media-button presses, so they work
 * even when the app itself is not running.
 */
class AoideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val (t, playing) = last
        ids.forEach { manager.updateAppWidget(it, views(context, t, playing)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val key = when (intent.action) {
            ACTION_TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            ACTION_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            ACTION_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val press = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(context, PlaybackService::class.java)).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, key))
        runCatching { ContextCompat.startForegroundService(context, press) }
    }

    companion object {
        private const val ACTION_TOGGLE = "app.aoide.widget.TOGGLE"
        private const val ACTION_NEXT = "app.aoide.widget.NEXT"
        private const val ACTION_PREV = "app.aoide.widget.PREV"
        private var last: Pair<Track?, Boolean> = null to false
        private val scope = CoroutineScope(Dispatchers.Main)

        /** Called by the player controller whenever the song or the play state changes. */
        fun push(context: Context, t: Track?, playing: Boolean) {
            last = t to playing
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AoideWidget::class.java))
            if (ids.isEmpty()) return
            val rv = views(context, t, playing)
            ids.forEach { manager.updateAppWidget(it, rv) }
            val art = Catalog.cover(t?.album?.cover, 320) ?: return
            scope.launch {
                val bmp = runCatching { ImageLoader(context).execute(ImageRequest.Builder(context).data(art).build()).image?.toBitmap() }.getOrNull() ?: return@launch
                if (last.first?.id != t?.id) return@launch
                val withArt = views(context, t, last.second).apply { setImageViewBitmap(R.id.widget_art, bmp) }
                ids.forEach { manager.updateAppWidget(it, withArt) }
            }
        }

        private fun views(context: Context, t: Track?, playing: Boolean): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget)
            rv.setTextViewText(R.id.widget_title, t?.title ?: "Aoide")
            rv.setTextViewText(R.id.widget_artist, t?.artistNames ?: "Nothing playing")
            rv.setImageViewResource(R.id.widget_toggle, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            rv.setOnClickPendingIntent(R.id.widget_toggle, action(context, ACTION_TOGGLE, 1))
            rv.setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT, 2))
            rv.setOnClickPendingIntent(R.id.widget_prev, action(context, ACTION_PREV, 3))
            rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return rv
        }

        private fun action(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(context, code, Intent(context, AoideWidget::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
