package app.aoide.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small settings. Anything bigger lives in Library (a JSON file). */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _quality = MutableStateFlow(Quality.LOSSLESS)
    val quality: StateFlow<Quality> = _quality
    private val _previewNoted = MutableStateFlow(false)
    val previewNoted: StateFlow<Boolean> = _previewNoted
    private val _youtube = MutableStateFlow(true)
    /** Full songs from YouTube Music; on unless the listener turns it off. */
    val youtubeSource: StateFlow<Boolean> = _youtube

    /** A boolean setting with a flow the screens can watch. */
    class Switch(val key: String, val default: Boolean) {
        private val flow = MutableStateFlow(default)
        val value: StateFlow<Boolean> get() = flow
        val on: Boolean get() = flow.value
        fun set(v: Boolean) { flow.value = v; if (isReady()) sp.edit().putBoolean(key, v).apply() }
        internal fun load() { if (isReady()) flow.value = sp.getBoolean(key, default) }
    }
    val dataSaver = Switch("data_saver", false)
    val skipSilence = Switch("skip_silence", false)
    val pauseOnMute = Switch("pause_on_mute", false)
    val resumeOnBluetooth = Switch("resume_on_bt", true)
    val autoplay = Switch("autoplay", true)
    val pureBlack = Switch("pure_black", false)
    val translateLyrics = Switch("translate_lyrics", false)
    val switches = listOf(dataSaver, skipSilence, pauseOnMute, resumeOnBluetooth, autoplay, pureBlack, translateLyrics)

    private val _fadeMs = MutableStateFlow(0)
    /** Fade between songs, in ms; 0 is off. */
    val fadeMs: StateFlow<Int> = _fadeMs
    fun setFadeMs(ms: Int) { _fadeMs.value = ms; if (isReady()) sp.edit().putInt("fade_ms", ms).apply() }
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed
    fun setSpeed(v: Float) { _speed.value = v; if (isReady()) sp.edit().putFloat("speed", v).apply() }
    private val _accent = MutableStateFlow("orange")
    val accent: StateFlow<String> = _accent
    fun setAccent(name: String) { _accent.value = name; if (isReady()) sp.edit().putString("accent", name).apply() }
    private val _lyricsLang = MutableStateFlow("en")
    val lyricsLang: StateFlow<String> = _lyricsLang
    fun setLyricsLang(code: String) { _lyricsLang.value = code; if (isReady()) sp.edit().putString("lyrics_lang", code).apply() }
    private val _lyricsSize = MutableStateFlow(26)
    val lyricsSize: StateFlow<Int> = _lyricsSize
    fun setLyricsSize(sp_: Int) { _lyricsSize.value = sp_; if (isReady()) sp.edit().putInt("lyrics_size", sp_).apply() }

    fun init(context: Context) {
        sp = context.getSharedPreferences("aoide", Context.MODE_PRIVATE)
        _quality.value = runCatching { Quality.valueOf(sp.getString("quality", null) ?: "") }.getOrDefault(Quality.LOSSLESS)
        _previewNoted.value = sp.getBoolean("preview_noted", false)
        _youtube.value = sp.getBoolean("youtube_source", true)
        switches.forEach { it.load() }
        _fadeMs.value = sp.getInt("fade_ms", 0)
        _speed.value = sp.getFloat("speed", 1f)
        _accent.value = sp.getString("accent", "orange") ?: "orange"
        _lyricsLang.value = sp.getString("lyrics_lang", "en") ?: "en"
        _lyricsSize.value = sp.getInt("lyrics_size", 26)
    }

    fun setQuality(q: Quality) {
        _quality.value = q
        sp.edit().putString("quality", q.name).apply()
    }

    fun setYouTubeSource(on: Boolean) {
        _youtube.value = on
        if (isReady()) sp.edit().putBoolean("youtube_source", on).apply()
    }

    /** False until init has run (plain JVM tests, very early code paths). */
    fun isReady(): Boolean = ::sp.isInitialized

    fun notePreview() {
        _previewNoted.value = true
        sp.edit().putBoolean("preview_noted", true).apply()
    }

    fun getLong(key: String): Long = sp.getLong(key, -1L)
    fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()
    fun getString(key: String): String? = sp.getString(key, null)
    fun putString(key: String, value: String?) = sp.edit().putString(key, value).apply()
}
