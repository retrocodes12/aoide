package app.aoide.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Request
import java.io.File

/** One song kept on the phone: the file plus what a DASH manifest needs to play it without the network. */
@Serializable
data class Download(
    val track: Track,
    val file: String,
    val bytes: Long,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val durationMs: Long,
    val initStart: Long,
    val initEnd: Long,
    val indexStart: Long,
    val indexEnd: Long,
    val sampleRate: Int,
    val channels: Int,
    val savedAt: Long,
    /** A plain MP4 that plays straight from the file, rather than a DASH-wrapped stream. */
    val progressive: Boolean = false,
) {
    val label: String get() = "${if (codecs.startsWith("opus")) "OPUS" else "AAC"} ${bitrate / 1000} kbps"
}

/** What the queue is doing right now, for the Downloads screen and the notification. */
data class DownloadProgress(val track: Track, val fraction: Float)

/**
 * The download manager. Songs are fetched from the music service one at a time on a background queue, written under the app's private storage, and listed
 * in an index the resolver checks before it touches the network. A foreground service keeps the
 * process alive while the queue drains and shows progress in the shade.
 */
object Downloads {
    private lateinit var dir: File
    private lateinit var indexFile: File
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _all = MutableStateFlow<Map<String, Download>>(emptyMap())
    val all: StateFlow<Map<String, Download>> = _all
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue
    private val _current = MutableStateFlow<DownloadProgress?>(null)
    val current: StateFlow<DownloadProgress?> = _current
    private val _failed = MutableStateFlow<Map<String, String>>(emptyMap())
    val failed: StateFlow<Map<String, String>> = _failed
    private var worker: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(context.filesDir, "downloads").apply { mkdirs() }
        indexFile = File(context.filesDir, "downloads.json")
        if (indexFile.exists()) runCatching {
            val list = json.decodeFromString<List<Download>>(indexFile.readText())
            _all.value = list.filter { File(it.file).exists() }.associateBy { it.track.id }
        }
    }

    fun has(trackId: String): Boolean = _all.value.containsKey(trackId)
    fun get(trackId: String): Download? = _all.value[trackId]
    fun isQueued(trackId: String): Boolean = _queue.value.any { it.id == trackId } || _current.value?.track?.id == trackId
    val totalBytes: Long get() = _all.value.values.sumOf { it.bytes }

    /** Queue songs that are not already kept or waiting; duplicates are dropped silently. */
    fun enqueue(tracks: List<Track>) {
        val fresh = tracks.filter { !it.isLocal && !has(it.id) && !isQueued(it.id) }.distinctBy { it.id }
        if (fresh.isEmpty()) return
        _queue.value = _queue.value + fresh
        _failed.value = _failed.value - fresh.map { it.id }.toSet()
        start()
    }

    fun cancel(trackId: String) {
        _queue.value = _queue.value.filter { it.id != trackId }
    }

    fun remove(trackId: String) {
        _all.value[trackId]?.let { runCatching { File(it.file).delete() } }
        _all.value = _all.value - trackId
        persist()
    }

    fun removeAll() {
        _all.value.values.forEach { runCatching { File(it.file).delete() } }
        _all.value = emptyMap()
        persist()
    }

    private fun start() {
        if (worker?.isActive == true) return
        appContext?.let { ctx -> runCatching { ctx.startForegroundService(Intent(ctx, app.aoide.player.DownloadService::class.java)) } }
        worker = scope.launch {
            while (true) {
                val next = _queue.value.firstOrNull() ?: break
                // Current is set before the queue shrinks, so the service never sees both empty mid-way.
                _current.value = DownloadProgress(next, 0f)
                _queue.value = _queue.value.drop(1)
                try {
                    fetch(next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _failed.value = _failed.value + (next.id to (e.message ?: "Download failed"))
                }
                _current.value = null
            }
        }
    }

    private suspend fun fetch(t: Track) {
        _current.value = DownloadProgress(t, 0f)
        // The second source's 320 kbps file, when it has the song and the listener wants it.
        if (HiRate.enabled && Prefs.quality.value != Quality.LOW) {
            val hi = HiRate.url(t.id) ?: runCatching { HiRate.lookup(t) }.getOrNull()
            if (hi != null) return fetchProgressive(t, hi)
        }
        val s = Music.stream(t.id, Prefs.quality.value) ?: throw IllegalStateException("The music service did not hand out a whole file")
        val ext = if (s.mimeType.contains("webm")) "webm" else "m4a"
        val out = File(dir, "${t.id}.$ext")
        val tmp = File(dir, "${t.id}.part")
        // Ranged 1 MiB reads, the way the player itself streams: one long GET is what the CDN cuts short.
        val ua = Music.userAgentFor(s.client)
        val total = s.contentLength
        val chunk = 1L shl 20
        var offset = 0L
        tmp.outputStream().use { out ->
            while (offset < total) {
                val end = minOf(offset + chunk, total) - 1
                val req = Request.Builder().url(s.url).header("User-Agent", ua).header("Range", "bytes=$offset-$end").build()
                val whole = ApiClient.http.newCall(req).execute().use { res ->
                    if (res.code != 206 && res.code != 200) throw ApiException(res.code, "stream ${res.code}")
                    (res.body ?: throw IllegalStateException("empty stream")).byteStream().copyTo(out)
                    res.code == 200
                }
                if (whole) break
                offset = end + 1
                _current.value = DownloadProgress(t, (offset.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        if (s.contentLength > 0 && tmp.length() != s.contentLength) {
            tmp.delete()
            throw IllegalStateException("The file stopped short")
        }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        val d = Download(t, out.absolutePath, out.length(), s.mimeType, s.codecs, s.averageBitrate, s.durationMs, s.initRange.first, s.initRange.last, s.indexRange.first, s.indexRange.last, s.sampleRate, s.channels, System.currentTimeMillis())
        _all.value = _all.value + (t.id to d)
        persist()
    }

    /** A plain file, read straight through; nothing about it needs a manifest. */
    private suspend fun fetchProgressive(t: Track, url: String) {
        val out = File(dir, "${t.id}.m4a")
        val tmp = File(dir, "${t.id}.part")
        var total = 0L
        ApiClient.http.newCall(Request.Builder().url(url).header("User-Agent", ApiClient.UA).build()).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "The second source answered ${res.code}")
            val body = res.body ?: throw IllegalStateException("empty stream")
            total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) _current.value = DownloadProgress(t, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (tmp.length() <= 0L) { tmp.delete(); throw IllegalStateException("The file came back empty") }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        val d = Download(t, out.absolutePath, out.length(), "audio/mp4", "mp4a.40.2", HiRate.KBPS * 1000, t.duration * 1000L, 0L, 0L, 0L, 0L, 44_100, 2, System.currentTimeMillis(), progressive = true)
        _all.value = _all.value + (t.id to d)
        persist()
    }

    private fun persist() {
        runCatching { indexFile.writeText(json.encodeToString(_all.value.values.toList())) }
    }

    /** A DASH manifest for a kept file, so offline songs ride the player's normal path. */
    fun manifest(d: Download): String {
        val seconds = String.format(java.util.Locale.US, "%.3f", d.durationMs / 1000.0)
        val url = "file://" + d.file
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.500S" mediaPresentationDuration="PT${seconds}S">
<Period id="0" start="PT0S">
<AdaptationSet id="0" contentType="audio" mimeType="${d.mimeType}" subsegmentAlignment="true">
<Representation id="1" codecs="${d.codecs}" bandwidth="${d.bitrate}" audioSamplingRate="${d.sampleRate}">
<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${d.channels}"/>
<BaseURL>$url</BaseURL>
<SegmentBase indexRange="${d.indexStart}-${d.indexEnd}"><Initialization range="${d.initStart}-${d.initEnd}"/></SegmentBase>
</Representation>
</AdaptationSet>
</Period>
</MPD>
"""
    }
}
