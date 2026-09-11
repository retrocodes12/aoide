package app.aoide.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.aoide.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File

/**
 * In-app updates from this repository's Releases page. The check runs at launch at most once
 * every twelve hours and from Settings on demand; the APK is downloaded into the app's cache and
 * handed to the system installer, which accepts it because every release is signed with the
 * same key. Nothing is installed without the listener tapping Install.
 */
object Updates {
    @Serializable
    data class Release(val version: String, val name: String, val notes: String, val page: String, val apkUrl: String, val apkBytes: Long, val publishedAt: String)

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val latest: String) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val fraction: Float) : State()
        data class Ready(val release: Release, val file: File) : State()
        data class Failed(val message: String, val release: Release? = null) : State()
    }

    const val PAGE = "https://github.com/retrocodes12/aoide/releases"
    private const val API = "https://api.github.com/repos/retrocodes12/aoide/releases/latest"
    private const val EVERY_MS = 12 * 3_600_000L
    val current: String get() = BuildConfig.VERSION_NAME
    /** Test seam: the screenshot rig turns the launch-time check off so its own state is not overwritten. */
    var autoCheck = true
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Check the Releases page; [force] ignores the twelve-hour throttle. Returns the new state. */
    suspend fun check(force: Boolean = false): State {
        val now = System.currentTimeMillis()
        if (!force && Prefs.isReady()) {
            val last = Prefs.getLong("update_checked_at")
            // Inside the window, replay the last answer so the banner survives a relaunch without a request.
            if (last > 0 && now - last < EVERY_MS) {
                Prefs.getString("update_latest")?.let { s -> runCatching { json.decodeFromString<Release>(s) }.getOrNull() }?.let { r ->
                    val st = if (isNewer(r.version, current)) State.Available(r) else State.UpToDate(r.version)
                    _state.value = st
                    return st
                }
            }
        }
        if (_state.value is State.Downloading || _state.value is State.Ready) return _state.value
        _state.value = State.Checking
        val st = try {
            val r = fetchLatest()
            if (Prefs.isReady()) {
                Prefs.putLong("update_checked_at", now)
                Prefs.putString("update_latest", json.encodeToString(r))
            }
            if (isNewer(r.version, current)) State.Available(r) else State.UpToDate(r.version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            State.Failed(e.message ?: "Couldn't reach the releases page")
        }
        _state.value = st
        return st
    }

    /** The newest release as published, with its APK. */
    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val body = ApiClient.http.newCall(Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", ApiClient.UA).build()).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, if (res.code == 403 || res.code == 429) "The releases page is rate-limiting this address; try again in an hour." else "Releases page answered ${res.code}")
            res.body?.string() ?: throw IllegalStateException("The releases page sent an empty answer")
        }
        parse(body)
    }

    fun parse(body: String): Release {
        val o = json.parseToJsonElement(body) as? JsonObject ?: throw IllegalStateException("Unreadable release")
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("Release without a tag")
        val version = tag.removePrefix("v")
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "Aoide-$version.apk" }
            ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            ?: throw IllegalStateException("Release without an APK")
        return Release(
            version = version,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Aoide $version",
            notes = o["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            page = o["html_url"]?.jsonPrimitive?.contentOrNull ?: PAGE,
            apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("APK without a link"),
            apkBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            publishedAt = o["published_at"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    /** True when [candidate] is a higher version than [installed]: numeric, dot-separated; a pre-release suffix is ignored. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Download the release's APK into the cache, reporting progress through [state]. */
    suspend fun download(context: Context, r: Release): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "Aoide-${r.version}.apk")
        if (out.exists() && (r.apkBytes <= 0 || out.length() == r.apkBytes)) {
            _state.value = State.Ready(r, out)
            return@withContext out
        }
        dir.listFiles()?.forEach { it.delete() }
        _state.value = State.Downloading(r, 0f)
        try {
            ApiClient.http.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", ApiClient.UA).build()).execute().use { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "Download answered ${res.code}")
                val body = res.body ?: throw IllegalStateException("Empty download")
                val total = if (body.contentLength() > 0) body.contentLength() else r.apkBytes
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Downloading(r, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (r.apkBytes > 0 && out.length() != r.apkBytes) throw IllegalStateException("The download stopped short")
            _state.value = State.Ready(r, out)
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out.delete()
            _state.value = State.Failed(e.message ?: "Download failed", r)
            null
        }
    }

    /** Hand the APK to the system installer. Returns false, after opening the right settings page, when Aoide may not install apps yet. */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun dismiss(version: String) { if (Prefs.isReady()) Prefs.putString("update_dismissed", version) }
    fun isDismissed(version: String): Boolean = Prefs.isReady() && Prefs.getString("update_dismissed") == version

    /** Test seam: lets a screenshot test paint the banner and the Settings section. */
    fun setStateForTest(s: State) { _state.value = s }
}
