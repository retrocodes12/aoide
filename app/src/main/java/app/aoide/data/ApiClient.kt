package app.aoide.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * GET a hifi-api route across every configured mirror until one answers. 404 is authoritative;
 * anything else falls through to the next mirror, and when every mirror is benched the browsing
 * routes are answered from TIDAL's public catalogue instead (previews only for streams). Bodies
 * are memoised for 15 minutes and concurrent identical requests share one network run.
 */
object ApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val TTL = 15 * 60_000L
    private data class Entry(val at: Long, val body: String)
    private val cache = LinkedHashMap<String, Entry>()
    private val inflight = HashMap<String, Deferred<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun get(path: String, ttl: Long = TTL): String {
        synchronized(cache) { cache[path]?.let { if (System.currentTimeMillis() - it.at < ttl) return it.body } }
        val job = synchronized(inflight) {
            inflight[path] ?: scope.async { runFailover(path) }.also { d ->
                inflight[path] = d
                d.invokeOnCompletion { synchronized(inflight) { inflight.remove(path) } }
            }
        }
        return job.await()
    }

    private fun runFailover(path: String): String {
        if (Instances.allCooling()) {
            // Every mirror is benched: browse straight from TIDAL, keep the mirrors for later.
            runCatching { runBlocking { nativeFallback(path) } }.getOrNull()?.let { body ->
                synchronized(cache) { trim(); cache[path] = Entry(System.currentTimeMillis(), body) }
                Instances.noteSource("tidal")
                return body
            }
        }
        val instances = Instances.ordered()
        var last: Exception? = null
        passes@ for (pass in 0 until 2) {
            for (inst in instances) {
                if (Instances.allCooling()) break@passes
                if (inst.isCooling && instances.any { !it.isCooling }) continue
                val t0 = System.currentTimeMillis()
                try {
                    http.newCall(Request.Builder().url(inst.url + path).header("User-Agent", UA).build()).execute().use { res ->
                        if (res.code == 404) throw ApiException(404, "Not found")
                        if (!res.isSuccessful) {
                            // 5xx / 401 / 403 mean the mirror itself is broken right now: bench it at once.
                            Instances.reportFailure(inst.url, hard = res.code >= 500 || res.code == 401 || res.code == 403)
                            last = ApiException(res.code, "${inst.host} answered ${res.code}")
                            return@use
                        }
                        val body = res.body?.string() ?: ""
                        val trimmed = body.trimStart()
                        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                            Instances.reportFailure(inst.url, hard = true)
                            last = ApiException(res.code, "${inst.host} sent something that is not JSON")
                            return@use
                        }
                        // Some mirrors answer 200 with { detail: "Upstream API error" }: a failure wearing a success code.
                        if (trimmed.startsWith("{\"detail\"") || (trimmed.contains("\"detail\"") && !trimmed.contains("\"data\""))) {
                            Instances.reportFailure(inst.url)
                            last = ApiException(502, "${inst.host}: upstream error")
                            return@use
                        }
                        Instances.reportSuccess(inst.url, System.currentTimeMillis() - t0)
                        synchronized(cache) { trim(); cache[path] = Entry(System.currentTimeMillis(), body) }
                        Instances.noteSource("mirror")
                        return body
                    }
                } catch (e: ApiException) {
                    if (e.status == 404) throw e
                    last = e
                } catch (e: IOException) {
                    Instances.reportFailure(inst.url)
                    last = e
                }
            }
        }
        // Every mirror failed: fall back to TIDAL's public catalogue for browsing routes.
        try {
            val body = runBlocking { nativeFallback(path) }
            synchronized(cache) { trim(); cache[path] = Entry(System.currentTimeMillis(), body) }
            Instances.noteSource("tidal")
            return body
        } catch (e: ApiException) {
            // A definite not-found from TIDAL beats a mirror timeout: the thing does not exist.
            if (e.status == 404) throw e
        } catch (_: Exception) {
            /* fall through to the last mirror error */
        }
        throw last ?: ApiException(0, "No mirrors configured")
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    /** Called on every insert: past 200 entries, drop whatever has expired, then the oldest. */
    private fun trim() {
        if (cache.size < 200) return
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.at > TTL }
        while (cache.size >= 200) cache.remove(cache.keys.first())
    }

    /* ---------- Native TIDAL fallback ----------
     * Monochrome itself queries api.tidal.com with TIDAL's public browser client id. Client-credentials
     * tokens can browse the whole catalogue and fetch preview manifests; they cannot stream full songs. */
    const val UA = "Aoide/0.1 (Android)"
    private const val CLIENT_ID = "txNoH4kkV41MfH25"
    private const val CLIENT_SECRET = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="
    private var token: String? = null
    private var tokenExp = 0L
    private val tokenLock = Mutex()

    @Serializable
    private data class TokenRes(val access_token: String, val expires_in: Long = 3600)

    private suspend fun nativeToken(): String = tokenLock.withLock {
        token?.takeIf { System.currentTimeMillis() < tokenExp }?.let { return it }
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://auth.tidal.com/v1/oauth2/token")
                .header("Authorization", Credentials.basic(CLIENT_ID, CLIENT_SECRET))
                .post(FormBody.Builder().add("grant_type", "client_credentials").build()).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "token ${res.code}")
                val t = json.decodeFromString<TokenRes>(res.body!!.string())
                token = t.access_token
                tokenExp = System.currentTimeMillis() + (t.expires_in - 120) * 1000
                t.access_token
            }
        }
    }

    suspend fun nativeGet(path: String): String {
        val key = "native:$path"
        synchronized(cache) { cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL) return it.body } }
        val tok = nativeToken()
        return withContext(Dispatchers.IO) {
            val url = "https://api.tidal.com" + path + (if (path.contains('?')) "&" else "?") + "countryCode=US"
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $tok").build()).execute().use { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "tidal ${res.code}")
                val body = res.body!!.string()
                synchronized(cache) { trim(); cache[key] = Entry(System.currentTimeMillis(), body) }
                body
            }
        }
    }

    private fun nativeJson(path: String): JsonElement = runBlocking { json.parseToJsonElement(nativeGet(path)) }

    /** Page through a TIDAL v1 list (max 50 per page) up to [max] items. */
    private fun nativePages(path: String, max: Int): JsonArray {
        val out = ArrayList<JsonElement>()
        val sep = if (path.contains('?')) "&" else "?"
        var offset = 0
        while (offset < max) {
            val page = nativeJson("$path${sep}limit=50&offset=$offset").jsonObject
            val items = page["items"]?.jsonArray ?: JsonArray(emptyList())
            out.addAll(items)
            val total = page["totalNumberOfItems"]?.toString()?.toIntOrNull()
            if (items.size < 50 || (total != null && out.size >= total)) break
            offset += 50
        }
        return JsonArray(out)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Map a hifi-api route onto TIDAL's v1 API and hand back a body in the mirror's shape, so the
     * typed decoders in [Catalog] do not know the difference.
     */
    private suspend fun nativeFallback(path: String): String = withContext(Dispatchers.IO) {
        val route = path.substringBefore('?').trimEnd('/')
        val q = path.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { p -> p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), "UTF-8") }
        val lim = q["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 12
        fun wrap(data: JsonElement) = buildJsonObject { put("data", data) }.toString()
        when {
            route == "/search" && q.containsKey("s") -> wrap(nativeJson("/v1/search/tracks?query=${enc(q["s"]!!)}&limit=$lim&offset=${q["offset"] ?: "0"}"))
            route == "/search" && q.containsKey("a") -> {
                val r = nativeJson("/v1/search?query=${enc(q["a"]!!)}&limit=$lim&types=ARTISTS,ALBUMS,PLAYLISTS,TRACKS").jsonObject
                // TIDAL v1 answers with a single `topHit`; the mirrors expose `topHits`. Normalise.
                val fixed = buildJsonObject {
                    r.forEach { (k, v) -> put(k, v) }
                    if (!r.containsKey("topHits")) r["topHit"]?.let { put("topHits", JsonArray(listOf(it))) }
                }
                wrap(fixed)
            }
            route == "/search" && q.containsKey("al") -> wrap(nativeJson("/v1/search?query=${enc(q["al"]!!)}&limit=$lim&types=ALBUMS"))
            route == "/search" && q.containsKey("p") -> wrap(buildJsonObject { put("playlists", nativeJson("/v1/search/playlists?query=${enc(q["p"]!!)}&limit=$lim")) })
            route == "/album" && q.containsKey("id") -> {
                val id = q["id"]!!
                val album = nativeJson("/v1/albums/$id").jsonObject
                val items = nativeJson("/v1/albums/$id/items?limit=100").jsonObject["items"] ?: JsonArray(emptyList())
                wrap(buildJsonObject { album.forEach { (k, v) -> put(k, v) }; put("items", items) })
            }
            route == "/album/similar" -> buildJsonObject { put("albums", JsonArray(emptyList())) }.toString()
            route == "/artist" && q.containsKey("id") -> buildJsonObject { put("artist", nativeJson("/v1/artists/${q["id"]}")) }.toString()
            route == "/artist" && q.containsKey("f") -> {
                val id = q["f"]!!
                // TIDAL caps this list at 50 per page; walk two pages of each so a long discography still shows.
                val albums = nativePages("/v1/artists/$id/albums", 100)
                val eps = nativePages("/v1/artists/$id/albums?filter=EPSANDSINGLES", 100)
                buildJsonObject { put("albums", buildJsonObject { put("items", JsonArray(albums + eps)) }); put("tracks", JsonArray(emptyList())) }.toString()
            }
            route == "/artist/similar" -> buildJsonObject { put("artists", JsonArray(emptyList())) }.toString()
            route == "/playlist" && q.containsKey("id") -> {
                val id = q["id"]!!
                val playlist = nativeJson("/v1/playlists/$id")
                val items = nativeJson("/v1/playlists/$id/items?limit=100").jsonObject["items"] ?: JsonArray(emptyList())
                buildJsonObject { put("playlist", playlist); put("items", items) }.toString()
            }
            route == "/recommendations" -> wrap(buildJsonObject { put("items", JsonArray(emptyList())) })
            else -> throw ApiException(404, "No native route for $path")
        }
    }

    @Serializable
    data class NativeManifest(val trackPresentation: String = "PREVIEW", val previewReason: String? = null, val uri: String = "", val formats: List<String> = emptyList())

    @Serializable
    private data class NativeManifestEnvelope(val data: Data) {
        @Serializable
        data class Data(val attributes: NativeManifest)
    }

    /**
     * Last-resort stream source: TIDAL's own manifest endpoint (previews only without a subscription).
     * The endpoint honours the order of `formats`, so the requested tier decides what comes back.
     */
    suspend fun nativeManifest(id: Long, quality: Quality = Quality.LOSSLESS): NativeManifest {
        val tok = nativeToken()
        val formats = when (quality) {
            Quality.LOW -> listOf("HEAACV1", "AACLC")
            Quality.HIGH -> listOf("AACLC", "HEAACV1")
            else -> listOf("FLAC", "AACLC", "HEAACV1")
        }
        return withContext(Dispatchers.IO) {
            val url = "https://openapi.tidal.com/v2/trackManifests/$id?adaptive=false&manifestType=MPEG_DASH&uriScheme=HTTPS&usage=PLAYBACK&countryCode=US" + formats.joinToString("") { "&formats=$it" }
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $tok").header("Accept", "application/vnd.api+json").build()).execute().use { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "manifest ${res.code}")
                json.decodeFromString<NativeManifestEnvelope>(res.body!!.string()).data.attributes
            }
        }
    }
}

@Suppress("unused")
private fun keep(o: JsonObject) = o
