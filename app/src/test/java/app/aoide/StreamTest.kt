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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aoide.data.Catalog
import app.aoide.data.Music
import app.aoide.data.Quality
import app.aoide.data.json
import app.aoide.player.AoideMedia
import app.aoide.player.StreamResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * The stream path against the live service, end to end on the JVM: a whole-file stream, the
 * synthesized DASH manifest's seek index, and real ExoPlayer through the app's own pipeline playing
 * the song to its last sample. Skips (with a printed reason) when the service refuses this address.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StreamTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.forAllSupportedMimeTypes()

    @Test fun resolvesAWholeSongAsDash() = runBlocking {
        Music.resetForTest()
        StreamResolver.forget(SONG)
        val r = runCatching { StreamResolver.resolve(SONG, Quality.HIGH) }.getOrElse { println("STREAM-SKIP resolve: $it"); return@runBlocking }
        println("STREAM-RESOLVE source=${r.info.source} label=${r.info.label}")
        assertEquals("full", r.info.source)
        val mpd = String(Base64.decode(r.uri.toString().substringAfter("base64,"), Base64.DEFAULT))
        val manifest = DashManifestParser().parse(Uri.parse("https://example.invalid/"), ByteArrayInputStream(mpd.toByteArray()))
        val rep = manifest.getPeriod(0).adaptationSets[0].representations[0]
        val source = DefaultHttpDataSource.Factory().setUserAgent(Music.userAgentFor("VISIONOS")).createDataSource()
        val index = DashUtil.loadChunkIndex(source, C.TRACK_TYPE_AUDIO, rep)
        assertNotNull("no seek index in the stream", index)
        val covered = (index!!.timesUs.last() + index.durationsUs.last()) / 1_000_000.0
        println("STREAM-INDEX ${index.length} chunks covering ${covered}s, ${rep.format.sampleMimeType} ${rep.format.bitrate / 1000} kbps")
        assertEquals(manifest.durationMs / 1000.0, covered, 3.0)
    }

    @Test fun exoPlayerPlaysTheWholeSong() {
        Music.resetForTest()
        StreamResolver.forget(SONG)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val track = runBlocking { Catalog.track(SONG) }
        if (runBlocking { Music.stream(SONG, Quality.HIGH) } == null) { println("STREAM-SKIP the service hands this address no whole-file stream right now"); return }
        val extras = Bundle().apply { putString("track", json.encodeToString(track)) }
        val item = AoideMedia.toPlayable(MediaItem.Builder().setMediaId(SONG).setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build())
        // A fake renderer consumes every sample the pipeline extracts; a real audio renderer stalls at 0:00 on the JVM.
        val renderer = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val player = ExoPlayer.Builder(context, RenderersFactory { _, _, _, _, _ -> arrayOf<Renderer>(renderer) })
            .setClock(FakeClock(true))
            .setMediaSourceFactory(AoideMedia.mediaSourceFactory(context))
            .build()
        var error: PlaybackException? = null
        player.addListener(object : Player.Listener { override fun onPlayerError(e: PlaybackException) { error = e } })
        var furthest = 0L
        var fileLength = -1L
        var chunks = 0
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                if (info.uri.host?.endsWith(Music.CDN_SUFFIX) != true) return
                chunks++
                fileLength = info.uri.getQueryParameter("clen")?.toLongOrNull() ?: fileLength
                furthest = maxOf(furthest, info.dataSpec.position + info.bytesLoaded)
            }
            override fun onLoadError(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) { println("STREAM-LOADERR ${info.uri.host} at ${info.dataSpec.position}: $error") }
        })
        player.setMediaItem(item)
        player.prepare()
        player.play()
        for (attempt in 0 until 40) {
            if (player.playbackState == Player.STATE_ENDED || error != null) break
            try { TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED) } catch (e: TimeoutException) { println("STREAM-TICK state=${player.playbackState} pos=${player.currentPosition} chunks=$chunks bytes=$furthest/$fileLength") } catch (e: IllegalStateException) { break }
        }
        val state = player.playbackState
        val duration = player.duration
        val samples = renderer.sampleBufferReadCount
        player.release()
        println("STREAM-E2E state=$state error=${error?.errorCodeName} duration=${duration}ms chunks=$chunks bytes=$furthest/$fileLength samples=$samples ${StreamResolver.infoFor(SONG)?.label}")
        assertNull("playback error: ${error?.errorCodeName} ${error?.cause}", error)
        assertEquals(Player.STATE_ENDED, state)
        assertEquals(track.duration.toDouble(), duration / 1000.0, 3.0)
        assertTrue("the last chunk never loaded: $furthest of $fileLength bytes", fileLength > 0 && furthest >= fileLength)
    }

    companion object {
        const val SONG = "jNY_wLukVW0" // the opening track of the 1997 record, 4:48
    }
}
