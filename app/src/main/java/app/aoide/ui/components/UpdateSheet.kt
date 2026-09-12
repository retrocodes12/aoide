package app.aoide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aoide.data.Updates
import app.aoide.ui.Toasts
import app.aoide.ui.theme.Aoide
import kotlinx.coroutines.launch

/**
 * What a new build says for itself, offered once when the app is opened rather than waiting to be
 * found in Settings: what version it is, what changed, and a single button that carries it all the
 * way from download to the installer. "Next time" only quiets it until the next launch; the banner
 * on Home stays either way.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(onDismiss: () -> Unit) {
    val st by Updates.state.collectAsState()
    val release = when (val s = st) {
        is Updates.State.Available -> s.release
        is Updates.State.Downloading -> s.release
        is Updates.State.Ready -> s.release
        is Updates.State.Failed -> s.release
        else -> null
    } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aoide.elevated2, scrimColor = SheetScrim, dragHandle = null, modifier = Modifier.testTag("update_sheet")) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Aoide.accent.copy(alpha = .16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Download, null, tint = Aoide.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Update available", style = MaterialTheme.typography.labelSmall, color = Aoide.accent)
            }
            Spacer(Modifier.height(14.dp))
            Text("Version ${release.version} is available", style = MaterialTheme.typography.headlineSmall, color = Aoide.fg)
            Text(
                "You have ${Updates.current}" + (release.apkBytes.takeIf { it > 0 }?.let { " · ${formatBytes(it)} to download" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Aoide.subdued, modifier = Modifier.padding(top = 4.dp),
            )

            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("What's new", style = MaterialTheme.typography.titleSmall, color = Aoide.accent)
                // The notes are as long as the release made them; the sheet gives them room and then scrolls.
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    Text(release.notes, style = MaterialTheme.typography.bodyMedium, color = Aoide.subdued, modifier = Modifier.testTag("update_notes"))
                }
            }

            when (val s = st) {
                is Updates.State.Downloading -> {
                    Spacer(Modifier.height(18.dp))
                    Text("Downloading… ${(s.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = Aoide.fg)
                    LinearProgressIndicator(
                        progress = { s.fraction }, color = Aoide.accent, trackColor = Aoide.elevated,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).testTag("update_progress"),
                    )
                }
                is Updates.State.Failed -> {
                    Spacer(Modifier.height(14.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Aoide.accent)
                }
                else -> Unit
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (st !is Updates.State.Downloading) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("update_later")) {
                        Text("Next time", style = MaterialTheme.typography.labelLarge, color = Aoide.subdued)
                    }
                }
                Spacer(Modifier.weight(1f))
                when (val s = st) {
                    is Updates.State.Ready -> PillButton("Install", Icons.Filled.InstallMobile, filled = true, modifier = Modifier.testTag("update_install")) {
                        if (!Updates.install(context, s.file)) Toasts.show("Allow Aoide to install apps, then tap Install again.")
                    }
                    is Updates.State.Downloading -> Text("${(s.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = Aoide.accent)
                    is Updates.State.Failed -> PillButton("Try again", Icons.Filled.Download, filled = true, modifier = Modifier.testTag("update_retry")) {
                        scope.launch { Updates.download(context, release) }
                    }
                    else -> PillButton("Update", Icons.Filled.Download, filled = true, modifier = Modifier.testTag("update_now")) {
                        scope.launch { Updates.download(context, release) }
                    }
                }
            }
        }
    }
}
