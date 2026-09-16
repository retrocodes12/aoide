package app.aoide.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Stops playback later: at a clock time, or when the current song ends. The service watches it; the screens watch [remaining]. */
object SleepTimer {
    private val _endAt = MutableStateFlow<Long?>(null)
    val endAt: StateFlow<Long?> = _endAt
    private val _endOfTrack = MutableStateFlow(false)
    val endOfTrack: StateFlow<Boolean> = _endOfTrack
    /** The minutes that were asked for, so the sheet can mark the right choice rather than guess from the countdown. */
    private val _minutes = MutableStateFlow<Int?>(null)
    val minutes: StateFlow<Int?> = _minutes
    /** Milliseconds left, ticking once a second while a clock timer runs; null otherwise. */
    private val _remaining = MutableStateFlow<Long?>(null)
    val remaining: StateFlow<Long?> = _remaining
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    val isSet: Boolean get() = _endAt.value != null || _endOfTrack.value

    fun setMinutes(m: Int) {
        _endOfTrack.value = false
        _minutes.value = m
        _endAt.value = System.currentTimeMillis() + m * 60_000L
        tick()
    }
    fun setEndOfTrack() { _endAt.value = null; _minutes.value = null; _endOfTrack.value = true; ticker?.cancel(); _remaining.value = null }
    fun cancel() { _endAt.value = null; _minutes.value = null; _endOfTrack.value = false; ticker?.cancel(); _remaining.value = null }
    fun remainingMs(): Long {
        val end = _endAt.value ?: return 0L
        return end - System.currentTimeMillis()
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _endAt.value != null) {
                _remaining.value = remainingMs().coerceAtLeast(0)
                delay(1000)
            }
            _remaining.value = null
        }
    }

    fun label(): String? = when {
        _endOfTrack.value -> "After this song"
        _endAt.value != null -> "${((remainingMs() + 59_999) / 60_000).coerceAtLeast(0)} min left"
        else -> null
    }

    /** "28:14" for a running clock timer, "End of song" for the other kind, null when off. */
    fun short(remainingMs: Long?, endOfTrack: Boolean): String? = when {
        endOfTrack -> "End of song"
        remainingMs != null -> { val s = (remainingMs / 1000).toInt(); "%d:%02d".format(s / 60, s % 60) }
        else -> null
    }
}
