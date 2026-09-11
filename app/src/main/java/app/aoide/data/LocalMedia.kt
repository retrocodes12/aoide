package app.aoide.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Music files already on the phone, read from MediaStore. They become ordinary tracks with
 * negative ids and `content://` artwork, so every screen and the player treat them like the rest.
 */
object LocalMedia {
    const val SCHEME = "content://media/external/audio/media/"

    fun isLocal(trackId: String) = trackId.startsWith("local:")

    fun uriFor(trackId: String): String = SCHEME + trackId.removePrefix("local:")

    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000"
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val artistId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackNo = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                while (c.moveToNext()) {
                    val mediaId = c.getLong(id)
                    val art = ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), c.getLong(albumId)).toString()
                    val artistName = c.getString(artist)?.takeIf { it != "<unknown>" } ?: "Unknown artist"
                    out.add(
                        Track(
                            id = "local:$mediaId",
                            title = c.getString(title) ?: "Untitled",
                            duration = (c.getLong(duration) / 1000).toInt(),
                            trackNumber = c.getInt(trackNo).takeIf { it > 0 },
                            artist = ArtistRef("local-artist:${c.getLong(artistId)}", artistName),
                            artists = listOf(ArtistRef("local-artist:${c.getLong(artistId)}", artistName)),
                            album = AlbumRef("local-album:${c.getLong(albumId)}", c.getString(album) ?: "", cover = art),
                        ),
                    )
                }
            }
        }
        out
    }
}
