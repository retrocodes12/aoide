package app.aoide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import kotlin.math.abs

/**
 * Brings playlists over from elsewhere. A link to the music service's own playlist is simply
 * opened; a public playlist on the other big streaming service is read from its embed page, no
 * login needed, and each song is matched in the catalogue by artist, title and length. Misses
 * are reported rather than guessed.
 */
object Importer {
    data class Song(val title: String, val artists: String, val durationSec: Int)
    data class Result(val title: String, val matched: List<Track>, val missed: List<Song>, val source: String)

    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /** "list" (the other streaming service) or "video" (the music service), with the playlist id; null for anything else. */
    fun recognise(link: String): Pair<String, String>? {
        val s = link.trim()
        Regex("open\\.spotify\\.com/(?:embed/)?playlist/([A-Za-z0-9]+)").find(s)?.let { return "list" to it.groupValues[1] }
        Regex("spotify:playlist:([A-Za-z0-9]+)").find(s)?.let { return "list" to it.groupValues[1] }
        Regex("(?:music\\.youtube\\.com|youtube\\.com)/playlist\\?(?:.*&)?list=([A-Za-z0-9_-]+)").find(s)?.let { return "video" to it.groupValues[1] }
        return null
    }

    fun kindLabel(kind: String) = if (kind == "video" || kind == "youtube") "the music service" else "the other streaming service"

    /** Read and match, reporting progress as songs are looked up. */
    suspend fun import(kind: String, id: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result = when (kind) {
        "video", "youtube" -> {
            val (p, tracks) = Catalog.playlist(id)
            onProgress(tracks.size, tracks.size)
            Result(p.title, tracks, emptyList(), "video:$id")
        }
        else -> {
            val (title, songs) = embed(id)
            var done = 0
            val results = coroutineScope {
                songs.chunked(4).flatMap { chunk ->
                    chunk.map { song -> async { song to find(song) } }.map { it.await() }.also { done += chunk.size; onProgress(done, songs.size) }
                }
            }
            Result(title, results.mapNotNull { it.second }, results.filter { it.second == null }.map { it.first }, "list:$id")
        }
    }

    private suspend fun find(song: Song): Track? {
        val primary = song.artists.split(",", "&").firstOrNull()?.trim().orEmpty()
        val hits = runCatching { Catalog.searchTracks("$primary ${song.title}", 8) }.getOrDefault(emptyList())
        val want = Parse.norm(song.title)
        val wantArtist = Parse.norm(primary)
        return hits.filter { t ->
            val got = Parse.norm(t.title)
            val titleOk = got == want || got.startsWith(want) || want.startsWith(got)
            val artistOk = wantArtist.isEmpty() || Parse.norm(t.artistNames).contains(wantArtist)
            val lengthOk = song.durationSec <= 0 || t.duration <= 0 || abs(t.duration - song.durationSec) <= 5
            titleOk && artistOk && lengthOk
        }.minByOrNull { abs(it.duration - song.durationSec) }
    }

    private suspend fun embed(id: String): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        val html = ApiClient.http.newCall(Request.Builder().url("https://open.spotify.com/embed/playlist/$id").header("User-Agent", UA).build()).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "The playlist page answered ${res.code}")
            res.body?.string() ?: ""
        }
        val blob = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("That playlist could not be read; is it public?")
        val root = json.parseToJsonElement(blob)
        val entity = path(root, "props", "pageProps", "state", "data", "entity") as? JsonObject ?: throw IllegalStateException("Could not read the playlist")
        val title = entity["name"]?.jsonPrimitive?.contentOrNull ?: "Imported playlist"
        val songs = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Song(t, o["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty().replace(' ', ' '), ((o["duration"]?.jsonPrimitive?.longOrNull ?: 0L) / 1000).toInt())
        }
        title to songs
    }

    private fun path(root: JsonElement, vararg keys: String): JsonElement? {
        var cur: JsonElement? = root
        for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
        return cur
    }
}
