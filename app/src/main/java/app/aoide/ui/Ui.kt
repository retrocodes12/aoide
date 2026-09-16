package app.aoide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aoide.data.Track
import app.aoide.ui.theme.Tint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A destructive action waiting for the listener's say-so; rendered as a bottom sheet, never a system dialog. */
data class Confirm(val title: String, val action: String, val body: String? = null, val onConfirm: () -> Unit)

/**
 * Cross-screen UI state: overlays, toasts, the track menu, and the player's tint. [player] belongs
 * to the song that is playing (mini player, now playing, lyrics); browsing another record must
 * never recolour the capsule of the song still playing, so page heads keep their own tints.
 */
object AppUi {
    var nowPlayingOpen by mutableStateOf(false)
    var lyricsOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var sleepOpen by mutableStateOf(false)
    /** The update card, offered once a launch when a newer build is found. */
    var updateOpen by mutableStateOf(false)
    /** Set once the card has been answered, so it does not reappear until the app is opened again. */
    var updateAnswered by mutableStateOf(false)
    var menuTrack by mutableStateOf<Track?>(null)
    var menuRemove by mutableStateOf<(() -> Unit)?>(null)
    /** The list the song was opened from and its place in it, for "play from here" and "add the rest to the queue". */
    var menuList by mutableStateOf<List<Track>?>(null)
    var menuIndex by mutableStateOf(-1)
    var menuRemoveLabel by mutableStateOf("Remove from this playlist")
    var confirm by mutableStateOf<Confirm?>(null)
    var player by mutableStateOf(Tint.FALLBACK)

    fun openMenu(t: Track, onRemove: (() -> Unit)? = null, list: List<Track>? = null, index: Int = -1, removeLabel: String = "Remove from this playlist") {
        menuTrack = t
        menuRemove = onRemove
        menuList = list
        menuIndex = index
        menuRemoveLabel = removeLabel
    }

    fun ask(title: String, action: String, body: String? = null, onConfirm: () -> Unit) {
        confirm = Confirm(title, action, body, onConfirm)
    }

    fun closeOverlays() {
        nowPlayingOpen = false
        lyricsOpen = false
        queueOpen = false
        sleepOpen = false
        confirm = null
    }
}

/** A route the NavHost should go to next: launcher shortcuts, shared links and the tests all hand one in here. */
object TestNav {
    var request by mutableStateOf<String?>(null)
    fun go(route: String) { request = route }
}

object Toasts {
    private val _current = MutableStateFlow<Pair<Long, String>?>(null)
    val current: StateFlow<Pair<Long, String>?> = _current
    fun show(text: String) {
        _current.value = System.currentTimeMillis() to text
    }
    fun clear() {
        _current.value = null
    }
}

/** A loaded / loading / failed slot for one async resource. */
sealed class Resource<out T> {
    /** Set by [rememberResource]; lets a screen ask for another attempt. */
    var reloader: (() -> Unit)? = null
    object Loading : Resource<Nothing>()
    data class Ready<T>(val value: T) : Resource<T>()
    data class Failed(val error: Throwable) : Resource<Nothing>()
}

fun Resource<*>.reload() = reloader?.invoke()

@Composable
fun <T> rememberResource(vararg keys: Any?, loader: suspend () -> T): androidx.compose.runtime.State<Resource<T>> {
    val state = androidx.compose.runtime.remember(*keys) { mutableStateOf<Resource<T>>(Resource.Loading) }
    var tick by androidx.compose.runtime.remember(*keys) { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tick, *keys) {
        state.value = Resource.Loading
        val next: Resource<T> = try {
            Resource.Ready(loader())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Resource.Failed(e)
        }
        next.reloader = { tick++ }
        state.value = next
    }
    return state
}

/** "1 song", "12 songs". */
fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

/** Mood tiles carry their titles across navigation, which only passes ids. */
object MoodTitles {
    private val titles = HashMap<String, String>()
    fun put(key: String, title: String) { titles[key] = title }
    fun get(key: String): String? = titles[key]
}
