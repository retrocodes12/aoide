package app.aoide.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One lenient JSON instance for every mirror: shapes drift between hifi-api versions. */
val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class ArtistRef(val id: Long, val name: String = "", val type: String? = null, val picture: String? = null)

@Serializable
data class AlbumRef(
    val id: Long,
    val title: String = "",
    val cover: String? = null,
    val vibrantColor: String? = null,
    val releaseDate: String? = null,
)

@Serializable
data class Track(
    val id: Long,
    val title: String = "",
    val version: String? = null,
    val duration: Int = 0,
    val trackNumber: Int? = null,
    val explicit: Boolean = false,
    val isrc: String? = null,
    val popularity: Int? = null,
    val audioQuality: String? = null,
    val artist: ArtistRef? = null,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
) {
    val artistNames: String get() = (artists.ifEmpty { listOfNotNull(artist) }).joinToString(", ") { it.name }
    val primaryArtist: ArtistRef? get() = artist ?: artists.firstOrNull()
}

@Serializable
data class AlbumItem(val item: Track, val type: String? = null)

@Serializable
data class Album(
    val id: Long,
    val title: String = "",
    val cover: String? = null,
    val vibrantColor: String? = null,
    val releaseDate: String? = null,
    val duration: Int? = null,
    val numberOfTracks: Int? = null,
    val copyright: String? = null,
    val type: String? = null,
    val explicit: Boolean = false,
    val audioQuality: String? = null,
    val artist: ArtistRef? = null,
    val artists: List<ArtistRef> = emptyList(),
    val upc: String? = null,
    val items: List<AlbumItem>? = null,
) {
    val primaryArtist: ArtistRef? get() = artist ?: artists.firstOrNull()
    val year: String get() = releaseDate?.take(4) ?: ""
    fun asRef() = AlbumRef(id, title, cover, vibrantColor, releaseDate)
}

@Serializable
data class ArtistRole(val category: String = "")

@Serializable
data class Artist(
    val id: Long,
    val name: String = "",
    val picture: String? = null,
    val popularity: Double? = null,
    val artistRoles: List<ArtistRole> = emptyList(),
)

@Serializable
data class Creator(val id: Long? = null, val name: String? = null)

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

@Serializable
data class Paged<T>(val limit: Int = 0, val offset: Int = 0, val totalNumberOfItems: Int = 0, val items: List<T> = emptyList())

@Serializable
data class TopHit(val value: JsonElement, val type: String = "")

@Serializable
data class SearchAll(
    val artists: Paged<Artist>? = null,
    val albums: Paged<Album>? = null,
    val playlists: Paged<Playlist>? = null,
    val tracks: Paged<Track>? = null,
    val topHits: List<TopHit> = emptyList(),
)

@Serializable
data class ManifestInfo(
    val trackId: Long = 0,
    val assetPresentation: String = "FULL",
    val audioQuality: String = "",
    val audioMode: String? = null,
    val manifestMimeType: String = "",
    val manifest: String = "",
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
)

enum class Quality(val label: String, val note: String) {
    HI_RES_LOSSLESS("Hi-Res Lossless", "FLAC up to 24-bit / 192 kHz when the mirror has it"),
    LOSSLESS("Lossless", "FLAC 16-bit / 44.1 kHz. The default."),
    HIGH("High", "AAC 320 kbps"),
    LOW("Low", "AAC 96 kbps for thin connections"),
}

/** A playlist the user made on this phone. */
@Serializable
data class LocalPlaylist(val id: String, val title: String, val tracks: List<Track> = emptyList(), val createdAt: Long = 0)

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
