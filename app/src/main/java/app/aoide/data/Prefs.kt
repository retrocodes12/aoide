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

    fun init(context: Context) {
        sp = context.getSharedPreferences("aoide", Context.MODE_PRIVATE)
        _quality.value = runCatching { Quality.valueOf(sp.getString("quality", null) ?: "") }.getOrDefault(Quality.LOSSLESS)
        _previewNoted.value = sp.getBoolean("preview_noted", false)
        _youtube.value = sp.getBoolean("youtube_source", true)
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
