package app.aoide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Album
import app.aoide.data.Catalog
import app.aoide.data.Instances
import app.aoide.data.Playlist
import app.aoide.data.Library
import app.aoide.data.PlayContext
import app.aoide.data.Track
import app.aoide.player.PlayerController
import app.aoide.ui.Resource
import app.aoide.ui.Toasts
import app.aoide.ui.components.CardRow
import app.aoide.ui.components.ErrorState
import app.aoide.ui.components.LikedTile
import app.aoide.ui.components.MediaCard
import app.aoide.ui.components.SectionTitle
import app.aoide.ui.components.SkeletonCards
import app.aoide.ui.rememberResource
import app.aoide.ui.reload
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.launch
import java.util.Calendar

const val NEW_ARRIVALS = "1b418bb8-90a7-4f87-901d-707993838346"

fun albumsFromTracks(tracks: List<Track>): List<Album> {
    val seen = LinkedHashMap<Long, Album>()
    for (t in tracks) {
        val a = t.album ?: continue
        if (seen.containsKey(a.id)) continue
        seen[a.id] = Album(id = a.id, title = a.title, cover = a.cover, vibrantColor = a.vibrantColor, artist = t.primaryArtist)
    }
    return seen.values.toList()
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val lib by Library.state.collectAsState()
    val scope = rememberCoroutineScope()
    val arrivals by rememberResource("arrivals") { Catalog.playlist(NEW_ARRIVALS) }
    val seed = lib.recentTracks.firstOrNull()
    val because by rememberResource("because", seed?.id) { seed?.let { Catalog.recommendations(it.id) } ?: emptyList() }
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 5 -> "Good night"
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }

    // Quick grid: Liked Songs, then your playlists and recent records, up to 8
    data class Quick(val key: String, val title: String, val image: String?, val liked: Boolean = false, val onOpen: () -> Unit)
    val quick = buildList {
        add(Quick("liked", "Liked Songs", null, liked = true) { onNavigate("liked") })
        lib.playlists.take(2).forEach { p -> add(Quick(p.id, p.title, Catalog.cover(p.tracks.firstOrNull()?.album?.cover, 160)) { onNavigate("local/${p.id}") }) }
        lib.followedPlaylists.take(2).forEach { p -> add(Quick(p.uuid, p.title, Catalog.playlistImage(p, 160)) { onNavigate("playlist/${p.uuid}") }) }
        for (a in lib.recentAlbums) {
            if (size >= 8) break
            if (any { it.key == "a${a.id}" }) continue
            add(Quick("a${a.id}", a.title, Catalog.cover(a.cover, 160)) { onNavigate("album/${a.id}") })
        }
    }

    LazyColumn(Modifier.testTag("home")) {
        item {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onNavigate("settings") }, modifier = Modifier.semantics { contentDescription = "Settings" }.testTag("settings_button")) { Icon(Icons.Outlined.Settings, null, tint = Aoide.fg) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { q ->
                            Row(
                                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(4.dp)).background(Aoide.highlight).clickable(onClick = q.onOpen).testTag("quick_${q.key}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (q.liked) LikedTile(56.dp, RoundedCornerShape(0.dp)) else app.aoide.ui.components.Artwork(q.image, Modifier.size(56.dp), RoundedCornerShape(0.dp))
                                Text(q.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (quick.size < 3) Text("Albums and playlists you open land here.", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item { SectionTitle("New releases", onSeeAll = { onNavigate("playlist/$NEW_ARRIVALS") }) }
        item {
            when (val r = arrivals) {
                is Resource.Loading -> SkeletonCards()
                is Resource.Failed -> ErrorState(r.error) { r.reload() }
                is Resource.Ready -> {
                    val albums = albumsFromTracks(r.value.second)
                    Column {
                        albums.firstOrNull()?.let { lead ->
                            // Apple-style hero: one big card for the newest record
                            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Aoide.elevated).clickable { onNavigate("album/${lead.id}") }.testTag("hero_card")) {
                                app.aoide.ui.components.Artwork(Catalog.cover(lead.cover, 640), Modifier.fillMaxWidth().height(200.dp), RoundedCornerShape(0.dp))
                                Box(Modifier.fillMaxWidth().height(200.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = .75f)))))
                                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                                    Text("JUST ADDED", style = MaterialTheme.typography.labelSmall, color = Aoide.accent)
                                    Text(lead.title, style = MaterialTheme.typography.headlineSmall, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(lead.primaryArtist?.name ?: "", style = MaterialTheme.typography.bodyMedium, color = Aoide.fg.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        CardRow(albums.drop(1).take(12), { it.id }) { a ->
                            MediaCard(Catalog.cover(a.cover, 320), a.title, a.primaryArtist?.name) { onNavigate("album/${a.id}") }
                        }
                    }
                }
            }
        }

        if (seed != null && because is Resource.Ready && (because as Resource.Ready).value.isNotEmpty()) {
            item { SectionTitle("More like ${seed.title}") }
            item {
                val tracks = (because as Resource.Ready).value
                CardRow(albumsFromTracks(tracks).take(12), { it.id }) { a ->
                    MediaCard(Catalog.cover(a.cover, 320), a.title, a.primaryArtist?.name) { onNavigate("album/${a.id}") }
                }
            }
        }

        if (lib.recentTracks.isNotEmpty()) {
            item { SectionTitle("Recently played", onSeeAll = { onNavigate("library") }) }
            item {
                CardRow(lib.recentTracks.take(12), { it.id }) { t ->
                    MediaCard(Catalog.cover(t.album?.cover, 320), t.title, t.artistNames, tag = "recent_card") {
                        scope.launch { PlayerController.playTracks(lib.recentTracks, lib.recentTracks.indexOfFirst { it.id == t.id }, PlayContext("recent", "Recently played")) }
                    }
                }
            }
        }

        listOf("chill" to "Chill", "focus" to "Focus", "workout" to "Workout", "party" to "Party").forEach { (term, title) ->
            item { MoodRow(term, title, onNavigate) }
        }
        item { Spacer(Modifier.height(24.dp)) }
        item {
            val health by Instances.health.collectAsState()
            val source by Instances.source.collectAsState()
            val down = remember(health, source) { Instances.mirrorsDown() }
            Text(
                (if (down) "Every Monochrome mirror is down right now; browsing TIDAL's catalogue directly, previews only. " else "Catalogue from Monochrome mirrors. ") + "Lyrics from lrclib.",
                style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(16.dp).clickable { onNavigate("settings") }.testTag("source_note"),
            )
            Spacer(Modifier.height(140.dp))
        }
    }
}

@Composable
private fun MoodRow(term: String, title: String, onNavigate: (String) -> Unit) {
    val res by rememberResource("mood", term) { englishFirst(Catalog.searchPlaylists(term, 16)).take(12) }
    val r = res
    if (r is Resource.Failed || (r is Resource.Ready && r.value.isEmpty())) return
    Column {
        SectionTitle(title)
        when (r) {
            is Resource.Ready -> CardRow(r.value, { it.uuid }) { p ->
                MediaCard(Catalog.playlistImage(p, 320), p.title, p.cleanDescription.ifBlank { p.numberOfTracks?.let { "$it songs" } }) { onNavigate("playlist/${p.uuid}") }
            }
            else -> SkeletonCards()
        }
    }
}

/** Mood rows should not open with Danish and Portuguese titles; prefer playlists whose words are plain ASCII. */
fun englishFirst(list: List<Playlist>): List<Playlist> {
    val ascii = Regex("^[\\x20-\\x7E]*$")
    val (en, other) = list.partition { ascii.matches(it.title) && ascii.matches(it.cleanDescription.take(80)) }
    return en + other
}

@Composable
fun QuickHint() { Box(Modifier) }

@Suppress("unused")
private fun toastUnused() = Toasts
