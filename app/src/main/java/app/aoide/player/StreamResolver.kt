package app.aoide.player

import android.net.Uri
import android.util.Base64
import app.aoide.data.Catalog
import app.aoide.data.Download
import app.aoide.data.Downloads
import app.aoide.data.Music
import app.aoide.data.Quality
import app.aoide.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** What we learned about a track's stream when it was resolved. The UI shows it in the player. */
data class StreamInfo(
    val trackId: String,
    val quality: String,
    val sampleRate: Int?,
    val source: String,
) {
    val label: String get() = quality
}

data class Resolved(val uri: Uri, val info: StreamInfo)

/** Tracks the queue knows about, so the resolver has their details without another catalogue call. */
object TrackRegistry {
    private val tracks = ConcurrentHashMap<String, Track>()
    fun put(t: Track) { tracks[t.id] = t }
    fun get(id: String): Track? = tracks[id]
}

/**
 * Turns a track id into something ExoPlayer can open, taking the best source that answers:
 *  1. a file kept on the phone
 *  2. the music service itself, as a one-file DASH manifest (the full song, Opus)
 *
 * Songs the second source carries never reach here: they are plain files decided when the queue
 * item is built, in [AoideMedia].
 */
object StreamResolver {
    /** Signed segment URLs go stale, so a resolve is only trusted for ten minutes and the map is capped. */
    private data class Cached(val at: Long, val value: Resolved)
    private const val TTL_MS = 10 * 60_000L
    private val cache = LinkedHashMap<String, Cached>()
    private val _infos = MutableStateFlow<Map<String, StreamInfo>>(emptyMap())
    val infos: StateFlow<Map<String, StreamInfo>> = _infos

    fun infoFor(trackId: String): StreamInfo? = _infos.value[trackId]

    suspend fun resolve(trackId: String, quality: Quality): Resolved {
        // A song kept on the phone never touches the network, whatever was cached before it was saved.
        Downloads.get(trackId)?.let { d -> return fromDownload(d).also { _infos.value = _infos.value + (trackId to it.info) } }
        val key = "$trackId:${quality.name}"
        synchronized(cache) { cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.value else cache.remove(key) } }
        val r = fresh(trackId, quality)
        synchronized(cache) {
            if (cache.size >= 200) cache.remove(cache.keys.first())
            cache[key] = Cached(System.currentTimeMillis(), r)
        }
        _infos.value = _infos.value + (trackId to r.info)
        return r
    }

    private suspend fun fresh(trackId: String, quality: Quality): Resolved =
        fromService(trackId, quality) ?: throw IllegalStateException("No client of the music service handed out a whole file for this song")

    private fun fromDownload(d: Download): Resolved {
        val info = downloadInfo(d)
        // A plain MP4 has no manifest to build; it is played straight from the file.
        if (d.progressive) return Resolved(Uri.parse("file://" + d.file), info)
        return Resolved(Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(Downloads.manifest(d).toByteArray(), Base64.NO_WRAP)), info)
    }

    fun downloadInfo(d: Download) = StreamInfo(d.track.id, quality = "Downloaded · ${d.label}", sampleRate = d.sampleRate, source = "download")

    private suspend fun fromService(trackId: String, quality: Quality): Resolved? {
        val s = Music.stream(trackId, quality) ?: return null
        val mpd = Music.dashManifest(s)
        val codec = if (s.codecs.startsWith("opus")) "OPUS" else "AAC"
        val info = StreamInfo(trackId, quality = "$codec ${s.averageBitrate / 1000} kbps", sampleRate = s.sampleRate, source = "full")
        return Resolved(Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(mpd.toByteArray(), Base64.NO_WRAP)), info)
    }

    fun forget(trackId: String) = synchronized(cache) { cache.keys.removeAll { it.startsWith("$trackId:") } }

    /** Record what a song is playing as, for paths that never reach the resolver (a kept file, the second source). */
    fun note(info: StreamInfo) { _infos.value = _infos.value + (info.trackId to info) }

    /** Test seam: lets a screenshot test show a stream badge without resolving a stream. */
    fun setInfoForTest(info: StreamInfo) { _infos.value = _infos.value + (info.trackId to info) }
}
