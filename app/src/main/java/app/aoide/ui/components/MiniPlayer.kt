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

/** The floating bar above the tab bar. Tinted like the record, hairline progress along the bottom. */
@Composable
fun MiniPlayer(tint: Color) {
    val s by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val t = s.current ?: return
    val info = infos[t.id]
    val progress = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 1f)).background(Color.Black.copy(alpha = .45f))
            .clickable { AppUi.nowPlayingOpen = true }.testTag("mini_player"),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(Catalog.cover(t.album?.cover, 160), Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mini_title"))
                Text(
                    when {
                        s.status == Status.ERROR -> s.error ?: "Playback failed"
                        s.status == Status.LOADING -> "Loading…"
                        info?.isPreview == true -> "${t.artistNames} · Preview"
                        else -> t.artistNames
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (info?.isPreview == true) Aoide.accent else Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            LikeButton(t, size = 22.dp)
            IconButton(onClick = { PlayerController.toggle() }, modifier = Modifier.semantics { contentDescription = if (s.isPlaying) "Pause" else "Play" }.testTag("mini_toggle")) {
                Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Aoide.fg, modifier = Modifier.size(28.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).background(Color.White.copy(alpha = .25f))) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Aoide.fg))
        }
        Spacer(Modifier.height(4.dp))
    }
}
