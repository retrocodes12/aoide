package app.aoide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aoide.player.AudioEffects
import app.aoide.ui.components.Chip
import app.aoide.ui.theme.Aoide

/** Five bands, presets and bass boost, on Android's own audio effects. */
@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val eq by AudioEffects.state.collectAsState()
    val freqs by AudioEffects.freqs.collectAsState()
    val colors = SliderDefaults.colors(thumbColor = Aoide.fg, activeTrackColor = Aoide.accent, inactiveTrackColor = Aoide.elevated2, disabledThumbColor = Aoide.muted, disabledActiveTrackColor = Aoide.elevated2, disabledInactiveTrackColor = Aoide.elevated2)
    Column(Modifier.verticalScroll(rememberScrollState()).testTag("equalizer_screen")) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Aoide.fg) }
            Text("Equalizer", style = MaterialTheme.typography.headlineSmall)
        }
        ToggleRow("Equalizer", if (eq.enabled) "On. Shapes every song, whatever the source." else "Off. Songs play as recorded.", eq.enabled, "eq_toggle") { AudioEffects.set(eq.copy(enabled = it)) }
        Text("Presets", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AudioEffects.PRESETS.keys.toList()) { name -> Chip(name, eq.preset == name) { AudioEffects.setPreset(name); if (!eq.enabled) AudioEffects.set(AudioEffects.state.value.copy(enabled = true)) } }
        }
        Spacer(Modifier.height(18.dp))
        freqs.forEachIndexed { i, hz ->
            val db = eq.bands.getOrElse(i) { 0 }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, modifier = Modifier.width(60.dp))
                Slider(
                    value = db.toFloat(), onValueChange = { AudioEffects.setBand(i, it.toInt()) }, valueRange = -15f..15f, enabled = eq.enabled, colors = colors,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Band $hz Hz" }.testTag("eq_band"),
                )
                Text((if (db > 0) "+$db" else "$db") + " dB", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (eq.enabled) Aoide.fg else Aoide.subdued, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bass boost", style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, modifier = Modifier.width(60.dp))
            Slider(value = eq.bass.toFloat(), onValueChange = { AudioEffects.set(eq.copy(bass = it.toInt())) }, valueRange = 0f..100f, enabled = eq.enabled, colors = colors, modifier = Modifier.weight(1f).semantics { contentDescription = "Bass boost" }.testTag("eq_bass"))
            Text("${eq.bass}%", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (eq.enabled) Aoide.fg else Aoide.subdued, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        }
        Text("Runs on Android's built-in Equalizer and BassBoost effects, attached to Aoide's own audio session, so it never touches other apps. Bands and ranges are whatever this phone's audio chip offers.", style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp))
        Spacer(Modifier.height(160.dp))
    }
}
