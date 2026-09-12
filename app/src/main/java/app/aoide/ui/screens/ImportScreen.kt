package app.aoide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.Importer
import app.aoide.data.Library
import app.aoide.data.PlayContext
import app.aoide.player.PlayerController
import app.aoide.ui.Toasts
import app.aoide.ui.components.AoideField
import app.aoide.ui.components.PillButton
import app.aoide.ui.components.SectionTitle
import app.aoide.ui.components.TrackRow
import app.aoide.ui.plural
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd

private sealed class Step {
    object Idle : Step()
    object Reading : Step()
    data class Matching(val done: Int, val total: Int, val title: String) : Step()
    data class Done(val result: Importer.Result) : Step()
    data class Failed(val message: String) : Step()
}

/** Paste a Spotify or YouTube Music playlist link; every song is looked up in the catalogue and the hits become a playlist here. */
@Composable
fun ImportScreen(initialLink: String?, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var link by remember { mutableStateOf(initialLink ?: "") }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val kind = remember(link) { Importer.recognise(link) }

    fun run() {
        val (k, id) = kind ?: return
        step = Step.Reading
        scope.launch {
            try {
                step = Step.Matching(0, 0, "the playlist")
                val r = Importer.import(k, id) { d, t -> step = Step.Matching(d, t, "the playlist") }
                step = Step.Done(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step = Step.Failed(e.message ?: "Couldn't read that playlist")
            }
        }
    }
    LaunchedEffect(initialLink) { if (!initialLink.isNullOrBlank() && kind != null) run() }

    LazyColumn(Modifier.testTag("import_screen")) {
        item {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
                Text("Import a playlist", style = MaterialTheme.typography.headlineSmall)
            }
            Text("Paste a public playlist link from the music service or from the other big streaming service. Songs from elsewhere are matched here by artist, title and length; anything that doesn't match is listed, not guessed.", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AoideField(link, { link = it }, "Paste a playlist link", Modifier.weight(1f).testTag("import_link"), onDone = { if (kind != null) run() })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }, modifier = Modifier.testTag("import_paste")) { Text("Paste", color = Aoide.accent) }
            }
            // What the link was taken to be, under the field it belongs to rather than beside the button.
            val hint = when {
                link.isBlank() -> ""
                kind == null -> "That doesn't look like a playlist link Aoide can read."
                kind.first == "list" -> "Playlist from the other streaming service"
                else -> "Playlist from the music service"
            }
            if (hint.isNotBlank()) Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (kind == null) Aoide.accent else Aoide.subdued,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp).testTag("import_hint"),
            )
            // The action spans the field and its Paste button, so the block has one width and one edge.
            PillButton(
                "Import", Icons.Filled.PlaylistAdd, filled = true,
                enabled = kind != null && step !is Step.Reading && step !is Step.Matching,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("import_go"),
            ) { run() }
        }
        when (val s = step) {
            is Step.Idle -> Unit
            is Step.Reading -> item { Text("Reading the playlist…", color = Aoide.subdued, modifier = Modifier.padding(16.dp)) }
            is Step.Matching -> item {
                Column(Modifier.padding(16.dp)) {
                    Text("Matching ${s.title}: ${s.done} of ${s.total}", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, color = Aoide.accent, trackColor = Aoide.elevated2, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp))
                }
            }
            is Step.Failed -> item { Text(s.message, color = Aoide.accent, modifier = Modifier.padding(16.dp)) }
            is Step.Done -> {
                val r = s.result
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("import_title"))
                        Text("${plural(r.matched.size, "song")} matched" + (if (r.missed.isNotEmpty()) ", ${r.missed.size} not in the catalogue" else ""), style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
                        // The same pair the album and playlist heads use: two pills sharing the width.
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton("Save playlist", Icons.Filled.PlaylistAdd, filled = true, enabled = r.matched.isNotEmpty(), modifier = Modifier.weight(1f).testTag("import_save")) {
                                val p = Library.createPlaylist(r.title, r.matched, r.source)
                                Toasts.show("Saved ${p.title}. Sync it any time from the playlist.")
                                onNavigate("local/${p.id}")
                            }
                            PillButton("Play", Icons.Filled.PlayArrow, filled = false, enabled = r.matched.isNotEmpty(), modifier = Modifier.weight(1f).testTag("import_play")) {
                                PlayerController.playTracks(r.matched, 0, PlayContext("playlist", r.title))
                            }
                        }
                    }
                }
                if (r.matched.isNotEmpty()) item { SectionTitle("Found", Modifier.padding(top = 8.dp)) }
                items(r.matched, key = { it.id }) { t -> TrackRow(t, onClick = { PlayerController.playTracks(r.matched, r.matched.indexOf(t), PlayContext("playlist", r.title)) }) }
                if (r.missed.isNotEmpty()) item { SectionTitle("Not found", Modifier.padding(top = 8.dp)) }
                items(r.missed, key = { "${it.title}|${it.artists}" }) { m ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("import_missed")) {
                        Text(m.title, style = MaterialTheme.typography.bodyLarge, color = Aoide.subdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.artists, style = MaterialTheme.typography.bodyMedium, color = Aoide.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}
