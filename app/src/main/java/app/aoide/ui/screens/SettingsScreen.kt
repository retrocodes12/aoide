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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.aoide.data.ApiClient
import app.aoide.data.Instance
import app.aoide.data.Instances
import app.aoide.data.Prefs
import app.aoide.data.Quality
import app.aoide.data.json
import app.aoide.player.PlayerController
import app.aoide.player.StreamResolver
import app.aoide.ui.Toasts
import app.aoide.ui.components.OutlinePill
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val quality by Prefs.quality.collectAsState()
    val instances by Instances.list.collectAsState()
    val health by Instances.health.collectAsState()
    val anyFull by Instances.anyFull.collectAsState()
    val player by PlayerController.state.collectAsState()
    val infos by StreamResolver.infos.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    val status = remember { mutableStateMapOf<String, String>() }
    var checking by remember { mutableStateOf<String?>(null) }
    // A benched mirror stays benched for 90 s; re-probe on entry so the labels describe now, not the last failure.
    LaunchedEffect(Unit) { Instances.probe() }
    val current = player.current?.let { infos[it.id] }

    fun test(inst: Instance) {
        checking = inst.url
        scope.launch {
            val t0 = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                var last = "no answer"
                repeat(3) {
                    runCatching {
                        ApiClient.http.newCall(Request.Builder().url("${inst.url}/track/?id=${Instances.PROBE_TRACK}&quality=LOSSLESS").header("User-Agent", ApiClient.UA).build()).execute().use { res ->
                            if (res.isSuccessful) {
                                val body = res.body?.string() ?: ""
                                val full = runCatching { json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["assetPresentation"]!!.jsonPrimitive.content == "FULL" }.getOrDefault(false)
                                if (body.contains("\"manifest\"")) {
                                    Instances.noteFull(inst.url, full)
                                    return@withContext "${if (full) "full songs, lossless" else "30-second previews only"} · ${System.currentTimeMillis() - t0} ms"
                                }
                                last = "answering, but its upstream is down"
                            } else last = "HTTP ${res.code}"
                        }
                    }.onFailure { last = "no answer" }
                }
                last
            }
            status[inst.url] = result
            checking = null
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).testTag("settings")) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        UpdatesSection()

        Section("Streaming quality", if (anyFull) "Lossless and Hi-Res come from your lossless mirror when it has the song (those songs wear an HD mark); everything else plays as Opus from the music service. Applies to the next song, and reloads the one playing." else "Songs play as Opus from the music service. Lossless and Hi-Res need a lossless mirror, set up below; until then they pick the best Opus stream.")
        Quality.entries.forEach { q ->
            val pick = { Prefs.setQuality(q); PlayerController.reloadCurrent(); if (player.current != null) Toasts.show("${q.label}. Reloading the current song.") }
            // A full-width row, label at the page's 16 dp edge, an orange check on the right when chosen.
            Row(Modifier.fillMaxWidth().clickable(onClick = pick).padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = "Quality ${q.label}" }.testTag("quality_${q.name}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(q.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (quality == q && current != null) "${q.note} This song is playing as ${current.label}${if (current.source == "mirror") " from your mirror" else ""}." else q.note,
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

        Section("Lossless mirrors", "A catalogue mirror backed by a subscribed account serves songs as FLAC. Aoide finds each song on the mirror by artist, title and length, marks it HD, and plays the FLAC when your quality is Lossless or Hi-Res. Public mirrors only preview, which is no use here; add your own. Yours are tried first.")
        instances.forEach { inst ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(Aoide.highlight).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("instance_row"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(inst.host, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val cooling = health >= 0 && inst.isCooling
                    val tags = listOfNotNull(if (inst.isUser) "yours" else "public", inst.version?.takeIf { it != "custom" }?.let { "v$it" }, if (cooling) "not answering" else null, when (inst.full) { true -> "full songs"; false -> "previews only"; null -> null }, inst.lastLatencyMs.takeIf { it >= 0 && !cooling }?.let { "$it ms" })
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (inst.full == true) Aoide.accent else Aoide.subdued, modifier = Modifier.testTag("instance_tags"))
                    val st = if (checking == inst.url) "checking…" else status[inst.url]
                    if (st != null) Text(st, style = MaterialTheme.typography.bodySmall, color = if (st.contains("lossless")) Aoide.accent else Aoide.subdued, modifier = Modifier.testTag("instance_status"))
                }
                OutlinePill("Test", enabled = checking == null) { if (checking == null) test(inst) }
                IconButton(onClick = { Instances.remove(inst.url) }, modifier = Modifier.semantics { contentDescription = "Remove ${inst.host}" }) { Icon(Icons.Filled.Delete, null, tint = Aoide.subdued) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            app.aoide.ui.components.AoideField(url, { url = it }, "https://your-mirror.example", Modifier.weight(1f).testTag("instance_url"))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (Instances.add(url)) { Toasts.show("Mirror added. It will be tried first."); url = ""; scope.launch { Instances.probe() } } else Toasts.show("Not a valid https origin, or already listed.") }, modifier = Modifier.testTag("instance_add")) { Text("Add", color = Aoide.accent) }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePill("Reset") { Instances.reset(); Toasts.show("Mirrors reset") }
            OutlinePill("Clear cache") { ApiClient.clearCache(); Toasts.show("Cache cleared") }
        }

        Section("About", "Aoide ${app.aoide.BuildConfig.VERSION_NAME}, the muse of song. A phone player in the shape of the big streaming apps, with the polish of the premium ones. Liked songs, playlists, downloads and history stay on this phone; nothing leaves it.\n\nAlso on the car screen through Android Auto, and on the home screen as a widget (long-press the launcher, Widgets, Aoide).\n\nCatalogue, songs and radio come from a public music service's own interfaces; lossless from any mirror you add; lyrics from a community database and the service; translations from a public translation service. Aoide is not affiliated with or endorsed by any of them.")
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
