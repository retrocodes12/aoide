package app.aoide.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

/**
 * Lyrics in the listener's language. Three routes, tried in order: Google Translate's web endpoint
 * (fast, keeps line breaks), Google's mobile page, then MyMemory one line at a time. Google rate-
 * limits an address that asks too often, which is why the others exist; a screen of lyrics goes in
 * chunks of 40 lines so a song is one or two requests, and results are cached in memory.
 */
object Translate {
    val LANGUAGES = listOf(
        "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "kn" to "Kannada", "ml" to "Malayalam", "bn" to "Bengali", "mr" to "Marathi",
        "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese", "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "zh-CN" to "Chinese", "ar" to "Arabic", "ru" to "Russian", "tr" to "Turkish", "id" to "Indonesian",
    )

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    private val cache = HashMap<String, List<String>>()
    @Volatile private var gtxBlockedUntil = 0L

    /** One translated line per input line, in order; a line that fails comes back unchanged. */
    suspend fun lines(lines: List<String>, to: String): List<String> = withContext(Dispatchers.IO) {
        val key = to + " " + lines.joinToString("\n")
        synchronized(cache) { cache[key]?.let { return@withContext it } }
        val out = ArrayList<String>(lines.size)
        for (chunk in lines.chunked(40)) {
            // gtx keeps newlines; the mobile page flattens them, but a " ||| " marker between lines comes through untouched.
            val parts = gtx(chunk.joinToString("\n"), to)?.split("\n")?.takeIf { it.size == chunk.size }
                ?: mobile(chunk.joinToString(" ||| "), to)?.split(Regex("\\s*\\|\\|\\|\\s*"))?.takeIf { it.size == chunk.size }
                ?: chunk.map { line -> if (line.isBlank()) line else myMemory(line, to) ?: line }
            out.addAll(parts)
        }
        synchronized(cache) { if (cache.size > 40) cache.clear(); cache[key] = out }
        out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? = runCatching {
        ApiClient.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { res -> if (res.isSuccessful) res.body?.string() else null }
    }.getOrNull()

    /** The endpoint the Google Translate website uses. Answers 429 to an address it has seen too much of; then it rests for an hour. */
    private fun gtx(text: String, to: String): String? {
        if (System.currentTimeMillis() < gtxBlockedUntil) return null
        val body = get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$to&dt=t&q=${enc(text)}")
        if (body == null) { gtxBlockedUntil = System.currentTimeMillis() + 3_600_000L; return null }
        return runCatching { (json.parseToJsonElement(body).jsonArray[0] as JsonArray).joinToString("") { seg -> seg.jsonArray[0].jsonPrimitive.content } }.getOrNull()
    }

    /** Google's no-script mobile page; the result sits in one span with line breaks flattened. */
    private fun mobile(text: String, to: String): String? {
        val html = get("https://translate.google.com/m?sl=auto&tl=$to&q=${enc(text)}") ?: return null
        val raw = Regex("class=\"result-container\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        return unescape(raw.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""))
    }

    /** MyMemory, one line per call, as the last resort; a few thousand characters a day per address. */
    private fun myMemory(line: String, to: String): String? {
        val source = if (line.all { it.code < 0x250 }) "en" else "Autodetect"
        val body = get("https://api.mymemory.translated.net/get?q=${enc(line)}&langpair=$source|$to") ?: return null
        return runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            if (o["responseStatus"]?.jsonPrimitive?.content != "200") null
            else o["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && !it.startsWith("MYMEMORY WARNING") }
        }.getOrNull()
    }

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&")
}
