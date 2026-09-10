package app.aoide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aoide.data.Library
import app.aoide.data.Track
import app.aoide.ui.Toasts
import app.aoide.ui.theme.Aoide
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Album / artist / playlist artwork with a quiet placeholder. */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp), contentDescription: String? = null) {
    Box(modifier.clip(shape).background(Aoide.elevated2)) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(url).crossfade(150).build(),
                contentDescription = contentDescription,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** The Liked Songs tile: the only gradient in the app, as in Spotify. */
@Composable
fun LikedTile(size: Dp, shape: Shape = RoundedCornerShape(4.dp)) {
    Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(Aoide.likedGradient)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Favorite, null, tint = Color(0xFF1A1A1A), modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).let { m -> if (onSeeAll != null) m.clickable(onClick = onSeeAll) else m }, verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onSeeAll != null) Icon(androidx.compose.material.icons.Icons.Filled.ChevronRight, null, tint = Aoide.subdued, modifier = Modifier.padding(start = 2.dp, top = 2.dp).size(22.dp))
        }
        action?.invoke()
    }
}

/** A square card in a horizontal row: art, title, subtitle. */
@Composable
fun MediaCard(image: String?, title: String, subtitle: String?, round: Boolean = false, width: Dp = 156.dp, tag: String = "card", onClick: () -> Unit) {
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Column(Modifier.width(width).clickable(onClick = onClick).testTag(tag)) {
        Artwork(image, Modifier.size(width).shadow(10.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black).semantics { contentDescription = title }, shape = shape)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Aoide.fg)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun <T> CardRow(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = key) { content(it) }
    }
}

/** Filter chip in Spotify's shape: pill, translucent, white when selected. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Aoide.fg else Aoide.highlight
    val fg = if (selected) Aoide.base else Aoide.fg
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).semantics { contentDescription = "Filter $text" },
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = fg) }
}

/** The big orange play button. */
@Composable
fun PlayFab(playing: Boolean, size: Dp = 56.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) Aoide.accent else Aoide.accent.copy(alpha = .4f)).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" }.testTag("play_fab"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Aoide.accentInk, modifier = Modifier.size(size * 0.5f))
    }
}

/** Heart that toggles Liked Songs. Spotify draws it as a + / check; the heart says more here. */
@Composable
fun LikeButton(track: Track, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val lib by Library.state.collectAsState()
    val liked = lib.isLiked(track.id)
    IconButton(
        onClick = { Toasts.show(if (Library.toggleLike(track)) "Added to Liked Songs" else "Removed from Liked Songs") },
        modifier = modifier.semantics { contentDescription = if (liked) "Remove from Liked Songs" else "Add to Liked Songs" }.testTag("like"),
    ) {
        Icon(if (liked) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline, null, tint = if (liked) Aoide.accent else Aoide.subdued, modifier = Modifier.size(size))
    }
}

@Composable
fun OutlinePill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (selected) Aoide.fg else Aoide.subdued.copy(alpha = .6f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Aoide.fg) }
}

@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Aoide.fg, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun ErrorState(error: Throwable, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Text("Every mirror failed for this request. ${error.message ?: ""}", style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.padding(top = 20.dp).clip(RoundedCornerShape(50)).background(Aoide.fg).clickable(onClick = onRetry).padding(horizontal = 28.dp, vertical = 12.dp)) {
            Text("Try again", style = MaterialTheme.typography.labelLarge, color = Aoide.base)
        }
    }
}

@Composable
fun SkeletonRows(n: Int = 8) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        repeat(n) { i ->
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Aoide.elevated))
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(Modifier.width((120 + (i * 37) % 90).dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Aoide.elevated))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width((70 + (i * 23) % 60).dp).height(11.dp).clip(RoundedCornerShape(4.dp)).background(Aoide.elevated))
                }
            }
        }
    }
}

@Composable
fun SkeletonCards(n: Int = 4) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(n) {
            Column(Modifier.width(148.dp)) {
                Box(Modifier.size(148.dp).clip(RoundedCornerShape(4.dp)).background(Aoide.elevated))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(.7f).height(12.dp).background(Aoide.elevated))
            }
        }
    }
}

fun Modifier.aspectSquare() = this.aspectRatio(1f)
