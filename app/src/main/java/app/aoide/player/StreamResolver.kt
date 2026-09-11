package app.aoide.player

import android.net.Uri
import android.util.Base64
import app.aoide.data.ApiClient
import app.aoide.data.Catalog
import app.aoide.data.Instances
import app.aoide.data.Prefs
import app.aoide.data.Quality
import app.aoide.data.Track
import app.aoide.data.YouTubeMusic
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
    val trackId: Long,
    val isPreview: Boolean,
    val quality: String,
    val bitDepth: Int?,
    val sampleRate: Int?,
    val source: String,
) {
    val label: String
        get() = when {
            isPreview -> "PREVIEW"
            source == "youtube" -> quality
            bitDepth != null && sampleRate != null -> "FLAC $bitDepth/${sampleRate / 1000}"
            quality.contains("LOSSLESS") -> "FLAC"
            quality.isNotBlank() -> quality
            else -> ""
        }
}

data class Resolved(val uri: Uri, val info: StreamInfo)

/** Tracks the queue knows about, so the resolver can match them on YouTube Music without another catalogue call. */
object TrackRegistry {
    private val tracks = ConcurrentHashMap<Long, Track>()
    fun put(t: Track) { tracks[t.id] = t }
    fun get(id: Long): Track? = tracks[id]
}

/**
 * Turns a track id into something ExoPlayer can open, taking the best source that answers:
 *  1. a mirror of the listener's own, when it serves the song in full (a subscribed account means lossless)
 *  2. YouTube Music, matched by artist, title and length, as a one-file DASH manifest (the full song, Opus)
 *  3. the public mirrors' manifest (usually a 30-second preview)
 *  4. TIDAL's own manifest endpoint (a preview without a subscription)
 */
object StreamResolver {
    /** Signed segment URLs go stale, so a resolve is only trusted for ten minutes and the map is capped. */
    private data class Cached(val at: Long, val value: Resolved)
    private const val TTL_MS = 10 * 60_000L
    private val cache = LinkedHashMap<String, Cached>()
    private val _infos = MutableStateFlow<Map<Long, StreamInfo>>(emptyMap())
    val infos: StateFlow<Map<Long, StreamInfo>> = _infos

    fun infoFor(trackId: Long): StreamInfo? = _infos.value[trackId]

    suspend fun resolve(trackId: Long, quality: Quality): Resolved {
        // The YouTube switch is part of the key, so flipping it never replays a stream from the other source.
        val key = "$trackId:${quality.name}:${Prefs.youtubeSource.value}"
        synchronized(cache) { cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.value else cache.remove(key) } }
        val r = fresh(trackId, quality)
        synchronized(cache) {
            if (cache.size >= 200) cache.remove(cache.keys.first())
            cache[key] = Cached(System.currentTimeMillis(), r)
        }
        _infos.value = _infos.value + (trackId to r.info)
        return r
    }

    private suspend fun fresh(trackId: Long, quality: Quality): Resolved {
        val ownMirror = Instances.list.value.any { it.isUser }
        var mirror: Resolved? = null
        if (ownMirror) {
            mirror = attempt { fromMirror(trackId, quality) }
            if (mirror != null && !mirror.info.isPreview) return mirror
        }
        if (Prefs.youtubeSource.value) attempt { fromYouTube(trackId, quality) }?.let { return it }
        if (!ownMirror) mirror = attempt { fromMirror(trackId, quality) }
        return mirror ?: fromTidal(trackId, quality)
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun fromYouTube(trackId: Long, quality: Quality): Resolved? {
        val track = TrackRegistry.get(trackId) ?: Catalog.track(trackId).also(TrackRegistry::put)
        val videoId = YouTubeMusic.match(track) ?: return null
        val s = YouTubeMusic.stream(videoId, quality) ?: return null
        val mpd = YouTubeMusic.dashManifest(s)
        val codec = if (s.codecs.startsWith("opus")) "OPUS" else "AAC"
        val info = StreamInfo(trackId, isPreview = false, quality = "$codec ${s.averageBitrate / 1000} kbps", bitDepth = null, sampleRate = s.sampleRate, source = "youtube")
        return Resolved(Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(mpd.toByteArray(), Base64.NO_WRAP)), info)
    }

    private suspend fun fromMirror(trackId: Long, quality: Quality): Resolved {
        val m = Catalog.manifest(trackId, quality)
        if (m.manifest.isBlank()) throw IllegalStateException("No manifest from mirror")
        val decoded = runCatching { String(Base64.decode(m.manifest, Base64.DEFAULT)) }.getOrDefault(m.manifest)
        val isPreview = m.assetPresentation.equals("PREVIEW", true)
        val info = StreamInfo(trackId, isPreview, m.audioQuality, m.bitDepth, m.sampleRate, "mirror")
        val uri = when {
            decoded.contains("<MPD") -> Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(decoded.toByteArray(), Base64.NO_WRAP))
            else -> Uri.parse(directUrl(decoded) ?: throw IllegalStateException("Unreadable manifest"))
        }
        return Resolved(uri, info)
    }

    private suspend fun fromTidal(trackId: Long, quality: Quality): Resolved {
        val n = ApiClient.nativeManifest(trackId, quality)
        val fmt = n.formats.firstOrNull() ?: quality.name
        // Report the tier that was actually granted; TIDAL honours the format order but a track may lack a tier.
        val tier = when {
            fmt == "FLAC" -> "LOSSLESS"
            fmt.startsWith("HEAAC") -> "LOW"
            fmt.startsWith("AAC") -> "HIGH"
            else -> fmt
        }
        return Resolved(Uri.parse(n.uri), StreamInfo(trackId, n.trackPresentation.equals("PREVIEW", true), tier, null, null, "tidal"))
    }

    private fun directUrl(decoded: String): String? {
        runCatching {
            val obj = Json.parseToJsonElement(decoded).jsonObject
            val urls: JsonArray = obj["urls"]?.jsonArray ?: return@runCatching
            urls.firstOrNull()?.jsonPrimitive?.content?.let { return it }
        }
        return Regex("https?://[^\\s\"'<>]+").find(decoded)?.value
    }

    fun forget(trackId: Long) = synchronized(cache) { cache.keys.removeAll { it.startsWith("$trackId:") } }

    /** Test seam: lets a screenshot test show a stream badge without resolving a stream. */
    fun setInfoForTest(info: StreamInfo) { _infos.value = _infos.value + (info.trackId to info) }
}
