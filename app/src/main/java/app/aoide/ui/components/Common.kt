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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddCircleOutline
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aoide.data.ApiException
import app.aoide.data.Library
import app.aoide.data.Track
import app.aoide.ui.Toasts
import app.aoide.ui.theme.Aoide
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Rendering knobs a screenshot test can turn: the crossfade needs a running clock, which a JVM render does not have. */
object ArtworkConfig {
    var crossfadeMs: Int = 150
}

/** Album / artist / playlist artwork with a quiet placeholder. */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp), contentDescription: String? = null) {
    Box(modifier.clip(shape).background(Aoide.elevated2)) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(url).let { if (ArtworkConfig.crossfadeMs > 0) it.crossfade(ArtworkConfig.crossfadeMs) else it.crossfade(false) }.build(),
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
            if (onSeeAll != null) Icon(Icons.Filled.ChevronRight, null, tint = Aoide.subdued, modifier = Modifier.padding(start = 2.dp, top = 2.dp).size(22.dp))
        }
        action?.invoke()
    }
}

/** A square card in a horizontal row: art, title, subtitle. */
@Composable
fun MediaCard(image: String?, title: String, subtitle: String?, round: Boolean = false, width: Dp = 156.dp, tag: String = "card", onClick: () -> Unit) {
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Column(Modifier.width(width).pressable(onClick = onClick).testTag(tag)) {
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
        Modifier.pressable(onClick = onClick).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 8.dp).semantics { contentDescription = "Filter $text" },
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = fg) }
}

/** The big orange play button. */
@Composable
fun PlayFab(playing: Boolean, size: Dp = 56.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.pressable(enabled = enabled, onClick = onClick).size(size).clip(CircleShape).background(if (enabled) Aoide.accent else Aoide.elevated2)
            .semantics { contentDescription = if (playing) "Pause" else "Play" }.testTag("play_fab"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = if (enabled) Aoide.accentInk else Aoide.subdued, modifier = Modifier.size(size * 0.5f))
    }
}

/** A translucent disc behind an icon, so a control over artwork clears 3:1 on any tint. Apple Music's move. */
@Composable
fun IconDisc(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier.pressable(onClick = onClick).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)) }
}

/** Heart that toggles Liked Songs. Spotify draws it as a + / check; the heart says more here. */
@Composable
fun LikeButton(track: Track, size: Dp = 24.dp, modifier: Modifier = Modifier, tint: Color = Aoide.subdued) {
    val lib by Library.state.collectAsState()
    val liked = lib.isLiked(track.id)
    val haptics = rememberHaptics()
    IconButton(
        onClick = { Haptics.confirm(haptics); Toasts.show(if (Library.toggleLike(track)) "Added to Liked Songs" else "Removed from Liked Songs") },
        modifier = modifier.semantics { contentDescription = if (liked) "Remove from Liked Songs" else "Add to Liked Songs" }.testTag("like"),
    ) {
        Icon(if (liked) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline, null, tint = if (liked) Aoide.accent else tint, modifier = Modifier.size(size))
    }
}

@Composable
fun OutlinePill(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (selected) Aoide.fg else Aoide.subdued.copy(alpha = .6f), RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Aoide.fg else Aoide.subdued) }
}

@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Aoide.fg, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        if (action != null) Box(Modifier.padding(top = 20.dp)) { action() }
    }
}

/** White pill, the secondary action of empty and error states. */
@Composable
fun WhitePill(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Aoide.fg).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Aoide.base)
    }
}

/**
 * A failed load in words a listener can act on. A 404 says the thing is gone; anything else blames
 * the mirrors without quoting them. Raw transport messages never reach the screen.
 */
@Composable
fun ErrorState(error: Throwable, what: String = "page", onHome: (() -> Unit)? = null, onRetry: () -> Unit) {
    val missing = (error as? ApiException)?.status == 404
    if (missing) {
        EmptyState("We couldn't find that $what", "It may have been removed from the catalogue, or the link is wrong.", action = onHome?.let { { WhitePill("Home", it) } })
    } else {
        EmptyState("Something went wrong", "The catalogue mirrors did not answer. Give it a moment and try again.", action = { WhitePill("Try again", onRetry) })
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
                Box(Modifier.size(148.dp).clip(RoundedCornerShape(10.dp)).background(Aoide.elevated))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(.7f).height(12.dp).background(Aoide.elevated))
            }
        }
    }
}

fun Modifier.aspectSquare() = this.aspectRatio(1f)
