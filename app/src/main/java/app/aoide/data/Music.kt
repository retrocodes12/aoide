package app.aoide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The music service behind everything: its catalogue (search, browse, queues) through the web
 * client's private API, and its audio through the player API while posing as one of the
 * service's own apps. Only streams handed out as plain URLs are used: no signature cipher, no
 * anti-bot challenge, no JavaScript. Some clients' URLs are cut off at about 1 MiB, so every
 * stream is checked by reading its last bytes before it is trusted.
 *
 * Which app to pose as changes when the service tightens one, so the client list is also fetched
 * from the repo (config/yt-clients.json) and the built-in copy below is only the fallback.
 */
object Music {
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
    data class Config(val searchClientVersion: String = WEB_VERSION, val clients: List<Client> = BUILT_IN)

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

    private const val WEB_VERSION = "1.20260213.01.00"
    private const val WEB_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private const val MUSIC = "https://music.youtube.com"
    const val CONFIG_URL = "https://raw.githubusercontent.com/retrocodes12/aoide/main/config/yt-clients.json"
    const val CDN_SUFFIX = "googlevideo.com"

    /** Search filters: songs, albums, artists, featured playlists, community playlists. */
    const val F_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    const val F_ALBUMS = "EgWKAQIYAWoKEAkQBRAKEAMQBA=="
    const val F_ARTISTS = "EgWKAQIgAWoKEAkQBRAKEAMQBA=="
    const val F_PLAYLISTS = "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU="
    const val F_COMMUNITY = "EgeKAQQoAEABagoQAxAEEAkQChAF"

    /**
     * Measured 2026-09-11: the visionOS client served every file tested start to finish. The
     * VR client returns full-length URLs that stop at about 1 MiB; it stays listed in case that
     * changes, and the end-of-file check rejects it while it does not.
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
    /** Catalogue answers are memoised for fifteen minutes; pages are heavy and the same one is asked for again and again. */
    private data class Entry(val at: Long, val body: JsonObject)
    private val cache = LinkedHashMap<String, Entry>()
    private const val TTL = 15 * 60_000L

    /* ---------- catalogue ---------- */

    private fun webContext(): JsonObject = buildJsonObject {
        putJsonObject("context") { putJsonObject("client") { put("clientName", "WEB_REMIX"); put("clientVersion", config.searchClientVersion); put("hl", "en"); put("gl", "US") } }
    }

    private suspend fun call(endpoint: String, extra: JsonObjectBuilder.() -> Unit): JsonObject = withContext(Dispatchers.IO) {
        clients()
        val body = buildJsonObject { for ((k, v) in webContext()) put(k, v); extra() }
        val key = endpoint + body.toString()
        synchronized(cache) { cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL) return@withContext it.body else cache.remove(key) } }
        val d = postJson("$MUSIC/youtubei/v1/$endpoint?prettyPrint=false", body, mapOf("User-Agent" to WEB_UA, "Origin" to MUSIC, "Referer" to "$MUSIC/"))
        synchronized(cache) {
            if (cache.size >= 120) cache.remove(cache.keys.first())
            cache[key] = Entry(System.currentTimeMillis(), d)
        }
        d
    }

    suspend fun search(query: String, params: String? = null): JsonObject = call("search") { put("query", query); params?.let { put("params", it) } }
    suspend fun browse(browseId: String, params: String? = null): JsonObject = call("browse") { put("browseId", browseId); params?.let { put("params", it) } }
    suspend fun continuation(token: String): JsonObject = call("browse") { put("continuation", token) }
    /** The service's own queue for a song or a playlist: its radio when [playlistId] is `RDAMVM` + the video id. */
    suspend fun next(videoId: String?, playlistId: String?): JsonObject = call("next") {
        videoId?.let { put("videoId", it) }
        playlistId?.let { put("playlistId", it) }
        put("isAudioOnly", true)
        put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        put("enablePersistentPlaylistPanel", true)
    }

    /** Artwork at [size] px square: the service's image host resizes and crops by URL suffix. Other hosts are left alone. */
    fun image(url: String?, size: Int, crop: Boolean = true): String? {
        if (url == null) return null
        if (!url.contains("googleusercontent.com") && !url.contains("ggpht.com")) return url
        val base = url.substringBeforeLast('=', url)
        return "$base=w$size-h$size${if (crop) "-p" else ""}-l90-rj"
    }

    /** A wide crop, for artist banners. */
    fun imageWide(url: String?, w: Int, h: Int): String? {
        if (url == null) return null
        if (!url.contains("googleusercontent.com") && !url.contains("ggpht.com")) return url
        return "${url.substringBeforeLast('=', url)}=w$w-h$h-p-l90-rj"
    }

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

    /** The CDN caps some clients' URLs at about 1 MiB and answers 403 past it; read the last bytes before trusting a stream. */
    private fun servesWholeFile(s: AudioStream, c: Client): Boolean = runCatching {
        val from = (s.contentLength - 1024).coerceAtLeast(0)
        val req = Request.Builder().url(s.url).header("User-Agent", c.userAgent).header("Range", "bytes=$from-${s.contentLength - 1}").build()
        ApiClient.http.newCall(req).execute().use { it.code == 206 }
    }.getOrDefault(false)

    /** The user agent a CDN URL was issued to, from its `c=` parameter. */
    fun userAgentFor(clientName: String?): String = config.clients.firstOrNull { it.name == clientName }?.userAgent ?: BUILT_IN.first().userAgent

    /**
     * A one-file DASH manifest for [s]: the playback service treats every song as DASH, so a stream
     * rides the same path as a mirror's manifest. The init and index byte ranges let ExoPlayer
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

    /** The player API wants a visitor id; one is good for a day. */
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
            if (!res.isSuccessful) throw ApiException(res.code, "The music service answered ${res.code}")
            return json.parseToJsonElement(res.body?.string() ?: "{}") as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    /** Test seam: forget benches and memoised pages so a test starts clean. */
    fun resetForTest() {
        benched.clear()
        synchronized(cache) { cache.clear() }
    }
}
