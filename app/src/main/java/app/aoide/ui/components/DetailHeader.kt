package app.aoide.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.Tint

/**
 * Album / playlist header: Spotify's tinted ground, Apple Music's composition. Artwork centred
 * with a deep soft shadow, title and artist centred under it, then Play and Shuffle as a pair of
 * wide pills. The tint lives behind the artwork; by the title the ground has taken over, so white
 * and orange text sit on near-black whatever the record's colour.
 */
@Composable
fun DetailHeader(
    tint: Tint,
    image: String?,
    title: String,
    round: Boolean = false,
    artist: (@Composable () -> Unit)? = null,
    description: String? = null,
    meta: String,
    badge: String? = null,
    onBack: () -> Unit,
    leftActions: @Composable () -> Unit,
    shuffle: (() -> Unit)? = null,
    playing: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to tint.accent, 0.64f to Aoide.ground, 1f to Aoide.ground, endY = 1100f))) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconDisc(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.testTag("back"), onClick = onBack)
            Spacer(Modifier.weight(1f))
            leftActions()
            if (onMore != null) IconDisc(Icons.Filled.MoreVert, "More options", Modifier.padding(start = 8.dp), onClick = onMore)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            Artwork(image, Modifier.size(248.dp).shadow(28.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black), shape = shape, contentDescription = title)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Aoide.fg, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.testTag("detail_title"))
            if (artist != null) Box(Modifier.padding(top = 4.dp)) { artist() }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, textAlign = TextAlign.Center)
                if (badge != null) { Spacer(Modifier.width(8.dp)); QualityBadge(badge, onDark = true) }
            }
            if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (playing) "Pause" else "Play", if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, filled = true, enabled = canPlay, modifier = Modifier.weight(1f).testTag("play_fab"), onClick = onPlay)
            PillButton("Shuffle", Icons.Filled.Shuffle, filled = false, enabled = canPlay && shuffle != null, modifier = Modifier.weight(1f).testTag("shuffle")) { shuffle?.invoke() }
        }
    }
}

/** Apple Music's wide pill: filled orange for the primary, translucent for the secondary. Disabled pills go neutral, never dark orange. */
@Composable
fun PillButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, filled: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = when {
        !enabled -> if (filled) Aoide.elevated2 else Aoide.highlight
        filled -> Aoide.accent
        else -> Aoide.highlight
    }
    val fg = when {
        !enabled -> Aoide.subdued
        filled -> Aoide.accentInk
        else -> Aoide.accent
    }
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = text },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = fg)
    }
}

/** "Lossless" / "Hi-Res" tag, in the shape Apple Music uses. [onDark] gives it its own ground for tinted surfaces. */
@Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier, onDark: Boolean = false, accent: Boolean = false) {
    val bg = when {
        accent -> Color.Black.copy(alpha = .45f)
        onDark -> Color.Black.copy(alpha = .35f)
        else -> Aoide.subdued.copy(alpha = .22f)
    }
    val fg = when {
        accent -> Aoide.accent
        onDark -> Aoide.fg
        else -> Aoide.subdued
    }
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp), color = fg)
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
private val Double.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
