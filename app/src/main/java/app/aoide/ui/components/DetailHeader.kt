package app.aoide.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.ui.theme.Aoide

/**
 * Album / playlist header in Spotify's mobile shape: artwork centred on a tint that fades into
 * the page, then title, an optional artist line, a meta line, and the action row.
 */
@Composable
fun DetailHeader(
    tint: Color,
    image: String?,
    title: String,
    round: Boolean = false,
    artist: (@Composable () -> Unit)? = null,
    description: String? = null,
    meta: String,
    onBack: () -> Unit,
    leftActions: @Composable () -> Unit,
    shuffle: (() -> Unit)? = null,
    playing: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint, Aoide.ground), endY = 900f))) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Artwork(image, Modifier.size(232.dp).shadow(24.dp, if (round) CircleShape else RoundedCornerShape(4.dp), clip = false), shape = if (round) CircleShape else RoundedCornerShape(4.dp), contentDescription = title)
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Aoide.fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("detail_title"))
            if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (artist != null) Box(Modifier.padding(top = 10.dp)) { artist() }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            leftActions()
            if (onMore != null) IconButton(onClick = onMore, modifier = Modifier.semantics { contentDescription = "More options" }) { Icon(Icons.Filled.MoreVert, null, tint = Aoide.subdued) }
            Spacer(Modifier.weight(1f))
            if (shuffle != null) IconButton(onClick = shuffle, enabled = canPlay, modifier = Modifier.semantics { contentDescription = "Shuffle play" }.testTag("shuffle")) { Icon(Icons.Filled.Shuffle, null, tint = Aoide.subdued, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(8.dp))
            PlayFab(playing = playing, enabled = canPlay, onClick = onPlay)
        }
        Spacer(Modifier.height(4.dp))
    }
}
