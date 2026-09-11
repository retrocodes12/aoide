package app.aoide.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * GET a route across every configured lossless mirror until one answers. 404 is authoritative;
 * anything else falls through to the next mirror. Bodies are memoised for 15 minutes and
 * concurrent identical requests share one network run. Also home to the one OkHttp client.
 */
object ApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    const val UA = "Aoide/0.1 (Android)"
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

    /** Called on every insert: past 200 entries, drop whatever has expired, then the oldest. */
    private fun trim() {
        if (cache.size < 200) return
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.at > TTL }
        while (cache.size >= 200) cache.remove(cache.keys.first())
    }
}
