package app.aoide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.aoide.player.PlayerController
import app.aoide.player.StreamResolver
import app.aoide.data.Prefs
import app.aoide.ui.components.MiniPlayer
import app.aoide.ui.components.TrackMenuSheet
import app.aoide.ui.screens.AlbumScreen
import app.aoide.ui.screens.ArtistScreen
import app.aoide.ui.screens.HomeScreen
import app.aoide.ui.screens.LibraryScreen
import app.aoide.ui.screens.LikedScreen
import app.aoide.ui.screens.LocalPlaylistScreen
import app.aoide.ui.screens.LyricsScreen
import app.aoide.ui.screens.NowPlayingScreen
import app.aoide.ui.screens.PlaylistScreen
import app.aoide.ui.screens.QueueScreen
import app.aoide.ui.screens.SearchScreen
import app.aoide.ui.screens.SettingsScreen
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.delay

private data class Tab(val route: String, val label: String, val on: androidx.compose.ui.graphics.vector.ImageVector, val off: androidx.compose.ui.graphics.vector.ImageVector)
private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    Tab("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    Tab("library", "Your Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
)

@androidx.compose.ui.ExperimentalComposeUiApi
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val player by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val previewNoted by Prefs.previewNoted.collectAsState()
    val navigate: (String) -> Unit = { r -> nav.navigate(r) { launchSingleTop = true } }

    // Follow the playing record's tint while the player is open; screens set their own otherwise.
    val current = player.current
    LaunchedEffect(current?.id, infos[current?.id ?: -1]) {
        val info = current?.let { infos[it.id] }
        if (info?.isPreview == true && !previewNoted) {
            Prefs.notePreview()
            Toasts.show("This mirror serves 30-second previews. Add a subscribed instance in Settings for full songs.")
        }
    }

    Box(Modifier.fillMaxSize().background(Aoide.ground).semantics { testTagsAsResourceId = true }) {
        NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
            composable("home") { HomeScreen(navigate) }
            composable("search?q={q}", arguments = listOf(navArgument("q") { nullable = true; defaultValue = null })) { SearchScreen(it.arguments?.getString("q"), navigate) }
            composable("library") { LibraryScreen(navigate) }
            composable("liked") { LikedScreen({ nav.popBackStack() }) }
            composable("album/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { AlbumScreen(it.arguments!!.getLong("id"), { nav.popBackStack() }, navigate) }
            composable("artist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { ArtistScreen(it.arguments!!.getLong("id"), { nav.popBackStack() }, navigate) }
            composable("playlist/{uuid}") { PlaylistScreen(it.arguments!!.getString("uuid")!!, { nav.popBackStack() }, navigate) }
            composable("local/{id}") { LocalPlaylistScreen(it.arguments!!.getString("id")!!, { nav.popBackStack() }) }
            composable("settings") { SettingsScreen { nav.popBackStack() } }
        }

        // Mini player + tab bar float over the content on a scrim, as Spotify does
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Aoide.base.copy(alpha = .9f), Aoide.base), endY = 320f))) {
            if (player.current != null) MiniPlayer(AppUi.tint)
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, windowInsets = NavigationBarDefaults.windowInsets, modifier = Modifier.testTag("tab_bar")) {
                TABS.forEach { t ->
                    val selected = route.startsWith(t.route)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(if (selected) t.on else t.off, null) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Aoide.fg, selectedTextColor = Aoide.fg, unselectedIconColor = Aoide.subdued, unselectedTextColor = Aoide.subdued, indicatorColor = Color.Transparent),
                        modifier = Modifier.semantics { contentDescription = "Tab ${t.label}" }.testTag("tab_${t.route}"),
                    )
                }
            }
        }

        AnimatedVisibility(AppUi.nowPlayingOpen, enter = slideInVertically(tween(280)) { it } + fadeIn(), exit = slideOutVertically(tween(220)) { it } + fadeOut()) {
            NowPlayingScreen(AppUi.tint, navigate)
        }
        AnimatedVisibility(AppUi.lyricsOpen, enter = slideInVertically(tween(280)) { it }, exit = slideOutVertically(tween(220)) { it }) { LyricsScreen(AppUi.tint) }
        AnimatedVisibility(AppUi.queueOpen, enter = slideInVertically(tween(280)) { it }, exit = slideOutVertically(tween(220)) { it }) { QueueScreen() }

        AppUi.menuTrack?.let { t -> TrackMenuSheet(t, AppUi.menuRemove, { r -> AppUi.closeOverlays(); navigate(r) }) { AppUi.menuTrack = null } }

        ToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 132.dp).navigationBarsPadding())
    }
}

@Composable
private fun ToastHost(modifier: Modifier) {
    val toast by Toasts.current.collectAsState()
    val t = toast ?: return
    LaunchedEffect(t.first) {
        delay(3200)
        if (Toasts.current.value?.first == t.first) Toasts.clear()
    }
    Box(modifier.padding(horizontal = 24.dp).testTag("toast")) {
        Snackbar(containerColor = Aoide.accent, contentColor = Aoide.accentInk, shape = MaterialTheme.shapes.small) { Text(t.second, style = MaterialTheme.typography.labelLarge) }
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Suppress("unused")
private fun keep(n: NavHostController) = n
