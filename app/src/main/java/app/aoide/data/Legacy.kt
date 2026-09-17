package app.aoide.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Songs, records and artists saved before 0.7.0 still carry the first catalogue's numeric ids and
 * bare cover ids. The current catalogue answers to neither: no cover, no radio, no album page. Each
 * one is looked up again by artist, title and length and rewritten in place. The answers are kept,
 * so a queue restored from an older session is upgraded as well.
 */
object Legacy {
    @Serializable
    private data class Answers(
        val tracks: Map<String, Track> = emptyMap(),
        val albums: Map<String, Album> = emptyMap(),
        val artists: Map<String, Artist> = emptyMap(),
        /** Ids looked up with no match, and when; asked again after a day. */
        val missed: Map<String, Long> = emptyMap(),
        /** Which matcher gave the misses; a better matcher asks about them again. */
        val matcher: Int = 0,
    )

    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var answers = Answers(matcher = MATCHER)
    private val _tracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    /** Old song id -> the song on the current catalogue, as found so far. */
    val tracks: StateFlow<Map<String, Track>> = _tracks
    private const val RETRY_MS = 86_400_000L
    private const val MATCHER = 3

    /** Numeric ids are the first catalogue's; the current one's are letters and digits, and phone files start with `local:`. */
    fun isOldId(id: String) = id.isNotEmpty() && id.all(Char::isDigit)
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    fun isOldCover(cover: String?) = cover != null && UUID.matches(cover)
    fun isOld(t: Track) = !t.isLocal && (isOldId(t.id) || isOldCover(t.album?.cover))

    /** Looked up at least once and not found. */
    fun gaveUp(id: String) = id in answers.missed

    /** The current catalogue's version of a song, when it has been found; otherwise the song as it is. */
    fun upgrade(t: Track): Track = answers.tracks[t.id] ?: t

    fun init(context: Context) {
        file = File(context.filesDir, "legacy.json")
        Store.read(file) { json.decodeFromString<Answers>(it) }?.let { a ->
            answers = if (a.matcher < MATCHER) a.copy(missed = emptyMap(), matcher = MATCHER) else a
            _tracks.value = answers.tracks
        }
    }

    /** Look everything old up and rewrite the library and the download index. Cheap when there is nothing to do. */
    fun migrate() = scope.launch {
        val lib = Library.state.value
        val now = System.currentTimeMillis()
        fun due(id: String) = (answers.missed[id] ?: 0L) + RETRY_MS < now
        val songs = (lib.liked + lib.playlists.flatMap { it.tracks } + lib.recentTracks + Downloads.all.value.values.map { it.track })
            .filter { isOld(it) && it.id !in answers.tracks && due(it.id) }.distinctBy { it.id }
        val records = (lib.albums + lib.recentAlbums).filter { isOldId(it.id) && it.id !in answers.albums && due(it.id) }.distinctBy { it.id }
        val people = lib.artists.filter { isOldId(it.id) && it.id !in answers.artists && due(it.id) }.distinctBy { it.id }
        if (songs.isNotEmpty() || records.isNotEmpty() || people.isNotEmpty()) {
            val foundSongs = lookUp(songs, { it.id }) { findTrack(it) }
            val foundRecords = lookUp(records, { it.id }) { findAlbum(it) }
            val foundPeople = lookUp(people, { it.id }) { findArtist(it) }
            val missed = (songs.map { it.id } - foundSongs.keys) + (records.map { it.id } - foundRecords.keys) + (people.map { it.id } - foundPeople.keys)
            answers = answers.copy(
                tracks = answers.tracks + foundSongs,
                albums = answers.albums + foundRecords,
                artists = answers.artists + foundPeople,
                missed = answers.missed - foundSongs.keys - foundRecords.keys - foundPeople.keys + missed.associateWith { now },
            )
            _tracks.value = answers.tracks
            runCatching { Store.writeAtomic(file, json.encodeToString(answers)) }
        }
        // Applied every launch, so a backup restored from an older phone is rewritten with what is already known.
        if (answers.tracks.isNotEmpty() || answers.albums.isNotEmpty() || answers.artists.isNotEmpty() || answers.missed.isNotEmpty()) {
            Library.replaceOld(answers.tracks, answers.albums, answers.artists)
            Downloads.replaceOld(answers.tracks)
        }
    }

    private suspend fun <T, R> lookUp(items: List<T>, id: (T) -> String, find: suspend (T) -> R?): Map<String, R> = coroutineScope {
        items.chunked(4).flatMap { chunk -> chunk.map { item -> async { runCatching { find(item) }.getOrNull()?.let { id(item) to it } } }.awaitAll() }.filterNotNull().toMap()
    }

    private suspend fun findTrack(t: Track): Track? {
        // The first catalogue kept remix and live labels apart from the title; the current one folds them in.
        val title = t.title.trim().let { base -> t.version?.trim()?.takeIf { it.isNotEmpty() && !base.contains(it, true) }?.let { "$base ($it)" } ?: base }
        val found = Importer.match(Importer.Song(title, t.artistNames, t.duration))
            ?: if (title != t.title.trim()) Importer.match(Importer.Song(t.title.trim(), t.artistNames, t.duration)) else null
        return found
    }

    private suspend fun findAlbum(a: Album): Album? {
        val artist = a.primaryArtist?.name.orEmpty()
        val want = Parse.norm(a.title)
        val wantArtist = Parse.norm(artist)
        val hits = Catalog.searchAlbums("$artist ${a.title}".trim(), 10).ifEmpty { Catalog.searchAlbums(a.title, 10) }
        fun artistOk(got: Album) = wantArtist.isEmpty() || Parse.norm(got.primaryArtist?.name.orEmpty()).let { it.isNotEmpty() && (it.contains(wantArtist) || wantArtist.contains(it)) }
        fun close(got: Album) = Parse.norm(got.title).let { t -> t == want || t.startsWith(want) || want.startsWith(t) }
        // A title alone is not enough: singles share names across artists.
        return hits.firstOrNull { close(it) && artistOk(it) }
    }

    private suspend fun findArtist(a: Artist): Artist? {
        val want = Parse.norm(a.name)
        if (want.isEmpty()) return null
        return Catalog.searchArtists(a.name, 10).firstOrNull { Parse.norm(it.name) == want }
    }
}
