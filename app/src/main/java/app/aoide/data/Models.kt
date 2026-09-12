package app.aoide.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One lenient JSON instance for everything: the catalogue's shapes drift between versions. */
val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Ids are the catalogue's own strings: a video id for a song, a browse id for an album or artist, a playlist id for a list. */
@Serializable
data class ArtistRef(val id: String = "", val name: String = "", val picture: String? = null)

@Serializable
data class AlbumRef(
    val id: String = "",
    val title: String = "",
    val cover: String? = null,
    val vibrantColor: String? = null,
    val releaseDate: String? = null,
)

@Serializable
data class Track(
    val id: String,
    val title: String = "",
    val version: String? = null,
    val duration: Int = 0,
    val trackNumber: Int? = null,
    val explicit: Boolean = false,
    val artist: ArtistRef? = null,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    /** A music video rather than an audio-only track; its artwork is a frame, not a cover. */
    val video: Boolean = false,
    val plays: String? = null,
) {
    val artistNames: String get() = (artists.ifEmpty { listOfNotNull(artist) }).joinToString(", ") { it.name }
    val primaryArtist: ArtistRef? get() = artist ?: artists.firstOrNull()
    /** Files on this phone carry `local:` ids and never go near the network. */
    val isLocal: Boolean get() = id.startsWith("local:")
}

@Serializable
data class Album(
    val id: String,
    val title: String = "",
    val cover: String? = null,
    val vibrantColor: String? = null,
    val releaseDate: String? = null,
    val duration: Int? = null,
    val numberOfTracks: Int? = null,
    /** "ALBUM", "SINGLE" or "EP", as the catalogue labels it. */
    val type: String? = null,
    val explicit: Boolean = false,
    val artist: ArtistRef? = null,
    val artists: List<ArtistRef> = emptyList(),
    val description: String? = null,
    /** The album's own play queue on the service, when known. */
    val playlistId: String? = null,
    val copyright: String? = null,
) {
    val primaryArtist: ArtistRef? get() = artist ?: artists.firstOrNull()
    val year: String get() = releaseDate?.take(4) ?: ""
    fun asRef() = AlbumRef(id, title, cover, vibrantColor, releaseDate)
}

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    /** A square picture, for round avatars. */
    val picture: String? = null,
    /** The wide header image, when the catalogue has one. */
    val banner: String? = null,
    val listeners: String? = null,
    val bio: String? = null,
    /** The playlist id of the artist's radio, when offered. */
    val radio: String? = null,
)

@Serializable
data class Creator(val id: String? = null, val name: String? = null)

@Serializable
data class Playlist(
    val uuid: String,
    val title: String = "",
    val description: String? = null,
    val numberOfTracks: Int? = null,
    val duration: Int? = null,
    val image: String? = null,
    val squareImage: String? = null,
    val creator: Creator? = null,
    val lastUpdated: String? = null,
    val type: String? = null,
) {
    val cleanDescription: String get() = (description ?: "").replace(Regex("\\s*\\(Cover:.*$"), "").trim()
}

/** The best single answer for a search. */
sealed class Hit {
    data class ArtistHit(val artist: Artist) : Hit()
    data class AlbumHit(val album: Album) : Hit()
    data class TrackHit(val track: Track) : Hit()
    data class PlaylistHit(val playlist: Playlist) : Hit()
}

data class SearchAll(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val top: Hit? = null,
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() && top == null
}

/** One titled row of the catalogue's browse pages: cards of whatever kind the service put there. */
data class Shelf(
    val title: String,
    val strapline: String? = null,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && playlists.isEmpty() && artists.isEmpty()
}

data class AlbumPage(val album: Album, val tracks: List<Track>, val others: List<Album> = emptyList())

data class ArtistPage(
    val artist: Artist,
    val topTracks: List<Track>,
    val albums: List<Album>,
    val singles: List<Album>,
    val similar: List<Artist>,
    val playlists: List<Playlist>,
    /** browseId and params for the full album and single lists, when the page offers them. */
    val albumsMore: Pair<String, String>? = null,
    val singlesMore: Pair<String, String>? = null,
)

/** A tile on the browse grid: a mood or a genre, with the colour the service gives it. */
data class Mood(val title: String, val color: Long, val browseId: String, val params: String, val group: String)

enum class Quality(val label: String, val note: String) {
    HIGH("High", "The best stream each source offers: AAC 320 kbps where the second source has the song, otherwise Opus at up to about 160 kbps. The default."),
    LOW("Low", "Opus at about 64 kbps, for thin connections."),
}

/** A playlist the user made on this phone. */
@Serializable
data class LocalPlaylist(val id: String, val title: String, val tracks: List<Track> = emptyList(), val createdAt: Long = 0, val source: String? = null)

data class LyricLine(val t: Double, val line: String)
data class Lyrics(val plain: String?, val synced: List<LyricLine>?)

/** Where the current queue came from, for "Playing from" labels. */
@Serializable
data class PlayContext(val kind: String, val title: String, val href: String = "")

fun formatTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatLength(seconds: Int): String {
    val m = (seconds + 30) / 60
    return if (m < 60) "$m min" else "${m / 60} hr ${m % 60} min"
}
