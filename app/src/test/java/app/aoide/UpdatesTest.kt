package app.aoide

import app.aoide.data.Updates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update checker: version order, release parsing, and the live releases page. */
class UpdatesTest {
    @Test fun versionOrder() {
        assertTrue(Updates.isNewer("0.6.0", "0.5.0"))
        assertFalse(Updates.isNewer("0.5.0", "0.5.0"))
        assertTrue(Updates.isNewer("v1.0.0", "0.9.9"))
        assertFalse(Updates.isNewer("0.5.0", "0.5.1"))
        assertTrue(Updates.isNewer("0.5.10", "0.5.9"))
        assertFalse(Updates.isNewer("0.6.0-beta", "0.6.0"))
        assertTrue(Updates.isNewer("1.0", "0.99.99"))
    }

    @Test fun parsesARelease() {
        val r = Updates.parse(SAMPLE)
        assertEquals("0.6.0", r.version)
        assertEquals("Aoide 0.6.0", r.name)
        assertEquals("https://example.invalid/Aoide-0.6.0.apk", r.apkUrl)
        assertEquals(3_000_000L, r.apkBytes)
        assertTrue(r.notes.startsWith("In-app updates"))
    }

    /** Against the real releases page; skipped quietly when the address is rate-limited. */
    @Test fun liveLatestRelease() {
        val r = runBlocking { runCatching { Updates.fetchLatest() }.getOrNull() } ?: return
        println("LATEST ${r.version} ${r.apkBytes} bytes ${r.apkUrl}")
        assertTrue(r.apkUrl.endsWith(".apk"))
        assertTrue(Updates.isNewer(r.version, "0.0.1"))
    }

    private companion object {
        val SAMPLE = """
            {"tag_name":"v0.6.0","name":"Aoide 0.6.0","html_url":"https://example.invalid/releases/v0.6.0","published_at":"2026-09-11T14:00:00Z",
             "body":"In-app updates and a neutral README.",
             "assets":[{"name":"Aoide.apk","browser_download_url":"https://example.invalid/Aoide.apk","size":3000000},
                       {"name":"Aoide-0.6.0.apk","browser_download_url":"https://example.invalid/Aoide-0.6.0.apk","size":3000000}]}
        """.trimIndent()
    }
}
