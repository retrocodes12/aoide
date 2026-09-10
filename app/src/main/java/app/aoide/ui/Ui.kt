package app.aoide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.aoide.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Cross-screen UI state: overlays, toasts, the track menu, and the page tint. */
object AppUi {
    var nowPlayingOpen by mutableStateOf(false)
    var lyricsOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var menuTrack by mutableStateOf<Track?>(null)
    var menuRemove by mutableStateOf<(() -> Unit)?>(null)
    var tint by mutableStateOf(Color(0xFF4A4A4A))

    fun openMenu(t: Track, onRemove: (() -> Unit)? = null) {
        menuTrack = t
        menuRemove = onRemove
    }

    fun closeOverlays() {
        nowPlayingOpen = false
        lyricsOpen = false
        queueOpen = false
    }
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
