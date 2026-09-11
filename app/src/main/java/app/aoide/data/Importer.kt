package app.aoide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs

/**
 * Brings playlists over from Spotify and YouTube Music. Neither needs a login: a public Spotify
 * playlist's embed page carries its track list, and a YouTube Music playlist reads through the
 * same browse call the app already makes. Each song is then matched on TIDAL by artist, title and
 * length; misses are reported rather than guessed.
 */
object Importer {
    data class Song(val title: String, val artists: String, val durationSec: Int)
    data class Source(val kind: String, val id: String, val title: String, val songs: List<Song>)
    data class Result(val title: String, val matched: List<Track>, val missed: List<Song>, val source: String)

    private val JSON_TYPE = "application/json".toMediaType()
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /** Which service a pasted link belongs to, or null when it is not a playlist link we can read. */
    fun recognise(link: String): Pair<String, String>? {
        val s = link.trim()
        Regex("open\\.spotify\\.com/(?:embed/)?playlist/([A-Za-z0-9]+)").find(s)?.let { return "spotify" to it.groupValues[1] }
        Regex("spotify:playlist:([A-Za-z0-9]+)").find(s)?.let { return "spotify" to it.groupValues[1] }
        Regex("(?:music\\.youtube\\.com|youtube\\.com)/playlist\\?(?:.*&)?list=([A-Za-z0-9_-]+)").find(s)?.let { return "youtube" to it.groupValues[1] }
        return null
    }

    suspend fun read(kind: String, id: String): Source = when (kind) {
        "spotify" -> spotify(id)
        "youtube" -> youtube(id)
        else -> throw IllegalArgumentException("Unknown source $kind")
    }

    /** Match every song on TIDAL, a few at a time. */
    suspend fun match(src: Source, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result = coroutineScope {
        var done = 0
        val results = src.songs.chunked(4).flatMap { chunk ->
            chunk.map { song -> async { song to find(song) } }.map { it.await() }.also { done += chunk.size; onProgress(done, src.songs.size) }
        }
        Result(src.title, results.mapNotNull { it.second }, results.filter { it.second == null }.map { it.first }, "${src.kind}:${src.id}")
    }

    private suspend fun find(song: Song): Track? {
        val primary = song.artists.split(",", "&").firstOrNull()?.trim().orEmpty()
        val hits = runCatching { Catalog.searchTracks("$primary ${song.title}", 8).items }.getOrDefault(emptyList())
        val want = YouTubeMusic.norm(song.title)
        val wantArtist = YouTubeMusic.norm(primary)
        return hits.filter { t ->
            val got = YouTubeMusic.norm(t.title)
            val titleOk = got == want || got.startsWith(want) || want.startsWith(got)
            val artistOk = wantArtist.isEmpty() || YouTubeMusic.norm(t.artistNames).contains(wantArtist)
            val lengthOk = song.durationSec <= 0 || abs(t.duration - song.durationSec) <= 5
            titleOk && artistOk && lengthOk
        }.minByOrNull { abs(it.duration - song.durationSec) }
    }

    private suspend fun spotify(id: String): Source = withContext(Dispatchers.IO) {
        val html = ApiClient.http.newCall(Request.Builder().url("https://open.spotify.com/embed/playlist/$id").header("User-Agent", UA).build()).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "spotify ${res.code}")
            res.body?.string() ?: ""
        }
        val blob = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Spotify did not return the playlist; is it public?")
        val root = json.parseToJsonElement(blob)
        val entity = path(root, "props", "pageProps", "state", "data", "entity") as? JsonObject ?: throw IllegalStateException("Could not read the playlist")
        val title = entity["name"]?.jsonPrimitive?.contentOrNull ?: "Spotify playlist"
        val songs = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Song(t, o["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty().replace(' ', ' '), ((o["duration"]?.jsonPrimitive?.longOrNull ?: 0L) / 1000).toInt())
        }
        Source("spotify", id, title, songs)
    }

    private suspend fun youtube(id: String): Source = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("context") { putJsonObject("client") { put("clientName", "WEB_REMIX"); put("clientVersion", "1.20260213.01.00"); put("hl", "en"); put("gl", "US") } }
            put("browseId", if (id.startsWith("VL")) id else "VL$id")
        }
        val req = Request.Builder().url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false").post(body.toString().toRequestBody(JSON_TYPE)).header("User-Agent", UA).header("Origin", "https://music.youtube.com").build()
        val root = ApiClient.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "youtube ${res.code}")
            json.parseToJsonElement(res.body?.string() ?: "{}")
        }
        var title: String? = null
        val songs = ArrayList<Song>()
        fun runs(t: JsonElement?): String = ((t as? JsonObject)?.get("runs") as? JsonArray)?.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: "" } ?: ""
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    for (k in listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer")) {
                        (e[k] as? JsonObject)?.let { h -> if (title == null) title = runs(h["title"]).ifBlank { null } }
                    }
                    (e["musicResponsiveListItemRenderer"] as? JsonObject)?.let { r ->
                        val cols = (r["flexColumns"] as? JsonArray).orEmpty().map { c -> runs(((c as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject)?.get("text")) }
                        val fixed = (r["fixedColumns"] as? JsonArray).orEmpty().map { c -> runs(((c as? JsonObject)?.get("musicResponsiveListItemFixedColumnRenderer") as? JsonObject)?.get("text")) }
                        val secs = fixed.firstOrNull()?.split(":")?.mapNotNull { it.toIntOrNull() }?.let { p -> if (p.size == 2) p[0] * 60 + p[1] else if (p.size == 3) p[0] * 3600 + p[1] * 60 + p[2] else null } ?: 0
                        if (cols.size >= 2 && cols[0].isNotBlank()) songs.add(Song(cols[0], cols[1].split(" • ").first(), secs))
                    }
                    e.values.forEach { walk(it) }
                }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        Source("youtube", id, title ?: "YouTube Music playlist", songs)
    }

    private fun path(root: JsonElement, vararg keys: String): JsonElement? {
        var cur: JsonElement? = root
        for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
        return cur
    }
}
