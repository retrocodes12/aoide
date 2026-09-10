package app.aoide.player

import android.net.Uri
import android.util.Base64
import app.aoide.data.ApiClient
import app.aoide.data.Catalog
import app.aoide.data.Instances
import app.aoide.data.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            bitDepth != null && sampleRate != null -> "FLAC $bitDepth/${sampleRate / 1000}"
            quality.contains("LOSSLESS") -> "FLAC"
            quality.isNotBlank() -> quality
            else -> ""
        }
}

data class Resolved(val uri: Uri, val info: StreamInfo)

/**
 * Turns a track id into something ExoPlayer can open. Order:
 *  1. the mirrors' /track/ route -> base64 DASH manifest, handed over as a data: URI
 *  2. TIDAL's own manifest endpoint (previews only without a subscription)
 */
object StreamResolver {
    private val cache = HashMap<String, Resolved>()
    private val _infos = MutableStateFlow<Map<Long, StreamInfo>>(emptyMap())
    val infos: StateFlow<Map<Long, StreamInfo>> = _infos

    fun infoFor(trackId: Long): StreamInfo? = _infos.value[trackId]

    suspend fun resolve(trackId: Long, quality: Quality): Resolved {
        val key = "$trackId:${quality.name}"
        synchronized(cache) { cache[key]?.let { return it } }
        val r = try {
            val m = Catalog.manifest(trackId, quality)
            if (m.manifest.isBlank()) throw IllegalStateException("No manifest from mirror")
            val decoded = runCatching { String(Base64.decode(m.manifest, Base64.DEFAULT)) }.getOrDefault(m.manifest)
            val isPreview = m.assetPresentation.equals("PREVIEW", true)
            val info = StreamInfo(trackId, isPreview, m.audioQuality, m.bitDepth, m.sampleRate, "mirror")
            val uri = when {
                decoded.contains("<MPD") -> Uri.parse("data:application/dash+xml;base64," + Base64.encodeToString(decoded.toByteArray(), Base64.NO_WRAP))
                else -> Uri.parse(directUrl(decoded) ?: throw IllegalStateException("Unreadable manifest"))
            }
            Instances.noteSource("mirror")
            Resolved(uri, info)
        } catch (e: Exception) {
            val n = ApiClient.nativeManifest(trackId, quality)
            Instances.noteSource("tidal")
            val fmt = n.formats.firstOrNull() ?: quality.name
            // Report the tier that was actually granted; TIDAL honours the format order but a track may lack a tier.
            val tier = when {
                fmt == "FLAC" -> "LOSSLESS"
                fmt.startsWith("HEAAC") -> "LOW"
                fmt.startsWith("AAC") -> "HIGH"
                else -> fmt
            }
            Resolved(Uri.parse(n.uri), StreamInfo(trackId, n.trackPresentation.equals("PREVIEW", true), tier, null, null, "tidal"))
        }
        synchronized(cache) { cache[key] = r }
        _infos.value = _infos.value + (trackId to r.info)
        return r
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

    /** Test seam: lets a screenshot test show a preview badge without resolving a stream. */
    fun setInfoForTest(info: StreamInfo) { _infos.value = _infos.value + (info.trackId to info) }
}
