package app.aoide.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.aoide.data.ApiClient
import app.aoide.data.Prefs
import app.aoide.data.Quality
import app.aoide.player.PlayerController
import app.aoide.player.StreamResolver
import app.aoide.ui.Toasts
import app.aoide.ui.components.OutlinePill
import app.aoide.ui.theme.Aoide

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val quality by Prefs.quality.collectAsState()
    val player by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val context = LocalContext.current
    val current = player.current?.let { infos[it.id] }

    Column(Modifier.verticalScroll(rememberScrollState()).testTag("settings")) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        UpdatesSection()

        Section("Streaming quality", "Everything Aoide plays is free to reach, so the ceiling is what the sources give away: AAC 320 kbps from the second source for the songs it carries, and Opus otherwise. Applies to the next song, and reloads the one playing.")
        Quality.entries.forEach { q ->
            val pick = { Prefs.setQuality(q); PlayerController.reloadCurrent(); if (player.current != null) Toasts.show("${q.label}. Reloading the current song.") }
            // A full-width row, label at the page's 16 dp edge, an orange check on the right when chosen.
            Row(Modifier.fillMaxWidth().clickable(onClick = pick).padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = "Quality ${q.label}" }.testTag("quality_${q.name}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(q.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (quality == q && current != null) "${q.note} This song is playing as ${current.label}." else q.note,
                        style = MaterialTheme.typography.bodySmall, color = Aoide.subdued,
                    )
                }
                if (quality == q) Icon(Icons.Filled.Check, null, tint = Aoide.accent, modifier = Modifier.padding(start = 12.dp))
            }
        }

        PlaybackSettings()
        FeatureRows(onNavigate)
        LookSettings()
        LyricsSettings()
        BackupSettings()

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Section("Notifications", "Notifications are off, so there is no media notification and no lock-screen control. Turn them on in the system settings.")
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinePill("Open notification settings") {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
        }

        Section("Storage", "Catalogue pages are remembered for a quarter of an hour so screens open instantly. Clearing that costs nothing but a reload.")
        Row(Modifier.padding(horizontal = 16.dp)) {
            OutlinePill("Clear cache") { ApiClient.clearCache(); Toasts.show("Cache cleared") }
        }

        Section("About", "Aoide ${app.aoide.BuildConfig.VERSION_NAME}, the muse of song. A phone player in the shape of the big streaming apps, with the polish of the premium ones. Liked songs, playlists, downloads and history stay on this phone; nothing leaves it.\n\nAlso on the car screen through Android Auto, and on the home screen as a widget (long-press the launcher, Widgets, Aoide).\n\nCatalogue, songs and radio come from a public music service's own interfaces; higher-bitrate audio from a second source where it has the song; lyrics from a community database and the service; translations from a public translation service. Aoide is not affiliated with or endorsed by any of them.")
        Spacer(Modifier.height(160.dp))
    }
}

@Composable
internal fun Section(title: String, hint: String) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 28.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A switch: a pill that fills orange, a dark knob that slides across. */
@Composable
internal fun ToggleRow(title: String, body: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = title; stateDescription = if (on) "On" else "Off" }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued)
        }
        Spacer(Modifier.width(12.dp))
        val knob by animateDpAsState(if (on) 18.dp else 0.dp, label = "knob")
        Box(Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(50)).background(if (on) Aoide.accent else Aoide.elevated2).padding(3.dp)) {
            Box(Modifier.offset(x = knob).size(20.dp).clip(CircleShape).background(if (on) Aoide.accentInk else Aoide.subdued))
        }
    }
}
