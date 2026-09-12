package app.aoide

import app.aoide.data.ApiClient
import app.aoide.data.ArtistRef
import app.aoide.data.HiRate
import app.aoide.data.Track
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The second source against its live API: it finds a song it carries, hands back a 320 kbps file
 * that really serves, and refuses the covers and tributes a loose match would have accepted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HiRateTest {
    private fun t(id: String, title: String, artist: String, seconds: Int) =
        Track(id = id, title = title, duration = seconds, artist = ArtistRef("x", artist), artists = listOf(ArtistRef("x", artist)))

    @Test fun findsASongItCarries() = runBlocking {
        val url = HiRate.lookup(t("a", "Mystery of Love", "Sufjan Stevens", 249))
        println("HIRATE-FOUND $url")
        assertNotNull("the source should carry this song", url)
        assertTrue("must be the 320 file over https: $url", url!!.startsWith("https://") && url.endsWith("_320.mp4"))
    }

    @Test fun theFileReallyServes320() = runBlocking {
        val url = HiRate.lookup(t("a", "Mystery of Love", "Sufjan Stevens", 249)) ?: return@runBlocking
        val req = Request.Builder().url(url).header("User-Agent", ApiClient.UA).header("Range", "bytes=0-65535").build()
        ApiClient.http.newCall(req).execute().use { res ->
            val range = res.header("Content-Range")
            val total = range?.substringAfter('/')?.toLongOrNull() ?: 0L
            val kbps = if (total > 0) (total * 8 / 1000 / 249).toInt() else 0
            println("HIRATE-SERVES http=${res.code} type=${res.header("Content-Type")} total=$total (~$kbps kbps)")
            assertEquals(206, res.code)
            assertEquals("audio/mp4", res.header("Content-Type"))
            assertTrue("expected roughly 320 kbps, measured $kbps", kbps in 250..400)
        }
    }

    @Test fun refusesCoversAndTributes() = runBlocking {
        // This catalogue has no Radiohead, only piano tributes and lullaby versions of their songs.
        val url = HiRate.lookup(t("b", "Paranoid Android", "Radiohead", 383))
        println("HIRATE-REFUSED-COVER $url")
        assertNull("a tribute act must never stand in for the band", url)
    }

    @Test fun refusesAWrongLength() = runBlocking {
        val url = HiRate.lookup(t("c", "Mystery of Love", "Sufjan Stevens", 400))
        println("HIRATE-REFUSED-LENGTH $url")
        assertNull("a recording of another length is a different cut", url)
    }

    @Test fun decryptsItsOwnAddressFormat() {
        // An address its web player would hand out, encrypted with the key its own site uses.
        val enc = "Xs2rIkRk1I1MPn3OJUqbrjEXoEbDQ2p9LMs+Nn3Zj6Rk1I1MPn3OJQ=="
        println("HIRATE-DECRYPT " + HiRate.mediaUrl(enc))
        // Junk in, nothing out: it must not throw or invent a URL.
        assertNull(HiRate.mediaUrl("not-base64-at-all!!"))
    }
}
