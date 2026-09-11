package app.aoide

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aoide.data.ArtistRef
import app.aoide.data.Catalog
import app.aoide.data.Prefs
import app.aoide.data.Quality
import app.aoide.data.Track
import app.aoide.data.YouTubeMusic
import app.aoide.data.json
import app.aoide.player.AoideMedia
import app.aoide.player.StreamResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeoutException

/**
 * The YouTube Music source against live YouTube, end to end on the JVM: matching TIDAL tracks,
 * resolving a whole-file stream, the synthesized DASH manifest's seek index, and real ExoPlayer
 * (through the app's own media pipeline) playing the song to its last sample.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class YouTubeSourceTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.forAllSupportedMimeTypes()

    private fun t(id: Long, title: String, artist: String, seconds: Int, explicit: Boolean = false) =
        Track(id = id, title = title, duration = seconds, explicit = explicit, artist = ArtistRef(1, artist), artists = listOf(ArtistRef(1, artist)))

    @Test fun matchesTheSameRecording() = runBlocking {
        YouTubeMusic.resetForTest()
        val cases = listOf(
            t(1, "15 Step", "Radiohead", 237) to "15 step",
            t(2, "HUMBLE.", "Kendrick Lamar", 177, explicit = true) to "humble",
            t(3, "Get Lucky", "Daft Punk", 370) to "get lucky",
            t(4, "Everything In Its Right Place", "Radiohead", 251) to "everything in its right place",
            t(5, "Bad Guy", "Billie Eilish", 194) to "bad guy",
        )
        for ((track, want) in cases) {
            val c = YouTubeMusic.find(track)
            assertNotNull("no match for ${track.title}", c)
            println("YT-MATCH ${track.title} -> ${c!!.videoId} '${c.title}' by ${c.artists} ${c.durationSec}s atv=${c.officialAudio}")
            assertTrue("${track.title} matched '${c.title}'", YouTubeMusic.norm(c.title).startsWith(want))
            assertTrue("${track.title}: length ${c.durationSec}s vs ${track.duration}s", kotlin.math.abs(c.durationSec - track.duration) <= 4)
        }
        // A length that no recording has must be refused, not guessed.
        assertNull(YouTubeMusic.find(t(6, "15 Step", "Radiohead", 300)))
    }

    @Test fun resolvesAWholeSongAsDash() = runBlocking {
        YouTubeMusic.resetForTest()
        StreamResolver.forget(FIFTEEN_STEP)
        // Nothing registered for this id: the resolver has to fetch the track from TIDAL itself.
        val r = StreamResolver.resolve(FIFTEEN_STEP, Quality.LOSSLESS)
        println("YT-RESOLVE source=${r.info.source} preview=${r.info.isPreview} label=${r.info.label}")
        assertEquals("youtube", r.info.source)
        assertFalse(r.info.isPreview)
        val mpd = String(Base64.decode(r.uri.toString().substringAfter("base64,"), Base64.DEFAULT))
        val manifest = DashManifestParser().parse(Uri.parse("https://example.invalid/"), ByteArrayInputStream(mpd.toByteArray()))
        val rep = manifest.getPeriod(0).adaptationSets[0].representations[0]
        assertEquals(237.0, manifest.durationMs / 1000.0, 3.0)
        val source = DefaultHttpDataSource.Factory().setUserAgent(YouTubeMusic.userAgentFor("VISIONOS")).createDataSource()
        val index = DashUtil.loadChunkIndex(source, C.TRACK_TYPE_AUDIO, rep)
        assertNotNull("no seek index in the stream", index)
        val covered = (index!!.timesUs.last() + index.durationsUs.last()) / 1_000_000.0
        println("YT-INDEX ${index.length} chunks covering ${covered}s, ${rep.format.sampleMimeType} ${rep.format.bitrate / 1000} kbps")
        assertEquals(237.0, covered, 3.0)
    }

    @Test fun exoPlayerPlaysTheWholeSong() {
        YouTubeMusic.resetForTest()
        StreamResolver.forget(FIFTEEN_STEP)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val track = runBlocking { Catalog.track(FIFTEEN_STEP) }
        // Built the way PlayerController builds queue items, then rebuilt the way the session service rebuilds them.
        val extras = Bundle().apply { putString("track", json.encodeToString(track)) }
        val item = AoideMedia.toPlayable(MediaItem.Builder().setMediaId(FIFTEEN_STEP.toString()).setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build())
        // A fake renderer consumes every sample the pipeline extracts. Robolectric's simulated audio output never
        // drains, so a real audio renderer stalls at 0:00 on the JVM even though everything upstream of it works.
        val renderer = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val player = ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf<Renderer>(renderer) })
            .setClock(FakeClock(true))
            .setMediaSourceFactory(AoideMedia.mediaSourceFactory(context))
            .build()
        var error: PlaybackException? = null
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = e }
        })
        var furthest = 0L
        var fileLength = -1L
        var chunks = 0
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                if (info.uri.host?.endsWith("googlevideo.com") != true) return
                chunks++
                fileLength = info.uri.getQueryParameter("clen")?.toLongOrNull() ?: fileLength
                furthest = maxOf(furthest, info.dataSpec.position + info.bytesLoaded)
            }
            override fun onLoadError(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                println("YT-LOADERR ${info.uri.host} at ${info.dataSpec.position}: $error")
            }
        })
        player.setMediaItem(item)
        player.prepare()
        player.play()
        for (attempt in 0 until 40) {
            if (player.playbackState == Player.STATE_ENDED || error != null) break
            try {
                TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
            } catch (e: TimeoutException) {
                println("YT-TICK state=${player.playbackState} pos=${player.currentPosition} buffered=${player.bufferedPosition} chunks=$chunks bytes=$furthest/$fileLength")
            } catch (e: IllegalStateException) {
                break
            }
        }
        val state = player.playbackState
        val duration = player.duration
        val info = StreamResolver.infoFor(FIFTEEN_STEP)
        val samples = renderer.sampleBufferReadCount
        player.release()
        println("YT-E2E state=$state error=${error?.errorCodeName} ${error?.cause} duration=${duration}ms chunks=$chunks bytes=$furthest/$fileLength samples=$samples via=${info?.source} ${info?.label}")
        assertNull("playback error: ${error?.errorCodeName} ${error?.cause}", error)
        assertEquals(Player.STATE_ENDED, state)
        assertEquals("youtube", info?.source)
        assertEquals(237.0, duration / 1000.0, 3.0)
        assertTrue("the last chunk never loaded: $furthest of $fileLength bytes", fileLength > 0 && furthest >= fileLength)
    }

    @Test fun switchedOffItFallsBackToAPreview() = runBlocking {
        Prefs.setYouTubeSource(false)
        try {
            StreamResolver.forget(FIFTEEN_STEP)
            val r = StreamResolver.resolve(FIFTEEN_STEP, Quality.LOSSLESS)
            println("YT-OFF source=${r.info.source} preview=${r.info.isPreview} label=${r.info.label}")
            assertTrue(r.info.source != "youtube")
        } finally {
            Prefs.setYouTubeSource(true)
            StreamResolver.forget(FIFTEEN_STEP)
        }
    }

    companion object {
        const val FIFTEEN_STEP = 61799590L // Radiohead, 15 Step (237 s)
    }
}
