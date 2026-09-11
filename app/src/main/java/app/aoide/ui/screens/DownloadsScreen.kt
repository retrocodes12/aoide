package app.aoide.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.aoide.data.Catalog
import app.aoide.data.Downloads
import app.aoide.data.LocalMedia
import app.aoide.data.PlayContext
import app.aoide.data.Track
import app.aoide.player.PlayerController
import app.aoide.ui.AppUi
import app.aoide.ui.Resource
import app.aoide.ui.Toasts
import app.aoide.ui.components.Artwork
import app.aoide.ui.components.EmptyState
import app.aoide.ui.components.OutlinePill
import app.aoide.ui.components.PillButton
import app.aoide.ui.components.SectionTitle
import app.aoide.ui.components.TrackRow
import app.aoide.ui.components.formatBytes
import app.aoide.ui.plural
import app.aoide.ui.rememberResource
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.Tint

/** Liked Songs' head with an icon tile instead of the heart: the shape every built-in collection shares. */
@Composable
internal fun IconHead(icon: ImageVector, title: String, meta: String, playing: Boolean, canPlay: Boolean, onBack: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Aoide.accent.copy(alpha = .7f), 0.64f to Aoide.ground, 1f to Aoide.ground, endY = 900f))) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(200.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(Aoide.likedGradient)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Aoide.accentInk.copy(alpha = .85f), modifier = Modifier.size(84.dp)) }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(top = 22.dp).testTag("detail_title"), textAlign = TextAlign.Center)
        Text(meta, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 24.dp, end = 24.dp), textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (playing) "Pause" else "Play", if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, filled = true, enabled = canPlay, modifier = Modifier.weight(1f).testTag("play_fab"), onClick = onPlay)
            PillButton("Shuffle", Icons.Filled.Shuffle, filled = false, enabled = canPlay, modifier = Modifier.weight(1f).testTag("shuffle"), onClick = onShuffle)
        }
    }
}

/** Songs kept on the phone, what is still coming down, and what failed. */
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val all by Downloads.all.collectAsState()
    val queue by Downloads.queue.collectAsState()
    val current by Downloads.current.collectAsState()
    val failed by Downloads.failed.collectAsState()
    val player by PlayerController.state.collectAsState()
    val tracks = remember(all) { all.values.sortedByDescending { it.savedAt }.map { it.track } }
    val ctx = PlayContext("downloads", "Downloads", "downloads")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    LaunchedEffect(Unit) { AppUi.page = Tint.from(Aoide.accent) }
    LazyColumn(Modifier.testTag("downloads_screen")) {
        item {
            IconHead(
                Icons.Filled.Download, "Downloads", "${plural(tracks.size, "song")} · ${formatBytes(all.values.sumOf { it.bytes })} · plays with no connection at all",
                thisPlaying, tracks.isNotEmpty(), onBack,
                onPlay = { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(tracks, 0, ctx) },
                onShuffle = { if (!player.shuffle) PlayerController.toggleShuffle(); PlayerController.playTracks(tracks, tracks.indices.random(), ctx) },
                trailing = { if (tracks.isNotEmpty()) OutlinePill("Remove all") { AppUi.ask("Remove every download?", "Remove", "Frees ${formatBytes(all.values.sumOf { it.bytes })}. Songs stay in your library and play online.") { Downloads.removeAll(); Toasts.show("Downloads removed") } } },
            )
        }
        val cur = current
        if (cur != null || queue.isNotEmpty()) {
            item { SectionTitle("Downloading", Modifier.padding(top = 8.dp)) }
            if (cur != null) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("download_progress"), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(Catalog.cover(cur.track.album?.cover, 160), Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur.track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (cur.fraction <= 0f) "Finding the full song…" else "${(cur.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
                        LinearProgressIndicator(progress = { cur.fraction }, color = Aoide.accent, trackColor = Aoide.elevated2, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp))
                    }
                }
            }
            items(queue, key = { "q${it.id}" }) { t ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(Catalog.cover(t.album?.cover, 160), Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Waiting · ${t.artistNames}", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { Downloads.cancel(t.id) }, modifier = Modifier.semantics { contentDescription = "Cancel ${t.title}" }) { Icon(Icons.Filled.Cancel, null, tint = Aoide.subdued) }
                }
            }
        }
        if (failed.isNotEmpty()) {
            item { SectionTitle("Couldn't save", Modifier.padding(top = 8.dp)) }
            items(failed.entries.toList(), key = { "f${it.key}" }) { (id, reason) ->
                val t = tracks.find { it.id == id } ?: app.aoide.player.TrackRegistry.get(id)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("download_failed"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t?.title ?: "Song $id", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (t != null) IconButton(onClick = { Downloads.enqueue(listOf(t)) }, modifier = Modifier.semantics { contentDescription = "Retry ${t.title}" }) { Icon(Icons.Filled.Refresh, null, tint = Aoide.accent) }
                }
            }
        }
        if (tracks.isEmpty() && cur == null && queue.isEmpty()) item { EmptyState("Nothing saved yet", "Use ··· on any song, or the download arrow on an album or playlist. Downloads come from YouTube Music, so a song needs a full-length match.") }
        else if (tracks.isNotEmpty()) item { SectionTitle("Saved", Modifier.padding(top = 8.dp)) }
        items(tracks, key = { it.id }) { t ->
            TrackRow(t, subtitle = "${all[t.id]?.label ?: ""} · ${t.artistNames}", onClick = { PlayerController.playTracks(tracks, tracks.indexOf(t), ctx) })
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

/** Music already on the phone, read from MediaStore. */
@Composable
fun LocalFilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    var tick by remember { mutableStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; tick++ }
    val scan by rememberResource("local", tick, granted) { if (granted) LocalMedia.scan(context) else emptyList() }
    val player by PlayerController.state.collectAsState()
    val ctx = PlayContext("local", "On this phone", "local_files")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    val tracks: List<Track> = (scan as? Resource.Ready)?.value ?: emptyList()
    LaunchedEffect(Unit) { AppUi.page = Tint.from(Aoide.accent) }
    LazyColumn(Modifier.testTag("local_files_screen")) {
        item {
            IconHead(
                Icons.Filled.FolderOpen, "On this phone", if (granted) "${plural(tracks.size, "song")} found in your music folders" else "Aoide can play the music files already on this phone",
                thisPlaying, tracks.isNotEmpty(), onBack,
                onPlay = { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(tracks, 0, ctx) },
                onShuffle = { if (!player.shuffle) PlayerController.toggleShuffle(); PlayerController.playTracks(tracks, tracks.indices.random(), ctx) },
                trailing = { if (granted) OutlinePill("Rescan") { tick++ } },
            )
        }
        if (!granted) item { EmptyState("Allow access to your music", "Aoide only reads audio files; nothing is uploaded anywhere.", action = { PillButton("Allow", Icons.Filled.FolderOpen, filled = true, modifier = Modifier.testTag("allow_media")) { ask.launch(permission) } }) }
        else if (scan is Resource.Loading) item { Text("Scanning…", color = Aoide.subdued, modifier = Modifier.padding(16.dp)) }
        else if (tracks.isEmpty()) item { EmptyState("No music files found", "Songs longer than 30 seconds in your Music or Download folders show up here.") }
        items(tracks, key = { it.id }) { t -> TrackRow(t, subtitle = listOfNotNull(t.artistNames.takeIf { it.isNotBlank() }, t.album?.title?.takeIf { it.isNotBlank() }).joinToString(" · "), onClick = { PlayerController.playTracks(tracks, tracks.indexOf(t), ctx) }) }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

@Suppress("unused")
private val keepColor = Color.Transparent
