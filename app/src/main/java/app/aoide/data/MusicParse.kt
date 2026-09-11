package app.aoide.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.util.Locale

/**
 * Readers for the music service's renderer trees. Every page is a nest of `...Renderer` objects;
 * these pick out the few fields the app shows and turn rows and cards into the app's own models.
 */
internal object Parse {
    fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
    fun arr(e: JsonElement?): JsonArray? = e as? JsonArray
    fun str(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.contentOrNull

    /** The text of a `runs` list, joined. */
    fun runs(t: JsonElement?): String = arr(obj(t)?.get("runs"))?.joinToString("") { str(obj(it), "text") ?: "" } ?: ""
    fun runList(t: JsonElement?): List<JsonObject> = arr(obj(t)?.get("runs"))?.mapNotNull { obj(it) } ?: emptyList()

    /** Depth-first, the first value under [key] anywhere below [e]. */
    fun first(e: JsonElement?, key: String, depth: Int = 0): JsonElement? {
        if (depth > 24) return null
        when (e) {
            is JsonObject -> {
                e[key]?.let { return it }
                for (v in e.values) first(v, key, depth + 1)?.let { return it }
            }
            is JsonArray -> for (v in e) first(v, key, depth + 1)?.let { return it }
            else -> Unit
        }
        return null
    }

    /** Every value under [key] anywhere below [e], in document order. */
    fun all(e: JsonElement?, key: String, out: MutableList<JsonElement> = ArrayList(), depth: Int = 0): List<JsonElement> {
        if (depth > 24) return out
        when (e) {
            is JsonObject -> for ((k, v) in e) { if (k == key) out.add(v); all(v, key, out, depth + 1) }
            is JsonArray -> for (v in e) all(v, key, out, depth + 1)
            else -> Unit
        }
        return out
    }

    fun browseId(e: JsonElement?): String? = str(obj(first(e, "browseEndpoint")), "browseId")
    fun browseParams(e: JsonElement?): String? = str(obj(first(e, "browseEndpoint")), "params")
    fun watchId(e: JsonElement?): String? = str(obj(first(e, "watchEndpoint")), "videoId")
    fun watchPlaylist(e: JsonElement?): String? = str(obj(first(e, "watchEndpoint")), "playlistId") ?: str(obj(first(e, "watchPlaylistEndpoint")), "playlistId")

    /** The largest thumbnail under [e], as the service serves it. */
    fun thumb(e: JsonElement?): String? {
        val list = arr(first(e, "thumbnails")) ?: return null
        return str(obj(list.lastOrNull()), "url")
    }

    fun parseDuration(s: String?): Int? {
        val p = s?.trim()?.split(":")?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        return when (p.size) {
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> null
        }
    }

    private val DUR = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    private val PLAYS = Regex("^[\\d.,]+[KMB]?\\s+(plays|views)$")
    private val YEAR = Regex("^(19|20)\\d{2}$")

    fun flexCols(r: JsonObject): List<List<JsonObject>> = arr(r["flexColumns"])?.map { c -> runList(obj(obj(c)?.get("musicResponsiveListItemFlexColumnRenderer"))?.get("text")) } ?: emptyList()
    fun fixedCols(r: JsonObject): List<String> = arr(r["fixedColumns"])?.map { c -> runs(obj(obj(c)?.get("musicResponsiveListItemFixedColumnRenderer"))?.get("text")) } ?: emptyList()
    private fun text(run: JsonObject) = str(run, "text") ?: ""
    private fun explicitIn(r: JsonObject) = r.toString().contains("MUSIC_EXPLICIT_BADGE")

    /** What kind of thing a row or card is, from the page type on its link. */
    fun pageType(e: JsonElement?): String? = str(obj(first(e, "browseEndpointContextMusicConfig")), "pageType")

    /* ---------- rows (musicResponsiveListItemRenderer) ---------- */

    fun trackFromRow(r: JsonObject, albumCtx: AlbumRef? = null, artistCtx: ArtistRef? = null): Track? {
        val vid = str(obj(r["playlistItemData"]), "videoId") ?: watchId(r["overlay"]) ?: watchId(arr(r["flexColumns"])?.firstOrNull()) ?: return null
        val cols = flexCols(r)
        val title = cols.firstOrNull()?.joinToString("") { text(it) }?.trim()?.ifEmpty { null } ?: return null
        val rest = cols.drop(1).flatten()
        val artists = rest.filter { browseId(it)?.startsWith("UC") == true }.map { ArtistRef(browseId(it)!!, text(it)) }.distinctBy { it.id }
        val albumRun = rest.firstOrNull { browseId(it)?.startsWith("MPREb") == true }
        val fixed = fixedCols(r).firstOrNull()
        val duration = parseDuration(fixed) ?: rest.map { text(it).trim() }.lastOrNull { DUR.matches(it) }?.let(::parseDuration) ?: 0
        val plays = rest.map { text(it).trim() }.firstOrNull { PLAYS.matches(it) }
        val thumb = thumb(r["thumbnail"])
        val type = str(obj(first(r["overlay"], "watchEndpointMusicConfig")), "musicVideoType") ?: str(obj(first(arr(r["flexColumns"])?.firstOrNull(), "watchEndpointMusicConfig")), "musicVideoType")
        val fallbackArtist = rest.map { text(it).trim() }.firstOrNull { it.isNotBlank() && !DUR.matches(it) && !PLAYS.matches(it) && it != "•" && it !in setOf("Song", "Video") }
        val artistList = artists.ifEmpty { listOfNotNull(artistCtx ?: fallbackArtist?.let { ArtistRef("", it) }) }
        val album = albumRun?.let { AlbumRef(browseId(it)!!, text(it), cover = thumb ?: albumCtx?.cover) } ?: albumCtx?.let { if (thumb != null) it.copy(cover = it.cover ?: thumb) else it } ?: AlbumRef("", "", cover = thumb)
        return Track(
            id = vid, title = title, duration = duration, trackNumber = runs(r["index"]).toIntOrNull(), explicit = explicitIn(r),
            artist = artistList.firstOrNull(), artists = artistList, album = album, video = type != null && type != "MUSIC_VIDEO_TYPE_ATV", plays = plays,
        )
    }

    fun albumFromRow(r: JsonObject): Album? {
        val id = browseId(r["navigationEndpoint"]) ?: return null
        if (!id.startsWith("MPREb")) return null
        val cols = flexCols(r)
        val title = cols.firstOrNull()?.joinToString("") { text(it) }?.trim() ?: return null
        val sub = cols.getOrNull(1).orEmpty()
        val artists = sub.filter { browseId(it)?.startsWith("UC") == true }.map { ArtistRef(browseId(it)!!, text(it)) }
        val words = sub.map { text(it).trim() }
        val year = words.lastOrNull { YEAR.matches(it) }
        val type = words.firstOrNull()?.uppercase(Locale.ROOT)?.takeIf { it in setOf("ALBUM", "SINGLE", "EP") } ?: "ALBUM"
        val artistName = artists.firstOrNull()?.name ?: words.getOrNull(2)?.takeIf { it.isNotBlank() && !YEAR.matches(it) }
        return Album(id = id, title = title, cover = thumb(r["thumbnail"]), releaseDate = year, type = type, explicit = explicitIn(r), artist = artists.firstOrNull() ?: artistName?.let { ArtistRef("", it) }, artists = artists, playlistId = watchPlaylist(r["overlay"]))
    }

    fun artistFromRow(r: JsonObject): Artist? {
        val id = browseId(r["navigationEndpoint"]) ?: return null
        if (!id.startsWith("UC")) return null
        val cols = flexCols(r)
        val name = cols.firstOrNull()?.joinToString("") { text(it) }?.trim() ?: return null
        val sub = cols.getOrNull(1).orEmpty().map { text(it).trim() }
        return Artist(id = id, name = name, picture = thumb(r["thumbnail"]), listeners = sub.lastOrNull { it.contains("audience") || it.contains("subscriber") })
    }

    fun playlistFromRow(r: JsonObject): Playlist? {
        val id = browseId(r["navigationEndpoint"])?.takeIf { it.startsWith("VL") || it.startsWith("RD") || it.startsWith("PL") || it.startsWith("OL") } ?: return null
        val cols = flexCols(r)
        val title = cols.firstOrNull()?.joinToString("") { text(it) }?.trim() ?: return null
        val sub = cols.getOrNull(1).orEmpty().map { text(it).trim() }.filter { it.isNotBlank() && it != "•" && it != "Playlist" }
        val count = sub.firstOrNull { it.endsWith("songs") || it.endsWith("song") || it.endsWith("tracks") }?.substringBefore(' ')?.replace(",", "")?.toIntOrNull()
        val creator = sub.firstOrNull { !it.endsWith("songs") && !it.endsWith("song") && !it.endsWith("tracks") && !it.endsWith("views") }
        return Playlist(uuid = id.removePrefix("VL"), title = title, numberOfTracks = count, squareImage = thumb(r["thumbnail"]), creator = creator?.let { Creator(null, it) })
    }

    /* ---------- cards (musicTwoRowItemRenderer) ---------- */

    fun cardInto(c: JsonObject, shelf: MutableShelf) {
        val title = runs(c["title"]).trim()
        val sub = runList(c["subtitle"])
        val subWords = sub.map { text(it).trim() }.filter { it.isNotBlank() && it != "•" }
        val thumb = thumb(c["thumbnailRenderer"]) ?: thumb(c["thumbnail"])
        val nav = c["navigationEndpoint"]
        val id = browseId(nav)
        val vid = watchId(nav)
        when {
            id != null && id.startsWith("MPREb") -> {
                val artists = sub.filter { browseId(it)?.startsWith("UC") == true }.map { ArtistRef(browseId(it)!!, text(it)) }
                val type = subWords.firstOrNull()?.uppercase(Locale.ROOT)?.takeIf { it in setOf("ALBUM", "SINGLE", "EP") } ?: "ALBUM"
                shelf.albums.add(Album(id = id, title = title, cover = thumb, releaseDate = subWords.lastOrNull { YEAR.matches(it) }, type = type, explicit = explicitIn(c), artist = artists.firstOrNull() ?: subWords.drop(1).firstOrNull { !YEAR.matches(it) }?.let { ArtistRef("", it) }, artists = artists, playlistId = watchPlaylist(c["thumbnailOverlay"])))
            }
            id != null && id.startsWith("UC") -> shelf.artists.add(Artist(id = id, name = title, picture = thumb, listeners = subWords.lastOrNull { it.contains("audience") || it.contains("subscriber") }))
            id != null && (id.startsWith("VL") || id.startsWith("RD") || id.startsWith("PL")) -> {
                val words = subWords.filter { it != "Playlist" }
                shelf.playlists.add(Playlist(uuid = id.removePrefix("VL"), title = title, squareImage = thumb, description = runs(c["subtitle"]).takeIf { it.isNotBlank() }, creator = words.firstOrNull()?.let { Creator(null, it) }, numberOfTracks = words.firstOrNull { it.endsWith("songs") }?.substringBefore(' ')?.toIntOrNull()))
            }
            vid != null -> {
                val artists = sub.filter { browseId(it)?.startsWith("UC") == true }.map { ArtistRef(browseId(it)!!, text(it)) }
                val type = str(obj(first(nav, "watchEndpointMusicConfig")), "musicVideoType")
                val artistName = artists.firstOrNull()?.name ?: subWords.firstOrNull { it != "Song" && it != "Video" && !PLAYS.matches(it) }
                shelf.tracks.add(Track(id = vid, title = title, explicit = explicitIn(c), artist = artists.firstOrNull() ?: artistName?.let { ArtistRef("", it) }, artists = artists, album = AlbumRef("", "", cover = thumb), video = type != null && type != "MUSIC_VIDEO_TYPE_ATV", plays = subWords.firstOrNull { PLAYS.matches(it) }))
            }
        }
    }

    /** A row of any kind, sorted into the shelf by what its link points at. */
    fun rowInto(r: JsonObject, shelf: MutableShelf) {
        when (pageType(r["navigationEndpoint"])) {
            "MUSIC_PAGE_TYPE_ALBUM" -> albumFromRow(r)?.let { shelf.albums.add(it) }
            "MUSIC_PAGE_TYPE_ARTIST" -> artistFromRow(r)?.let { shelf.artists.add(it) }
            "MUSIC_PAGE_TYPE_PLAYLIST" -> playlistFromRow(r)?.let { shelf.playlists.add(it) }
            else -> trackFromRow(r)?.let { shelf.tracks.add(it) }
        }
    }

    class MutableShelf(val title: String, val strapline: String? = null) {
        val tracks = ArrayList<Track>()
        val albums = ArrayList<Album>()
        val playlists = ArrayList<Playlist>()
        val artists = ArrayList<Artist>()
        fun freeze() = Shelf(title, strapline, tracks.distinctBy { it.id }, albums.distinctBy { it.id }, playlists.distinctBy { it.uuid }, artists.distinctBy { it.id })
    }

    /** Every carousel and list shelf in a section list, in page order. */
    fun shelves(sectionList: JsonElement?, artistCtx: ArtistRef? = null): List<Shelf> {
        val out = ArrayList<Shelf>()
        for (section in arr(obj(sectionList)?.get("contents")).orEmpty()) {
            val s = obj(section) ?: continue
            obj(s["musicCarouselShelfRenderer"])?.let { c ->
                val header = obj(first(c["header"], "musicCarouselShelfBasicHeaderRenderer"))
                val shelf = MutableShelf(runs(header?.get("title")).trim(), runs(header?.get("strapline")).trim().ifBlank { null })
                for (item in arr(c["contents"]).orEmpty()) {
                    obj(obj(item)?.get("musicTwoRowItemRenderer"))?.let { cardInto(it, shelf) }
                    obj(obj(item)?.get("musicResponsiveListItemRenderer"))?.let { rowInto(it, shelf) }
                }
                if (shelf.tracks.isNotEmpty() || shelf.albums.isNotEmpty() || shelf.playlists.isNotEmpty() || shelf.artists.isNotEmpty()) out.add(shelf.freeze())
            }
            obj(s["musicShelfRenderer"])?.let { c ->
                val shelf = MutableShelf(runs(c["title"]).trim())
                for (item in arr(c["contents"]).orEmpty()) obj(obj(item)?.get("musicResponsiveListItemRenderer"))?.let { r -> if (artistCtx != null) trackFromRow(r, artistCtx = artistCtx)?.let { shelf.tracks.add(it) } else rowInto(r, shelf) }
                if (shelf.tracks.isNotEmpty() || shelf.albums.isNotEmpty() || shelf.playlists.isNotEmpty() || shelf.artists.isNotEmpty()) out.add(shelf.freeze())
            }
        }
        return out
    }

    /** The "more" link on a carousel's title: a browse id and its params. */
    fun shelfMore(sectionList: JsonElement?, title: String): Pair<String, String>? {
        for (section in arr(obj(sectionList)?.get("contents")).orEmpty()) {
            val c = obj(obj(section)?.get("musicCarouselShelfRenderer")) ?: continue
            val header = obj(first(c["header"], "musicCarouselShelfBasicHeaderRenderer")) ?: continue
            if (runs(header["title"]).trim() != title) continue
            val ep = obj(first(header["title"], "browseEndpoint")) ?: return null
            return (str(ep, "browseId") ?: return null) to (str(ep, "params") ?: "")
        }
        return null
    }

    /** A song from the play queue the service builds (`playlistPanelVideoRenderer`). */
    fun panelTrack(v: JsonObject): Track? {
        val vid = str(v, "videoId") ?: return null
        val title = runs(v["title"]).trim().ifEmpty { return null }
        val by = runList(v["longBylineText"])
        val artists = by.filter { browseId(it)?.startsWith("UC") == true }.map { ArtistRef(browseId(it)!!, text(it)) }
        val albumRun = by.firstOrNull { browseId(it)?.startsWith("MPREb") == true }
        val thumb = thumb(v["thumbnail"])
        val type = str(obj(first(v["navigationEndpoint"], "watchEndpointMusicConfig")), "musicVideoType")
        val artistName = artists.firstOrNull()?.name ?: runs(v["shortBylineText"]).substringBefore(" • ").trim()
        return Track(
            id = vid, title = title, duration = parseDuration(runs(v["lengthText"])) ?: 0, explicit = explicitIn(v),
            artist = artists.firstOrNull() ?: artistName.takeIf { it.isNotBlank() }?.let { ArtistRef("", it) }, artists = artists,
            album = albumRun?.let { AlbumRef(browseId(it)!!, text(it), cover = thumb) } ?: AlbumRef("", "", cover = thumb),
            video = type != null && type != "MUSIC_VIDEO_TYPE_ATV",
        )
    }

    /** Lowercase, accents off, punctuation to spaces; letters of every script survive. */
    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
