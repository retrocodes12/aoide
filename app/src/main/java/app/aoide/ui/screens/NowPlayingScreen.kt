@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.aoide.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import app.aoide.ui.components.Haptics
import app.aoide.ui.components.pressable
import app.aoide.ui.components.rememberHaptics
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
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.aoide.data.Catalog
import app.aoide.data.Lyrics
import app.aoide.data.Track
import app.aoide.data.formatTime
import app.aoide.player.PlayerController
import app.aoide.player.Status
import app.aoide.player.StreamResolver
import app.aoide.ui.AppUi
import app.aoide.ui.Resource
import app.aoide.ui.components.Artwork
import app.aoide.ui.components.Equaliser
import app.aoide.ui.components.LikeButton
import app.aoide.ui.components.QualityBadge
import app.aoide.ui.rememberResource
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.Tint
import kotlin.math.roundToInt

/**
 * Full-screen player. Apple Music's stage: the artwork blurred and darkened behind everything,
 * the cover large with a deep shadow and shrinking on pause, a lossless badge under the title.
 * Spotify's furniture: the white play disc, orange for shuffle/repeat, the lyrics card below.
 * Pull down from the top half to dismiss; the sheet follows the finger.
 */
@Composable
fun NowPlayingScreen(tint: Tint, onNavigate: (String) -> Unit) {
    val s by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val t = s.current ?: return
    val info = infos[t.id]
    val failed = s.status == Status.ERROR
    BackHandler { AppUi.nowPlayingOpen = false }
    var drag by remember { mutableStateOf(0f) }
    val lyrics by rememberResource("lyrics", t.id) { Catalog.lyrics(t) }
    val artScale by animateFloatAsState(if (s.isPlaying) 1f else 0.8f, spring(dampingRatio = 0.68f, stiffness = 260f), label = "art")
    val art = Catalog.cover(t.album?.cover, 640)
    val haptics = rememberHaptics()
    // The cover is a pager over the queue: swipe it to skip, and it follows along when a song ends.
    val pager = rememberPagerState(initialPage = s.index.coerceAtLeast(0)) { s.queue.size.coerceAtLeast(1) }
    LaunchedEffect(s.index) { if (s.index >= 0 && pager.currentPage != s.index && !pager.isScrollInProgress) pager.animateScrollToPage(s.index) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val now = PlayerController.state.value
            if (page != now.index && page in now.queue.indices) { Haptics.tap(haptics); PlayerController.jumpTo(page) }
        }
    }
    Box(Modifier.fillMaxSize().offset { IntOffset(0, drag.coerceAtLeast(0f).roundToInt()) }.background(tint.accent).testTag("now_playing")) {
        // Blurred artwork backdrop (a no-op below API 31, where the tint alone carries it)
        Artwork(art, Modifier.fillMaxSize().blur(70.dp), RoundedCornerShape(0.dp))
        // The scrim is what makes white ink safe over any cover: 50% black at the top, 72% by the title, ground below.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .5f), Color.Black.copy(alpha = .72f), Aoide.ground.copy(alpha = .97f)))))
        Column(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag > 120f) AppUi.nowPlayingOpen = false; drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
                }
                .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { AppUi.nowPlayingOpen = false }, modifier = Modifier.semantics { contentDescription = "Close now playing" }.testTag("np_close")) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Aoide.fg, modifier = Modifier.size(28.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM ${s.context?.kind?.uppercase() ?: "QUEUE"}", style = MaterialTheme.typography.labelSmall, color = Aoide.fg.copy(alpha = .75f))
                    Text(s.context?.title ?: t.album?.title ?: "", style = MaterialTheme.typography.titleSmall, color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { s.context?.href?.takeIf { it.isNotEmpty() }?.let { AppUi.nowPlayingOpen = false; onNavigate(it) } })
                }
                IconButton(onClick = { AppUi.openMenu(t) }, modifier = Modifier.semantics { contentDescription = "More options" }) { Icon(Icons.Filled.MoreVert, null, tint = Aoide.fg) }
            }
            Spacer(Modifier.height(28.dp))
            HorizontalPager(pager, Modifier.fillMaxWidth().testTag("np_pager"), contentPadding = PaddingValues(horizontal = 26.dp), pageSpacing = 14.dp, beyondViewportPageCount = 1) { page ->
                val q = s.queue.getOrNull(page) ?: t
                val scale = if (page == s.index) artScale else 0.92f
                // Each page is a square of its own width, so the cover is never cropped to the pager's full-width height.
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Artwork(Catalog.cover(q.album?.cover, 640), Modifier.fillMaxSize().scale(scale).shadow(40.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = Color.Black, spotColor = Color.Black), RoundedCornerShape(12.dp), contentDescription = q.album?.title)
                }
            }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.padding(horizontal = 26.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), color = Aoide.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("np_title"))
                    Text(t.artistNames, style = MaterialTheme.typography.bodyLarge, color = Aoide.fg.copy(alpha = .78f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { t.primaryArtist?.let { AppUi.nowPlayingOpen = false; onNavigate("artist/${it.id}") } })
                    info?.label?.takeIf { it.isNotBlank() }?.let { label ->
                        Box(Modifier.padding(top = 6.dp)) { QualityBadge(if (info.isPreview) "Preview" else if (label.startsWith("FLAC 24")) "Hi-Res Lossless" else if (label.startsWith("FLAC")) "Lossless" else label, onDark = !info.isPreview, accent = info.isPreview) }
                    }
                }
                LikeButton(t, size = 28.dp, tint = Aoide.fg.copy(alpha = .85f))
            }
            if (failed) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .28f)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("np_error"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.error ?: "Couldn't play this song", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Aoide.fg, modifier = Modifier.weight(1f))
                    TextButton(onClick = { PlayerController.toggle() }, modifier = Modifier.testTag("np_retry")) { Text("Try again", color = Aoide.accent) }
                }
            }
            Spacer(Modifier.height(6.dp))
            // Only the media's own length drives the slider. Until it is known the transport waits rather than guessing.
            var scrub by remember(t.id) { mutableStateOf<Float?>(null) }
            val known = s.durationMs > 0 && !failed
            val dur = s.durationMs.coerceAtLeast(1L)
            val pos = scrub ?: (if (known) (s.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f)
            val sliderColors = SliderDefaults.colors(thumbColor = Aoide.fg, activeTrackColor = Aoide.fg.copy(alpha = .92f), inactiveTrackColor = Color.White.copy(alpha = .25f), disabledThumbColor = Aoide.fg.copy(alpha = .45f), disabledActiveTrackColor = Aoide.fg.copy(alpha = .35f), disabledInactiveTrackColor = Color.White.copy(alpha = .18f))
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Slider(
                value = pos, onValueChange = { if (known) scrub = it }, onValueChangeFinished = { scrub?.let { PlayerController.seekTo((it * dur).toLong()) }; scrub = null },
                enabled = known, interactionSource = interaction, colors = sliderColors,
                // Spotify's hairline: a 4 dp track with no gap and a small round thumb, not Material's chunky expressive bar.
                track = { state -> SliderDefaults.Track(sliderState = state, colors = sliderColors, enabled = known, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 2.dp, drawStopIndicator = null, modifier = Modifier.height(4.dp)) },
                thumb = { SliderDefaults.Thumb(interactionSource = interaction, colors = sliderColors, enabled = known, thumbSize = androidx.compose.ui.unit.DpSize(12.dp, 12.dp)) },
                modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Seek" }.testTag("np_seek"),
            )
            Row(Modifier.padding(horizontal = 26.dp).fillMaxWidth()) {
                Text(formatTime(((pos * dur) / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = Aoide.fg.copy(alpha = .7f), modifier = Modifier.testTag("np_position"))
                Spacer(Modifier.weight(1f))
                Text(if (known) "-" + formatTime((((1 - pos) * dur) / 1000).toInt()) else "-:--", style = MaterialTheme.typography.bodySmall, color = Aoide.fg.copy(alpha = .7f), modifier = Modifier.testTag("np_remaining"))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { Haptics.tap(haptics); PlayerController.toggleShuffle() }, modifier = Modifier.semantics { contentDescription = "Shuffle" }.testTag("np_shuffle")) { Icon(Icons.Filled.Shuffle, null, tint = if (s.shuffle) Aoide.accent else Aoide.fg.copy(alpha = .7f), modifier = Modifier.size(26.dp)) }
                IconButton(onClick = { PlayerController.prev() }, modifier = Modifier.semantics { contentDescription = "Previous" }.testTag("np_prev")) { Icon(Icons.Filled.SkipPrevious, null, tint = Aoide.fg, modifier = Modifier.size(42.dp)) }
                Box(Modifier.pressable { Haptics.confirm(haptics); PlayerController.toggle() }.size(72.dp).clip(CircleShape).background(Aoide.fg).semantics { contentDescription = if (failed) "Retry" else if (s.isPlaying) "Pause" else "Play" }.testTag("np_toggle"), contentAlignment = Alignment.Center) {
                    Icon(if (failed) Icons.Filled.Refresh else if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Aoide.base, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { PlayerController.next() }, modifier = Modifier.semantics { contentDescription = "Next" }.testTag("np_next")) { Icon(Icons.Filled.SkipNext, null, tint = Aoide.fg, modifier = Modifier.size(42.dp)) }
                IconButton(onClick = { Haptics.tap(haptics); PlayerController.cycleRepeat() }, modifier = Modifier.semantics { contentDescription = "Repeat" }.testTag("np_repeat")) {
                    Icon(if (s.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, null, tint = if (s.repeat != Player.REPEAT_MODE_OFF) Aoide.accent else Aoide.fg.copy(alpha = .7f), modifier = Modifier.size(26.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { AppUi.lyricsOpen = true }, modifier = Modifier.semantics { contentDescription = "Lyrics" }.testTag("np_lyrics")) { Icon(Icons.Filled.Lyrics, null, tint = Aoide.fg.copy(alpha = .8f)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { AppUi.queueOpen = true }, modifier = Modifier.semantics { contentDescription = "Queue" }.testTag("np_queue")) { Icon(Icons.Filled.QueueMusic, null, tint = Aoide.fg.copy(alpha = .8f)) }
            }
            // Lyrics card, as Spotify shows under the controls
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.accent).clickable { AppUi.lyricsOpen = true }.padding(18.dp).testTag("lyrics_card"),
            ) {
                // The card is a slice of the lyrics screen: the tint's own solved ink, never white on colour.
                Text("Lyrics", style = MaterialTheme.typography.titleSmall, color = tint.soft)
                Spacer(Modifier.height(10.dp))
                when (val l = lyrics) {
                    is Resource.Ready -> {
                        // Same rule as the lyrics screen: synced lines when there are any, else plain text, else nothing.
                        val lines = lyricLines(l.value)
                        if (lines.isEmpty()) Text("We don't have lyrics for this one.", style = MaterialTheme.typography.bodyMedium, color = tint.ink)
                        else lines.take(4).forEach { Text(it, style = MaterialTheme.typography.titleLarge, color = tint.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    is Resource.Loading -> Text("Looking for lyrics…", style = MaterialTheme.typography.bodyMedium, color = tint.ink)
                    is Resource.Failed -> Text("Lyrics unavailable", style = MaterialTheme.typography.bodyMedium, color = tint.ink)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun lyricLines(l: Lyrics?): List<String> {
    val synced = l?.synced?.takeIf { it.isNotEmpty() }
    return (synced?.map { it.line } ?: l?.plain?.lines() ?: emptyList()).filter { it.isNotBlank() }
}

@Composable
fun LyricsScreen(tint: Tint) {
    val s by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val t = s.current ?: return
    BackHandler { AppUi.lyricsOpen = false }
    val lyrics by rememberResource("lyrics", t.id) { Catalog.lyrics(t) }
    val listState = rememberLazyListState()
    val isPreview = infos[t.id]?.isPreview == true
    Column(Modifier.fillMaxSize().background(tint.accent).statusBarsPadding().navigationBarsPadding().testTag("lyrics_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = tint.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artistNames, style = MaterialTheme.typography.bodySmall, color = tint.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { AppUi.lyricsOpen = false }, modifier = Modifier.semantics { contentDescription = "Close lyrics" }.testTag("lyrics_close")) { Icon(Icons.Filled.Close, null, tint = tint.ink) }
        }
        when (val l = lyrics) {
            is Resource.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Looking for lyrics…", style = MaterialTheme.typography.titleLarge, color = tint.ink) }
            is Resource.Failed -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Lyrics unavailable", style = MaterialTheme.typography.titleLarge, color = tint.ink) }
            is Resource.Ready -> LyricsBody(l.value, s.positionMs, s.durationMs, isPreview, tint, listState, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics provided by lrclib", style = MaterialTheme.typography.bodySmall, color = tint.soft, modifier = Modifier.weight(1f))
            Box(Modifier.size(52.dp).clip(CircleShape).background(tint.ink).clickable { PlayerController.toggle() }.semantics { contentDescription = if (s.isPlaying) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
                Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = tint.accent, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun LyricsBody(l: Lyrics?, positionMs: Long, durationMs: Long, isPreview: Boolean, tint: Tint, listState: LazyListState, modifier: Modifier) {
    val synced = l?.synced?.takeIf { it.isNotEmpty() }
    val plain = l?.plain?.takeIf { it.isNotBlank() }
    if (synced == null && plain == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("We don't have lyrics for this one.", style = MaterialTheme.typography.titleLarge, color = tint.ink, textAlign = TextAlign.Center) }
        return
    }
    val pos = positionMs / 1000.0 + 0.25
    val active = synced?.indexOfLast { it.t <= pos } ?: -1
    // On a 30-second preview the lyrics still cover the whole song; lines past the clip cannot be reached.
    val reachable: (Double) -> Boolean = { t -> !isPreview || durationMs <= 0 || t < durationMs / 1000.0 - 0.5 }
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
        if (synced != null) {
            itemsIndexed(synced) { i, line ->
                val ok = reachable(line.t)
                val color = when {
                    !ok -> tint.faint
                    i == active -> tint.ink
                    i < active -> tint.faint
                    else -> tint.soft
                }
                val scale by animateFloatAsState(if (i == active) 1.04f else 1f, label = "line")
                Text(
                    line.line.ifBlank { "♪" },
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = if (ok) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
                    color = color,
                    modifier = Modifier.fillMaxWidth().scale(scale).clickable(enabled = ok) { PlayerController.seekTo((line.t * 1000).toLong()) }.padding(vertical = 6.dp).semantics { contentDescription = if (ok) "Lyric line" else "Lyric line, past the preview" }.testTag("lyric_line"),
                )
            }
            if (isPreview && synced.any { !reachable(it.t) }) {
                itemsIndexed(listOf("Only the first 30 seconds play on this mirror; the rest of the lyrics are shown for reading.")) { _, note ->
                    Text(note, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = tint.soft, modifier = Modifier.padding(top = 18.dp))
                }
            }
        } else {
            itemsIndexed(plain!!.lines()) { _, line -> Text(line, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 30.sp), color = tint.ink) }
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
            Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
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
                        TextButton(onClick = { AppUi.ask("Clear the queue?", "Clear", "Everything after the current song is removed. The song playing now keeps playing.") { PlayerController.clearUpcoming() } }, modifier = Modifier.testTag("queue_clear")) { Text("Clear queue", color = Aoide.subdued) }
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
