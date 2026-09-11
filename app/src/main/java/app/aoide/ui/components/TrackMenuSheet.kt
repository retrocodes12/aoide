package app.aoide.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Radio
import androidx.compose.ui.platform.LocalContext
import app.aoide.data.Downloads
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Catalog
import app.aoide.data.Library
import app.aoide.data.Track
import app.aoide.player.PlayerController
import app.aoide.ui.AppUi
import app.aoide.ui.Toasts
import app.aoide.ui.theme.Aoide

/** The ··· sheet for a song. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuSheet(track: Track, onRemove: (() -> Unit)?, onNavigate: (String) -> Unit, onDismiss: () -> Unit) {
    val lib by Library.state.collectAsState()
    val liked = lib.isLiked(track.id)
    val downloads by Downloads.all.collectAsState()
    val kept = downloads[track.id]
    val context = LocalContext.current
    var pickPlaylist by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aoide.elevated2, scrimColor = SheetScrim, dragHandle = null, modifier = Modifier.testTag("track_menu")) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(Catalog.cover(track.album?.cover, 160), Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artistNames, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = Aoide.rule)
            if (!pickPlaylist) {
                MenuItem(if (liked) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline, if (liked) "Remove from Liked Songs" else "Add to Liked Songs", tint = if (liked) Aoide.accent else Aoide.subdued) {
                    Toasts.show(if (Library.toggleLike(track)) "Added to Liked Songs" else "Removed from Liked Songs"); onDismiss()
                }
                MenuItem(Icons.Filled.PlaylistAdd, "Add to playlist") { pickPlaylist = true }
                MenuItem(Icons.Filled.QueueMusic, "Add to queue") { PlayerController.enqueueLast(track); Toasts.show("Added to queue"); onDismiss() }
                MenuItem(Icons.Filled.SkipNext, "Play next") { PlayerController.enqueueNext(track); Toasts.show("Playing next"); onDismiss() }
                if (!track.isLocal) MenuItem(Icons.Filled.Radio, "Start radio", subtitle = "Songs like this one, from the music service") { PlayerController.playRadio(track); onDismiss() }
                if (!track.isLocal) {
                    if (kept != null) MenuItem(Icons.Filled.DownloadDone, "Remove download", subtitle = kept.label, tint = Aoide.accent) { Downloads.remove(track.id); Toasts.show("Download removed"); onDismiss() }
                    else if (Downloads.isQueued(track.id)) MenuItem(Icons.Outlined.ArrowCircleDown, "Downloading…", subtitle = "In the queue") { Downloads.cancel(track.id); Toasts.show("Download cancelled"); onDismiss() }
                    else MenuItem(Icons.Outlined.ArrowCircleDown, "Download", subtitle = "Keep the full song on this phone") { Downloads.enqueue(listOf(track)); Toasts.show("Downloading ${track.title}"); onDismiss() }
                }
                MenuItem(Icons.Filled.Share, "Share") { app.aoide.ui.components.TrackActions.share(context, track); onDismiss() }
                if (kept != null) MenuItem(Icons.Filled.RingVolume, "Set as ringtone") { Toasts.show(app.aoide.ui.components.TrackActions.setRingtone(context, kept)); onDismiss() }
                MenuItem(Icons.Filled.Bedtime, "Sleep timer", subtitle = app.aoide.player.SleepTimer.label()) { onDismiss(); AppUi.sleepOpen = true }
                track.album?.let { a -> MenuItem(Icons.Filled.Album, "Go to album") { onNavigate("album/${a.id}"); onDismiss() } }
                track.primaryArtist?.let { a -> MenuItem(Icons.Filled.Person, "Go to artist") { onNavigate("artist/${a.id}"); onDismiss() } }
                if (onRemove != null) MenuItem(Icons.Filled.RemoveCircleOutline, "Remove from this playlist") { onRemove(); onDismiss() }
            } else {
                Text("Add to playlist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                lib.playlists.forEach { p ->
                    MenuItem(Icons.Filled.QueueMusic, p.title, subtitle = "${p.tracks.size} songs") {
                        Library.addToPlaylist(p.id, track); Toasts.show("Added to ${p.title}"); onDismiss()
                    }
                }
                val create = {
                    val p = Library.createPlaylist(newName.ifBlank { "My Playlist #${lib.playlists.size + 1}" }, listOf(track))
                    Toasts.show("Added to ${p.title}"); onDismiss()
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AoideField(newName, { newName = it }, "New playlist name", Modifier.weight(1f).testTag("new_playlist_name"), onDone = create)
                    TextButton(onClick = create, modifier = Modifier.testTag("new_playlist_create")) { Text("Create", color = Aoide.accent) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, subtitle: String? = null, tint: androidx.compose.ui.graphics.Color = Aoide.subdued, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp).testTag("menu_item"), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
        }
    }
}

/** Adds a ".remove" when a screen wants a row's ··· to offer removal. */
fun AppUi.menuFor(track: Track) = openMenu(track)
