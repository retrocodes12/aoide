package app.aoide.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The lossless upgrade. A mirror backed by a subscribed account can serve a song as FLAC; this
 * finds the same recording on the mirror's catalogue (artist, title and length must agree) and
 * remembers the answer, so rows can wear an HD mark and the resolver can ask for the manifest.
 * Nothing runs unless a mirror has been seen serving full songs.
 */
object Lossless {
    /** video id -> the mirror's track id, or "" when the mirror does not have the song. */
    private val _known = MutableStateFlow<Map<String, String>>(emptyMap())
    val known: StateFlow<Map<String, String>> = _known
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private const val TTL = 7 * 86_400_000L

    val enabled: StateFlow<Boolean> get() = Instances.anyFull

    fun has(videoId: String): Boolean = _known.value[videoId]?.isNotEmpty() == true
    fun mirrorId(videoId: String): Long? = _known.value[videoId]?.toLongOrNull()

    /** Ask, once, whether the mirror has this song. Cheap to call from every row. */
    fun request(t: Track) {
        if (!enabled.value || t.isLocal || t.duration <= 0) return
        if (_known.value.containsKey(t.id) || !pending.add(t.id)) return
        stored(t.id)?.let { _known.value = _known.value + (t.id to it); pending.remove(t.id); return }
        scope.launch {
            gate.withPermit {
                val id = runCatching { lookup(t) }.getOrNull()
                val v = id?.toString() ?: ""
                if (id != null || true) remember(t.id, v)
                _known.value = _known.value + (t.id to v)
                pending.remove(t.id)
            }
        }
    }

    @Serializable private data class Wrap(val data: Paged)
    @Serializable private data class Paged(val items: List<MirrorTrack> = emptyList())
    @Serializable private data class MirrorTrack(val id: Long, val title: String = "", val duration: Int = 0, val artist: MirrorArtist? = null, val artists: List<MirrorArtist> = emptyList())
    @Serializable private data class MirrorArtist(val name: String = "")

    /** The mirror's id for the same recording, or null. */
    suspend fun lookup(t: Track): Long? {
        val primary = t.primaryArtist?.name.orEmpty()
        val body = ApiClient.get("/search/?s=${URLEncoder.encode("$primary ${t.title}".trim(), "UTF-8")}&limit=12")
        val hits = json.decodeFromString<Wrap>(body).data.items
        val want = Parse.norm(t.title)
        val wantArtist = Parse.norm(primary)
        return hits.filter { m ->
            val got = Parse.norm(m.title)
            val titleOk = got == want || got.startsWith(want) || want.startsWith(got)
            val names = (m.artists.map { it.name } + listOfNotNull(m.artist?.name)).map(Parse::norm)
            val artistOk = wantArtist.isEmpty() || names.any { it.contains(wantArtist) || wantArtist.contains(it) }
            val lengthOk = t.duration <= 0 || abs(m.duration - t.duration) <= 3
            titleOk && artistOk && lengthOk
        }.minByOrNull { abs(it.duration - t.duration) }?.id
    }

    @Serializable private data class ManifestWrap(val data: ManifestInfo)

    /** The mirror's manifest for a song it has, FULL only; null when it only previews. */
    suspend fun manifest(t: Track, quality: Quality): ManifestInfo? {
        val id = mirrorId(t.id) ?: lookup(t)?.also { remember(t.id, it.toString()); _known.value = _known.value + (t.id to it.toString()) } ?: return null
        val m = json.decodeFromString<ManifestWrap>(ApiClient.get("/track/?id=$id&quality=${quality.name}", ttl = 20 * 60_000L)).data
        return m.takeIf { it.manifest.isNotBlank() && it.assetPresentation.equals("FULL", true) }
    }

    private fun remember(videoId: String, v: String) { if (Prefs.isReady()) Prefs.putString("ll:$videoId", "$v|${System.currentTimeMillis()}") }
    private fun stored(videoId: String): String? {
        val s = (if (Prefs.isReady()) Prefs.getString("ll:$videoId") else null) ?: return null
        val at = s.substringAfter('|').toLongOrNull() ?: return null
        return if (System.currentTimeMillis() - at < TTL) s.substringBefore('|') else null
    }

    /** Test seam. */
    fun setKnownForTest(videoId: String, mirrorId: String) { _known.value = _known.value + (videoId to mirrorId) }
}
