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
import app.aoide.data.Downloads
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import app.aoide.ui.theme.Aoide

private enum class Filter(val label: String) { ALL("All"), PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists") }

@Composable
fun LibraryScreen(onNavigate: (String) -> Unit) {
    val lib by Library.state.collectAsState()
    val downloads by Downloads.all.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var creating by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    data class Row(val key: String, val image: String?, val title: String, val sub: String, val round: Boolean = false, val liked: Boolean = false, val route: String, val icon: ImageVector? = null)
    val rows = buildList {
        if (filter == Filter.ALL || filter == Filter.PLAYLISTS) {
            add(Row("liked", null, "Liked Songs", "Playlist · ${plural(lib.liked.size, "song")}", liked = true, route = "liked"))
            add(Row("downloads", null, "Downloads", "Offline · ${plural(downloads.size, "song")}", route = "downloads", icon = Icons.Filled.Download))
            add(Row("history", null, "History", "Recently played · ${plural(lib.recentTracks.size, "song")}", route = "history", icon = Icons.Filled.History))
            add(Row("local", null, "On this phone", "Your own music files", route = "local_files", icon = Icons.Filled.FolderOpen))
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
                IconButton(onClick = { adding = true }, modifier = Modifier.semantics { contentDescription = "Create playlist" }.testTag("create_playlist")) { Icon(Icons.Filled.Add, null, tint = Aoide.fg, modifier = Modifier.size(28.dp)) }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Filter.entries.drop(1)) { f -> Chip(f.label, filter == f) { filter = if (filter == f) Filter.ALL else f } }
            }
        }
        items(rows, key = { it.key }) { r ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { onNavigate(r.route) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("library_row"), verticalAlignment = Alignment.CenterVertically) {
                if (r.liked) LikedTile(64.dp)
                else if (r.icon != null) Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Aoide.elevated2, Aoide.elevated))), contentAlignment = Alignment.Center) { Icon(r.icon, null, tint = Aoide.fg, modifier = Modifier.size(28.dp)) }
                else Artwork(r.image, Modifier.size(64.dp), if (r.round) CircleShape else RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.sub, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (rows.size <= 4 && filter == Filter.ALL) item { EmptyState("Start your library", "Save albums and follow artists, or make a playlist with +.") }
        if (rows.isEmpty()) item { EmptyState("Nothing here yet") }
        item { Spacer(Modifier.height(160.dp)) }
    }
    if (adding) {
        AddSheet(onDismiss = { adding = false }, onCreate = { adding = false; creating = true }, onImport = { adding = false; onNavigate("import") })
    }
    if (creating) {
        app.aoide.ui.components.NameSheet("Give your playlist a name", "My Playlist #${lib.playlists.size + 1}", "Create", "playlist_name", "playlist_create", onDismiss = { creating = false }) { name ->
            val p = Library.createPlaylist(name); creating = false; Toasts.show("Created ${p.title}"); onNavigate("local/${p.id}")
        }
    }
}

/** The + sheet: a new playlist here, or one brought over from another service. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onDismiss: () -> Unit, onCreate: () -> Unit, onImport: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aoide.elevated2, scrimColor = app.aoide.ui.components.SheetScrim, dragHandle = null, modifier = Modifier.testTag("add_sheet")) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            listOf(
                Triple(Icons.Filled.Add, "Playlist" to "Build a playlist with songs from the catalogue", onCreate),
                Triple(Icons.Filled.PlaylistAdd, "Import a playlist" to "Paste a playlist link from another service; songs are matched here", onImport),
            ).forEach { (icon, text, act) ->
                Row(Modifier.fillMaxWidth().clickable(onClick = act).padding(horizontal = 20.dp, vertical = 16.dp).testTag("add_option"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Aoide.highlight), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Aoide.fg) }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(text.first, style = MaterialTheme.typography.bodyLarge)
                        Text(text.second, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
                    }
                }
            }
            HorizontalDivider(color = Aoide.rule)
        }
    }
}
