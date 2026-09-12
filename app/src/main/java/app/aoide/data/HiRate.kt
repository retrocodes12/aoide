package app.aoide.data

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * A second music source, for songs it carries at 320 kbps AAC rather than the first service's
 * Opus. It needs no account: its search is open, and each song's media URL arrives encrypted with
 * a fixed key that its own web player also uses.
 *
 * Matching is deliberately strict. This catalogue is deep in Indian music and thin elsewhere, and
 * a loose match happily returns a piano tribute or a lullaby cover instead of the record you
 * asked for, so the artist, the title and the length must all agree or the song is left alone.
 *
 * The files are plain (non-fragmented) MP4, so they play progressively rather than through the
 * app's DASH path, and their addresses are static: once found, a song's URL keeps working, which
 * is why the answers are cached for a week and can be baked straight into a queue item.
 */
object HiRate {
    private const val CIPHER_KEY = "38346591"
    private const val API = "https://www.jiosaavn.com/api.php"
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private const val TTL = 7 * 86_400_000L
    /** The bitrate the CDN serves when its 96 kbps address is asked for the bigger file. */
    const val KBPS = 320

    /** track id -> the 320 kbps URL, or "" when this source does not have the song. */
    private val _known = MutableStateFlow<Map<String, String>>(emptyMap())
    val known: StateFlow<Map<String, String>> = _known
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    val enabled: Boolean get() = !Prefs.isReady() || Prefs.hiRate.on

    /** The 320 kbps address for a song, if this source has been found to carry it. */
    fun url(trackId: String): String? = _known.value[trackId]?.takeIf { it.isNotEmpty() }
    fun has(trackId: String): Boolean = url(trackId) != null
    /** True once the answer is known either way, so callers can tell "no" from "not asked yet". */
    fun isAnswered(trackId: String): Boolean = _known.value.containsKey(trackId)

    /** Ask, once, whether this source carries the song. Cheap to call from every row. */
    fun request(t: Track) {
        if (!enabled || t.isLocal || t.duration <= 0) return
        if (_known.value.containsKey(t.id) || !pending.add(t.id)) return
        stored(t.id)?.let { _known.value = _known.value + (t.id to it); pending.remove(t.id); return }
        scope.launch {
            gate.withPermit {
                val found = runCatching { lookup(t) }.getOrNull().orEmpty()
                remember(t.id, found)
                _known.value = _known.value + (t.id to found)
                pending.remove(t.id)
            }
        }
    }

    fun requestAll(tracks: List<Track>) = tracks.take(30).forEach(::request)

    /** One candidate from this source's search. */
    data class Candidate(val title: String, val artists: String, val album: String, val durationSec: Int, val has320: Boolean, val encrypted: String)

    /** The 320 kbps URL for the same recording, or null when this source does not have it. */
    suspend fun lookup(t: Track): String? {
        val primary = t.primaryArtist?.name.orEmpty()
        val hits = search("$primary ${t.title}".trim())
        val want = Parse.norm(t.title)
        val wantArtist = Parse.norm(primary)
        val best = hits.filter { c ->
            val got = Parse.norm(c.title)
            // A title may carry a "(From "...")" tail the first service leaves off, so a prefix counts.
            val titleOk = want.isNotEmpty() && (got == want || got.startsWith(want) || want.startsWith(got))
            // The artist must really be there: this catalogue is full of tribute and cover acts.
            val names = Parse.norm(c.artists)
            val artistOk = wantArtist.isNotEmpty() && (names.contains(wantArtist) || wantArtist.contains(names))
            val lengthOk = t.duration <= 0 || abs(c.durationSec - t.duration) <= 3
            titleOk && artistOk && lengthOk && c.has320
        }.minByOrNull { abs(it.durationSec - t.duration) } ?: return null
        return mediaUrl(best.encrypted)
    }

    suspend fun search(query: String): List<Candidate> = withContext(Dispatchers.IO) {
        val url = "$API?__call=search.getResults&_format=json&_marker=0&ctx=web6dot0&api_version=4&p=1&n=8&q=" + URLEncoder.encode(query, "UTF-8")
        val body = ApiClient.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "The second source answered ${res.code}")
            res.body?.string() ?: ""
        }
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@withContext emptyList()
        (root["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val mi = o["more_info"] as? JsonObject ?: return@mapNotNull null
            val enc = mi["encrypted_media_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artists = ((mi["artistMap"] as? JsonObject)?.get("primary_artists") as? JsonArray).orEmpty()
                .mapNotNull { a -> (a as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.joinToString(", ")
            Candidate(
                title = unescape(o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null),
                artists = artists.ifBlank { unescape(o["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()).substringBefore(" - ") },
                album = unescape(mi["album"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                durationSec = mi["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                has320 = mi["320kbps"]?.jsonPrimitive?.contentOrNull == "true",
                encrypted = enc,
            )
        }
    }

    /** The address its web player uses, decrypted and pointed at the biggest file it serves. */
    fun mediaUrl(encrypted: String): String? {
        val plain = runCatching {
            val c = Cipher.getInstance("DES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(CIPHER_KEY.toByteArray(Charsets.UTF_8), "DES"))
            String(c.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrNull()?.trim() ?: return null
        if (!plain.contains("://")) return null
        // The URL it hands out points at the 96 kbps file; the 320 one sits beside it under the same name.
        return plain.replace("_96.mp4", "_$KBPS.mp4").replace("http://", "https://")
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun remember(trackId: String, v: String) { if (Prefs.isReady()) Prefs.putString("hr:$trackId", "$v|${System.currentTimeMillis()}") }
    private fun stored(trackId: String): String? {
        val s = (if (Prefs.isReady()) Prefs.getString("hr:$trackId") else null) ?: return null
        val at = s.substringAfterLast('|').toLongOrNull() ?: return null
        return if (System.currentTimeMillis() - at < TTL) s.substringBeforeLast('|') else null
    }

    /** Test seam. */
    fun setKnownForTest(trackId: String, url: String) { _known.value = _known.value + (trackId to url) }
}
