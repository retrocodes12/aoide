package app.aoide.player

import android.net.Uri
import android.util.Base64
import app.aoide.data.Catalog
import app.aoide.data.Download
import app.aoide.data.Downloads
import app.aoide.data.Lossless
import app.aoide.data.Music
import app.aoide.data.Quality
import app.aoide.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** What we learned about a track's stream when it was resolved. The UI shows it in the player. */
data class StreamInfo(
    val trackId: String,
    val isPreview: Boolean,
    val quality: String,
    val bitDepth: Int?,
    val sampleRate: Int?,
    val source: String,
) {
    val label: String
        get() = when {
            isPreview -> "PREVIEW"
            source == "full" || source == "download" -> quality
            bitDepth != null && sampleRate != null -> "FLAC $bitDepth/${sampleRate / 1000}"
            quality.contains("LOSSLESS") -> "FLAC"
            quality.isNotBlank() -> quality
            else -> ""
        }
    val lossless: Boolean get() = source == "mirror" && !isPreview
}

data class Resolved(val uri: Uri, val info: StreamInfo)

/** Tracks the queue knows about, so the resolver can find them on the mirror without another catalogue call. */
object TrackRegistry {
    private val tracks = ConcurrentHashMap<String, Track>()
    fun put(t: Track) { tracks[t.id] = t }
    fun get(id: String): Track? = tracks[id]
}

/**
 * Turns a track id into something ExoPlayer can open, taking the best source that answers:
 *  1. a file kept on the phone
 *  2. a lossless mirror, when one has been seen serving full songs and it has this recording (FLAC)
 *  3. the music service itself, as a one-file DASH manifest (the full song, Opus)
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
        val key = "$trackId:${quality.name}:${Lossless.enabled.value}"
        synchronized(cache) { cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.value else cache.remove(key) } }
        val r = fresh(trackId, quality)
        synchronized(cache) {
            if (cache.size >= 200) cache.remove(cache.keys.first())
            cache[key] = Cached(System.currentTimeMillis(), r)
        }
        _infos.value = _infos.value + (trackId to r.info)
        return r
    }

    private suspend fun fresh(trackId: String, quality: Quality): Resolved {
        val track = TrackRegistry.get(trackId) ?: runCatching { Catalog.track(trackId) }.getOrNull()?.also(TrackRegistry::put)
        if (track != null && Lossless.enabled.value && (quality == Quality.LOSSLESS || quality == Quality.HI_RES_LOSSLESS)) {
            attempt { fromMirror(track, quality) }?.let { return it }
        }
        return fromService(trackId, quality) ?: throw IllegalStateException("No client of the music service handed out a whole file for this song")
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun fromDownload(d: Download): Resolved {
        val info = StreamInfo(d.track.id, isPreview = false, quality = "Downloaded · ${d.label}", bitDepth = null, sampleRate = d.sampleRate, source = "download")
        return Resolved(Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(Downloads.manifest(d).toByteArray(), Base64.NO_WRAP)), info)
    }

    private suspend fun fromService(trackId: String, quality: Quality): Resolved? {
        val s = Music.stream(trackId, quality) ?: return null
        val mpd = Music.dashManifest(s)
        val codec = if (s.codecs.startsWith("opus")) "OPUS" else "AAC"
        val info = StreamInfo(trackId, isPreview = false, quality = "$codec ${s.averageBitrate / 1000} kbps", bitDepth = null, sampleRate = s.sampleRate, source = "full")
        return Resolved(Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(mpd.toByteArray(), Base64.NO_WRAP)), info)
    }

    private suspend fun fromMirror(track: Track, quality: Quality): Resolved? {
        val m = Lossless.manifest(track, quality) ?: return null
        val decoded = runCatching { String(Base64.decode(m.manifest, Base64.DEFAULT)) }.getOrDefault(m.manifest)
        val info = StreamInfo(track.id, false, m.audioQuality, m.bitDepth, m.sampleRate, "mirror")
        val uri = when {
            decoded.contains("<MPD") -> Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(decoded.toByteArray(), Base64.NO_WRAP))
            else -> Uri.parse(directUrl(decoded) ?: return null)
        }
        return Resolved(uri, info)
    }

    private fun directUrl(decoded: String): String? {
        runCatching {
            val obj = Json.parseToJsonElement(decoded).jsonObject
            val urls: JsonArray = obj["urls"]?.jsonArray ?: return@runCatching
            urls.firstOrNull()?.jsonPrimitive?.content?.let { return it }
        }
        return Regex("https?://[^\\s\"'<>]+").find(decoded)?.value
    }

    fun forget(trackId: String) = synchronized(cache) { cache.keys.removeAll { it.startsWith("$trackId:") } }

    /** Test seam: lets a screenshot test show a stream badge without resolving a stream. */
    fun setInfoForTest(info: StreamInfo) { _infos.value = _infos.value + (info.trackId to info) }
}
