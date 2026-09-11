package app.aoide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aoide.data.Library
import app.aoide.data.PlayContext
import app.aoide.player.PlayerController
import app.aoide.ui.AppUi
import app.aoide.ui.Toasts
import app.aoide.ui.components.Chip
import app.aoide.ui.components.EmptyState
import app.aoide.ui.components.OutlinePill
import app.aoide.ui.components.TrackRow
import app.aoide.ui.plural
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.Tint
import java.text.DateFormat
import java.util.Date

/** What has been played, in order and by count, with the numbers that fall out of it. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val lib by Library.state.collectAsState()
    val player by PlayerController.state.collectAsState()
    var mostPlayed by rememberSaveable { mutableStateOf(false) }
    val recent = lib.recentTracks
    val top = remember(lib.plays, recent) { recent.sortedByDescending { lib.plays[it.id] ?: 0 }.filter { (lib.plays[it.id] ?: 0) > 1 } }
    val list = if (mostPlayed) top else recent
    val ctx = PlayContext("history", if (mostPlayed) "Most played" else "Recently played", "history")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    val totalPlays = lib.plays.values.sum()
    val topArtist = remember(lib.plays, recent) {
        recent.groupBy { it.primaryArtist?.name ?: "" }.mapValues { (_, ts) -> ts.sumOf { lib.plays[it.id] ?: 1 } }.filterKeys { it.isNotBlank() }.maxByOrNull { it.value }?.key
    }
    val minutes = remember(lib.plays, recent) { recent.sumOf { (lib.plays[it.id] ?: 1) * it.duration } / 60 }
    LaunchedEffect(Unit) { AppUi.page = Tint.from(Aoide.accent) }
    LazyColumn(Modifier.testTag("history_screen")) {
        item {
            IconHead(
                Icons.Filled.History, "History", "${plural(totalPlays, "play")} · ${plural(lib.plays.size, "song")} · stays on this phone",
                thisPlaying, list.isNotEmpty(), onBack,
                onPlay = { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(list, 0, ctx) },
                onShuffle = { if (!player.shuffle) PlayerController.toggleShuffle(); PlayerController.playTracks(list, list.indices.random(), ctx) },
                trailing = { if (recent.isNotEmpty()) OutlinePill("Clear") { AppUi.ask("Clear your history?", "Clear", "Recently played, play counts and the stats below are wiped. Liked songs and playlists stay.") { Library.clearHistory(); Toasts.show("History cleared") } } },
            )
        }
        if (recent.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("$minutes", "minutes", Modifier.weight(1f))
                Stat("${lib.plays.size}", "songs", Modifier.weight(1f))
                Stat(topArtist ?: "—", "top artist", Modifier.weight(1.4f))
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(false, true)) { m -> Chip(if (m) "Most played" else "Recent", mostPlayed == m) { mostPlayed = m } }
            }
        }
        if (list.isEmpty()) item { EmptyState(if (mostPlayed) "Nothing played twice yet" else "Nothing played yet", "Every song you play is noted here, on this phone only.") }
        items(list, key = { it.id }) { t ->
            val count = lib.plays[t.id] ?: 1
            val at = lib.playedAt[t.id]
            val sub = if (mostPlayed) "${plural(count, "play")} · ${t.artistNames}" else (at?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) + " · " } ?: "") + t.artistNames
            TrackRow(t, subtitle = sub, onClick = { PlayerController.playTracks(list, list.indexOf(t), ctx) })
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Aoide.highlight).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
    }
}
