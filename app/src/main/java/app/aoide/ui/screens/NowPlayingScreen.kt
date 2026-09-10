package app.aoide.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.aoide.data.Catalog
import app.aoide.data.Lyrics
import app.aoide.data.Track
import app.aoide.data.formatTime
import app.aoide.player.PlayerController
import app.aoide.player.StreamResolver
import app.aoide.ui.AppUi
import app.aoide.ui.Resource
import app.aoide.ui.components.Artwork
import app.aoide.ui.components.Equaliser
import app.aoide.ui.components.LikeButton
import app.aoide.ui.rememberResource
import app.aoide.ui.theme.Aoide

/** Full-screen player. Swipe down or back closes it. */
@Composable
fun NowPlayingScreen(tint: Color, onNavigate: (String) -> Unit) {
    val s by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val t = s.current ?: return
    val info = infos[t.id]
    BackHandler { AppUi.nowPlayingOpen = false }
    var drag by remember { mutableStateOf(0f) }
    val lyrics by rememberResource("lyrics", t.id) { Catalog.lyrics(t) }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, Aoide.ground), endY = 1400f))
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (drag > 120f) AppUi.nowPlayingOpen = false; drag = 0f }) { _, dy -> drag += dy } }
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).testTag("now_playing"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { AppUi.nowPlayingOpen = false }, modifier = Modifier.semantics { contentDescription = "Close now playing" }.testTag("np_close")) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Aoide.fg, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text((if (info?.isPreview == true) "PREVIEW · 30 SECONDS" else "PLAYING FROM ${s.context?.kind?.uppercase() ?: "QUEUE"}"), style = MaterialTheme.typography.labelSmall, color = if (info?.isPreview == true) Aoide.accent else Aoide.fg.copy(alpha = .8f))
                Text(s.context?.title ?: t.album?.title ?: "", style = MaterialTheme.typography.titleSmall, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { s.context?.href?.takeIf { it.isNotEmpty() }?.let { AppUi.nowPlayingOpen = false; onNavigate(it) } })
            }
            IconButton(onClick = { AppUi.openMenu(t) }, modifier = Modifier.semantics { contentDescription = "More options" }) { Icon(Icons.Filled.MoreVert, null, tint = Aoide.fg) }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.padding(horizontal = 28.dp).fillMaxWidth().aspectRatio(1f).shadow(28.dp, RoundedCornerShape(8.dp), clip = false)) {
            Artwork(Catalog.cover(t.album?.cover, 640), Modifier.fillMaxSize(), RoundedCornerShape(8.dp), contentDescription = t.album?.title)
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("np_title"))
                Text(t.artistNames, style = MaterialTheme.typography.bodyLarge, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { t.primaryArtist?.let { AppUi.nowPlayingOpen = false; onNavigate("artist/${it.id}") } })
            }
            LikeButton(t, size = 28.dp)
        }
        Spacer(Modifier.height(4.dp))
        var scrub by remember(t.id) { mutableStateOf<Float?>(null) }
        val dur = s.durationMs.coerceAtLeast(1L)
        val pos = scrub ?: (s.positionMs.toFloat() / dur).coerceIn(0f, 1f)
        Slider(
            value = pos, onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { PlayerController.seekTo((it * dur).toLong()) }; scrub = null },
            colors = SliderDefaults.colors(thumbColor = Aoide.fg, activeTrackColor = Aoide.fg, inactiveTrackColor = Color.White.copy(alpha = .3f)),
            modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Seek" }.testTag("np_seek"),
        )
        Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
            Text(formatTime(((pos * dur) / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.testTag("np_position"))
            Spacer(Modifier.weight(1f))
            Text(formatTime((dur / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { PlayerController.toggleShuffle() }, modifier = Modifier.semantics { contentDescription = "Shuffle" }.testTag("np_shuffle")) { Icon(Icons.Filled.Shuffle, null, tint = if (s.shuffle) Aoide.accent else Aoide.subdued, modifier = Modifier.size(26.dp)) }
            IconButton(onClick = { PlayerController.prev() }, modifier = Modifier.semantics { contentDescription = "Previous" }.testTag("np_prev")) { Icon(Icons.Filled.SkipPrevious, null, tint = Aoide.fg, modifier = Modifier.size(40.dp)) }
            Box(Modifier.size(68.dp).clip(CircleShape).background(Aoide.fg).clickable { PlayerController.toggle() }.semantics { contentDescription = if (s.isPlaying) "Pause" else "Play" }.testTag("np_toggle"), contentAlignment = Alignment.Center) {
                Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Aoide.base, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { PlayerController.next() }, modifier = Modifier.semantics { contentDescription = "Next" }.testTag("np_next")) { Icon(Icons.Filled.SkipNext, null, tint = Aoide.fg, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = { PlayerController.cycleRepeat() }, modifier = Modifier.semantics { contentDescription = "Repeat" }.testTag("np_repeat")) {
                Icon(if (s.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, null, tint = if (s.repeat != Player.REPEAT_MODE_OFF) Aoide.accent else Aoide.subdued, modifier = Modifier.size(26.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info?.label?.takeIf { it.isNotBlank() }?.let { if (info.isPreview) "Preview. Add a subscribed mirror in Settings for full songs." else it } ?: "", style = MaterialTheme.typography.bodySmall, color = if (info?.isPreview == true) Aoide.accent else Aoide.subdued, modifier = Modifier.weight(1f))
            IconButton(onClick = { AppUi.queueOpen = true }, modifier = Modifier.semantics { contentDescription = "Queue" }.testTag("np_queue")) { Icon(Icons.Filled.QueueMusic, null, tint = Aoide.fg) }
        }
        // Lyrics card, as Spotify shows under the controls
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tint).clickable { AppUi.lyricsOpen = true }.padding(16.dp).testTag("lyrics_card"),
        ) {
            Text("Lyrics", style = MaterialTheme.typography.titleSmall, color = Aoide.fg)
            Spacer(Modifier.height(8.dp))
            when (val l = lyrics) {
                is Resource.Ready -> {
                    val lines = l.value?.synced?.map { it.line }?.filter { it.isNotBlank() } ?: l.value?.plain?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                    if (lines.isEmpty()) Text("We don't have lyrics for this one.", style = MaterialTheme.typography.bodyMedium, color = Aoide.fg.copy(alpha = .8f))
                    else lines.take(4).forEach { Text(it, style = MaterialTheme.typography.titleLarge, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                is Resource.Loading -> Text("Looking for lyrics…", style = MaterialTheme.typography.bodyMedium, color = Aoide.fg.copy(alpha = .8f))
                is Resource.Failed -> Text("Lyrics unavailable", style = MaterialTheme.typography.bodyMedium, color = Aoide.fg.copy(alpha = .8f))
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun LyricsScreen(tint: Color) {
    val s by PlayerController.state.collectAsState()
    val t = s.current ?: return
    BackHandler { AppUi.lyricsOpen = false }
    val lyrics by rememberResource("lyrics", t.id) { Catalog.lyrics(t) }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(tint).statusBarsPadding().navigationBarsPadding().testTag("lyrics_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artistNames, style = MaterialTheme.typography.bodySmall, color = Aoide.fg.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { AppUi.lyricsOpen = false }, modifier = Modifier.semantics { contentDescription = "Close lyrics" }.testTag("lyrics_close")) { Icon(Icons.Filled.Close, null, tint = Aoide.fg) }
        }
        when (val l = lyrics) {
            is Resource.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Looking for lyrics…", style = MaterialTheme.typography.titleLarge, color = Aoide.fg) }
            is Resource.Failed -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Lyrics unavailable", style = MaterialTheme.typography.titleLarge, color = Aoide.fg) }
            is Resource.Ready -> LyricsBody(l.value, s.positionMs, listState, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics provided by lrclib", style = MaterialTheme.typography.bodySmall, color = Aoide.fg.copy(alpha = .7f), modifier = Modifier.weight(1f))
            Box(Modifier.size(52.dp).clip(CircleShape).background(Aoide.fg).clickable { PlayerController.toggle() }.semantics { contentDescription = if (s.isPlaying) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
                Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Aoide.base, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun LyricsBody(l: Lyrics?, positionMs: Long, listState: LazyListState, modifier: Modifier) {
    val synced = l?.synced
    if (l == null || (synced == null && l.plain.isNullOrBlank())) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("We don't have lyrics for this one.", style = MaterialTheme.typography.titleLarge, color = Aoide.fg) }
        return
    }
    val pos = positionMs / 1000.0 + 0.25
    val active = synced?.indexOfLast { it.t <= pos } ?: -1
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
        if (synced != null) {
            itemsIndexed(synced) { i, line ->
                val color = when {
                    i == active -> Aoide.fg
                    i < active -> Color.Black.copy(alpha = .45f)
                    else -> Color.Black.copy(alpha = .7f)
                }
                Text(line.line.ifBlank { "♪" }, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 34.sp), color = color, modifier = Modifier.fillMaxWidth().clickable { PlayerController.seekTo((line.t * 1000).toLong()) }.padding(vertical = 6.dp).testTag("lyric_line"))
            }
        } else {
            itemsIndexed(l.plain!!.lines()) { _, line -> Text(line, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 30.sp), color = Aoide.fg) }
        }
    }
}

/** The queue, with long-press drag to reorder the upcoming songs. */
@Composable
fun QueueScreen() {
    val s by PlayerController.state.collectAsState()
    BackHandler { AppUi.queueOpen = false }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().background(Aoide.ground).statusBarsPadding().navigationBarsPadding().testTag("queue_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { AppUi.queueOpen = false }, modifier = Modifier.semantics { contentDescription = "Close queue" }.testTag("queue_close")) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Aoide.fg) }
            Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        val cur = s.current
        if (cur == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Add songs with ··· on any row.", color = Aoide.subdued) }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            item { Text("Now playing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp)) }
            item { QueueRow(cur, current = true, onClick = { PlayerController.toggle() }) }
            if (s.upcoming.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (s.context != null) "Next from: ${s.context!!.title}" else "Next in queue", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { PlayerController.clearUpcoming() }, modifier = Modifier.testTag("queue_clear")) { Text("Clear queue", color = Aoide.subdued) }
                    }
                }
                itemsIndexed(s.upcoming, key = { i, t -> "${s.index + 1 + i}-${t.id}" }) { i, t ->
                    val absolute = s.index + 1 + i
                    QueueRow(
                        t, current = false,
                        onClick = { PlayerController.jumpTo(absolute) },
                        onRemove = { PlayerController.removeAt(absolute) },
                        dragHandle = Modifier.pointerInput(absolute) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = absolute; dragOffset = 0f },
                                onDragEnd = {
                                    val from = dragging
                                    if (from != null) {
                                        val steps = (dragOffset / 64.dp.toPx()).toInt()
                                        val to = (from + steps).coerceIn(s.index + 1, s.queue.lastIndex)
                                        if (to != from) PlayerController.move(from, to)
                                    }
                                    dragging = null
                                },
                                onDragCancel = { dragging = null },
                            ) { _, dy -> dragOffset += dy }
                        },
                        lifted = dragging == absolute,
                    )
                }
            } else {
                item { Text("End of queue.", style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun QueueRow(t: Track, current: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null, dragHandle: Modifier? = null, lifted: Boolean = false) {
    val s by PlayerController.state.collectAsState()
    Row(Modifier.fillMaxWidth().background(if (lifted) Aoide.elevated else Color.Transparent).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("queue_row"), verticalAlignment = Alignment.CenterVertically) {
        Artwork(Catalog.cover(t.album?.cover, 160), Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, color = if (current) Aoide.accent else Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current && s.isPlaying) { Equaliser(); Spacer(Modifier.width(6.dp)) }
                Text(t.artistNames, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Remove ${t.title} from queue" }.testTag("queue_remove")) { Icon(Icons.Filled.RemoveCircleOutline, null, tint = Aoide.subdued) }
        if (dragHandle != null) Icon(Icons.Filled.DragHandle, "Drag to reorder", tint = Aoide.subdued, modifier = dragHandle.padding(12.dp))
    }
}
