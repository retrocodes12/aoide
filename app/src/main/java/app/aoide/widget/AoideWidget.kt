package app.aoide.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.aoide.MainActivity
import app.aoide.R
import app.aoide.data.Catalog
import app.aoide.data.Track
import coil3.SingletonImageLoader
import coil3.request.allowHardware
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A home-screen strip with the song playing and transport buttons. Buttons go to [WidgetActions],
 * which drives the playback session directly, so they work even when the app itself is not running.
 */
class AoideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val (t, playing) = last
        ids.forEach { manager.updateAppWidget(it, views(context, t, playing)) }
    }

    companion object {
        private var last: Pair<Track?, Boolean> = null to false
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
                // The app's one image loader, so the cover comes from its cache rather than the network each time.
                val bmp = runCatching { SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(art).allowHardware(false).build()).image?.toBitmap() }.getOrNull() ?: return@launch
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
            rv.setOnClickPendingIntent(R.id.widget_toggle, action(context, WidgetActions.TOGGLE, 1))
            rv.setOnClickPendingIntent(R.id.widget_next, action(context, WidgetActions.NEXT, 2))
            rv.setOnClickPendingIntent(R.id.widget_prev, action(context, WidgetActions.PREV, 3))
            rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return rv
        }

        private fun action(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(context, code, Intent(context, WidgetActions::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
