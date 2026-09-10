package app.aoide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class Instance(
    val url: String,
    val version: String? = null,
    val isUser: Boolean = false,
) {
    @kotlinx.serialization.Transient var fails: Int = 0
    @kotlinx.serialization.Transient var coolUntil: Long = 0
    @kotlinx.serialization.Transient var lastLatencyMs: Long = -1
    val host: String get() = url.removePrefix("https://").removePrefix("http://")
    val isCooling: Boolean get() = coolUntil > System.currentTimeMillis()
}

/**
 * Registry of hifi-api compatible mirrors, the same ones Monochrome lists. User-added instances
 * are tried first; the public list refreshes from the uptime worker on launch. A mirror that
 * fails three times (or answers 5xx / 401 / 403 once) is benched for 90 s; while every mirror is
 * benched the app browses TIDAL directly and says so.
 */
object Instances {
    private const val KEY = "instances_v1"
    private const val UPTIME = "https://tidal-uptime.props-76styles.workers.dev/"
    private const val COOL_MS = 90_000L
    val DEFAULTS = listOf(
        Instance("https://lol.samidy.workers.dev", "2.10"),
        Instance("https://monochrome-api.samidy.com", "2.3"),
    )
    private val _list = MutableStateFlow(DEFAULTS)
    val list: StateFlow<List<Instance>> = _list
    /** Bumped on every health change so observers re-read [list] (its items are mutated in place). */
    private val _health = MutableStateFlow(0L)
    val health: StateFlow<Long> = _health
    /** Where the last successful answer came from: "mirror" or "tidal". */
    private val _source = MutableStateFlow<String?>(null)
    val source: StateFlow<String?> = _source
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    fun load() {
        val saved = Prefs.getString(KEY)?.let { runCatching { json.decodeFromString<List<Instance>>(it) }.getOrNull() }
        if (!saved.isNullOrEmpty()) _list.value = dedupe(saved.filter { it.isUser } + DEFAULTS + saved)
    }

    fun normalize(url: String): String? {
        val t = url.trim().trimEnd('/')
        return if (Regex("^https?://[^\\s/]+$", RegexOption.IGNORE_CASE).matches(t)) t else null
    }

    private fun dedupe(items: List<Instance>): List<Instance> {
        val seen = LinkedHashMap<String, Instance>()
        for (i in items) {
            val u = normalize(i.url) ?: continue
            val prev = seen[u]
            seen[u] = Instance(u, i.version ?: prev?.version, (prev?.isUser ?: false) || i.isUser)
        }
        return seen.values.toList()
    }

    private fun persist() {
        Prefs.putString(KEY, json.encodeToString(_list.value))
    }

    private fun bump() { _health.value = System.currentTimeMillis() }

    /** User first, then whatever is not cooling down, fastest first. */
    fun ordered(): List<Instance> {
        val now = System.currentTimeMillis()
        return _list.value.sortedWith(compareBy<Instance> { if (it.coolUntil > now) 1 else 0 }.thenBy { if (it.isUser) 0 else 1 }.thenBy { if (it.lastLatencyMs < 0) Long.MAX_VALUE else it.lastLatencyMs })
    }

    /** True when every mirror is benched, so callers can go straight to the fallback. */
    fun allCooling(): Boolean {
        val now = System.currentTimeMillis()
        return _list.value.isNotEmpty() && _list.value.all { it.coolUntil > now }
    }

    /** True while the app is living off TIDAL directly: every mirror benched, or the last answer came from the fallback. */
    fun mirrorsDown(): Boolean = allCooling() || _source.value == "tidal"

    fun noteSource(src: String) {
        if (_source.value == src) return
        _source.value = src
        bump()
    }

    fun add(url: String): Boolean {
        val u = normalize(url) ?: return false
        if (_list.value.any { it.url == u }) return false
        _list.value = listOf(Instance(u, null, true)) + _list.value
        persist()
        return true
    }

    fun remove(url: String) {
        _list.value = _list.value.filter { it.url != url }.ifEmpty { DEFAULTS }
        persist()
    }

    fun reset() {
        _list.value = DEFAULTS
        persist()
    }

    fun reportSuccess(url: String, latencyMs: Long) {
        _list.value.find { it.url == url }?.let { it.fails = 0; it.coolUntil = 0; it.lastLatencyMs = latencyMs }
        bump()
    }

    /** [hard] failures (5xx, 401, 403, non-JSON bodies) bench the mirror at once. */
    fun reportFailure(url: String, hard: Boolean = false) {
        _list.value.find { it.url == url }?.let {
            it.fails += if (hard) 3 else 1
            if (it.fails >= 3) it.coolUntil = System.currentTimeMillis() + COOL_MS
        }
        bump()
    }

    /** One cheap request per mirror, in parallel, so the labels describe now rather than the last failure. */
    suspend fun probe() = withContext(Dispatchers.IO) {
        coroutineScope {
            _list.value.map { inst ->
                async {
                    val t0 = System.currentTimeMillis()
                    runCatching {
                        http.newCall(Request.Builder().url("${inst.url}/search/?s=a&limit=1").header("User-Agent", ApiClient.UA).build()).execute().use { res ->
                            val body = res.body?.string() ?: ""
                            if (res.isSuccessful && body.trimStart().startsWith("{") && !body.contains("\"detail\"")) reportSuccess(inst.url, System.currentTimeMillis() - t0)
                            else reportFailure(inst.url, hard = true)
                        }
                    }.onFailure { reportFailure(inst.url) }
                }
            }.awaitAll()
        }
        Unit
    }

    @Serializable
    private data class UptimeApi(val url: String, val version: String? = null)

    @Serializable
    private data class Uptime(val api: List<UptimeApi> = emptyList())

    /** Blocking; call from a background dispatcher. Never removes user instances. */
    fun refreshFromUptime() {
        runCatching {
            http.newCall(Request.Builder().url(UPTIME).build()).execute().use { res ->
                if (!res.isSuccessful) return
                val body = res.body?.string() ?: return
                val fresh = json.decodeFromString<Uptime>(body).api.mapNotNull { a -> normalize(a.url)?.let { Instance(it, a.version) } }
                if (fresh.isEmpty()) return
                _list.value = dedupe(_list.value + fresh)
                persist()
            }
        }
    }
}
