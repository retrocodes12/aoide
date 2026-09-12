package app.aoide.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.aoide.data.ApiClient
import app.aoide.data.Catalog
import app.aoide.data.Downloads
import app.aoide.data.HiRate
import app.aoide.data.LocalMedia
import app.aoide.data.Lossless
import app.aoide.data.Prefs
import app.aoide.data.Quality
import app.aoide.data.Track
import app.aoide.data.Music
import app.aoide.data.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/**
 * The media pipeline, shared by the playback service and the tests so both run the same code.
 *
 * Most queue items are `aoide://track/{id}` DASH items. The resolver turns each into a real
 * manifest on ExoPlayer's loader thread the moment the song is reached, and stamps every request
 * with a user agent: the service's CDN gets the one its stream was issued to, everything else gets
 * Aoide's own.
 *
 * Three kinds of song skip all of that and play straight from a plain file, because that is what
 * they are: music already on the phone, a song kept as a plain download, and a song the second
 * source carries at a higher bitrate. ExoPlayer has to be told which kind an item is when the item
 * is built, not when it is reached, so each of those is decided here and needs no resolving later.
 */
@UnstableApi
object AoideMedia {
    private var appContext: Context? = null

    fun mediaSourceFactory(context: Context): MediaSource.Factory {
        appContext = context.applicationContext
        // No factory-wide user agent: DefaultHttpDataSource would let it override the per-request one.
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setAllowCrossProtocolRedirects(true)
        return DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http), Resolver))
    }

    /** The queue item for a track: id, metadata for the notification and lock screen, and the track itself in the extras. */
    fun mediaItemFor(t: Track, autoplay: Boolean = false): MediaItem {
        TrackRegistry.put(t)
        val extras = Bundle().apply { putString("track", json.encodeToString(t)); if (autoplay) putBoolean("autoplay", true) }
        val meta = MediaMetadata.Builder()
            .setTitle(t.title + (t.version?.let { " - $it" } ?: ""))
            .setArtist(t.artistNames)
            .setAlbumTitle(t.album?.title)
            .setArtworkUri(Catalog.cover(t.album?.cover, 640)?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(t.id.toString()).setMediaMetadata(meta).build()
    }

    /** MediaItems lose their URI crossing the session's IPC boundary; rebuild it from the id and keep the track for matching. */
    fun toPlayable(item: MediaItem): MediaItem {
        val id = item.mediaId
        val fromExtras = item.mediaMetadata.extras?.getString("track")?.let { s -> runCatching { json.decodeFromString<Track>(s) }.getOrNull() }
        fromExtras?.let(TrackRegistry::put)
        // A browser (Android Auto) sends a bare id: rebuild the whole item from the track the library handed out.
        val base = if (fromExtras == null) TrackRegistry.get(id)?.let(::mediaItemFor) ?: item else item
        if (LocalMedia.isLocal(id)) return base.buildUpon().setUri(Uri.parse(LocalMedia.uriFor(id))).setMimeType(null).build()
        Downloads.get(id)?.takeIf { it.progressive }?.let { d ->
            StreamResolver.note(StreamResolver.downloadInfo(d))
            return base.buildUpon().setUri(Uri.parse("file://" + d.file)).setMimeType(null).build()
        }
        hiRateUri(id)?.let { url ->
            StreamResolver.note(StreamInfo(id, isPreview = false, quality = "AAC ${HiRate.KBPS} kbps", bitDepth = null, sampleRate = 44_100, source = "hirate"))
            return base.buildUpon().setUri(Uri.parse(url)).setMimeType(null).build()
        }
        return base.buildUpon().setUri(Uri.parse("aoide://track/${item.mediaId}")).setMimeType(MimeTypes.APPLICATION_MPD).build()
    }

    /**
     * The second source's file for a song, unless something better applies: a lossless mirror that
     * has the song beats it at the lossless tiers, and Low wants the smallest stream, not the biggest.
     */
    fun hiRateUri(trackId: String): String? {
        if (!HiRate.enabled) return null
        val q = effectiveQuality()
        if (q == Quality.LOW) return null
        if ((q == Quality.LOSSLESS || q == Quality.HI_RES_LOSSLESS) && Lossless.has(trackId)) return null
        return HiRate.url(trackId)
    }

    /** Data saver drops to the smallest stream on a metered connection. */
    fun effectiveQuality(): Quality {
        if (!Prefs.dataSaver.on) return Prefs.quality.value
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Prefs.quality.value
        return if (runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)) Quality.LOW else Prefs.quality.value
    }

    /** Runs on ExoPlayer's loader thread, so blocking network here is expected. */
    object Resolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (dataSpec.uri.scheme == "aoide") {
                val id = dataSpec.uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Bad track uri ${dataSpec.uri}")
                val resolved = runBlocking { StreamResolver.resolve(id, effectiveQuality()) }
                return stamp(dataSpec.buildUpon().setUri(resolved.uri).build())
            }
            return stamp(dataSpec)
        }

        private fun stamp(spec: DataSpec): DataSpec {
            if (spec.uri.scheme != "http" && spec.uri.scheme != "https") return spec
            val host = spec.uri.host ?: return spec
            val ua = if (host.endsWith(Music.CDN_SUFFIX)) Music.userAgentFor(spec.uri.getQueryParameter("c")) else ApiClient.UA
            return spec.withAdditionalHeaders(mapOf("User-Agent" to ua))
        }
    }
}
