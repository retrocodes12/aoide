package app.aoide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Catalog
import app.aoide.data.Library
import app.aoide.ui.Toasts
import app.aoide.ui.plural
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import app.aoide.ui.components.Artwork
import app.aoide.ui.components.Chip
import app.aoide.ui.components.EmptyState
import app.aoide.ui.components.LikedTile
import app.aoide.ui.theme.Aoide

private enum class Filter(val label: String) { ALL("All"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists") }

@Composable
fun LibraryScreen(onNavigate: (String) -> Unit) {
    val lib by Library.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var creating by remember { mutableStateOf(false) }
    data class Row(val key: String, val image: String?, val title: String, val sub: String, val round: Boolean = false, val liked: Boolean = false, val route: String)
    val rows = buildList {
        if (filter == Filter.ALL || filter == Filter.PLAYLISTS) {
            add(Row("liked", null, "Liked Songs", "Playlist · ${plural(lib.liked.size, "song")}", liked = true, route = "liked"))
            lib.playlists.forEach { add(Row(it.id, Catalog.cover(it.tracks.firstOrNull()?.album?.cover, 160), it.title, "Playlist · ${plural(it.tracks.size, "song")}", route = "local/${it.id}")) }
            lib.followedPlaylists.forEach { add(Row(it.uuid, Catalog.playlistImage(it, 160), it.title, "Playlist" + (it.numberOfTracks?.let { n -> " · ${plural(n, "song")}" } ?: ""), route = "playlist/${it.uuid}")) }
        }
        if (filter == Filter.ALL || filter == Filter.ALBUMS) lib.albums.forEach { add(Row("a${it.id}", Catalog.cover(it.cover, 160), it.title, "Album · ${it.primaryArtist?.name ?: ""}", route = "album/${it.id}")) }
        if (filter == Filter.ALL || filter == Filter.ARTISTS) lib.artists.forEach { add(Row("r${it.id}", Catalog.artistPicture(it.picture, 160), it.name, "Artist", round = true, route = "artist/${it.id}")) }
    }
    LazyColumn(Modifier.testTag("library")) {
        item {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }, modifier = Modifier.semantics { contentDescription = "Create playlist" }.testTag("create_playlist")) { Icon(Icons.Filled.Add, null, tint = Aoide.fg, modifier = Modifier.size(28.dp)) }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Filter.entries.drop(1)) { f -> Chip(f.label, filter == f) { filter = if (filter == f) Filter.ALL else f } }
            }
        }
        items(rows, key = { it.key }) { r ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { onNavigate(r.route) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("library_row"), verticalAlignment = Alignment.CenterVertically) {
                if (r.liked) LikedTile(64.dp) else Artwork(r.image, Modifier.size(64.dp), if (r.round) CircleShape else RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.sub, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (rows.size <= 1 && filter == Filter.ALL) item { EmptyState("Start your library", "Save albums and follow artists, or make a playlist with +.") }
        if (rows.isEmpty()) item { EmptyState("Nothing here yet") }
        item { Spacer(Modifier.height(160.dp)) }
    }
    if (creating) {
        var name by remember { mutableStateOf("My Playlist #${lib.playlists.size + 1}") }
        val create = { val p = Library.createPlaylist(name); creating = false; Toasts.show("Created ${p.title}"); onNavigate("local/${p.id}") }
        AlertDialog(onDismissRequest = { creating = false }, containerColor = Aoide.elevated2, title = { Text("Give your playlist a name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { create() }), modifier = Modifier.testTag("playlist_name")) },
            confirmButton = { TextButton(onClick = create, modifier = Modifier.testTag("playlist_create")) { Text("Create", color = Aoide.accent) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel", color = Aoide.subdued) } })
    }
}
