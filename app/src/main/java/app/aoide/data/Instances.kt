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
    /** True once this mirror has been seen serving a song in full (a subscribed account behind it), false once seen previewing, null until probed. */
    @kotlinx.serialization.Transient var full: Boolean? = null
    val host: String get() = url.removePrefix("https://").removePrefix("http://")
    val isCooling: Boolean get() = coolUntil > System.currentTimeMillis()
}

/**
 * Registry of lossless mirrors: catalogue proxies that, when backed by a subscribed account,
 * serve a song as FLAC. User-added mirrors are tried first. A mirror that fails three times (or
 * answers 5xx / 401 / 403 once) is benched for 90 s. The mirrors are an upgrade, never the
 * catalogue: browsing and the default stream come from the music service.
 */
object Instances {
    private const val KEY = "instances_v1"
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
    /** True while at least one mirror has been seen serving full songs; the lossless lookups only run then. */
    private val _anyFull = MutableStateFlow(false)
    val anyFull: StateFlow<Boolean> = _anyFull
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    fun load() {
        val saved = Prefs.getString(KEY)?.let { runCatching { json.decodeFromString<List<Instance>>(it) }.getOrNull() }
        if (!saved.isNullOrEmpty()) _list.value = dedupe(saved.filter { it.isUser } + DEFAULTS + saved)
    }

    fun normalize(url: String): String? {
        val t = url.trim().trimEnd('/')
        // The manifest forbids cleartext, so an http:// mirror would be accepted and then fail every request silently.
        return if (Regex("^https://[^\\s/]+$", RegexOption.IGNORE_CASE).matches(t)) t else null
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

    fun noteFull(url: String, full: Boolean) {
        _list.value.find { it.url == url }?.full = full
        _anyFull.value = _list.value.any { it.full == true }
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
        // Fresh objects: the defaults are shared instances whose bench state would otherwise survive a reset.
        _list.value = DEFAULTS.map { it.copy() }
        persist()
        bump()
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

    /**
     * One request per mirror, in parallel: a known song's manifest, which says whether the mirror
     * serves songs in full or only previews. The labels then describe now, not the last failure.
     */
    suspend fun probe() = withContext(Dispatchers.IO) {
        coroutineScope {
            _list.value.map { inst ->
                async {
                    val t0 = System.currentTimeMillis()
                    runCatching {
                        http.newCall(Request.Builder().url("${inst.url}/track/?id=$PROBE_TRACK&quality=LOSSLESS").header("User-Agent", ApiClient.UA).build()).execute().use { res ->
                            val body = res.body?.string() ?: ""
                            if (res.isSuccessful && body.trimStart().startsWith("{") && body.contains("\"manifest\"")) {
                                reportSuccess(inst.url, System.currentTimeMillis() - t0)
                                noteFull(inst.url, Regex("\"assetPresentation\"\\s*:\\s*\"FULL\"").containsMatchIn(body))
                            } else reportFailure(inst.url, hard = true)
                        }
                    }.onFailure { reportFailure(inst.url) }
                }
            }.awaitAll()
        }
        Unit
    }

    /** A song every mirror's catalogue has, for the probe. */
    const val PROBE_TRACK = 58990486L
}
