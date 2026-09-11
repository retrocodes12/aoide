package app.aoide

import app.aoide.data.Catalog
import app.aoide.data.Hit
import app.aoide.data.Music
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music service's catalogue, live: search, album, artist, playlist, radio, moods and lyrics all parse into the app's models. */
class CatalogTest {
    @Test fun searchAllParses() = runBlocking {
        val r = Catalog.searchAll("radiohead")
        println("CAT-SEARCH tracks=${r.tracks.size} albums=${r.albums.size} artists=${r.artists.size} playlists=${r.playlists.size} top=${r.top?.javaClass?.simpleName}")
        assertTrue(r.tracks.size >= 10)
        assertTrue(r.albums.size >= 5)
        assertTrue(r.artists.isNotEmpty())
        assertTrue(r.playlists.isNotEmpty())
        assertTrue(r.top is Hit.ArtistHit)
        val t = r.tracks.first()
        println("CAT-TRACK ${t.id} '${t.title}' by ${t.artistNames} ${t.duration}s album='${t.album?.title}' cover=${t.album?.cover?.take(60)} plays=${t.plays}")
        assertTrue(t.duration > 0)
        assertTrue(t.artistNames.isNotBlank())
        assertNotNull(t.album?.cover)
    }

    @Test fun albumPageParses() = runBlocking {
        val p = Catalog.album("MPREb_yXhSI4FCUo6")
        println("CAT-ALBUM '${p.album.title}' by ${p.album.primaryArtist?.name} ${p.album.year} ${p.album.type} tracks=${p.tracks.size} others=${p.others.size} cover=${p.album.cover?.take(50)}")
        assertEquals("OK Computer", p.album.title)
        assertEquals(12, p.tracks.size)
        assertTrue(p.tracks.all { it.duration > 0 && it.album?.id == p.album.id && it.artistNames.isNotBlank() })
        assertEquals(1, p.tracks.first().trackNumber)
    }

    @Test fun artistPageParses() = runBlocking {
        val p = Catalog.artist("UCr_iyUANcn9OX_yy9piYoLw")
        println("CAT-ARTIST '${p.artist.name}' listeners=${p.artist.listeners} radio=${p.artist.radio} top=${p.topTracks.size} albums=${p.albums.size} singles=${p.singles.size} similar=${p.similar.size} playlists=${p.playlists.size} more=${p.albumsMore} bio=${p.artist.bio?.take(40)}")
        assertEquals("Radiohead", p.artist.name)
        assertTrue(p.topTracks.size >= 5)
        assertTrue(p.albums.size >= 5)
        assertTrue(p.similar.isNotEmpty())
        assertNotNull(p.artist.radio)
        assertTrue(p.topTracks.all { it.artistNames.isNotBlank() && it.album?.cover != null })
        val disco = Catalog.discography(p.albumsMore!!.first, p.albumsMore!!.second)
        println("CAT-DISCO ${disco.size} albums, first '${disco.firstOrNull()?.title}'")
        assertTrue(disco.size >= p.albums.size)
    }

    @Test fun playlistPagesThroughContinuations() = runBlocking {
        val (p, tracks) = Catalog.playlist("RDCLAK5uy_m_h-nx7OCFaq9AlyXv78lG0AuloqW_NUA")
        println("CAT-PLAYLIST '${p.title}' says ${p.numberOfTracks} songs, parsed ${tracks.size}; first '${tracks.firstOrNull()?.title}' by ${tracks.firstOrNull()?.artistNames}")
        assertTrue(tracks.size > 100)
        assertTrue(tracks.all { it.duration > 0 })
    }

    @Test fun radioAndRelated() = runBlocking {
        val radio = Catalog.radio("ZVgHPSyEIqk")
        println("CAT-RADIO ${radio.size} songs: ${radio.take(5).joinToString { "'${it.title}' by ${it.artistNames}" }}")
        assertTrue(radio.size >= 20)
        assertEquals("ZVgHPSyEIqk", radio.first().id)
        assertTrue(radio.all { it.duration > 0 && it.album?.cover != null })
        val rel = Catalog.related("ZVgHPSyEIqk")
        println("CAT-RELATED ${rel.map { "${it.title}(${it.tracks.size}t/${it.albums.size}a/${it.playlists.size}p/${it.artists.size}ar)" }}")
        assertTrue(rel.any { it.tracks.isNotEmpty() })
        val t = Catalog.track("ZVgHPSyEIqk")
        println("CAT-TRACK-BY-ID '${t.title}' ${t.duration}s by ${t.artistNames}")
        assertTrue(t.title.startsWith("Let Down"))
    }

    @Test fun homeMoodsAndReleases() = runBlocking {
        val home = Catalog.home()
        println("CAT-HOME ${home.map { "${it.title}(${it.tracks.size}t/${it.albums.size}a/${it.playlists.size}p)" }}")
        assertTrue(home.isNotEmpty())
        val moods = Catalog.moods()
        println("CAT-MOODS ${moods.size}: ${moods.take(6).map { it.title + "/" + it.group }}")
        assertTrue(moods.size >= 20)
        val shelves = Catalog.moodShelves(moods.first().browseId, moods.first().params)
        println("CAT-MOOD '${moods.first().title}' -> ${shelves.map { "${it.title}(${it.playlists.size})" }}")
        assertTrue(shelves.any { it.playlists.isNotEmpty() })
        val fresh = Catalog.newReleases()
        println("CAT-NEW ${fresh.size}: ${fresh.take(3).map { "'${it.title}' by ${it.primaryArtist?.name} ${it.type}" }}")
        assertTrue(fresh.size >= 20)
    }

    @Test fun lyricsFallThroughToTheService() = runBlocking {
        val t = Catalog.track("ZVgHPSyEIqk")
        val l = Catalog.lyrics(t)
        println("CAT-LYRICS synced=${l?.synced?.size} plain=${l?.plain?.take(60)?.replace("\n", " | ")}")
        assertNotNull(l)
        assertTrue(!l!!.plain.isNullOrBlank() || !l.synced.isNullOrEmpty())
        println("CAT-IMAGE " + Music.image("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 640))
        assertEquals("https://lh3.googleusercontent.com/abc=w640-h640-p-l90-rj", Music.image("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 640))
    }
}
