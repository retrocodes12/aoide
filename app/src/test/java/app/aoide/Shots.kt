package app.aoide

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
        throw AssertionError("timed out waiting")
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun launch() {
        AppUi.closeOverlays()
        rule.setContent { AoideTheme { AppRoot() } }
        settle(300)
    }

    private fun album(): Pair<app.aoide.data.Album, List<Track>> = runBlocking { Catalog.album(ALBUM) }

    private fun playing(tracks: List<Track>, title: String, status: Status = Status.PLAYING) {
        StreamResolver.setInfoForTest(StreamInfo(tracks[0].id, isPreview = true, quality = "LOSSLESS", bitDepth = null, sampleRate = null, source = "tidal"))
        PlayerController.setStateForTest(PlayerUiState(queue = tracks, index = 0, status = status, positionMs = 9_000, durationMs = 30_000, context = PlayContext("album", title, "album/$ALBUM")))
    }

    @Test fun home() { launch(); await { has("hero_card") }; settle(2500); shot("01-home") }

    @Test fun searchBrowse() { launch(); rule.onNodeWithTag("tab_search").performClick(); await { has("browse_tile") }; settle(1200); shot("02-search-browse") }

    @Test fun searchResults() {
        launch(); rule.onNodeWithTag("tab_search").performClick(); await { has("search_field") }
        rule.onNodeWithTag("search_field").performTextInput("radiohead")
        await { has("track_row") }; settle(2500); shot("03-search-results")
    }

    @Test fun albumPage() {
        launch(); val (a, tracks) = album(); playing(tracks, a.title)
        await { has("hero_card") }
        navigate("album/$ALBUM"); await { has("track_row") }; settle(2500); shot("04-album")
    }

    @Test fun artistPage() { launch(); navigate("artist/$ARTIST"); await { has("track_row") }; settle(3500); shot("05-artist") }

    @Test fun playlistPage() { launch(); navigate("playlist/$PLAYLIST"); await { has("track_row") }; settle(2500); shot("06-playlist") }

    @Test fun libraryAndLiked() {
        launch(); val (_, tracks) = album(); Library.toggleLike(tracks[0])
        rule.onNodeWithTag("tab_library").performClick(); await { has("library_row") }; settle(1500); shot("07-library")
        navigate("liked"); await { has("track_row") }; settle(1500); shot("08-liked")
    }

    @Test fun settings() { launch(); navigate("settings"); await { has("instance_row") }; settle(2500); shot("09-settings") }

    @Test fun nowPlaying() {
        launch(); val (a, tracks) = album(); playing(tracks, a.title)
        await { has("mini_player") }; settle(1500); shot("10-mini")
        AppUi.nowPlayingOpen = true; await { has("now_playing") }; settle(3000); shot("11-now-playing")
        AppUi.lyricsOpen = true; await { has("lyrics_screen") }; settle(3000); shot("12-lyrics")
        AppUi.lyricsOpen = false; AppUi.queueOpen = true; await { has("queue_screen") }; settle(800); shot("13-queue")
    }

    @Test fun nowPlayingPausedAndError() {
        launch(); val (a, tracks) = album(); playing(tracks, a.title, Status.PAUSED)
        AppUi.nowPlayingOpen = true; await { has("now_playing") }; settle(2500); shot("14-now-playing-paused")
        PlayerController.setStateForTest(PlayerUiState(queue = tracks, index = 0, status = Status.ERROR, error = "Playback isn't working right now. Check your connection, then try again.", context = PlayContext("album", a.title, "album/$ALBUM")))
        await { has("np_error") }; settle(800); shot("15-now-playing-error")
    }

    @Test fun localPlaylistAndConfirm() {
        launch(); val p = Library.createPlaylist("Late nights")
        navigate("local/${p.id}"); await { has("local_playlist_screen") }; settle(800); shot("16-local-playlist-empty")
        rule.onNodeWithTag("delete_playlist").performClick(); await { has("confirm_sheet") }; settle(600); shot("17-confirm-delete")
        Library.deletePlaylist(p.id)
    }

    @Test fun trackMenu() {
        launch(); val (_, tracks) = album(); AppUi.openMenu(tracks[0]); await { has("track_menu") }; settle(1200); shot("18-track-menu")
        AppUi.menuTrack = null
    }

    private fun navigate(route: String) { TestNav.go(route); settle(300) }

    companion object {
        const val ALBUM = 61799588L // Radiohead, In Rainbows: a bright yellow tint, the hardest case for ink
        const val ARTIST = 3816041L // Kendrick Lamar
        const val PLAYLIST = "1b418bb8-90a7-4f87-901d-707993838346" // New arrivals
    }
}
