package app.aoide

import app.aoide.data.Importer
import app.aoide.data.Music
import app.aoide.data.Parse
import app.aoide.data.Store
import app.aoide.data.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Plain-JVM checks on the pieces that need no Android: file safety, link recognition, matching helpers, manifests. */
class PureTest {
    @Test fun aKillMidWriteKeepsTheLastGoodCopy() {
        val dir = File(System.getProperty("java.io.tmpdir"), "aoide-store-${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, "library.json")
        Store.writeAtomic(f, """{"v":1}""")
        Store.writeAtomic(f, """{"v":2}""")
        assertEquals("""{"v":2}""", f.readText())
        assertEquals("""{"v":1}""", File(dir, "library.json.bak").readText())
        // The main file is truncated, as a crash between truncate and write would leave it.
        f.writeText("""{"v":""")
        val read = Store.read(f) { s -> if (s.endsWith("}")) s else throw IllegalStateException("truncated") }
        assertEquals("""{"v":1}""", read)
        dir.deleteRecursively()
    }

    @Test fun recognisesPlaylistLinks() {
        assertEquals("list" to "37i9dQZF1DXcBWIGoYBM5M", Importer.recognise("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("list" to "37i9dQZF1DXcBWIGoYBM5M", Importer.recognise("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("video" to "PLabc_-123", Importer.recognise("https://music.youtube.com/playlist?list=PLabc_-123"))
        assertEquals("video" to "PLxyz", Importer.recognise("https://www.youtube.com/playlist?feature=share&list=PLxyz"))
        assertNull(Importer.recognise("https://example.com/not-a-playlist"))
    }

    @Test fun durationsAndNormalisedNames() {
        assertEquals(187, Parse.parseDuration("3:07"))
        assertEquals(3723, Parse.parseDuration("1:02:03"))
        assertNull(Parse.parseDuration("abc"))
        assertNull(Parse.parseDuration(null))
        assertEquals("beyonce halo", Parse.norm("Beyoncé — Halo!"))
        assertEquals("cafe del mar", Parse.norm("Café   del Mar"))
    }

    @Test fun versionsCompare() {
        assertTrue(Updates.isNewer("0.10.0", "0.9.4"))
        assertTrue(Updates.isNewer("v1.0.0-beta", "0.9.9"))
        assertFalse(Updates.isNewer("0.9.4", "0.9.4"))
        assertFalse(Updates.isNewer("0.9.3", "0.9.4"))
    }

    @Test fun aReleaseWithoutAnApkIsRefused() {
        val body = """{"tag_name":"v9.9.9","name":"x","body":"","html_url":"https://example.com","assets":[{"name":"notes.txt","browser_download_url":"https://example.com/n","size":1}]}"""
        val thrown = runCatching { Updates.parse(body) }.exceptionOrNull()
        assertTrue("a release with no APK must not yield an install link", thrown is IllegalStateException)
    }

    @Test fun theDashManifestSurvivesAnAmpersandInTheUrl() {
        val s = Music.AudioStream(
            url = "https://cdn.example.com/videoplayback?expire=1&id=\"x\"&c=VISIONOS", itag = 251, mimeType = "audio/webm", codecs = "opus",
            bitrate = 130_000, averageBitrate = 128_000, contentLength = 4_000_000, durationMs = 237_000, initRange = 0L..258L, indexRange = 259L..800L, sampleRate = 48_000, channels = 2, client = "VISIONOS",
        )
        val xml = Music.dashManifest(s)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val base = doc.getElementsByTagName("BaseURL").item(0).textContent
        assertEquals(s.url, base)
        assertEquals("259-800", doc.getElementsByTagName("SegmentBase").item(0).attributes.getNamedItem("indexRange").textContent)
    }

    @Test fun recognisesEntriesSavedBeforeTheCatalogueChanged() {
        assertTrue(app.aoide.data.Legacy.isOldId("251380837"))
        assertFalse(app.aoide.data.Legacy.isOldId("dQw4w9WgXcQ"))
        assertFalse(app.aoide.data.Legacy.isOldId("MPREb_abc123"))
        assertFalse(app.aoide.data.Legacy.isOldId(""))
        assertTrue(app.aoide.data.Legacy.isOldCover("7e0dd366-7fb9-4326-a1bf-19a3fc750bda"))
        assertFalse(app.aoide.data.Legacy.isOldCover("https://lh3.googleusercontent.com/abc=w120-h120"))
        val old = app.aoide.data.Track("251380837", "Lovers Rock", version = "")
        assertTrue(app.aoide.data.Legacy.isOld(old))
        assertFalse(app.aoide.data.Legacy.isOld(old.copy(id = "local:12")))
    }
}
