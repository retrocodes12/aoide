package app.aoide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.net.URLEncoder

/** Typed catalogue calls over the mirror client, plus image URL helpers and lyrics. */
object Catalog {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun cover(uuid: String?, size: Int = 320): String? = uuid?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/${size}x${size}.jpg" }
    fun artistPicture(uuid: String?, size: Int = 320): String? = cover(uuid, size)
    fun playlistImage(p: Playlist, size: Int = 320): String? = cover(p.squareImage ?: p.image, size)

    @Serializable private data class DataWrap<T>(val data: T)
    @Serializable private data class ArtistWrap(val artist: Artist)
    @Serializable private data class DiscoWrap(val albums: Paged<Album>? = null, val tracks: List<Track> = emptyList())
    @Serializable private data class PlaylistWrap(val playlist: Playlist, val items: List<AlbumItem>? = null, val tracks: Paged<AlbumItem>? = null)
    @Serializable private data class SimilarArtists(val artists: List<Artist> = emptyList())
    @Serializable private data class SimilarAlbumRaw(val id: Long, val title: String = "", val cover: String? = null, val releaseDate: String? = null, val type: String? = null, val artists: List<ArtistRef> = emptyList())
    @Serializable private data class SimilarAlbums(val albums: List<SimilarAlbumRaw> = emptyList())
    @Serializable private data class RecItem(val track: Track? = null)
    @Serializable private data class Bio(val text: String? = null)

    suspend fun searchTracks(term: String, limit: Int = 25, offset: Int = 0): Paged<Track> =
        json.decodeFromString<DataWrap<Paged<Track>>>(ApiClient.get("/search/?s=${enc(term)}&limit=$limit&offset=$offset")).data

    /**
     * On a hifi-api mirror `a=` answers with artists, tracks and top hits only; albums and playlists
     * have their own parameters. Ask for all three at once and merge, so the Albums and Playlists
     * tabs are never empty just because a mirror is serving instead of the fallback.
     */
    suspend fun searchAll(term: String, limit: Int = 12): SearchAll = coroutineScope {
        val main = async { json.decodeFromString<DataWrap<SearchAll>>(ApiClient.get("/search/?a=${enc(term)}&limit=$limit")).data }
        val albums = async { runCatching { json.decodeFromString<DataWrap<SearchAll>>(ApiClient.get("/search/?al=${enc(term)}&limit=$limit")).data.albums }.getOrNull() }
        val playlists = async { runCatching { json.decodeFromString<DataWrap<SearchAll>>(ApiClient.get("/search/?p=${enc(term)}&limit=$limit")).data.playlists }.getOrNull() }
        val m = main.await()
        m.copy(
            albums = if (m.albums?.items.isNullOrEmpty()) albums.await() ?: m.albums else m.albums,
            playlists = if (m.playlists?.items.isNullOrEmpty()) playlists.await() ?: m.playlists else m.playlists,
        )
    }

    suspend fun searchPlaylists(term: String, limit: Int = 25): List<Playlist> =
        json.decodeFromString<DataWrap<SearchAll>>(ApiClient.get("/search/?p=${enc(term)}&limit=$limit")).data.playlists?.items ?: emptyList()

    /** Album plus its tracks, each track carrying the album ref (the API omits it inside items). */
    suspend fun album(id: Long): Pair<Album, List<Track>> {
        val a = json.decodeFromString<DataWrap<Album>>(ApiClient.get("/album/?id=$id")).data
        val ref = a.asRef()
        val tracks = (a.items ?: emptyList()).filter { it.type == null || it.type == "track" }.map { it.item.copy(album = it.item.album ?: ref) }
        return a to tracks
    }

    suspend fun similarAlbums(id: Long): List<Album> = runCatching {
        json.decodeFromString<SimilarAlbums>(ApiClient.get("/album/similar/?id=$id")).albums.map {
            Album(id = it.id, title = it.title, cover = it.cover, releaseDate = it.releaseDate, type = it.type, artist = it.artists.firstOrNull(), artists = it.artists)
        }
    }.getOrDefault(emptyList())

    suspend fun artist(id: Long): Artist = try {
        json.decodeFromString<ArtistWrap>(ApiClient.get("/artist/?id=$id")).artist
    } catch (e: ApiException) {
        if (e.status == 404) throw e
        json.decodeFromString<Artist>(ApiClient.nativeGet("/v1/artists/$id"))
    }

    suspend fun discography(id: Long): Pair<List<Album>, List<Track>> {
        val d = json.decodeFromString<DiscoWrap>(ApiClient.get("/artist/?f=$id"))
        return (d.albums?.items ?: emptyList()) to d.tracks
    }

    suspend fun topTracks(id: Long, name: String): List<Track> {
        runCatching { json.decodeFromString<Paged<Track>>(ApiClient.nativeGet("/v1/artists/$id/toptracks?limit=10")).items }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return searchTracks(name, 40).items
            .filter { t -> t.artists.any { it.id == id } || t.artist?.id == id }
            .sortedByDescending { it.popularity ?: 0 }
            .take(10)
    }

    suspend fun similarArtists(id: Long): List<Artist> = runCatching {
        json.decodeFromString<SimilarArtists>(ApiClient.get("/artist/similar/?id=$id")).artists
    }.getOrDefault(emptyList())

    suspend fun artistBio(id: Long): String = runCatching {
        (json.decodeFromString<Bio>(ApiClient.nativeGet("/v1/artists/$id/bio")).text ?: "")
            .replace(Regex("\\[wimpLink[^]]*]"), "").replace("[/wimpLink]", "").replace(Regex("<br\\s*/?>"), "\n")
    }.getOrDefault("")

    suspend fun playlist(uuid: String): Pair<Playlist, List<Track>> {
        val p = json.decodeFromString<PlaylistWrap>(ApiClient.get("/playlist/?id=$uuid"))
        val raw = p.items ?: p.tracks?.items ?: emptyList()
        return p.playlist to raw.map { it.item }.filter { it.duration > 0 }
    }

    suspend fun recommendations(trackId: Long): List<Track> = runCatching {
        json.decodeFromString<DataWrap<Paged<RecItem>>>(ApiClient.get("/recommendations/?id=$trackId")).data.items.mapNotNull { it.track }
    }.getOrDefault(emptyList())

    /** One track's metadata from TIDAL, for resolving a song the queue restored without it. */
    suspend fun track(id: Long): Track = json.decodeFromString<Track>(ApiClient.nativeGet("/v1/tracks/$id"))

    suspend fun manifest(id: Long, quality: Quality): ManifestInfo =
        json.decodeFromString<DataWrap<ManifestInfo>>(ApiClient.get("/track/?id=$id&quality=${quality.name}", ttl = 20 * 60_000L)).data

    /** Top hit of a search, resolved to something navigable. */
    sealed class Hit {
        data class ArtistHit(val artist: Artist) : Hit()
        data class AlbumHit(val album: Album) : Hit()
        data class TrackHit(val track: Track) : Hit()
    }

    fun topHit(all: SearchAll): Hit? {
        val h = all.topHits.firstOrNull() ?: return null
        val obj: JsonObject = runCatching { h.value.jsonObject }.getOrNull() ?: return null
        if (obj["id"]?.jsonPrimitive?.longOrNull == null && h.type != "PLAYLISTS") return null
        return runCatching {
            when (h.type) {
                "ARTISTS" -> Hit.ArtistHit(json.decodeFromJsonElement(Artist.serializer(), h.value))
                "ALBUMS" -> Hit.AlbumHit(json.decodeFromJsonElement(Album.serializer(), h.value))
                "TRACKS" -> Hit.TrackHit(json.decodeFromJsonElement(Track.serializer(), h.value))
                else -> null
            }
        }.getOrNull()
    }

    /* ---------- lyrics (lrclib.net, no key) ---------- */
    @Serializable
    private data class LrcRes(val plainLyrics: String? = null, val syncedLyrics: String? = null, val instrumental: Boolean = false)

    suspend fun lyrics(t: Track): Lyrics? = withContext(Dispatchers.IO) {
        val artist = t.primaryArtist?.name ?: ""
        fun call(params: String): LrcRes? = runCatching {
            ApiClient.http.newCall(Request.Builder().url("https://lrclib.net/api/get?$params").header("User-Agent", ApiClient.UA).build()).execute().use { res ->
                if (!res.isSuccessful) null else json.decodeFromString<LrcRes>(res.body!!.string())
            }
        }.getOrNull()
        val full = "artist_name=${enc(artist)}&track_name=${enc(t.title)}" + (t.album?.let { "&album_name=${enc(it.title)}" } ?: "") + "&duration=${t.duration}"
        val r = call(full) ?: call("artist_name=${enc(artist)}&track_name=${enc(t.title)}") ?: return@withContext null
        if (r.instrumental) return@withContext Lyrics("Instrumental", null)
        // LRC files open with metadata tags ([ar:], [ti:], [by:] ...); those are not lyrics.
        val plain = r.plainLyrics?.lines()?.filterNot { Regex("^\\[[a-z]{2,}:.*]\\s*$", RegexOption.IGNORE_CASE).matches(it) }?.joinToString("\n")?.trim()?.ifBlank { null }
        val synced = r.syncedLyrics?.lines()?.mapNotNull { line ->
            Regex("^\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)$").find(line)?.let { m ->
                LyricLine(m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(), m.groupValues[3].trim())
            }
        }?.takeIf { it.isNotEmpty() }
        if (plain == null && synced == null) null else Lyrics(plain, synced)
    }
}
