package app.aoide.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Catalog
import app.aoide.data.Library
import app.aoide.data.Lossless
import app.aoide.data.PlayContext
import app.aoide.data.Updates
import app.aoide.player.PlayerController
import app.aoide.ui.MoodTitles
import app.aoide.ui.Resource
import app.aoide.ui.components.CardRow
import app.aoide.ui.components.Chip
import app.aoide.ui.components.ErrorState
import app.aoide.ui.components.LikedTile
import app.aoide.ui.components.MediaCard
import app.aoide.ui.components.SectionTitle
import app.aoide.ui.components.SkeletonCards
import app.aoide.ui.components.pressable
import app.aoide.ui.reload
import app.aoide.ui.rememberResource
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val lib by Library.state.collectAsState()
    val scope = rememberCoroutineScope()
    val shelves by rememberResource("home") { Catalog.home() }
    val releases by rememberResource("releases") { Catalog.newReleases() }
    val moods by rememberResource("moods") { Catalog.moods() }
    // The listener's own history seeds the personal rows: songs like the last one played, more from its artist.
    val seed = lib.recentTracks.firstOrNull { !it.isLocal }
    val related by rememberResource("related", seed?.id) { seed?.let { runCatching { Catalog.related(it.id) }.getOrDefault(emptyList()) } ?: emptyList() }
    val seedArtist = lib.recentTracks.firstOrNull { it.primaryArtist?.id?.startsWith("UC") == true }?.primaryArtist
    val fromArtist by rememberResource("fromArtist", seedArtist?.id) { seedArtist?.let { runCatching { Catalog.artist(it.id) }.getOrNull() } }
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 5 -> "Good night"
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    // A wash of colour behind the greeting, keyed to the time of day: amber mornings, teal afternoons, violet evenings, indigo nights.
    val wash = when {
        hour < 5 -> Color(0xFF1B2140)
        hour < 12 -> Color(0xFF4A3316)
        hour < 18 -> Color(0xFF123A3C)
        else -> Color(0xFF32214A)
    }
    val homeShelves = (shelves as? Resource.Ready)?.value.orEmpty()

    // Quick grid: Liked Songs, your playlists and recent records, then the service's own lists until there are eight.
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
        for (p in homeShelves.flatMap { it.playlists }) {
            if (size >= 8) break
            if (any { it.key == p.uuid }) continue
            add(Quick(p.uuid, p.title, Catalog.playlistImage(p, 160)) { onNavigate("playlist/${p.uuid}") })
        }
        for (a in (releases as? Resource.Ready)?.value.orEmpty()) {
            if (size >= 8) break
            if (any { it.key == "a${a.id}" }) continue
            add(Quick("a${a.id}", a.title, Catalog.cover(a.cover, 160)) { onNavigate("album/${a.id}") })
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(300.dp).background(Brush.verticalGradient(listOf(wash, Aoide.ground))))
        LazyColumn(Modifier.fillMaxSize().testTag("home")) {
        item {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onNavigate("settings") }, modifier = Modifier.semantics { contentDescription = "Settings" }.testTag("settings_button")) { Icon(Icons.Outlined.Settings, null, tint = Aoide.fg) }
            }
        }
        item {
            val up by Updates.state.collectAsState()
            val rel = (up as? Updates.State.Available)?.release ?: (up as? Updates.State.Ready)?.release
            if (rel != null && !Updates.isDismissed(rel.version)) {
                var hidden by remember(rel.version) { mutableStateOf(false) }
                if (!hidden) Row(
                    Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Aoide.accent.copy(alpha = .14f)).border(1.dp, Aoide.accent.copy(alpha = .35f), RoundedCornerShape(10.dp))
                        .clickable { onNavigate("settings") }.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("update_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Aoide ${rel.version} is out", style = MaterialTheme.typography.titleSmall)
                        Text(if (up is Updates.State.Ready) "Downloaded. Tap to install." else "Tap to download and install.", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
                    }
                    IconButton(onClick = { Updates.dismiss(rel.version); hidden = true }, modifier = Modifier.semantics { contentDescription = "Dismiss update" }) { Icon(Icons.Filled.Close, null, tint = Aoide.subdued) }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { q ->
                            Row(
                                Modifier.weight(1f).height(56.dp).pressable(onClick = q.onOpen).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .12f)).testTag("quick_${q.key}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (q.liked) LikedTile(56.dp, RoundedCornerShape(0.dp)) else app.aoide.ui.components.Artwork(q.image, Modifier.size(56.dp), RoundedCornerShape(0.dp))
                                Text(q.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (quick.size < 3 && shelves is Resource.Loading) Text("Albums and playlists you open land here.", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item { SectionTitle("New releases") }
        item {
            when (val r = releases) {
                is Resource.Loading -> SkeletonCards()
                is Resource.Failed -> ErrorState(r.error) { r.reload() }
                is Resource.Ready -> {
                    val albums = r.value
                    Column {
                        albums.firstOrNull()?.let { lead ->
                            // One big card for the newest record.
                            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().pressable { onNavigate("album/${lead.id}") }.clip(RoundedCornerShape(14.dp)).background(Aoide.elevated).testTag("hero_card")) {
                                app.aoide.ui.components.Artwork(Catalog.cover(lead.cover, 640), Modifier.fillMaxWidth().height(200.dp), RoundedCornerShape(0.dp))
                                Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))))
                                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                                    Text("JUST ADDED", style = MaterialTheme.typography.labelSmall, color = Aoide.accent)
                                    Text(lead.title, style = MaterialTheme.typography.headlineSmall, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(lead.primaryArtist?.name ?: "", style = MaterialTheme.typography.bodyMedium, color = Aoide.fg.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        CardRow(albums.drop(1).take(16), { it.id }) { a ->
                            MediaCard(Catalog.cover(a.cover, 320), a.title, a.primaryArtist?.name) { onNavigate("album/${a.id}") }
                        }
                    }
                }
            }
        }

        if (lib.recentTracks.isNotEmpty()) {
            item { SectionTitle("Jump back in", onSeeAll = { onNavigate("history") }) }
            item {
                CardRow(lib.recentTracks.take(12), { it.id }) { t ->
                    MediaCard(Catalog.cover(t.album?.cover, 320), t.title, t.artistNames, tag = "recent_card") {
                        scope.launch { PlayerController.playTracks(lib.recentTracks, lib.recentTracks.indexOfFirst { it.id == t.id }, PlayContext("recent", "Recently played")) }
                    }
                }
            }
        }

        val rel = (related as? Resource.Ready)?.value.orEmpty()
        if (seed != null && rel.isNotEmpty()) {
            rel.firstOrNull { it.tracks.isNotEmpty() }?.let { s -> item { ShelfRow(s, onNavigate, title = "More like ${seed.title}", tag = "popular_card") } }
            rel.filter { it.tracks.isEmpty() }.take(2).forEach { s -> item { ShelfRow(s, onNavigate) } }
        }

        val fa = (fromArtist as? Resource.Ready)?.value
        if (fa != null && (fa.albums.isNotEmpty() || fa.singles.isNotEmpty())) {
            item { SectionTitle("More from ${fa.artist.name}", onSeeAll = { onNavigate("artist/${fa.artist.id}") }) }
            item { CardRow((fa.albums + fa.singles).distinctBy { it.id }.take(12), { it.id }) { a -> MediaCard(Catalog.cover(a.cover, 320), a.title, a.year.ifBlank { a.type?.lowercase()?.replaceFirstChar(Char::uppercase) }) { onNavigate("album/${a.id}") } } }
        }

        when (val s = shelves) {
            is Resource.Loading -> item { SkeletonCards() }
            is Resource.Failed -> item { ErrorState(s.error) { s.reload() } }
            is Resource.Ready -> s.value.forEach { shelf -> item { ShelfRow(shelf, onNavigate, tag = "home_card") } }
        }

        val tiles = (moods as? Resource.Ready)?.value.orEmpty()
        if (tiles.isNotEmpty()) {
            item { SectionTitle("Browse by mood", onSeeAll = { onNavigate("search") }) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tiles.take(14), key = { it.title + it.params }) { m -> Chip(m.title, false) { MoodTitles.put(m.browseId + m.params, m.title); onNavigate("mood/${m.browseId}?p=${Uri.encode(m.params)}") } }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
        item {
            val losslessOn by Lossless.enabled.collectAsState()
            Text(
                "Catalogue and songs from the music service. " + (if (losslessOn) "Songs marked HD play as FLAC from your lossless mirror. " else "Add a lossless mirror in Settings for FLAC. ") + "Lyrics from a community database.",
                style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(16.dp).clickable { onNavigate("settings") }.testTag("source_note"),
            )
            Spacer(Modifier.height(140.dp))
        }
        }
    }
}
