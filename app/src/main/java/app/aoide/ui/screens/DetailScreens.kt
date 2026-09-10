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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import app.aoide.data.Album
import app.aoide.data.Catalog
import app.aoide.data.Library
import app.aoide.data.PlayContext
import app.aoide.data.Track
import app.aoide.data.formatLength
import app.aoide.player.PlayerController
import app.aoide.ui.AppUi
import app.aoide.ui.Resource
import app.aoide.ui.Toasts
import app.aoide.ui.components.Artwork
import app.aoide.ui.components.CardRow
import app.aoide.ui.components.Chip
import app.aoide.ui.components.DetailHeader
import app.aoide.ui.components.EmptyState
import app.aoide.ui.components.ErrorState
import app.aoide.ui.components.LikedTile
import app.aoide.ui.components.MediaCard
import app.aoide.ui.components.OutlinePill
import app.aoide.ui.components.PlayFab
import app.aoide.ui.components.SectionTitle
import app.aoide.ui.components.SkeletonRows
import app.aoide.ui.components.TrackRow
import app.aoide.ui.rememberResource
import app.aoide.ui.reload
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.adjustTint
import app.aoide.ui.theme.tintOf
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sample a header tint from artwork when the catalogue gives no vibrant colour. */
@Composable
fun rememberImageTint(url: String?, fallback: Color): Color {
    val ctx = LocalContext.current
    var tint by remember(url) { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val c = withContext(Dispatchers.IO) {
            runCatching {
                val result = ImageLoader(ctx).execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(96).build())
                val bmp = result.image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bmp).generate()
                (p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb?.let { Color(it) }
            }.getOrNull()
        }
        if (c != null) tint = adjustTint(c)
    }
    return tint
}

@Composable
fun AlbumScreen(id: Long, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val res by rememberResource("album", id) { Catalog.album(id) }
    val similar by rememberResource("similar", id) { Catalog.similarAlbums(id) }
    val player by PlayerController.state.collectAsState()
    val lib by Library.state.collectAsState()
    val r = res
    val album = (r as? Resource.Ready)?.value?.first
    val tracks = (r as? Resource.Ready)?.value?.second.orEmpty()
    val tint = tintOf(album?.vibrantColor)
    LaunchedEffect(album) { album?.let { Library.recordAlbum(it); AppUi.tint = tint } }
    val ctx = PlayContext("album", album?.title ?: "", "album/$id")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    if (r is Resource.Failed) return Column { BackRow(onBack); ErrorState(r.error) { r.reload() } }
    LazyColumn(Modifier.testTag("album_screen")) {
        item {
            DetailHeader(
                tint = tint, image = Catalog.cover(album?.cover, 640), title = album?.title ?: "",
                artist = album?.primaryArtist?.let { a ->
                    { Text(a.name, style = MaterialTheme.typography.titleMedium, color = Aoide.accent, modifier = Modifier.clickable { onNavigate("artist/${a.id}") }) }
                },
                badge = album?.let { a -> if (a.audioQuality == "HI_RES_LOSSLESS") "Hi-Res Lossless" else if (a.audioQuality == "LOSSLESS") "Lossless" else null },
                meta = album?.let { a -> listOfNotNull(a.type?.let { if (it == "ALBUM") "Album" else it.lowercase().replaceFirstChar(Char::uppercase) } ?: "Album", a.year.ifBlank { null }, "${tracks.size} songs", tracks.sumOf { it.duration }.takeIf { it > 0 }?.let(::formatLength)).joinToString(" · ") } ?: "Album",
                onBack = onBack,
                leftActions = {
                    if (album != null) {
                        val saved = lib.hasAlbum(album.id)
                        IconButton(onClick = { Toasts.show(if (Library.toggleAlbum(album)) "Added to Your Library" else "Removed from Your Library") }, modifier = Modifier.semantics { contentDescription = if (saved) "Remove from Your Library" else "Save to Your Library" }.testTag("save_album")) {
                            Icon(if (saved) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline, null, tint = if (saved) Aoide.accent else Aoide.subdued, modifier = Modifier.size(26.dp))
                        }
                    }
                },
                shuffle = { if (!player.shuffle) PlayerController.toggleShuffle(); PlayerController.playTracks(tracks, (tracks.indices).random(), ctx) },
                playing = thisPlaying, canPlay = tracks.isNotEmpty(),
                onPlay = { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(tracks, 0, ctx) },
            )
        }
        if (r is Resource.Loading) item { SkeletonRows(8) }
        items(tracks.withIndex().toList(), key = { it.value.id }) { (i, t) -> TrackRow(t, showArt = false, number = i + 1, onClick = { PlayerController.playTracks(tracks, i, ctx) }) }
        if (album != null) {
            item {
                Column(Modifier.padding(16.dp)) {
                    album.releaseDate?.let { Text(prettyDate(it), style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued) }
                    album.copyright?.let { Text(if (it.startsWith("©") || it.startsWith("(")) it else "© $it", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued) }
                }
            }
        }
        val sim = (similar as? Resource.Ready)?.value.orEmpty()
        if (sim.isNotEmpty()) {
            item { SectionTitle("You might also like") }
            item { CardRow(sim.take(12), { it.id }) { a -> MediaCard(Catalog.cover(a.cover, 320), a.title, a.primaryArtist?.name) { onNavigate("album/${a.id}") } } }
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

fun prettyDate(iso: String): String = runCatching {
    val (y, m, d) = iso.take(10).split("-").map { it.toInt() }
    val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    "$d ${months[m - 1]} $y"
}.getOrDefault(iso.take(10))

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.statusBarsPadding().padding(4.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Aoide.fg) } }
}

@Composable
fun PlaylistScreen(uuid: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val res by rememberResource("playlist", uuid) { Catalog.playlist(uuid) }
    val player by PlayerController.state.collectAsState()
    val lib by Library.state.collectAsState()
    val r = res
    val p = (r as? Resource.Ready)?.value?.first
    val tracks = (r as? Resource.Ready)?.value?.second.orEmpty()
    val tint = rememberImageTint(p?.let { Catalog.playlistImage(it, 160) }, tintOf(tracks.firstOrNull()?.album?.vibrantColor))
    LaunchedEffect(tint) { AppUi.tint = tint }
    val ctx = PlayContext("playlist", p?.title ?: "", "playlist/$uuid")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    if (r is Resource.Failed) return Column { BackRow(onBack); ErrorState(r.error) { r.reload() } }
    LazyColumn(Modifier.testTag("playlist_screen")) {
        item {
            DetailHeader(
                tint = tint, image = p?.let { Catalog.playlistImage(it, 640) }, title = p?.title ?: "", description = p?.cleanDescription,
                meta = listOfNotNull(p?.creator?.name ?: if (p?.type == "EDITORIAL") "Editorial" else "Playlist", "${tracks.size} songs", tracks.sumOf { it.duration }.takeIf { it > 0 }?.let(::formatLength)).joinToString(" · "),
                onBack = onBack,
                leftActions = {
                    if (p != null) {
                        val saved = lib.hasPlaylist(p.uuid)
                        IconButton(onClick = { Toasts.show(if (Library.togglePlaylist(p)) "Added to Your Library" else "Removed from Your Library") }, modifier = Modifier.semantics { contentDescription = if (saved) "Remove from Your Library" else "Add to Your Library" }.testTag("save_playlist")) {
                            Icon(if (saved) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline, null, tint = if (saved) Aoide.accent else Aoide.subdued, modifier = Modifier.size(26.dp))
                        }
                    }
                },
                shuffle = { if (!player.shuffle) PlayerController.toggleShuffle(); PlayerController.playTracks(tracks, tracks.indices.random(), ctx) },
                playing = thisPlaying, canPlay = tracks.isNotEmpty(),
                onPlay = { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(tracks, 0, ctx) },
            )
        }
        if (r is Resource.Loading) item { SkeletonRows(10) }
        items(tracks, key = { "${it.id}" }) { t -> TrackRow(t, onClick = { PlayerController.playTracks(tracks, tracks.indexOf(t), ctx) }) }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

@Composable
fun LocalPlaylistScreen(id: String, onBack: () -> Unit) {
    val lib by Library.state.collectAsState()
    val pl = lib.playlists.find { it.id == id }
    val player by PlayerController.state.collectAsState()
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (pl == null) return Column { BackRow(onBack); EmptyState("That playlist is gone") }
    val tint = tintOf(pl.tracks.firstOrNull()?.album?.vibrantColor)
    LaunchedEffect(tint) { AppUi.tint = tint }
    val ctx = PlayContext("playlist", pl.title, "local/${pl.id}")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    LazyColumn(Modifier.testTag("local_playlist_screen")) {
        item {
            DetailHeader(
                tint = tint, image = Catalog.cover(pl.tracks.firstOrNull()?.album?.cover, 640), title = pl.title,
                meta = "You · ${pl.tracks.size} songs" + (pl.tracks.sumOf { it.duration }.takeIf { it > 0 }?.let { " · ${formatLength(it)}" } ?: ""),
                onBack = onBack,
                leftActions = {
                    IconButton(onClick = { rename = true }, modifier = Modifier.semantics { contentDescription = "Rename playlist" }) { Icon(Icons.Filled.Edit, null, tint = Aoide.subdued) }
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.semantics { contentDescription = "Delete playlist" }) { Icon(Icons.Filled.Delete, null, tint = Aoide.subdued) }
                },
                shuffle = { if (!player.shuffle) PlayerController.toggleShuffle(); PlayerController.playTracks(pl.tracks, pl.tracks.indices.random(), ctx) },
                playing = thisPlaying, canPlay = pl.tracks.isNotEmpty(),
                onPlay = { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(pl.tracks, 0, ctx) },
            )
        }
        if (pl.tracks.isEmpty()) item { EmptyState("Let's find something for your playlist", "Use ··· on any song to add it here.") }
        items(pl.tracks.withIndex().toList(), key = { "${it.index}-${it.value.id}" }) { (i, t) ->
            TrackRow(t, onClick = { PlayerController.playTracks(pl.tracks, i, ctx) }, onRemove = { Library.removeFromPlaylist(pl.id, i); Toasts.show("Removed from ${pl.title}") })
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
    if (rename) {
        var name by remember { mutableStateOf(pl.title) }
        AlertDialog(onDismissRequest = { rename = false }, containerColor = Aoide.elevated2, title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { Library.renamePlaylist(pl.id, name); rename = false }) { Text("Save", color = Aoide.accent) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel", color = Aoide.subdued) } })
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = Aoide.elevated2, title = { Text("Delete \"${pl.title}\"?") }, text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { Library.deletePlaylist(pl.id); confirmDelete = false; Toasts.show("Playlist deleted"); onBack() }) { Text("Delete", color = Aoide.accent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Aoide.subdued) } })
    }
}

@Composable
fun LikedScreen(onBack: () -> Unit) {
    val lib by Library.state.collectAsState()
    val player by PlayerController.state.collectAsState()
    val ctx = PlayContext("liked", "Liked Songs", "liked")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    LaunchedEffect(Unit) { AppUi.tint = Aoide.accent }
    LazyColumn(Modifier.testTag("liked_screen")) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Aoide.accent.copy(alpha = .85f), Aoide.ground), endY = 900f))) {
                BackRow(onBack)
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { LikedTile(232.dp) }
                Text("Liked Songs", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp).testTag("detail_title"))
                Text("You · ${lib.liked.size} songs", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    PlayFab(playing = thisPlaying, enabled = lib.liked.isNotEmpty()) { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(lib.liked, 0, ctx) }
                }
            }
        }
        if (lib.liked.isEmpty()) item { EmptyState("Songs you like will appear here", "Save songs by tapping the + on a song.") }
        items(lib.liked, key = { it.id }) { t -> TrackRow(t, onClick = { PlayerController.playTracks(lib.liked, lib.liked.indexOf(t), ctx) }) }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

@Composable
fun ArtistScreen(id: Long, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val artist by rememberResource("artist", id) { Catalog.artist(id) }
    val disco by rememberResource("disco", id) { Catalog.discography(id) }
    val a = (artist as? Resource.Ready)?.value
    val top by rememberResource("top", id, a?.name) { a?.let { Catalog.topTracks(id, it.name) } ?: emptyList() }
    val similar by rememberResource("simartists", id) { Catalog.similarArtists(id) }
    val bio by rememberResource("bio", id) { Catalog.artistBio(id) }
    val player by PlayerController.state.collectAsState()
    val lib by Library.state.collectAsState()
    var filter by remember { mutableStateOf("ALL") }
    var moreTop by remember { mutableStateOf(false) }
    var moreBio by remember { mutableStateOf(false) }
    val tint = rememberImageTint(a?.picture?.let { Catalog.artistPicture(it, 160) }, Color(0xFF4A4A4A))
    LaunchedEffect(tint) { AppUi.tint = tint }
    val ar = artist
    if (ar is Resource.Failed) return Column { BackRow(onBack); ErrorState(ar.error) { ar.reload() } }
    val tracks = (top as? Resource.Ready)?.value.orEmpty()
    val ctx = PlayContext("artist", a?.name ?: "", "artist/$id")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    val albums = (disco as? Resource.Ready)?.value?.first.orEmpty().filter { filter == "ALL" || (it.type ?: "ALBUM") == filter }.sortedByDescending { it.releaseDate ?: "" }
    LazyColumn(Modifier.testTag("artist_screen")) {
        item {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                Artwork(Catalog.artistPicture(a?.picture, 750), Modifier.fillMaxWidth().height(320.dp), RoundedCornerShape(0.dp))
                Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .35f), Aoide.ground))))
                BackRow(onBack)
                Text(a?.name ?: "", style = MaterialTheme.typography.displayLarge, color = Aoide.fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).testTag("detail_title"))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (a != null) OutlinePill(if (lib.follows(a.id)) "Following" else "Follow", lib.follows(a.id)) { Toasts.show(if (Library.toggleArtist(a)) "Following ${a.name}" else "Unfollowed ${a.name}") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { if (!player.shuffle) PlayerController.toggleShuffle(); PlayerController.playTracks(tracks, tracks.indices.random(), ctx) }, enabled = tracks.isNotEmpty(), modifier = Modifier.semantics { contentDescription = "Shuffle play" }) { Icon(Icons.Filled.Shuffle, null, tint = Aoide.subdued, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(8.dp))
                PlayFab(playing = thisPlaying, enabled = tracks.isNotEmpty()) { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(tracks, 0, ctx) }
            }
        }
        item { SectionTitle("Popular") }
        when (val t = top) {
            is Resource.Ready -> {
                val shown = tracks.take(if (moreTop) 10 else 5)
                items(shown.withIndex().toList(), key = { "p${it.value.id}" }) { (i, tr) ->
                    TrackRow(tr, number = i + 1, subtitle = tr.album?.title, onClick = { PlayerController.playTracks(tracks, i, ctx) })
                }
                if (tracks.size > 5) item { TextButton(onClick = { moreTop = !moreTop }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (moreTop) "Show less" else "See more", color = Aoide.subdued) } }
                if (tracks.isEmpty() && t.value.isEmpty()) item { EmptyState("No popular songs found") }
            }
            else -> item { SkeletonRows(5) }
        }
        item { SectionTitle("Discography") }
        item {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("ALL" to "All", "ALBUM" to "Albums", "EP" to "EPs", "SINGLE" to "Singles")) { (k, l) -> Chip(l, filter == k) { filter = k } }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            if (disco is Resource.Loading) app.aoide.ui.components.SkeletonCards()
            else CardRow(albums, { it.id }) { al -> MediaCard(Catalog.cover(al.cover, 320), al.title, listOfNotNull(al.year.ifBlank { null }, al.type?.let { if (it == "ALBUM") "Album" else it.lowercase().replaceFirstChar(Char::uppercase) }).joinToString(" · ")) { onNavigate("album/${al.id}") } }
        }
        val sim = (similar as? Resource.Ready)?.value.orEmpty()
        if (sim.isNotEmpty()) {
            item { SectionTitle("Fans also like") }
            item { CardRow(sim.take(12), { it.id }) { s -> MediaCard(Catalog.artistPicture(s.picture, 320), s.name, "Artist", round = true) { onNavigate("artist/${s.id}") } } }
        }
        val b = (bio as? Resource.Ready)?.value.orEmpty()
        if (b.isNotBlank()) {
            item { SectionTitle("About") }
            item {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Aoide.elevated).clickable { moreBio = !moreBio }) {
                    Artwork(Catalog.artistPicture(a?.picture, 480), Modifier.fillMaxWidth().height(200.dp), RoundedCornerShape(0.dp))
                    Text(b, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = if (moreBio) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp))
                }
            }
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

@Suppress("unused")
private fun keepAlbum(a: Album, t: Track) = a to t
