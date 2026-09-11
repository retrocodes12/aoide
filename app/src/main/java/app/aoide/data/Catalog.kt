package app.aoide.data

import app.aoide.data.Parse.arr
import app.aoide.data.Parse.first
import app.aoide.data.Parse.obj
import app.aoide.data.Parse.runs
import app.aoide.data.Parse.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.net.URLEncoder

/** Typed catalogue calls: everything the screens ask for, answered by the music service. */
object Catalog {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun cover(url: String?, size: Int = 320): String? = Music.image(url, size)
    fun artistPicture(url: String?, size: Int = 320): String? = Music.image(url, size, crop = true)
    fun playlistImage(p: Playlist, size: Int = 320): String? = cover(p.squareImage ?: p.image, size)

    /* ---------- search ---------- */

    suspend fun searchTracks(term: String, limit: Int = 25): List<Track> =
        Parse.all(Music.search(term, Music.F_SONGS), "musicResponsiveListItemRenderer").mapNotNull { Parse.trackFromRow(obj(it) ?: return@mapNotNull null) }.take(limit)

    suspend fun searchPlaylists(term: String, limit: Int = 25): List<Playlist> =
        Parse.all(Music.search(term, Music.F_PLAYLISTS), "musicResponsiveListItemRenderer").mapNotNull { Parse.playlistFromRow(obj(it) ?: return@mapNotNull null) }.take(limit)

    /** Five requests at once: the mixed answer with its top result, then one per kind so every tab has depth. */
    suspend fun searchAll(term: String): SearchAll = coroutineScope {
        val mixed = async { runCatching { Music.search(term) }.getOrNull() }
        val songs = async { runCatching { searchTracks(term, 40) }.getOrDefault(emptyList()) }
        val albums = async { runCatching { Parse.all(Music.search(term, Music.F_ALBUMS), "musicResponsiveListItemRenderer").mapNotNull { Parse.albumFromRow(obj(it) ?: return@mapNotNull null) } }.getOrDefault(emptyList()) }
        val artists = async { runCatching { Parse.all(Music.search(term, Music.F_ARTISTS), "musicResponsiveListItemRenderer").mapNotNull { Parse.artistFromRow(obj(it) ?: return@mapNotNull null) } }.getOrDefault(emptyList()) }
        val playlists = async { runCatching { searchPlaylists(term, 30) }.getOrDefault(emptyList()) }
        val m = mixed.await()
        val top = m?.let { topHit(it) }
        val mixedShelf = Parse.MutableShelf("").also { s -> Parse.all(m, "musicResponsiveListItemRenderer").forEach { r -> obj(r)?.let { Parse.rowInto(it, s) } } }.freeze()
        SearchAll(
            tracks = (songs.await() + mixedShelf.tracks).distinctBy { it.id },
            albums = (albums.await() + mixedShelf.albums).distinctBy { it.id },
            artists = (artists.await() + mixedShelf.artists).distinctBy { it.id },
            playlists = (playlists.await() + mixedShelf.playlists).distinctBy { it.uuid },
            top = top,
        )
    }

    private fun topHit(mixed: JsonObject): Hit? {
        val card = obj(first(mixed, "musicCardShelfRenderer")) ?: return null
        val title = runs(card["title"]).trim().ifEmpty { return null }
        val sub = Parse.runList(card["subtitle"])
        val words = sub.map { str(it, "text")?.trim() ?: "" }
        val thumb = Parse.thumb(card["thumbnail"])
        val id = Parse.browseId(card["title"])
        val vid = Parse.watchId(card["title"]) ?: Parse.watchId(card["onTap"])
        val artists = sub.filter { Parse.browseId(it)?.startsWith("UC") == true }.map { ArtistRef(Parse.browseId(it)!!, str(it, "text") ?: "") }
        return when {
            id != null && id.startsWith("UC") -> Hit.ArtistHit(Artist(id, title, picture = thumb, listeners = words.lastOrNull { it.contains("audience") }))
            id != null && id.startsWith("MPREb") -> Hit.AlbumHit(Album(id, title, cover = thumb, type = words.firstOrNull()?.uppercase(), releaseDate = words.lastOrNull { Regex("^(19|20)\\d{2}$").matches(it) }, artist = artists.firstOrNull(), artists = artists))
            id != null && (id.startsWith("VL") || id.startsWith("RD") || id.startsWith("PL")) -> Hit.PlaylistHit(Playlist(id.removePrefix("VL"), title, squareImage = thumb))
            vid != null -> Hit.TrackHit(Track(vid, title, artist = artists.firstOrNull() ?: words.getOrNull(1)?.let { ArtistRef("", it) }, artists = artists, album = AlbumRef("", "", cover = thumb), duration = words.lastOrNull { Regex("^\\d{1,2}:\\d{2}$").matches(it) }?.let { Parse.parseDuration(it) } ?: 0))
            else -> null
        }
    }

    /* ---------- pages ---------- */

    suspend fun album(id: String): AlbumPage {
        val d = Music.browse(id)
        val h = obj(first(d, "musicResponsiveHeaderRenderer")) ?: obj(first(d, "musicDetailHeaderRenderer")) ?: throw ApiException(404, "Album not found")
        val artistRuns = Parse.runList(h["straplineTextOne"])
        val artists = artistRuns.filter { Parse.browseId(it)?.startsWith("UC") == true }.map { ArtistRef(Parse.browseId(it)!!, str(it, "text") ?: "") }
        val artistName = artists.firstOrNull()?.name ?: runs(h["straplineTextOne"]).trim().ifBlank { null }
        val subWords = runs(h["subtitle"]).split("•").map { it.trim() }
        val type = subWords.firstOrNull()?.uppercase()?.takeIf { it in setOf("ALBUM", "SINGLE", "EP") } ?: "ALBUM"
        val year = subWords.lastOrNull { Regex("^(19|20)\\d{2}$").matches(it) }
        val second = runs(h["secondSubtitle"])
        val count = second.substringBefore(" song").trim().toIntOrNull()
        val cover = Parse.thumb(h["thumbnail"])
        val description = runs(first(h["description"], "description")).trim().ifBlank { null }
        val album = Album(
            id = id, title = runs(h["title"]).trim(), cover = cover, releaseDate = year, numberOfTracks = count, type = type,
            artist = artists.firstOrNull() ?: artistName?.let { ArtistRef("", it) }, artists = artists, description = description,
            playlistId = Parse.watchPlaylist(h["buttons"]),
        )
        val ref = album.asRef()
        val artistCtx = album.primaryArtist
        val rows = arr(obj(first(d, "musicShelfRenderer"))?.get("contents")).orEmpty().mapNotNull { obj(obj(it)?.get("musicResponsiveListItemRenderer")) }
        val tracks = rows.mapNotNull { Parse.trackFromRow(it, albumCtx = ref, artistCtx = artistCtx) }.mapIndexed { i, t -> t.copy(trackNumber = t.trackNumber ?: (i + 1), album = ref, artist = t.artist ?: artistCtx) }
        val secondary = first(obj(first(d, "twoColumnBrowseResultsRenderer"))?.get("secondaryContents"), "sectionListRenderer") ?: first(d, "sectionListRenderer")
        val others = Parse.shelves(secondary).flatMap { it.albums }.filter { it.id != id }
        return AlbumPage(album, tracks, others)
    }

    suspend fun artist(id: String): ArtistPage {
        val d = Music.browse(id)
        val h = obj(first(d, "musicImmersiveHeaderRenderer")) ?: obj(first(d, "musicVisualHeaderRenderer")) ?: throw ApiException(404, "Artist not found")
        val name = runs(h["title"]).trim()
        val banner = Parse.thumb(h["thumbnail"]) ?: Parse.thumb(h["foregroundThumbnail"])
        val listeners = runs(h["monthlyListenerCount"]).trim().ifBlank { runs(first(h["subscriptionButton"], "subscriberCountText")).trim().takeIf { it.isNotBlank() }?.let { "$it subscribers" } }
        val radio = Parse.watchPlaylist(h["startRadioButton"])
        val sections = first(d, "sectionListRenderer")
        val about = runs(obj(first(sections, "musicDescriptionShelfRenderer"))?.get("description")).trim().ifBlank { runs(h["description"]).trim() }
        val artist = Artist(id = id, name = name, picture = banner, banner = banner, listeners = listeners, bio = about.ifBlank { null }, radio = radio)
        val ref = ArtistRef(id, name)
        val shelves = Parse.shelves(sections, artistCtx = ref)
        val top = shelves.firstOrNull { it.tracks.isNotEmpty() }?.tracks.orEmpty().map { t -> if (t.artists.isEmpty()) t.copy(artist = ref, artists = listOf(ref)) else t }
        val albums = shelves.firstOrNull { it.albums.isNotEmpty() && it.title.contains("Album", true) }?.albums.orEmpty()
        val singles = shelves.firstOrNull { it.albums.isNotEmpty() && it.title.contains("Single", true) }?.albums.orEmpty()
        val similar = shelves.firstOrNull { it.artists.isNotEmpty() }?.artists.orEmpty()
        val playlists = shelves.filter { it.playlists.isNotEmpty() }.flatMap { it.playlists }.distinctBy { it.uuid }
        val fixArtist = { a: Album -> if (a.artist == null) a.copy(artist = ref, artists = listOf(ref)) else a }
        return ArtistPage(
            artist, top, albums.map(fixArtist), singles.map(fixArtist), similar, playlists,
            albumsMore = Parse.shelfMore(sections, shelves.firstOrNull { it.albums.isNotEmpty() && it.title.contains("Album", true) }?.title ?: ""),
            singlesMore = Parse.shelfMore(sections, shelves.firstOrNull { it.albums.isNotEmpty() && it.title.contains("Single", true) }?.title ?: ""),
        )
    }

    /** An artist's full album or single list, from the "more" link on the page's shelf. */
    suspend fun discography(browseId: String, params: String): List<Album> {
        val d = Music.browse(browseId, params.ifBlank { null })
        val shelf = Parse.MutableShelf("")
        Parse.all(d, "musicTwoRowItemRenderer").forEach { c -> obj(c)?.let { Parse.cardInto(it, shelf) } }
        return shelf.freeze().albums
    }

    suspend fun playlist(id: String): Pair<Playlist, List<Track>> {
        val d = Music.browse(if (id.startsWith("VL")) id else "VL$id")
        val h = obj(first(d, "musicResponsiveHeaderRenderer")) ?: obj(first(d, "musicDetailHeaderRenderer")) ?: obj(first(d, "musicEditablePlaylistDetailHeaderRenderer"))
        val second = runs(h?.get("secondSubtitle"))
        val creator = runs(h?.get("straplineTextOne")).trim().ifBlank { str(obj(first(h?.get("facepile"), "text")), "content")?.trim() ?: "" }
        val p = Playlist(
            uuid = id.removePrefix("VL"), title = runs(h?.get("title")).trim(), description = runs(first(h?.get("description"), "description")).trim().ifBlank { null },
            numberOfTracks = second.substringBefore(" song").trim().toIntOrNull(), squareImage = Parse.thumb(h?.get("thumbnail")),
            creator = creator.takeIf { it.isNotBlank() && !it.startsWith("Playlist") }?.let { Creator(null, it) },
        )
        val shelf = obj(first(d, "musicPlaylistShelfRenderer")) ?: obj(first(d, "musicShelfRenderer"))
        val tracks = ArrayList<Track>()
        var items = arr(shelf?.get("contents")).orEmpty()
        var pages = 0
        while (true) {
            val before = tracks.size
            items.forEach { it -> obj(obj(it)?.get("musicResponsiveListItemRenderer"))?.let { r -> Parse.trackFromRow(r)?.let { t -> if (tracks.none { it.id == t.id }) tracks.add(t) } } }
            val token = items.lastOrNull()?.let { str(obj(first(obj(it)?.get("continuationItemRenderer"), "continuationCommand")), "token") }
            // The service sometimes answers a continuation with the same page again; stop when nothing new arrives.
            if (token == null || (pages > 0 && tracks.size == before) || ++pages > 4) break
            val more = runCatching { Music.continuation(token) }.getOrNull() ?: break
            items = arr(obj(first(more, "appendContinuationItemsAction"))?.get("continuationItems")) ?: arr(obj(first(more, "musicPlaylistShelfContinuation"))?.get("contents")) ?: break
        }
        return p to tracks.distinctBy { it.id }
    }

    /* ---------- browse ---------- */

    suspend fun home(): List<Shelf> = Parse.shelves(first(Music.browse("FEmusic_home"), "sectionListRenderer"))

    suspend fun newReleases(): List<Album> {
        val shelf = Parse.MutableShelf("")
        Parse.all(Music.browse("FEmusic_new_releases_albums"), "musicTwoRowItemRenderer").forEach { c -> obj(c)?.let { Parse.cardInto(it, shelf) } }
        return shelf.freeze().albums
    }

    suspend fun moods(): List<Mood> {
        val d = Music.browse("FEmusic_moods_and_genres")
        val out = ArrayList<Mood>()
        for (g in Parse.all(d, "gridRenderer")) {
            val grid = obj(g) ?: continue
            val group = runs(first(grid["header"], "title")).trim()
            for (item in arr(grid["items"]).orEmpty()) {
                val b = obj(obj(item)?.get("musicNavigationButtonRenderer")) ?: continue
                val ep = obj(first(b["clickCommand"], "browseEndpoint")) ?: continue
                out.add(Mood(runs(b["buttonText"]).trim(), str(obj(b["solid"]), "leftStripeColor")?.toLongOrNull() ?: 0xFF444444L, str(ep, "browseId") ?: continue, str(ep, "params") ?: "", group))
            }
        }
        return out
    }

    suspend fun moodShelves(browseId: String, params: String): List<Shelf> = Parse.shelves(first(Music.browse(browseId, params.ifBlank { null }), "sectionListRenderer"))

    /* ---------- queues the service builds ---------- */

    private fun panel(d: JsonObject): List<Track> =
        arr(obj(first(d, "playlistPanelRenderer"))?.get("contents")).orEmpty().mapNotNull { item ->
            val v = obj(obj(item)?.get("playlistPanelVideoRenderer")) ?: obj(first(obj(item)?.get("playlistPanelVideoWrapperRenderer"), "playlistPanelVideoRenderer"))
            v?.let { Parse.panelTrack(it) }
        }

    /** Songs like this one, the service's own mix, the song itself first. */
    suspend fun radio(videoId: String): List<Track> = panel(Music.next(videoId, "RDAMVM$videoId"))

    /** A radio playlist (an artist's, for one), as the service queues it. */
    suspend fun radioPlaylist(playlistId: String): List<Track> = panel(Music.next(null, playlistId))

    /** The "related" page for a song: songs you might also like, playlists, similar artists. */
    suspend fun related(videoId: String): List<Shelf> {
        val d = Music.next(videoId, null)
        val tab = Parse.all(d, "tabRenderer").firstNotNullOfOrNull { t -> Parse.browseId(obj(t)?.get("endpoint"))?.takeIf { it.startsWith("MPTRt") } } ?: return emptyList()
        return Parse.shelves(first(Music.browse(tab), "sectionListRenderer"))
    }

    /** One song's metadata, for a queue restored without it. */
    suspend fun track(videoId: String): Track =
        panel(Music.next(videoId, null)).firstOrNull { it.id == videoId } ?: throw ApiException(404, "Song not found")

    /* ---------- lyrics: a community database first, then the service's own (plain text) ---------- */

    @Serializable
    private data class LrcRes(val plainLyrics: String? = null, val syncedLyrics: String? = null, val instrumental: Boolean = false)

    suspend fun lyrics(t: Track): Lyrics? = withContext(Dispatchers.IO) {
        val artist = t.primaryArtist?.name ?: ""
        fun call(params: String): LrcRes? = runCatching {
            ApiClient.http.newCall(Request.Builder().url("https://lrclib.net/api/get?$params").header("User-Agent", ApiClient.UA).build()).execute().use { res ->
                if (!res.isSuccessful) null else json.decodeFromString<LrcRes>(res.body!!.string())
            }
        }.getOrNull()
        val full = "artist_name=${enc(artist)}&track_name=${enc(t.title)}" + (t.album?.title?.takeIf { it.isNotBlank() }?.let { "&album_name=${enc(it)}" } ?: "") + "&duration=${t.duration}"
        val r = (if (t.duration > 0) call(full) else null) ?: call("artist_name=${enc(artist)}&track_name=${enc(t.title)}")
        if (r != null) {
            if (r.instrumental) return@withContext Lyrics("Instrumental", null)
            // LRC files open with metadata tags ([ar:], [ti:], [by:] ...); those are not lyrics.
            val plain = r.plainLyrics?.lines()?.filterNot { Regex("^\\[[a-z]{2,}:.*]\\s*$", RegexOption.IGNORE_CASE).matches(it) }?.joinToString("\n")?.trim()?.ifBlank { null }
            val synced = r.syncedLyrics?.lines()?.mapNotNull { line ->
                Regex("^\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)$").find(line)?.let { m -> LyricLine(m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(), m.groupValues[3].trim()) }
            }?.takeIf { it.isNotEmpty() }
            if (plain != null || synced != null) return@withContext Lyrics(plain, synced)
        }
        if (t.isLocal) return@withContext null
        runCatching {
            val d = Music.next(t.id, null)
            val tab = Parse.all(d, "tabRenderer").firstNotNullOfOrNull { x -> Parse.browseId(obj(x)?.get("endpoint"))?.takeIf { it.startsWith("MPLYt") } } ?: return@runCatching null
            val text = runs(obj(first(Music.browse(tab), "musicDescriptionShelfRenderer"))?.get("description")).trim()
            text.ifBlank { null }?.let { Lyrics(it, null) }
        }.getOrNull()
    }

    @Suppress("unused")
    private fun keep(e: JsonElement) = e
}
