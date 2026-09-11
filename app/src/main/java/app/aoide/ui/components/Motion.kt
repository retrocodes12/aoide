package app.aoide.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.Tint

/**
 * Apple's press: the thing you touch shrinks a little and springs back, with no ripple. Used on
 * cards, tiles and discs; list rows keep the ripple because a whole row shrinking looks wrong.
 */
fun Modifier.pressable(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
}

/** One place for the app's touch feedback, so every control ticks the same way. */
object Haptics {
    fun tap(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun confirm(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): HapticFeedback = LocalHapticFeedback.current

/**
 * Spotify's collapsing top bar. The list scrolls its big header away; once the title has gone
 * under, a compact bar with the title fades in over the list. It is only composed once it is
 * mostly visible, so it never steals touches from the header's own controls.
 */
@Composable
fun BoxScope.CollapsingBar(listState: LazyListState, title: String, tint: Tint, threshold: Dp, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    val density = LocalDensity.current
    val progress by remember(threshold) {
        derivedStateOf {
            val px = with(density) { threshold.toPx() }
            if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / px).coerceIn(0f, 1f)
        }
    }
    if (progress < 0.35f) return
    val alpha = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val bg = lerp(tint.accent, Aoide.ground, 0.6f)
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().graphicsLayer { this.alpha = alpha }.background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = .98f)))).testTag("collapsing_bar")) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}
