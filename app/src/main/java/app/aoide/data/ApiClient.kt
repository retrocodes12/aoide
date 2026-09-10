package app.aoide.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * GET a hifi-api route across every configured mirror until one answers. 404 is authoritative;
 * anything else falls through to the next mirror. Bodies are memoised for 15 minutes and
 * concurrent identical requests share one network run.
 */
object ApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val TTL = 15 * 60_000L
    private data class Entry(val at: Long, val body: String)
    private val cache = HashMap<String, Entry>()
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
        val instances = Instances.ordered()
        var last: Exception? = null
        for (pass in 0 until 2) {
            for (inst in instances) {
                val t0 = System.currentTimeMillis()
                try {
                    http.newCall(Request.Builder().url(inst.url + path).header("User-Agent", UA).build()).execute().use { res ->
                        if (res.code == 404) throw ApiException(404, "Not found")
                        if (!res.isSuccessful) {
                            Instances.reportFailure(inst.url)
                            last = ApiException(res.code, "${inst.host} answered ${res.code}")
                            return@use
                        }
                        val body = res.body?.string() ?: ""
                        if (!body.trimStart().startsWith("{") && !body.trimStart().startsWith("[")) {
                            Instances.reportFailure(inst.url)
                            last = ApiException(res.code, "${inst.host} sent something that is not JSON")
                            return@use
                        }
                        Instances.reportSuccess(inst.url, System.currentTimeMillis() - t0)
                        synchronized(cache) { cache[path] = Entry(System.currentTimeMillis(), body) }
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
        throw last ?: ApiException(0, "No mirrors configured")
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    /* ---------- Native TIDAL fallback, metadata only ----------
     * Monochrome itself queries api.tidal.com with TIDAL's public browser client id for routes
     * the mirrors do not expose (top tracks, bios). Client-credentials tokens cannot stream. */
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
                synchronized(cache) { cache[key] = Entry(System.currentTimeMillis(), body) }
                body
            }
        }
    }

    @Serializable
    data class NativeManifest(val trackPresentation: String = "PREVIEW", val previewReason: String? = null, val uri: String = "", val formats: List<String> = emptyList())

    @Serializable
    private data class NativeManifestEnvelope(val data: Data) {
        @Serializable
        data class Data(val attributes: NativeManifest)
    }

    /** Last-resort stream source: TIDAL's own manifest endpoint (previews only without a subscription). */
    suspend fun nativeManifest(id: Long): NativeManifest {
        val tok = nativeToken()
        return withContext(Dispatchers.IO) {
            val url = "https://openapi.tidal.com/v2/trackManifests/$id?adaptive=false&manifestType=MPEG_DASH&uriScheme=HTTPS&usage=PLAYBACK&countryCode=US&formats=FLAC&formats=AACLC&formats=HEAACV1"
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $tok").header("Accept", "application/vnd.api+json").build()).execute().use { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "manifest ${res.code}")
                json.decodeFromString<NativeManifestEnvelope>(res.body!!.string()).data.attributes
            }
        }
    }
}
