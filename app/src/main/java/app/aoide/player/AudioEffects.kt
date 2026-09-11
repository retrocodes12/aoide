package app.aoide.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import app.aoide.data.Prefs
import app.aoide.data.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class EqState(val enabled: Boolean = false, val bands: List<Int> = List(5) { 0 }, val bass: Int = 0, val preset: String = "Flat")

/**
 * The in-app equaliser: Android's own Equalizer and BassBoost effects bound to the player's audio
 * session. Gains are kept in dB (-15..15) and persisted; the effects are recreated whenever
 * ExoPlayer hands out a new session id, and they live in this process because the player does.
 */
object AudioEffects {
    val PRESETS: Map<String, List<Int>> = linkedMapOf(
        "Flat" to listOf(0, 0, 0, 0, 0),
        "Bass" to listOf(8, 5, 0, -1, -2),
        "Treble" to listOf(-2, -1, 0, 4, 7),
        "Rock" to listOf(5, 3, -1, 2, 4),
        "Pop" to listOf(-1, 2, 4, 2, -1),
        "Jazz" to listOf(4, 2, -1, 2, 4),
        "Classical" to listOf(4, 3, -1, 2, 3),
        "Vocal" to listOf(-2, 0, 4, 3, 0),
        "Electronic" to listOf(5, 3, 0, 2, 5),
    )
    /** Band centre frequencies in Hz: Android's usual five until a real Equalizer reports its own. */
    private val _freqs = MutableStateFlow(listOf(60, 230, 910, 3600, 14000))
    val freqs: StateFlow<List<Int>> = _freqs
    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var sessionId = 0

    fun load() {
        if (!Prefs.isReady()) return
        Prefs.getString("eq")?.let { s -> runCatching { json.decodeFromString<EqState>(s) }.getOrNull()?.let { _state.value = it } }
    }

    fun set(s: EqState) {
        val fixed = s.copy(bands = s.bands.map { it.coerceIn(-15, 15) }, bass = s.bass.coerceIn(0, 100))
        _state.value = fixed
        if (Prefs.isReady()) Prefs.putString("eq", json.encodeToString(fixed))
        apply()
    }

    fun setBand(i: Int, db: Int) {
        val b = _state.value.bands.toMutableList()
        if (i in b.indices) b[i] = db
        set(_state.value.copy(bands = b, preset = "Custom"))
    }

    fun setPreset(name: String) {
        val p = PRESETS[name] ?: return
        val n = _state.value.bands.size
        set(_state.value.copy(bands = List(n) { i -> p.getOrElse(i * 5 / n.coerceAtLeast(1)) { 0 } }, preset = name))
    }

    /** Called by the playback service whenever ExoPlayer's audio session changes. */
    fun attach(id: Int) {
        if (id == sessionId && eq != null) return
        release()
        sessionId = id
        if (id == 0) return
        runCatching {
            val e = Equalizer(0, id)
            eq = e
            val n = e.numberOfBands.toInt()
            _freqs.value = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
            if (_state.value.bands.size != n) _state.value = _state.value.copy(bands = List(n) { i -> _state.value.bands.getOrElse(i) { 0 } })
        }
        runCatching { bass = BassBoost(0, id) }
        apply()
    }

    private fun apply() {
        val s = _state.value
        eq?.let { e ->
            runCatching {
                e.enabled = s.enabled
                val range = e.bandLevelRange
                for (i in 0 until e.numberOfBands.toInt()) {
                    e.setBandLevel(i.toShort(), (s.bands.getOrElse(i) { 0 } * 100).coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
            }
        }
        bass?.let { b ->
            runCatching {
                b.enabled = s.enabled && s.bass > 0
                if (b.strengthSupported) b.setStrength((s.bass * 10).toShort())
            }
        }
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null
        bass = null
    }
}
