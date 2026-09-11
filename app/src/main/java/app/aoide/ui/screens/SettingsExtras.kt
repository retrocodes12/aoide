package app.aoide.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aoide.data.Downloads
import app.aoide.data.Library
import app.aoide.data.Prefs
import app.aoide.data.Translate
import app.aoide.data.Updates
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import app.aoide.player.PlayerController
import app.aoide.ui.Toasts
import app.aoide.ui.components.Chip
import app.aoide.ui.components.OutlinePill
import app.aoide.ui.components.formatBytes
import app.aoide.ui.plural
import app.aoide.ui.theme.Aoide

@Composable
internal fun PlaybackSettings() {
    val dataSaver by Prefs.dataSaver.value.collectAsState()
    val fade by Prefs.fadeMs.collectAsState()
    val speed by Prefs.speed.collectAsState()
    val skip by Prefs.skipSilence.value.collectAsState()
    val mute by Prefs.pauseOnMute.value.collectAsState()
    val bt by Prefs.resumeOnBluetooth.value.collectAsState()
    val auto by Prefs.autoplay.value.collectAsState()
    Section("Playback", "How songs start, end and follow one another.")
    ToggleRow("Data saver", if (dataSaver) "On. Mobile data gets the smallest stream; Wi-Fi keeps your quality." else "Off. Every connection gets the quality above.", dataSaver, "data_saver_toggle") { Prefs.dataSaver.set(it); PlayerController.reloadCurrent() }
    ChoiceRow("Fade between songs", if (fade == 0) "Off. Songs cut straight from one to the next." else "The end of a song fades out and the next fades in over ${fade / 1000} s. Play and pause fade too.", listOf(0, 1000, 2000, 3000, 5000), fade, { if (it == 0) "Off" else "${it / 1000} s" }, "fade_choice") { Prefs.setFadeMs(it) }
    ChoiceRow("Speed", if (speed == 1f) "Normal speed." else "Songs play at ${speed}× with the pitch kept.", listOf(0.75f, 1f, 1.25f, 1.5f, 2f), speed, { "${it}×" }, "speed_choice") { Prefs.setSpeed(it) }
    ToggleRow("Skip silence", if (skip) "On. Silent stretches inside a song are jumped over." else "Off.", skip, "skip_silence_toggle") { Prefs.skipSilence.set(it) }
    ToggleRow("Pause when muted", if (mute) "On. Turning the volume to zero pauses; turning it back up resumes." else "Off.", mute, "pause_mute_toggle") { Prefs.pauseOnMute.set(it) }
    ToggleRow("Resume on headphones", if (bt) "On. Music paused by unplugging or a lost Bluetooth connection resumes when they come back." else "Off.", bt, "resume_bt_toggle") { Prefs.resumeOnBluetooth.set(it) }
    ToggleRow("Autoplay", if (auto) "On. When the queue runs out, songs like the last one keep it going." else "Off. Playback stops at the end of the queue.", auto, "autoplay_toggle") { Prefs.autoplay.set(it) }
}

@Composable
private fun <T> ChoiceRow(title: String, body: String, options: List<T>, chosen: T, label: (T) -> String, tag: String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag(tag)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { o -> Chip(label(o), o == chosen) { onPick(o) } }
        }
    }
}

@Composable
internal fun FeatureRows(onNavigate: (String) -> Unit) {
    val downloads by Downloads.all.collectAsState()
    val lib by Library.state.collectAsState()
    Section("More", "")
    FeatureRow(Icons.Filled.GraphicEq, "Equalizer", if (app.aoide.player.AudioEffects.state.value.enabled) "On · ${app.aoide.player.AudioEffects.state.value.preset}" else "Off", "row_equalizer") { onNavigate("equalizer") }
    FeatureRow(Icons.Filled.Download, "Downloads", "${plural(downloads.size, "song")} · ${formatBytes(downloads.values.sumOf { it.bytes })}", "row_downloads") { onNavigate("downloads") }
    FeatureRow(Icons.Filled.History, "History", "${plural(lib.plays.values.sum(), "play")} on this phone", "row_history") { onNavigate("history") }
    FeatureRow(Icons.Filled.FolderOpen, "On this phone", "Music files already on the device", "row_local") { onNavigate("local_files") }
    FeatureRow(Icons.Filled.PlaylistAdd, "Import a playlist", "From another music service, by link", "row_import") { onNavigate("import") }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, sub: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Aoide.subdued, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Aoide.muted)
    }
}

@Composable
internal fun LookSettings() {
    val accent by Prefs.accent.collectAsState()
    val black by Prefs.pureBlack.value.collectAsState()
    Section("Look", "Orange is the house colour; the rest are for people who miss green.")
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("accent_row"), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Aoide.ACCENTS.forEach { (name, color) ->
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(color).border(2.dp, if (accent == name) Aoide.fg else Color.Transparent, CircleShape)
                    .clickable { Prefs.setAccent(name); Aoide.apply(name, Prefs.pureBlack.on) }
                    .semantics { contentDescription = "Accent $name" }.testTag("accent_$name"),
                contentAlignment = Alignment.Center,
            ) { if (accent == name) Icon(Icons.Filled.Check, null, tint = Aoide.accentInk) }
        }
    }
    ToggleRow("Pure black", if (black) "On. True black behind everything, for OLED screens." else "Off. Spotify's near-black grey.", black, "pure_black_toggle") { Prefs.pureBlack.set(it); Aoide.apply(Prefs.accent.value, it) }
}

@Composable
internal fun LyricsSettings() {
    val translate by Prefs.translateLyrics.value.collectAsState()
    val lang by Prefs.lyricsLang.collectAsState()
    val size by Prefs.lyricsSize.collectAsState()
    Section("Lyrics", "From lrclib.net, synced when they have it.")
    ToggleRow("Translate lyrics", if (translate) "On. A translation sits under each line, through Google Translate." else "Off.", translate, "translate_toggle") { Prefs.translateLyrics.set(it) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Translate.LANGUAGES) { (code, name) -> Chip(name, lang == code) { Prefs.setLyricsLang(code) } }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
        listOf(22 to "Small", 26 to "Medium", 32 to "Large").forEach { (sp, label) -> Chip(label, size == sp) { Prefs.setLyricsSize(sp) } }
    }
}

@Composable
internal fun BackupSettings() {
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(Library.export().toByteArray()) } }.isSuccess
        Toasts.show(if (ok) "Library saved" else "Couldn't write that file")
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
        Toasts.show(if (text != null && Library.import(text)) "Library restored" else "That file isn't an Aoide backup")
    }
    Section("Backup", "Liked songs, saved albums, followed artists, playlists and history as one JSON file. Downloads are not included.")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinePill("Save a backup") { export.launch("aoide-library.json") }
        OutlinePill("Restore") { import.launch(arrayOf("application/json", "*/*")) }
    }
}

/** Version, the last check, and the download-then-install flow. Nothing installs until the listener taps Install. */
@Composable
internal fun UpdatesSection() {
    val st by Updates.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Section("Updates", "Aoide looks at its releases page once a day and updates in place. Every build is signed with the same key, so the installer accepts the new one over the old.")
    Column(Modifier.padding(horizontal = 16.dp).testTag("updates_section")) {
        Text("Version ${Updates.current}", style = MaterialTheme.typography.bodyLarge)
        val status = when (val s = st) {
            is Updates.State.Idle -> "Not checked yet."
            is Updates.State.Checking -> "Checking…"
            is Updates.State.UpToDate -> "You're on the latest version."
            is Updates.State.Available -> "Aoide ${s.release.version} is out" + (if (s.release.apkBytes > 0) " · ${formatBytes(s.release.apkBytes)}" else "")
            is Updates.State.Downloading -> "Downloading ${s.release.version}: ${(s.fraction * 100).toInt()}%"
            is Updates.State.Ready -> "Aoide ${s.release.version} is downloaded. Tap Install to finish."
            is Updates.State.Failed -> s.message
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (st is Updates.State.Available || st is Updates.State.Ready) Aoide.accent else Aoide.subdued, modifier = Modifier.padding(top = 2.dp).testTag("update_status"))
        (st as? Updates.State.Downloading)?.let { d -> LinearProgressIndicator(progress = { d.fraction }, color = Aoide.accent, trackColor = Aoide.elevated2, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp)) }
        val notes = (st as? Updates.State.Available)?.release?.notes ?: (st as? Updates.State.Ready)?.release?.notes
        if (!notes.isNullOrBlank()) Text(notes, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, maxLines = 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).testTag("update_notes"))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = st) {
                is Updates.State.Available -> OutlinePill("Download") { scope.launch { Updates.download(context, s.release) } }
                is Updates.State.Ready -> OutlinePill("Install") { if (!Updates.install(context, s.file)) Toasts.show("Allow Aoide to install apps, then tap Install again.") }
                is Updates.State.Downloading -> Unit
                is Updates.State.Failed -> {
                    if (s.release != null) OutlinePill("Retry download") { scope.launch { Updates.download(context, s.release) } }
                    OutlinePill("Check again") { scope.launch { Updates.check(force = true) } }
                }
                else -> OutlinePill("Check for updates", enabled = st !is Updates.State.Checking) { scope.launch { Updates.check(force = true) } }
            }
            OutlinePill("Release notes") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse((st as? Updates.State.Available)?.release?.page ?: Updates.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
}
