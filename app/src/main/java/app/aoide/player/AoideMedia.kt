package app.aoide.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.aoide.data.ApiClient
import app.aoide.data.Prefs
import app.aoide.data.Track
import app.aoide.data.YouTubeMusic
import app.aoide.data.json
import kotlinx.coroutines.runBlocking

/**
 * The media pipeline, shared by the playback service and the tests so both run the same code.
 *
 * Queue items are `aoide://track/{id}` DASH items. The resolver turns each into a real manifest on
 * ExoPlayer's loader thread the moment the song is reached, and stamps every request with a user
 * agent: googlevideo gets the one its stream was issued to, everything else gets Aoide's own.
 */
@UnstableApi
object AoideMedia {
    fun mediaSourceFactory(context: Context): MediaSource.Factory {
        // No factory-wide user agent: DefaultHttpDataSource would let it override the per-request one.
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setAllowCrossProtocolRedirects(true)
        return DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http), Resolver))
    }

    /** MediaItems lose their URI crossing the session's IPC boundary; rebuild it from the id and keep the track for matching. */
    fun toPlayable(item: MediaItem): MediaItem {
        item.mediaMetadata.extras?.getString("track")?.let { s -> runCatching { json.decodeFromString<Track>(s) }.getOrNull()?.let(TrackRegistry::put) }
        return item.buildUpon().setUri(Uri.parse("aoide://track/${item.mediaId}")).setMimeType(MimeTypes.APPLICATION_MPD).build()
    }

    /** Runs on ExoPlayer's loader thread, so blocking network here is expected. */
    object Resolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (dataSpec.uri.scheme == "aoide") {
                val id = dataSpec.uri.lastPathSegment?.toLongOrNull() ?: throw IllegalArgumentException("Bad track uri ${dataSpec.uri}")
                val resolved = runBlocking { StreamResolver.resolve(id, Prefs.quality.value) }
                return stamp(dataSpec.buildUpon().setUri(resolved.uri).build())
            }
            return stamp(dataSpec)
        }

        private fun stamp(spec: DataSpec): DataSpec {
            val host = spec.uri.host ?: return spec
            val ua = if (host.endsWith("googlevideo.com")) YouTubeMusic.userAgentFor(spec.uri.getQueryParameter("c")) else ApiClient.UA
            return spec.withAdditionalHeaders(mapOf("User-Agent" to ua))
        }
    }
}
