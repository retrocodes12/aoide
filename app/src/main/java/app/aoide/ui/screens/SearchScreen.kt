package app.aoide.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Catalog
import app.aoide.data.Hit
import app.aoide.data.Library
import app.aoide.data.Mood
import app.aoide.data.PlayContext
import app.aoide.data.Track
import app.aoide.player.PlayerController
import app.aoide.ui.MoodTitles
import app.aoide.ui.Resource
import app.aoide.ui.components.Artwork
import app.aoide.ui.components.Chip
import app.aoide.ui.components.EmptyState
import app.aoide.ui.components.ErrorState
import app.aoide.ui.components.SectionTitle
import app.aoide.ui.components.SkeletonRows
import app.aoide.ui.components.TrackRow
import app.aoide.ui.plural
import app.aoide.ui.reload
import app.aoide.ui.rememberResource
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.delay

/** Tiles shown until the service's own mood and genre list arrives; each is a plain search. */
private val FALLBACK = listOf(
    Triple("pop", "Pop", 0xFF8D67AB), Triple("hip hop", "Hip-Hop", 0xFFBA5D07), Triple("rock", "Rock", 0xFFE61E32), Triple("indie", "Indie", 0xFF608108),
    Triple("electronic", "Electronic", 0xFF0D73EC), Triple("jazz", "Jazz", 0xFF7358FF), Triple("r&b", "R&B", 0xFFDC148C), Triple("classical", "Classical", 0xFF1E3264),
    Triple("chill", "Chill", 0xFF27856A), Triple("workout", "Workout", 0xFF503750), Triple("focus", "Focus", 0xFFD84000), Triple("sleep", "Sleep", 0xFF1E3264),
)

private enum class Tab(val label: String) { ALL("All"), SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists") }

@Composable
fun SearchScreen(initialQuery: String?, onNavigate: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf(initialQuery ?: "") }
    var term by rememberSaveable { mutableStateOf(initialQuery ?: "") }
    var tab by rememberSaveable { mutableStateOf(Tab.ALL) }
    val keyboard = LocalSoftwareKeyboardController.current
    val lib by Library.state.collectAsState()
    val moods by rememberResource("moods") { Catalog.moods() }
    LaunchedEffect(query) {
        delay(260)
        term = query.trim()
        if (term.length > 1) Library.recordSearch(term)
    }

    Column(Modifier.testTag("search")) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("What do you want to listen to?", color = Color(0xFF5A5A5A), style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.Black) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = "Clear search" }) { Icon(Icons.Filled.Close, null, tint = Color.Black) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { term = query.trim(); keyboard?.hide() }),
                shape = RoundedCornerShape(6.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, cursorColor = Color.Black,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Search field" }.testTag("search_field"),
            )
        }
        if (term.isEmpty()) {
            val tiles = (moods as? Resource.Ready)?.value.orEmpty()
            LazyColumn {
                if (lib.recentSearches.isNotEmpty()) {
                    item { SectionTitle("Recent searches") }
                    item {
                        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(lib.recentSearches) { r -> Chip(r, false) { query = r } }
                        }
                    }
                }
                if (tiles.isNotEmpty()) {
                    tiles.groupBy { it.group }.forEach { (group, list) ->
                        item { SectionTitle(group.ifBlank { "Browse all" }) }
                        items(list.chunked(2)) { pair ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { m -> Tile(m.title, m.color, Modifier.weight(1f)) { MoodTitles.put(m.browseId + m.params, m.title); onNavigate("mood/${m.browseId}?p=${Uri.encode(m.params)}") } }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    item { SectionTitle("Browse all") }
                    items(FALLBACK.chunked(2)) { pair ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (t, label, color) -> Tile(label, color, Modifier.weight(1f)) { query = t; tab = Tab.PLAYLISTS } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                item { Spacer(Modifier.height(160.dp)) }
            }
        } else {
            Results(term, tab, { tab = it }, onNavigate)
        }
    }
}

/** A browse tile: the service's colour, a tilted record peeking from the corner in the tile's own darker shade. */
@Composable
private fun Tile(label: String, color: Long, modifier: Modifier, onClick: () -> Unit) {
    val c = Color(color.toInt())
    Box(modifier.aspectRatio(1.8f).clip(RoundedCornerShape(8.dp)).background(if (c.alpha == 0f) Aoide.elevated2 else c).clickable(onClick = onClick).padding(12.dp).testTag("browse_tile")) {
        Box(Modifier.size(64.dp).align(Alignment.BottomEnd).offset(x = 14.dp, y = 10.dp).rotate(25f).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .28f)))
        Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Results(term: String, tab: Tab, setTab: (Tab) -> Unit, onNavigate: (String) -> Unit) {
    val all by rememberResource("all", term) { Catalog.searchAll(term) }
    var retried by remember(term) { mutableStateOf(false) }
    LazyColumn(Modifier.testTag("results")) {
        item {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Tab.entries) { t -> Chip(t.label, tab == t) { setTab(t) } }
            }
        }
        val a = all
        if (a is Resource.Failed) {
            item { ErrorState(a.error) { a.reload() } }
            return@LazyColumn
        }
        if (a is Resource.Loading) {
            item { SkeletonRows(6) }
            return@LazyColumn
        }
        val res = (a as Resource.Ready).value
        // An empty answer on the very first request is usually a hiccup; ask once more before calling it a miss.
        if (res.isEmpty && !retried) {
            item { SkeletonRows(4) }
            item { LaunchedEffect(term) { delay(700); retried = true; a.reload() } }
            return@LazyColumn
        }
        if (res.isEmpty) {
            item { EmptyState("No results found for \"$term\"", "Check the spelling, or try fewer or different words.") }
            return@LazyColumn
        }
        val songs = res.tracks
        if (tab == Tab.ALL) res.top?.let { hit ->
            item { SectionTitle("Top result") }
            item { TopHit(hit, songs, term, onNavigate) }
        }
        if ((tab == Tab.ALL || tab == Tab.SONGS) && songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            val list = if (tab == Tab.SONGS) songs else songs.take(6)
            items(list, key = { "t${it.id}" }) { t -> TrackRow(t, onClick = { PlayerController.playRadio(t) }) }
        }
        if ((tab == Tab.ALL || tab == Tab.ARTISTS) && res.artists.isNotEmpty()) {
            item { SectionTitle("Artists") }
            items(res.artists.take(if (tab == Tab.ARTISTS) 40 else 4), key = { "ar${it.id}" }) { ar ->
                ResultRow(Catalog.artistPicture(ar.picture, 160), ar.name, ar.listeners?.let { "Artist · $it" } ?: "Artist", round = true) { onNavigate("artist/${ar.id}") }
            }
        }
        if ((tab == Tab.ALL || tab == Tab.ALBUMS) && res.albums.isNotEmpty()) {
            item { SectionTitle("Albums") }
            items(res.albums.take(if (tab == Tab.ALBUMS) 40 else 4), key = { "al${it.id}" }) { al ->
                ResultRow(Catalog.cover(al.cover, 160), al.title, listOfNotNull(al.type?.let { if (it == "ALBUM") "Album" else it.lowercase().replaceFirstChar(Char::uppercase) } ?: "Album", al.year.ifBlank { null }, al.primaryArtist?.name).joinToString(" · ")) { onNavigate("album/${al.id}") }
            }
        }
        if ((tab == Tab.ALL || tab == Tab.PLAYLISTS) && res.playlists.isNotEmpty()) {
            item { SectionTitle("Playlists") }
            items(res.playlists.take(if (tab == Tab.PLAYLISTS) 40 else 4), key = { "pl${it.uuid}" }) { p ->
                ResultRow(Catalog.playlistImage(p, 160), p.title, listOfNotNull("Playlist", p.creator?.name, p.numberOfTracks?.let { plural(it, "song") }).joinToString(" · ")) { onNavigate("playlist/${p.uuid}") }
            }
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

@Composable
private fun TopHit(hit: Hit, songs: List<Track>, term: String, onNavigate: (String) -> Unit) {
    keep(songs, term)
    val (img, title, sub, round, route) = when (hit) {
        is Hit.ArtistHit -> Quint(Catalog.artistPicture(hit.artist.picture, 320), hit.artist.name, hit.artist.listeners?.let { "Artist · $it" } ?: "Artist", true, "artist/${hit.artist.id}")
        is Hit.AlbumHit -> Quint(Catalog.cover(hit.album.cover, 320), hit.album.title, "Album · ${hit.album.primaryArtist?.name ?: ""}", false, "album/${hit.album.id}")
        is Hit.PlaylistHit -> Quint(Catalog.playlistImage(hit.playlist, 320), hit.playlist.title, "Playlist", false, "playlist/${hit.playlist.uuid}")
        is Hit.TrackHit -> Quint(Catalog.cover(hit.track.album?.cover, 320), hit.track.title, "Song · ${hit.track.artistNames}", false, "")
    }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Aoide.elevated).clickable {
            if (hit is Hit.TrackHit) PlayerController.playRadio(hit.track)
            else if (route.isNotEmpty()) onNavigate(route)
        }.padding(16.dp).testTag("top_hit"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(img, Modifier.size(72.dp), if (round) CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class Quint(val a: String?, val b: String, val c: String, val d: Boolean, val e: String)

@Composable
private fun ResultRow(image: String?, title: String, subtitle: String, round: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp).testTag("result_row"), verticalAlignment = Alignment.CenterVertically) {
        Artwork(image, Modifier.size(48.dp), if (round) CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Suppress("unused")
private fun keep(m: Mood) = m

@Suppress("unused_parameter")
private fun keep(songs: List<Track>, term: String) = Unit
