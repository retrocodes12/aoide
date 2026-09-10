package app.aoide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Catalog
import app.aoide.player.PlayerController
import app.aoide.player.Status
import app.aoide.player.StreamResolver
import app.aoide.ui.AppUi
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.Tint

/**
 * Floating capsule above the tab bar: Apple's shape, tinted like the playing record, Spotify's
 * hairline progress. The tint sits under a 60% black scrim, so white text clears AA on the
 * brightest cover; nothing on it is ever orange, which measured under 2:1 on red records.
 */
@Composable
fun MiniPlayer(tint: Tint) {
    val s by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val t = s.current ?: return
    val info = infos[t.id]
    val failed = s.status == Status.ERROR
    val progress = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.padding(horizontal = 10.dp).fillMaxWidth().shadow(18.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(12.dp)).background(tint.accent).background(Color.Black.copy(alpha = .6f))
            .clickable { AppUi.nowPlayingOpen = true }.testTag("mini_player"),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(Catalog.cover(t.album?.cover, 160), Modifier.size(42.dp).shadow(6.dp, RoundedCornerShape(6.dp), clip = false), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mini_title"))
                Text(
                    when {
                        failed -> s.error ?: "Couldn't play this song"
                        s.status == Status.LOADING -> "Loading…"
                        info?.isPreview == true -> "${t.artistNames} · Preview"
                        else -> t.artistNames
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (failed) Color.White else Color.White.copy(alpha = .84f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("mini_sub"),
                )
            }
            IconButton(onClick = { PlayerController.toggle() }, modifier = Modifier.semantics { contentDescription = if (failed) "Retry" else if (s.isPlaying) "Pause" else "Play" }.testTag("mini_toggle")) {
                Icon(if (failed) Icons.Filled.Refresh else if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Aoide.fg, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { PlayerController.next() }, modifier = Modifier.semantics { contentDescription = "Next" }.testTag("mini_next")) {
                Icon(Icons.Filled.SkipNext, null, tint = Aoide.fg, modifier = Modifier.size(26.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(2.dp).background(Color.White.copy(alpha = .22f))) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Aoide.fg))
        }
        Spacer(Modifier.height(6.dp))
    }
}
