package app.aoide.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.aoide.player.SleepTimer
import app.aoide.ui.Toasts
import app.aoide.ui.theme.Aoide

/** Sleep timer choices, Spotify's list: minutes, or the end of the current song. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(onDismiss: () -> Unit) {
    val endAt by SleepTimer.endAt.collectAsState()
    val endOfTrack by SleepTimer.endOfTrack.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aoide.elevated2, scrimColor = SheetScrim, dragHandle = null, modifier = Modifier.testTag("sleep_sheet")) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
            val label = SleepTimer.label()
            if (label != null) Text("Music stops: $label", style = MaterialTheme.typography.bodySmall, color = Aoide.accent, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp))
            listOf(5, 10, 15, 30, 45, 60).forEach { m ->
                val chosen = endAt != null && !endOfTrack && SleepTimer.remainingMs() in ((m - 1) * 60_000L)..(m * 60_000L)
                Option("$m minutes", chosen) { SleepTimer.setMinutes(m); Toasts.show("Music stops in $m minutes"); onDismiss() }
            }
            Option("End of this song", endOfTrack) { SleepTimer.setEndOfTrack(); Toasts.show("Music stops after this song"); onDismiss() }
            if (SleepTimer.isSet) Option("Turn off timer", false) { SleepTimer.cancel(); Toasts.show("Sleep timer off"); onDismiss() }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Option(text: String, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp).testTag("sleep_option"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (chosen) Aoide.accent else Aoide.fg, modifier = Modifier.weight(1f))
        if (chosen) Icon(Icons.Filled.Check, null, tint = Aoide.accent)
    }
}
