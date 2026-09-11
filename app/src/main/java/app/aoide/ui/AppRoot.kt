package app.aoide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import app.aoide.ui.components.AoideIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.aoide.data.Instances
import app.aoide.data.Prefs
import app.aoide.data.Updates
import app.aoide.player.PlayerController
import app.aoide.player.StreamResolver
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
import app.aoide.ui.screens.DownloadsScreen
import app.aoide.ui.screens.LocalFilesScreen
import app.aoide.ui.screens.HistoryScreen
import app.aoide.ui.screens.EqualizerScreen
import app.aoide.ui.screens.ImportScreen
import app.aoide.ui.components.SleepSheet
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.delay

private data class Tab(val route: String, val label: String, val on: androidx.compose.ui.graphics.vector.ImageVector, val off: androidx.compose.ui.graphics.vector.ImageVector)
private val TABS = listOf(
    Tab("home", "Home", AoideIcons.Home, AoideIcons.HomeOutline),
    Tab("search", "Search", AoideIcons.SearchBold, AoideIcons.Search),
    Tab("library", "Your Library", AoideIcons.Library, AoideIcons.LibraryOutline),
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

    LaunchedEffect(Unit) {
        Instances.probe()
        // A moment after the mirrors are probed, so the first screen's requests go first.
        delay(2500)
        if (Updates.autoCheck) Updates.check()
    }
    val pending = TestNav.request
    LaunchedEffect(pending) { if (pending != null) { TestNav.request = null; navigate(pending) } }

    val current = player.current
    LaunchedEffect(current?.id, infos[current?.id ?: -1]) {
        val info = current?.let { infos[it.id] }
        if (info?.isPreview == true && !previewNoted) {
            Prefs.notePreview()
            Toasts.show(if (Prefs.youtubeSource.value) "No match on YouTube Music for this song, so it plays as a 30-second preview." else "Songs play as 30-second previews. Turn on full songs in Settings.")
        }
    }

    Box(Modifier.fillMaxSize().background(Aoide.ground).semantics { testTagsAsResourceId = true }) {
        // iOS's push: a detail screen slides in from the right over a fading parent, and slides back out on pop.
        // The three tabs crossfade instead, as Spotify's do.
        val push = spring<IntOffset>(dampingRatio = 1f, stiffness = 900f)
        NavHost(
            nav, startDestination = "home", modifier = Modifier.fillMaxSize(),
            enterTransition = { slideInHorizontally(push) { it / 3 } + fadeIn(tween(180)) },
            exitTransition = { slideOutHorizontally(push) { -it / 10 } + fadeOut(tween(160)) },
            popEnterTransition = { slideInHorizontally(push) { -it / 10 } + fadeIn(tween(160)) },
            popExitTransition = { slideOutHorizontally(push) { it / 3 } + fadeOut(tween(160)) },
        ) {
            val tab = fadeIn(tween(180))
            val tabOut = fadeOut(tween(140))
            composable("home", enterTransition = { tab }, exitTransition = { tabOut }, popEnterTransition = { tab }, popExitTransition = { tabOut }) { HomeScreen(navigate) }
            composable("search?q={q}", arguments = listOf(navArgument("q") { nullable = true; defaultValue = null }), enterTransition = { tab }, exitTransition = { tabOut }, popEnterTransition = { tab }, popExitTransition = { tabOut }) { SearchScreen(it.arguments?.getString("q"), navigate) }
            composable("library", enterTransition = { tab }, exitTransition = { tabOut }, popEnterTransition = { tab }, popExitTransition = { tabOut }) { LibraryScreen(navigate) }
            composable("liked") { LikedScreen({ nav.popBackStack() }) }
            composable("album/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { AlbumScreen(it.arguments!!.getLong("id"), { nav.popBackStack() }, navigate) }
            composable("artist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { ArtistScreen(it.arguments!!.getLong("id"), { nav.popBackStack() }, navigate) }
            composable("playlist/{uuid}") { PlaylistScreen(it.arguments!!.getString("uuid")!!, { nav.popBackStack() }, navigate) }
            composable("local/{id}") { LocalPlaylistScreen(it.arguments!!.getString("id")!!, { nav.popBackStack() }) }
            composable("settings") { SettingsScreen({ nav.popBackStack() }, navigate) }
            composable("downloads") { DownloadsScreen { nav.popBackStack() } }
            composable("local_files") { LocalFilesScreen { nav.popBackStack() } }
            composable("history") { HistoryScreen { nav.popBackStack() } }
            composable("equalizer") { EqualizerScreen { nav.popBackStack() } }
            composable("import?link={link}", arguments = listOf(navArgument("link") { nullable = true; defaultValue = null })) { ImportScreen(it.arguments?.getString("link"), { nav.popBackStack() }, navigate) }
        }

        // Mini player + tab bar float over the content on a tall fade, so rows are not sliced mid-height where they pass under
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 0.22f to Aoide.ground.copy(alpha = .94f), 0.4f to Aoide.ground, 1f to Aoide.base))) {
            Spacer(Modifier.height(72.dp))
            if (player.current != null) MiniPlayer(AppUi.player)
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

        AnimatedVisibility(AppUi.nowPlayingOpen, enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(120)), exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) { it } + fadeOut(tween(160))) {
            NowPlayingScreen(AppUi.player, navigate)
        }
        AnimatedVisibility(AppUi.lyricsOpen, enter = slideInVertically(tween(280)) { it }, exit = slideOutVertically(tween(220)) { it }) { LyricsScreen(AppUi.player) }
        AnimatedVisibility(AppUi.queueOpen, enter = slideInVertically(tween(280)) { it }, exit = slideOutVertically(tween(220)) { it }) { QueueScreen() }

        AppUi.menuTrack?.let { t -> TrackMenuSheet(t, AppUi.menuRemove, { r -> AppUi.closeOverlays(); navigate(r) }) { AppUi.menuTrack = null } }
        AppUi.confirm?.let { c -> ConfirmSheet(c) { AppUi.confirm = null } }
        if (AppUi.sleepOpen) SleepSheet { AppUi.sleepOpen = false }

        ToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = if (AppUi.nowPlayingOpen) 200.dp else 132.dp).navigationBarsPadding())
    }
}

/** Bottom-sheet confirmation, so destructive actions never fall back to a system dialog. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(c: Confirm, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aoide.elevated2, scrimColor = app.aoide.ui.components.SheetScrim, dragHandle = null, modifier = Modifier.testTag("confirm_sheet")) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
            if (c.body != null) Text(c.body, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(Aoide.highlight).clickable(onClick = onDismiss).testTag("confirm_cancel"), contentAlignment = Alignment.Center) { Text("Cancel", style = MaterialTheme.typography.labelLarge, color = Aoide.fg) }
                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(Aoide.accent).clickable { c.onConfirm(); onDismiss() }.testTag("confirm_ok"), contentAlignment = Alignment.Center) { Text(c.action, style = MaterialTheme.typography.labelLarge, color = Aoide.accentInk) }
            }
        }
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
    Box(modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(10.dp)).background(Aoide.elevated2).padding(horizontal = 16.dp, vertical = 12.dp).testTag("toast")) {
        Text(t.second, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = Aoide.fg)
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Suppress("unused")
private fun keep(n: NavHostController) = n
