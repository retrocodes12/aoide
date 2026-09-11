package app.aoide.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class LibraryState(
    val liked: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val followedPlaylists: List<Playlist> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val recentTracks: List<Track> = emptyList(),
    val recentAlbums: List<Album> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    /** Track id -> times played, for the history screen. */
    val plays: Map<Long, Int> = emptyMap(),
    /** Track id -> last played, epoch ms. */
    val playedAt: Map<Long, Long> = emptyMap(),
) {
    fun isLiked(id: Long) = liked.any { it.id == id }
    fun hasAlbum(id: Long) = albums.any { it.id == id }
    fun follows(id: Long) = artists.any { it.id == id }
    fun hasPlaylist(uuid: String) = followedPlaylists.any { it.uuid == uuid }
}

/** Everything the user keeps. One JSON file in filesDir, written a beat after each change. */
object Library {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state

    fun init(context: Context) {
        file = File(context.filesDir, "library.json")
        if (file.exists()) runCatching { _state.value = json.decodeFromString(file.readText()) }
    }

    private fun update(f: (LibraryState) -> LibraryState) {
        _state.value = f(_state.value)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            runCatching { file.writeText(json.encodeToString(_state.value)) }
        }
    }

    private fun slim(a: Album) = Album(id = a.id, title = a.title, cover = a.cover, vibrantColor = a.vibrantColor, releaseDate = a.releaseDate, numberOfTracks = a.numberOfTracks, type = a.type, artist = a.primaryArtist)

    /** Returns true when the track is now liked. */
    fun toggleLike(t: Track): Boolean {
        val liked = _state.value.isLiked(t.id)
        update { s -> s.copy(liked = if (liked) s.liked.filter { it.id != t.id } else listOf(t) + s.liked) }
        return !liked
    }

    fun toggleAlbum(a: Album): Boolean {
        val has = _state.value.hasAlbum(a.id)
        update { s -> s.copy(albums = if (has) s.albums.filter { it.id != a.id } else listOf(slim(a)) + s.albums) }
        return !has
    }

    fun toggleArtist(a: Artist): Boolean {
        val has = _state.value.follows(a.id)
        update { s -> s.copy(artists = if (has) s.artists.filter { it.id != a.id } else listOf(Artist(a.id, a.name, a.picture)) + s.artists) }
        return !has
    }

    fun togglePlaylist(p: Playlist): Boolean {
        val has = _state.value.hasPlaylist(p.uuid)
        update { s -> s.copy(followedPlaylists = if (has) s.followedPlaylists.filter { it.uuid != p.uuid } else listOf(Playlist(p.uuid, p.title, p.description, p.numberOfTracks, p.duration, p.image, p.squareImage)) + s.followedPlaylists) }
        return !has
    }

    fun createPlaylist(title: String, tracks: List<Track> = emptyList(), source: String? = null): LocalPlaylist {
        val pl = LocalPlaylist("local-${System.currentTimeMillis().toString(36)}", title.trim().ifEmpty { "Untitled" }, tracks, System.currentTimeMillis(), source)
        update { it.copy(playlists = listOf(pl) + it.playlists) }
        return pl
    }

    fun addToPlaylist(id: String, t: Track) = update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(tracks = it.tracks + t) else it }) }
    fun removeFromPlaylist(id: String, index: Int) = update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(tracks = it.tracks.filterIndexed { i, _ -> i != index }) else it }) }
    fun renamePlaylist(id: String, title: String) = update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(title = title.trim().ifEmpty { it.title }) else it }) }
    fun deletePlaylist(id: String) = update { s -> s.copy(playlists = s.playlists.filter { it.id != id }) }
    fun playlist(id: String): LocalPlaylist? = _state.value.playlists.find { it.id == id }

    fun recordPlay(t: Track) = update { s ->
        s.copy(
            recentTracks = (listOf(t) + s.recentTracks.filter { it.id != t.id }).take(100),
            plays = s.plays + (t.id to ((s.plays[t.id] ?: 0) + 1)),
            playedAt = s.playedAt + (t.id to System.currentTimeMillis()),
        )
    }

    /** Songs an import found since the last sync go on the end; nothing the listener removed comes back. */
    fun syncPlaylist(id: String, tracks: List<Track>) = update { s ->
        s.copy(playlists = s.playlists.map { p -> if (p.id != id) p else p.copy(tracks = p.tracks + tracks.filter { t -> p.tracks.none { it.id == t.id } }) })
    }

    /** The whole library as JSON, for a backup file. */
    fun export(): String = json.encodeToString(_state.value)

    /** Replace the library from a backup; returns false if the file is not one. */
    fun import(text: String): Boolean {
        val parsed = runCatching { json.decodeFromString<LibraryState>(text) }.getOrNull() ?: return false
        update { parsed }
        return true
    }
    fun recordAlbum(a: Album) = update { s -> s.copy(recentAlbums = (listOf(slim(a)) + s.recentAlbums.filter { it.id != a.id }).take(24)) }
    fun recordSearch(term: String) {
        val t = term.trim()
        if (t.length < 2) return
        update { s -> s.copy(recentSearches = (listOf(t) + s.recentSearches.filter { !it.equals(t, true) }).take(8)) }
    }
    fun clearRecentSearches() = update { it.copy(recentSearches = emptyList()) }
    fun clearHistory() = update { it.copy(recentTracks = emptyList(), plays = emptyMap(), playedAt = emptyMap()) }
}
