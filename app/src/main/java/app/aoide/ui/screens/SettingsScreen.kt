package app.aoide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aoide.data.ApiClient
import app.aoide.data.Instance
import app.aoide.data.Instances
import app.aoide.data.Prefs
import app.aoide.data.Quality
import app.aoide.data.json
import app.aoide.player.PlayerController
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
fun SettingsScreen(onBack: () -> Unit) {
    val quality by Prefs.quality.collectAsState()
    val instances by Instances.list.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    val status = remember { mutableStateMapOf<String, String>() }
    var checking by remember { mutableStateOf<String?>(null) }

    fun test(inst: Instance) {
        checking = inst.url
        scope.launch {
            val t0 = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                var last = "no answer"
                repeat(3) {
                    runCatching {
                        ApiClient.http.newCall(Request.Builder().url("${inst.url}/track/?id=58990486&quality=LOSSLESS").header("User-Agent", ApiClient.UA).build()).execute().use { res ->
                            if (res.isSuccessful) {
                                val body = res.body?.string() ?: ""
                                val full = runCatching { json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["assetPresentation"]!!.jsonPrimitive.content == "FULL" }.getOrDefault(false)
                                return@withContext "${if (full) "full tracks" else "30 s previews"} · ${System.currentTimeMillis() - t0} ms"
                            }
                            last = "HTTP ${res.code}"
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
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Streaming quality", "Applies to the next song, and reloads the one playing.")
        Quality.entries.forEach { q ->
            Row(Modifier.fillMaxWidth().clickable { Prefs.setQuality(q); PlayerController.reloadCurrent() }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("quality_${q.name}"), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = quality == q, onClick = { Prefs.setQuality(q); PlayerController.reloadCurrent() }, colors = RadioButtonDefaults.colors(selectedColor = Aoide.accent, unselectedColor = Aoide.subdued))
                Column { Text(q.label, style = MaterialTheme.typography.bodyLarge); Text(q.note, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued) }
            }
        }

        Section("Instances", "Aoide reads the catalogue from hifi-api compatible mirrors, the same ones Monochrome lists, and fails over between them. Public mirrors usually serve 30-second previews; a mirror backed by a subscribed account serves full songs. Yours are tried first.")
        instances.forEach { inst ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(Aoide.highlight).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("instance_row"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(inst.host, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val tags = listOfNotNull(if (inst.isUser) "yours" else "public", inst.version?.let { "v$it" }, if (inst.coolUntil > System.currentTimeMillis()) "cooling down" else null, inst.lastLatencyMs.takeIf { it >= 0 }?.let { "$it ms" })
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Aoide.muted)
                    val st = if (checking == inst.url) "checking…" else status[inst.url]
                    if (st != null) Text(st, style = MaterialTheme.typography.bodySmall, color = if (st.contains("ms")) Aoide.accent else Aoide.subdued, modifier = Modifier.testTag("instance_status"))
                }
                OutlinePill("Test") { if (checking == null) test(inst) }
                IconButton(onClick = { Instances.remove(inst.url) }, modifier = Modifier.semantics { contentDescription = "Remove ${inst.host}" }) { Icon(Icons.Filled.Delete, null, tint = Aoide.subdued) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, placeholder = { Text("https://your-hifi-api.example") }, singleLine = true, modifier = Modifier.weight(1f).testTag("instance_url"))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (Instances.add(url)) { Toasts.show("Instance added. It will be tried first."); url = "" } else Toasts.show("Not a valid https origin, or already listed.") }, modifier = Modifier.testTag("instance_add")) { Text("Add", color = Aoide.accent) }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePill("Refresh public list") { scope.launch { withContext(Dispatchers.IO) { Instances.refreshFromUptime() }; Toasts.show("Refreshed") } }
            OutlinePill("Reset") { Instances.reset(); Toasts.show("Instances reset") }
            OutlinePill("Clear cache") { ApiClient.clearCache(); Toasts.show("Cache cleared") }
        }

        Section("About", "Aoide, the muse of song. A Spotify-shaped player on the Monochrome catalogue. Liked songs, playlists and history stay on this phone; nothing leaves it.\n\nSources: Monochrome hifi-api mirrors, TIDAL public catalogue, lrclib.net lyrics. Not affiliated with Spotify, TIDAL or Monochrome.")
        Spacer(Modifier.height(160.dp))
    }
}

@Composable
private fun Section(title: String, hint: String) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 28.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(top = 4.dp))
    }
}
