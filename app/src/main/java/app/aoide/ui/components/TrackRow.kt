package app.aoide.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Catalog
import app.aoide.data.Track
import app.aoide.data.formatTime
import app.aoide.player.PlayerController
import app.aoide.ui.AppUi
import app.aoide.ui.theme.Aoide

/**
 * One song row, Spotify mobile shape: optional art, title, "E · artist · album", and ··· on the
 * right. Tap plays; the current track's title turns orange with a little equaliser.
 */
@Composable
fun TrackRow(
    track: Track,
    showArt: Boolean = true,
    number: Int? = null,
    subtitle: String? = null,
    showDuration: Boolean = false,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val player by PlayerController.state.collectAsState()
    val isCurrent = player.current?.id == track.id
    val playing = isCurrent && player.isPlaying
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .semantics { contentDescription = "Track ${track.title} by ${track.artistNames}" }.testTag("track_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (playing) Equaliser() else Text(number.toString(), style = MaterialTheme.typography.bodyMedium, color = if (isCurrent) Aoide.accent else Aoide.subdued)
            }
        }
        if (showArt) {
            Artwork(Catalog.cover(track.album?.cover, 160), Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title + (track.version?.let { " - $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) Aoide.accent else Aoide.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number == null && playing) {
                    Equaliser(); Spacer(Modifier.width(6.dp))
                }
                if (track.explicit) {
                    Box(Modifier.clip(RoundedCornerShape(2.dp)).background(Aoide.subdued).padding(horizontal = 3.dp)) {
                        Text("E", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Aoide.base)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(subtitle ?: track.artistNames, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showDuration) Text(formatTime(track.duration), style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(end = 4.dp))
        IconButton(onClick = { AppUi.openMenu(track, onRemove) }, modifier = Modifier.semantics { contentDescription = "More options for ${track.title}" }.testTag("track_more")) {
            Icon(Icons.Filled.MoreVert, null, tint = Aoide.subdued)
        }
    }
}

/** Four bars bouncing in the accent colour. */
@Composable
fun Equaliser(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 150, 300, 450).map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(450, delayMillis = d, easing = LinearEasing), RepeatMode.Reverse), label = "bar$d")
    }
    Row(modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        phases.forEach { p ->
            Box(Modifier.width(3.dp).height(14.dp).graphicsLayer { scaleY = p.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }.background(Aoide.accent, RoundedCornerShape(1.dp)))
        }
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Composable
fun TrackTitleWeight() = FontWeight.Normal
