package app.aoide.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Full-length audio from YouTube Music, for songs the mirrors only preview.
 *
 * Aoide browses TIDAL's catalogue. For each song this finds the same recording on YouTube Music
 * (artist, title and length must all agree) and asks YouTube's player API for its audio while
 * identifying as one of YouTube's own apps. Only streams handed out as plain URLs are used: no
 * signature cipher, no BotGuard, no JavaScript. Some clients' URLs are cut off by googlevideo at
 * about 1 MiB, so every stream is checked by reading its last bytes before it is trusted.
 *
 * Which app to pose as changes when YouTube tightens one, so the client list is also fetched from
 * the repo (config/yt-clients.json) and the built-in copy below is only the fallback.
 */
object YouTubeMusic {
    @Serializable
    data class Client(
        val name: String,
        val version: String,
        val id: String,
        val userAgent: String,
        val osName: String? = null,
        val osVersion: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val androidSdkVersion: Int? = null,
    )

    @Serializable
    data class Config(val searchClientVersion: String = SEARCH_VERSION, val clients: List<Client> = BUILT_IN)

    /** A song on YouTube Music's songs shelf. */
    data class Candidate(val videoId: String, val title: String, val artists: String, val album: String?, val durationSec: Int, val explicit: Boolean, val officialAudio: Boolean)

    /** One audio-only stream, with what a DASH manifest needs to describe it. */
    data class AudioStream(
        val url: String,
        val itag: Int,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val averageBitrate: Int,
        val contentLength: Long,
        val durationMs: Long,
        val initRange: LongRange,
        val indexRange: LongRange,
        val sampleRate: Int,
        val channels: Int,
        val client: String,
    )

    private const val SEARCH_VERSION = "1.20260213.01.00"
    /** The songs filter on music.youtube.com's search. */
    private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    private const val WEB_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    const val CONFIG_URL = "https://raw.githubusercontent.com/retrocodes12/aoide/main/config/yt-clients.json"

    /**
     * Measured 2026-09-11: VISIONOS served every file tested start to finish. ANDROID_VR returns
     * full-length URLs that stop at about 1 MiB; it stays listed in case that changes, and the
     * end-of-file check rejects it while it does not.
     */
    val BUILT_IN = listOf(
        Client(
            name = "VISIONOS", version = "0.1", id = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            osName = "visionOS", osVersion = "1.3.21O771", deviceMake = "Apple", deviceModel = "RealityDevice14,1",
        ),
        Client(
            name = "ANDROID_VR", version = "1.65.10", id = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android", osVersion = "12L", deviceMake = "Oculus", deviceModel = "Quest 3", androidSdkVersion = 32,
        ),
    )

    private val JSON_TYPE = "application/json".toMediaType()
    @Volatile private var config = Config()
    @Volatile private var configAt = 0L
    @Volatile private var visitor: String? = null
    @Volatile private var visitorAt = 0L
    /** Clients whose URLs were caught stopping short, benched for half an hour. */
    private val benched = ConcurrentHashMap<String, Long>()
    /** trackId -> videoId; "" remembers that there was no match. */
    private val matches = ConcurrentHashMap<Long, String>()

    /* ---------- matching ---------- */

    /** The YouTube Music video id for a TIDAL track, or null when no recording agrees on artist, title and length. */
    suspend fun match(track: Track): String? {
        matches[track.id]?.let { return it.ifEmpty { null } }
        stored(track.id)?.let { v ->
            if (!v.startsWith("-")) { matches[track.id] = v; return v }
            val at = v.drop(1).toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - at < NO_MATCH_TTL) { matches[track.id] = ""; return null }
        }
        val id = find(track)?.videoId
        remember(track.id, id)
        return id
    }

    /** The best YouTube Music candidate for [track], uncached: two searches at most. */
    suspend fun find(track: Track): Candidate? {
        val primary = track.primaryArtist?.name.orEmpty()
        val queries = listOf("$primary ${track.title}", "${track.title} ${track.artistNames}").map { it.trim() }.distinct()
        var best: Pair<Double, Candidate>? = null
        for (q in queries) {
            val found = try { search(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            for (c in found.take(10)) {
                val s = score(track, c) ?: continue
                if (best == null || s > best.first) best = s to c
            }
            if (best != null) break
        }
        return best?.second
    }

    /** Null means "not the same recording": title, artist and length all have to agree. Higher is a closer match. */
    fun score(track: Track, c: Candidate): Double? {
        val want = norm(track.title)
        val got = norm(c.title)
        val titleOk = want.isNotEmpty() && (got == want || got.startsWith(want) || want.startsWith(got) || got.contains(want))
        val names = track.artists.ifEmpty { listOfNotNull(track.artist) }.map { norm(it.name) }.filter { it.isNotEmpty() }
        val credited = norm(c.artists)
        val artistOk = names.any { credited.contains(it) }
        val diff = abs(c.durationSec - track.duration)
        val lengthOk = diff <= max(4, (track.duration * 0.03).toInt())
        if (!titleOk || !artistOk || !lengthOk) return null
        var s = 0.0
        if (got == want) s += 3
        if (c.officialAudio) s += 2
        if (c.explicit == track.explicit) s += 1
        track.version?.let { v -> if (norm(v).isNotEmpty() && got.contains(norm(v))) s += 1 }
        return s - diff / 3.0
    }

    /** Lowercase, accents off, punctuation to spaces; letters of every script survive. */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** YouTube Music's songs shelf for a query. */
    suspend fun search(query: String): List<Candidate> = withContext(Dispatchers.IO) {
        clients()
        val body = buildJsonObject {
            putJsonObject("context") { putJsonObject("client") { put("clientName", "WEB_REMIX"); put("clientVersion", config.searchClientVersion); put("hl", "en"); put("gl", "US") } }
            put("query", query)
            put("params", SONGS_FILTER)
        }
        val d = postJson("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", body, mapOf("User-Agent" to WEB_UA, "Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/"))
        val out = ArrayList<Candidate>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    (e["musicResponsiveListItemRenderer"] as? JsonObject)?.let { r -> candidate(r)?.let { out.add(it) } }
                    e.values.forEach { walk(it) }
                }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(d)
        out
    }

    private fun candidate(r: JsonObject): Candidate? {
        val videoId = (r["playlistItemData"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull ?: return null
        val cols = (r["flexColumns"] as? JsonArray)?.map { c -> runs(((c as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject)?.get("text")) } ?: return null
        if (cols.size < 2) return null
        // "Radiohead • In Rainbows • 3:58" (sometimes led by "Song")
        val parts = cols[1].split(" • ").map { it.trim() }.toMutableList()
        if (parts.firstOrNull() == "Song") parts.removeAt(0)
        val seconds = parts.lastOrNull()?.let(::parseDuration) ?: return null
        val raw = r.toString()
        return Candidate(
            videoId = videoId, title = cols[0], artists = parts.getOrElse(0) { "" },
            album = if (parts.size >= 3) parts[1] else null, durationSec = seconds,
            explicit = raw.contains("MUSIC_EXPLICIT_BADGE"), officialAudio = raw.contains("MUSIC_VIDEO_TYPE_ATV"),
        )
    }

    private fun runs(t: JsonElement?): String =
        ((t as? JsonObject)?.get("runs") as? JsonArray)?.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: "" } ?: ""

    private fun parseDuration(s: String): Int? {
        val p = s.split(":").map { it.toIntOrNull() ?: return null }
        return when (p.size) {
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> null
        }
    }

    private const val NO_MATCH_TTL = 3 * 86_400_000L

    private fun remember(trackId: Long, videoId: String?) {
        matches[trackId] = videoId ?: ""
        if (Prefs.isReady()) Prefs.putString("ytm:$trackId", videoId ?: "-${System.currentTimeMillis()}")
    }

    private fun stored(trackId: Long): String? = if (Prefs.isReady()) Prefs.getString("ytm:$trackId") else null

    /* ---------- streams ---------- */

    /** A whole-file audio stream for a video, or null when no client hands one out. */
    suspend fun stream(videoId: String, quality: Quality): AudioStream? = withContext(Dispatchers.IO) {
        val vd = visitorData()
        for (client in clients()) {
            val key = client.name + "/" + client.version
            if ((benched[key] ?: 0L) > System.currentTimeMillis()) continue
            val s = try { player(client, videoId, vd, quality) } catch (e: IOException) { null } ?: continue
            if (servesWholeFile(s, client)) return@withContext s
            benched[key] = System.currentTimeMillis() + 30 * 60_000L
        }
        null
    }

    private fun player(c: Client, videoId: String, vd: String?, quality: Quality): AudioStream? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", c.name)
                    put("clientVersion", c.version)
                    c.osName?.let { put("osName", it) }
                    c.osVersion?.let { put("osVersion", it) }
                    c.deviceMake?.let { put("deviceMake", it) }
                    c.deviceModel?.let { put("deviceModel", it) }
                    c.androidSdkVersion?.let { put("androidSdkVersion", it) }
                    put("hl", "en")
                    put("gl", "US")
                    vd?.let { put("visitorData", it) }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val headers = buildMap {
            put("User-Agent", c.userAgent)
            put("X-YouTube-Client-Name", c.id)
            put("X-YouTube-Client-Version", c.version)
            vd?.let { put("X-Goog-Visitor-Id", it) }
        }
        val d = postJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body, headers)
        if ((d["playabilityStatus"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull != "OK") return null
        val formats = (d["streamingData"] as? JsonObject)?.get("adaptiveFormats") as? JsonArray ?: return null
        val audio = formats.mapNotNull { (it as? JsonObject)?.let { f -> parseFormat(f, c.name) } }
        val order = if (quality == Quality.LOW) listOf(250, 249, 139, 251, 140) else listOf(251, 140, 250, 249, 139)
        return order.firstNotNullOfOrNull { itag -> audio.firstOrNull { it.itag == itag } } ?: audio.maxByOrNull { it.bitrate }
    }

    private fun parseFormat(f: JsonObject, client: String): AudioStream? {
        val mime = f["mimeType"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!mime.startsWith("audio/")) return null
        // Ciphered and SABR-only formats carry no plain url; they are skipped, never decoded.
        val url = f["url"]?.jsonPrimitive?.contentOrNull ?: return null
        fun range(k: String): LongRange? {
            val o = f[k] as? JsonObject ?: return null
            val a = o["start"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
            val b = o["end"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
            return a..b
        }
        val bitrate = f["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
        return AudioStream(
            url = url,
            itag = f["itag"]?.jsonPrimitive?.intOrNull ?: return null,
            mimeType = mime.substringBefore(";").trim(),
            codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1) ?: return null,
            bitrate = bitrate,
            averageBitrate = f["averageBitrate"]?.jsonPrimitive?.intOrNull ?: bitrate,
            contentLength = f["contentLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null,
            durationMs = f["approxDurationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null,
            initRange = range("initRange") ?: return null,
            indexRange = range("indexRange") ?: return null,
            sampleRate = f["audioSampleRate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 48_000,
            channels = f["audioChannels"]?.jsonPrimitive?.intOrNull ?: 2,
            client = client,
        )
    }

    /** googlevideo caps some clients' URLs at about 1 MiB and answers 403 past it; read the last bytes before trusting a stream. */
    private fun servesWholeFile(s: AudioStream, c: Client): Boolean = runCatching {
        val from = (s.contentLength - 1024).coerceAtLeast(0)
        val req = Request.Builder().url(s.url).header("User-Agent", c.userAgent).header("Range", "bytes=$from-${s.contentLength - 1}").build()
        ApiClient.http.newCall(req).execute().use { it.code == 206 }
    }.getOrDefault(false)

    /** The user agent a googlevideo URL was issued to, from its `c=` parameter. */
    fun userAgentFor(clientName: String?): String = config.clients.firstOrNull { it.name == clientName }?.userAgent ?: BUILT_IN.first().userAgent

    /**
     * A one-file DASH manifest for [s]: the playback service treats every song as DASH, so a YouTube
     * stream rides the same path as a mirror's manifest. The init and index byte ranges let ExoPlayer
     * seek without downloading the file first.
     */
    fun dashManifest(s: AudioStream): String {
        val seconds = String.format(Locale.US, "%.3f", s.durationMs / 1000.0)
        val url = s.url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.500S" mediaPresentationDuration="PT${seconds}S">
<Period id="0" start="PT0S">
<AdaptationSet id="0" contentType="audio" mimeType="${s.mimeType}" subsegmentAlignment="true">
<Representation id="${s.itag}" codecs="${s.codecs}" bandwidth="${s.bitrate}" audioSamplingRate="${s.sampleRate}">
<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${s.channels}"/>
<BaseURL>$url</BaseURL>
<SegmentBase indexRange="${s.indexRange.first}-${s.indexRange.last}"><Initialization range="${s.initRange.first}-${s.initRange.last}"/></SegmentBase>
</Representation>
</AdaptationSet>
</Period>
</MPD>
"""
    }

    /* ---------- plumbing ---------- */

    /** The client list, refreshed from the repo every 12 hours; the last good copy survives restarts. */
    private fun clients(): List<Client> {
        val now = System.currentTimeMillis()
        if (now - configAt > 12 * 3_600_000L) {
            configAt = now
            val fresh = runCatching {
                ApiClient.http.newCall(Request.Builder().url(CONFIG_URL).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
            val text = fresh ?: if (Prefs.isReady()) Prefs.getString("yt_config") else null
            val parsed = text?.let { t -> runCatching { json.decodeFromString<Config>(t) }.getOrNull() }?.takeIf { it.clients.isNotEmpty() }
            if (parsed != null) {
                config = parsed
                if (fresh != null && Prefs.isReady()) Prefs.putString("yt_config", fresh)
            }
        }
        return config.clients
    }

    /** YouTube wants a visitor id on player requests; one is good for a day. */
    private fun visitorData(): String? {
        val now = System.currentTimeMillis()
        visitor?.let { if (now - visitorAt < 86_400_000L) return it }
        val v = runCatching {
            val body = buildJsonObject { putJsonObject("context") { putJsonObject("client") { put("clientName", "WEB"); put("clientVersion", "2.20260213.00.00"); put("hl", "en"); put("gl", "US") } } }
            (postJson("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body, mapOf("User-Agent" to WEB_UA))["responseContext"] as? JsonObject)?.get("visitorData")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (v != null) {
            visitor = v
            visitorAt = now
        }
        return v ?: visitor
    }

    private fun postJson(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        ApiClient.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "youtube ${res.code}")
            return json.parseToJsonElement(res.body?.string() ?: "{}") as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    /** Test seam: forget matches and benches so a test starts clean. */
    fun resetForTest() {
        matches.clear()
        benched.clear()
    }
}
