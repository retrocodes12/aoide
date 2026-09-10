package app.aoide.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
}

/**
 * Registry of hifi-api compatible mirrors, the same ones Monochrome lists. User-added instances
 * are tried first; the public list refreshes from the uptime worker on launch.
 */
object Instances {
    private const val KEY = "instances_v1"
    private const val UPTIME = "https://tidal-uptime.props-76styles.workers.dev/"
    val DEFAULTS = listOf(
        Instance("https://lol.samidy.workers.dev", "2.10"),
        Instance("https://monochrome-api.samidy.com", "2.3"),
    )
    private val _list = MutableStateFlow(DEFAULTS)
    val list: StateFlow<List<Instance>> = _list
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

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

    /** User first, then whatever is not cooling down, fastest first. */
    fun ordered(): List<Instance> {
        val now = System.currentTimeMillis()
        return _list.value.sortedWith(compareBy<Instance> { if (it.coolUntil > now) 1 else 0 }.thenBy { if (it.isUser) 0 else 1 }.thenBy { if (it.lastLatencyMs < 0) Long.MAX_VALUE else it.lastLatencyMs })
    }

    fun add(url: String): Boolean {
        val u = normalize(url) ?: return false
        if (_list.value.any { it.url == u }) return false
        _list.value = listOf(Instance(u, "custom", true)) + _list.value
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
    }

    fun reportFailure(url: String) {
        _list.value.find { it.url == url }?.let {
            it.fails += 1
            if (it.fails >= 3) it.coolUntil = System.currentTimeMillis() + 60_000
        }
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
