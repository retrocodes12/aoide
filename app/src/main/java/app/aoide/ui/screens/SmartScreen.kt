package app.aoide.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.aoide.data.Library
import app.aoide.data.PlayContext
import app.aoide.player.PlayerController
import app.aoide.ui.components.EmptyState
import app.aoide.ui.components.TrackRow
import app.aoide.ui.plural

/** A list the library writes for itself: most played, this week, or liked songs never heard through. */
@Composable
fun SmartScreen(kind: String, onBack: () -> Unit) {
    val lib by Library.state.collectAsState()
    val player by PlayerController.state.collectAsState()
    val (icon, title, meta, tracks) = when (kind) {
        "most" -> Quad(Icons.Filled.Whatshot, "Most played", "Songs you have played three times or more", lib.mostPlayed)
        "week" -> Quad(Icons.Filled.DateRange, "This week", "Everything heard in the last seven days", lib.thisWeek)
        else -> Quad(Icons.Filled.Explore, "Liked, never played", "Songs you saved and have not heard through yet", lib.neverPlayed)
    }
    val ctx = PlayContext("playlist", title, "smart/$kind")
    val thisPlaying = player.context?.href == ctx.href && player.isPlaying
    LazyColumn(Modifier.testTag("smart_screen")) {
        item {
            IconHead(
                icon, title, "$meta · ${plural(tracks.size, "song")}", thisPlaying, tracks.isNotEmpty(), onBack,
                onPlay = { if (player.context?.href == ctx.href && player.index >= 0) PlayerController.toggle() else PlayerController.playTracks(tracks, 0, ctx) },
                onShuffle = { PlayerController.playTracks(tracks, tracks.indices.random(), ctx, shuffled = true) },
            )
        }
        if (tracks.isEmpty()) item { EmptyState("Nothing here yet", "This list fills itself in as you listen.") }
        itemsIndexed(tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
            val sub = if (kind == "most") "${plural(lib.plays[t.id] ?: 0, "play")} · ${t.artistNames}" else t.artistNames
            TrackRow(t, subtitle = sub, onClick = { PlayerController.playTracks(tracks, i, ctx) }, list = tracks, index = i)
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
