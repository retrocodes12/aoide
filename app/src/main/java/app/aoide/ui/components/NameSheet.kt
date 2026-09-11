package app.aoide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.aoide.ui.theme.Aoide

/**
 * "Give your playlist a name": a bottom sheet with one field and one orange pill, the same
 * furniture as the track menu and the confirm sheet, instead of a stock dialog. Enter commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameSheet(title: String, initial: String, action: String, fieldTag: String, actionTag: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val commit = { if (name.isNotBlank()) onConfirm(name.trim()) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aoide.elevated2, scrimColor = SheetScrim, dragHandle = null) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Aoide.highlight, unfocusedContainerColor = Aoide.highlight,
                        focusedTextColor = Aoide.fg, unfocusedTextColor = Aoide.fg, cursorColor = Aoide.accent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag(fieldTag),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.height(50.dp).clip(RoundedCornerShape(10.dp)).background(if (name.isBlank()) Aoide.elevated else Aoide.accent).clickable(enabled = name.isNotBlank()) { commit() }.padding(horizontal = 18.dp).testTag(actionTag), contentAlignment = Alignment.Center) {
                    Text(action, style = MaterialTheme.typography.labelLarge, color = if (name.isBlank()) Aoide.subdued else Aoide.accentInk)
                }
            }
        }
    }
}

/** Every sheet dims the page the same amount: enough that the hero behind the track menu stops competing with it. */
val SheetScrim: Color = Color.Black.copy(alpha = .58f)

/** The one text field: filled, rounded, orange caret, no Material focus ring. Enter commits when [onDone] is given. */
@Composable
fun AoideField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    TextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        placeholder = { Text(placeholder, color = Aoide.subdued) },
        keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Aoide.highlight, unfocusedContainerColor = Aoide.highlight,
            focusedTextColor = Aoide.fg, unfocusedTextColor = Aoide.fg, cursorColor = Aoide.accent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}
