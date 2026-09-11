package app.aoide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Catalog
import app.aoide.data.PlayContext
import app.aoide.data.Shelf
import app.aoide.player.PlayerController
import app.aoide.ui.Resource
import app.aoide.ui.components.CardRow
import app.aoide.ui.components.ErrorState
import app.aoide.ui.components.MediaCard
import app.aoide.ui.components.SectionTitle
import app.aoide.ui.components.SkeletonCards
import app.aoide.ui.plural
import app.aoide.ui.reload
import app.aoide.ui.rememberResource
import app.aoide.ui.theme.Aoide

/** One catalogue shelf as a titled card row; songs play as a queue, everything else opens its page. */
@Composable
fun ShelfRow(shelf: Shelf, onNavigate: (String) -> Unit, title: String = shelf.title, tag: String = "shelf_card") {
    if (shelf.isEmpty) return
    Column {
        SectionTitle(title.ifBlank { "For you" }, action = shelf.strapline?.let { s -> { Text(s, style = MaterialTheme.typography.labelSmall, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis) } })
        if (shelf.tracks.isNotEmpty()) CardRow(shelf.tracks.take(20), { it.id }) { t ->
            MediaCard(Catalog.cover(t.album?.cover, 320), t.title, t.artistNames, tag = tag) { PlayerController.playTracks(shelf.tracks, shelf.tracks.indexOfFirst { it.id == t.id }, PlayContext("shelf", title)) }
        }
        if (shelf.albums.isNotEmpty()) CardRow(shelf.albums.take(20), { it.id }) { a ->
            MediaCard(Catalog.cover(a.cover, 320), a.title, listOfNotNull(a.primaryArtist?.name, a.year.ifBlank { null }).joinToString(" · "), tag = tag) { onNavigate("album/${a.id}") }
        }
        if (shelf.playlists.isNotEmpty()) CardRow(shelf.playlists.take(20), { it.uuid }) { p ->
            MediaCard(Catalog.playlistImage(p, 320), p.title, p.creator?.name ?: p.numberOfTracks?.let { plural(it, "song") } ?: "Playlist", tag = tag) { onNavigate("playlist/${p.uuid}") }
        }
        if (shelf.artists.isNotEmpty()) CardRow(shelf.artists.take(20), { it.id }) { ar ->
            MediaCard(Catalog.artistPicture(ar.picture, 320), ar.name, ar.listeners ?: "Artist", round = true, width = 140.dp, tag = tag) { onNavigate("artist/${ar.id}") }
        }
    }
}

@Composable
private fun BackTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A mood or genre page: the service's playlist shelves for it. */
@Composable
fun MoodScreen(browseId: String, params: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val res by rememberResource("mood", browseId, params) { Catalog.moodShelves(browseId, params) }
    val title = app.aoide.ui.MoodTitles.get(browseId + params) ?: "Browse"
    LazyColumn(Modifier.testTag("mood_screen")) {
        item { BackTitle(title, onBack) }
        when (val r = res) {
            is Resource.Loading -> item { SkeletonCards() }
            is Resource.Failed -> item { ErrorState(r.error, what = "page") { r.reload() } }
            is Resource.Ready -> items(r.value, key = { it.title }) { shelf -> ShelfRow(shelf, onNavigate, tag = "mood_card") }
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

/** An artist's full album or single list. */
@Composable
fun DiscographyScreen(browseId: String, params: String, title: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val res by rememberResource("disco", browseId, params) { Catalog.discography(browseId, params) }
    LazyColumn(Modifier.testTag("discography_screen")) {
        item { BackTitle(title, onBack) }
        when (val r = res) {
            is Resource.Loading -> item { SkeletonCards() }
            is Resource.Failed -> item { ErrorState(r.error, what = "list") { r.reload() } }
            is Resource.Ready -> items(r.value.chunked(2), key = { it.first().id }) { pair ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { a -> Column(Modifier.weight(1f)) { MediaCard(Catalog.cover(a.cover, 320), a.title, listOfNotNull(a.year.ifBlank { null }, a.type?.lowercase()?.replaceFirstChar(Char::uppercase)).joinToString(" · "), width = 999.dp, tag = "disco_card") { onNavigate("album/${a.id}") } } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}
