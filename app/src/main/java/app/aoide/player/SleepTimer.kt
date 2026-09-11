package app.aoide.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stops playback later: at a clock time, or when the current song ends. The service watches it. */
object SleepTimer {
    private val _endAt = MutableStateFlow<Long?>(null)
    val endAt: StateFlow<Long?> = _endAt
    private val _endOfTrack = MutableStateFlow(false)
    val endOfTrack: StateFlow<Boolean> = _endOfTrack

    val isSet: Boolean get() = _endAt.value != null || _endOfTrack.value

    fun setMinutes(m: Int) { _endOfTrack.value = false; _endAt.value = System.currentTimeMillis() + m * 60_000L }
    fun setEndOfTrack() { _endAt.value = null; _endOfTrack.value = true }
    fun cancel() { _endAt.value = null; _endOfTrack.value = false }
    fun remainingMs(): Long {
        val end = _endAt.value ?: return 0L
        return end - System.currentTimeMillis()
    }

    fun label(): String? = when {
        _endOfTrack.value -> "After this song"
        _endAt.value != null -> "${((remainingMs() + 59_999) / 60_000).coerceAtLeast(0)} min left"
        else -> null
    }
}
