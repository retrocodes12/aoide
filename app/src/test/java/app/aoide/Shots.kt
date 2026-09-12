package app.aoide

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aoide.data.Catalog
import app.aoide.data.Library
import app.aoide.data.PlayContext
import app.aoide.data.Track
import app.aoide.player.PlayerController
import app.aoide.player.StreamInfo
import app.aoide.player.StreamResolver
import app.aoide.player.PlayerUiState
import app.aoide.player.Status
import app.aoide.ui.AppRoot
import app.aoide.ui.AppUi
import app.aoide.ui.TestNav
import app.aoide.ui.Toasts
import app.aoide.ui.theme.AoideTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Renders every screen of the real app on the JVM against the live catalogue and writes PNGs to
 * app/build/shots. This is how the auditor looks at the native build on a laptop that cannot run
 * the emulator: `./gradlew :app:recordRoborazziDebug`.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class Shots {
    @get:Rule
    val rule = createComposeRule()

    private fun shot(name: String) {
        Toasts.clear()
        settle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    /** Let network results land and every pending main-thread task (including delayed ones) run. */
    private fun settle(ms: Long = 600) {
        repeat((ms / 100).toInt().coerceAtLeast(1)) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            rule.waitForIdle()
            Thread.sleep(100)
        }
    }

    /** Poll until [cond] holds, driving the looper between checks so async work can complete. */
    private fun await(timeoutMs: Long = 45_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            rule.waitForIdle()
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(200)
        }
        runCatching { rule.onRoot().captureRoboImage("build/shots/zz-timeout-${System.currentTimeMillis()}.png") }
        throw AssertionError("timed out waiting")
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun launch() {
        // Robolectric's native graphics runtime must come up on the test thread before any image-decoding worker
        // thread touches it; otherwise its JNI bootstrap fails with "Class not found: java/nio/IntBuffer" and aborts.
        val warm = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(warm).drawColor(0xFF000000.toInt())
        android.graphics.Paint().apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }.measureText("Aoide")
        val png = java.io.ByteArrayOutputStream().also { warm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        // Decode artwork on the main thread: a decode on a worker thread is what trips the native runtime's JNI bootstrap.
        coil3.SingletonImageLoader.setSafe { c ->
            coil3.ImageLoader.Builder(c)
                .decoderCoroutineContext(kotlinx.coroutines.Dispatchers.Main.immediate)
                .eventListener(object : coil3.EventListener() {
                    override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) { println("COIL-ERROR ${request.data} -> ${result.throwable}") }
                    override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) { println("COIL-OK ${request.data} ${result.image.width}x${result.image.height}") }
                })
                .build()
        }
        app.aoide.ui.components.ArtworkConfig.crossfadeMs = 0
        app.aoide.data.Updates.autoCheck = false
        app.aoide.data.Updates.setStateForTest(app.aoide.data.Updates.State.Idle)
        AppUi.closeOverlays()
        PlayerController.setStateForTest(PlayerUiState())
        Toasts.clear()
        rule.setContent { AoideTheme { AppRoot() } }
        settle(300)
    }

    private fun album(): Pair<app.aoide.data.Album, List<Track>> = runBlocking { Catalog.album(ALBUM).let { it.album to it.tracks } }

    private fun playing(tracks: List<Track>, title: String, status: Status = Status.PLAYING) {
        StreamResolver.setInfoForTest(StreamInfo(tracks[0].id, isPreview = false, quality = "OPUS 139 kbps", bitDepth = null, sampleRate = 48_000, source = "full"))
        app.aoide.data.Lossless.setKnownForTest(tracks[0].id, "1")
        if (tracks.size > 2) app.aoide.data.Lossless.setKnownForTest(tracks[2].id, "2")
        PlayerController.setStateForTest(PlayerUiState(queue = tracks, index = 0, status = status, positionMs = 9_000, durationMs = 30_000, context = PlayContext("album", title, "album/$ALBUM")))
    }

    @Test fun home() {
        launch(); await { has("hero_card") }; settle(5000); shot("01-home")
        // Lazy lists compose nothing below the fold, so scroll to the shelves before looking for them.
        rule.onNodeWithTag("home").performScrollToNode(hasTestTag("home_card")); await { has("home_card") }; settle(5000); shot("01b-home-shelves")
    }

    @Test fun searchBrowse() { launch(); rule.onNodeWithTag("tab_search").performClick(); await { has("browse_tile") }; settle(1200); shot("02-search-browse") }

    @Test fun searchResults() {
        launch(); rule.onNodeWithTag("tab_search").performClick(); await { has("search_field") }
        rule.onNodeWithTag("search_field").performTextInput("daft punk")
        settle(600)
        rule.onNodeWithTag("search_field").performImeAction()
        await(60_000) { has("track_row") || has("top_hit") }; settle(4000); shot("03-search-results")
    }

    @Test fun albumPage() {
        launch(); val (a, tracks) = album(); playing(tracks, a.title)
        await { has("hero_card") }
        navigate("album/$ALBUM"); await { has("track_row") }; settle(2500); shot("04-album")
        rule.onNodeWithTag("album_screen").performScrollToIndex(7); await { has("collapsing_bar") }; settle(800); shot("20-album-collapsed")
    }

    @Test fun artistPage() { launch(); navigate("artist/$ARTIST"); await { has("track_row") }; settle(8000); shot("05-artist") }

    @Test fun playlistPage() { launch(); navigate("playlist/$PLAYLIST"); await { has("track_row") }; settle(6000); shot("06-playlist") }

    @Test fun libraryAndLiked() {
        launch(); val (_, tracks) = album(); Library.toggleLike(tracks[0])
        rule.onNodeWithTag("tab_library").performClick(); await { has("library_row") }; settle(1500); shot("07-library")
        navigate("liked"); await { has("track_row") }; settle(1500); shot("08-liked")
    }

    @Test fun settings() { launch(); navigate("settings"); await { has("instance_row") }; settle(2500); shot("09-settings") }

    @Test fun nowPlaying() {
        launch(); val (a, tracks) = album(); playing(tracks, a.title)
        await { has("mini_player") }; settle(1500); shot("10-mini")
        AppUi.nowPlayingOpen = true; await { has("now_playing") }; settle(7000); shot("11-now-playing")
        AppUi.lyricsOpen = true; await { has("lyrics_screen") }; settle(3000); shot("12-lyrics")
        AppUi.lyricsOpen = false; AppUi.queueOpen = true; await { has("queue_screen") }; settle(800); shot("13-queue")
    }

    @Test fun nowPlayingPausedAndError() {
        launch(); val (a, tracks) = album(); playing(tracks, a.title, Status.PAUSED)
        AppUi.nowPlayingOpen = true; await { has("now_playing") }; settle(6000); shot("14-now-playing-paused")
        PlayerController.setStateForTest(PlayerUiState(queue = tracks, index = 0, status = Status.ERROR, error = "Playback isn't working right now. Check your connection, then try again.", context = PlayContext("album", a.title, "album/$ALBUM")))
        await { has("np_error") }; settle(800); shot("15-now-playing-error")
    }

    @Test fun localPlaylistAndConfirm() {
        launch(); val p = Library.createPlaylist("Late nights")
        navigate("local/${p.id}"); await { has("local_playlist_screen") }; settle(800); shot("16-local-playlist-empty")
        rule.onNodeWithTag("delete_playlist").performClick(); await { has("confirm_sheet") }; settle(600); shot("17-confirm-delete")
        AppUi.confirm = null; settle(400)
        rule.onNodeWithTag("rename_playlist").performClick(); await { has("rename_input") }; settle(600); shot("19-rename-sheet")
        Library.deletePlaylist(p.id)
    }

    @Test fun downloadsAndHistory() {
        launch(); val (_, tracks) = album()
        navigate("downloads"); await { has("downloads_screen") }; settle(800); shot("21-downloads-empty")
        tracks.take(3).forEach { Library.recordPlay(it) }; Library.recordPlay(tracks[0])
        navigate("history"); await { has("history_screen") }; settle(1500); shot("22-history")
        Library.clearHistory()
    }

    @Test fun equalizerAndImport() {
        launch(); navigate("equalizer"); await { has("equalizer_screen") }; settle(800); shot("23-equalizer")
        navigate("import"); await { has("import_screen") }; settle(600)
        rule.onNodeWithTag("import_link").performTextInput("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        settle(400); shot("24-import")
        rule.onNodeWithTag("import_go").performClick()
        await(90_000) { has("import_title") }; settle(3000); shot("25-import-matched")
    }

    @Test fun settingsMore() {
        launch(); navigate("settings"); await { has("data_saver_toggle") }
        rule.onNodeWithTag("data_saver_toggle").performScrollTo(); settle(600); shot("26-settings-playback")
        rule.onNodeWithTag("translate_toggle").performScrollTo(); settle(600); shot("27-settings-look")
        navigate("local_files"); await { has("local_files_screen") }; settle(800); shot("28-local-files")
    }

    @Test fun sleepAndLyricsTranslation() {
        launch(); val (a, tracks) = album(); playing(tracks, a.title)
        AppUi.nowPlayingOpen = true; await { has("now_playing") }; settle(2000)
        AppUi.sleepOpen = true; await { has("sleep_sheet") }; settle(600); shot("29-sleep-timer")
        AppUi.sleepOpen = false; settle(300)
        app.aoide.data.Prefs.translateLyrics.set(true); app.aoide.data.Prefs.setLyricsLang("hi")
        // Translation comes over the network from a rate-limited endpoint; render whatever arrived rather than fail the run.
        AppUi.lyricsOpen = true; await { has("lyrics_screen") }; runCatching { await(40_000) { has("lyric_translation") } }; settle(1500); shot("30-lyrics-translated")
        app.aoide.data.Prefs.translateLyrics.set(false)
    }

    @Test fun updates() {
        launch()
        val rel = app.aoide.data.Updates.Release("9.9.0", "Aoide 9.9.0", "Sample release notes: a new screen, two fixes.", app.aoide.data.Updates.PAGE, "https://example.invalid/Aoide-9.9.0.apk", 3_100_000L, "")
        app.aoide.data.Updates.setStateForTest(app.aoide.data.Updates.State.Available(rel))
        await { has("update_banner") }; settle(600); shot("31-update-banner")
        navigate("settings"); await { has("updates_section") }; settle(600); shot("32-settings-updates")
        app.aoide.data.Updates.setStateForTest(app.aoide.data.Updates.State.Idle)
    }

    @Test fun queueRadio() {
        launch(); val (_, tracks) = album()
        StreamResolver.setInfoForTest(StreamInfo(tracks[0].id, isPreview = false, quality = "OPUS 139 kbps", bitDepth = null, sampleRate = 48_000, source = "full"))
        PlayerController.setStateForTest(PlayerUiState(queue = tracks, index = 0, status = Status.PLAYING, positionMs = 9_000, durationMs = 30_000, context = PlayContext("radio", "${tracks[0].title} Radio")))
        AppUi.queueOpen = true; await { has("queue_screen") }; settle(1500); shot("33-queue-radio")
        AppUi.queueOpen = false
    }

    @Test fun trackMenu() {
        launch(); val (_, tracks) = album(); AppUi.openMenu(tracks[0]); await { has("track_menu") }; settle(1200); shot("18-track-menu")
        AppUi.menuTrack = null
    }

    private fun navigate(route: String) { TestNav.go(route); settle(300) }

    companion object {
        const val ALBUM = "MPREb_yXhSI4FCUo6" // a well-known 1997 rock record
        const val ARTIST = "UCr_iyUANcn9OX_yy9piYoLw" // its band
        const val PLAYLIST = "RDCLAK5uy_m_h-nx7OCFaq9AlyXv78lG0AuloqW_NUA" // a curated '90s list
    }
}
