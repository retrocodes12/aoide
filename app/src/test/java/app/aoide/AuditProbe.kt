package app.aoide

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aoide.data.Album
import app.aoide.data.AlbumRef
import app.aoide.data.ArtistRef
import app.aoide.data.Catalog
import app.aoide.data.Instances
import app.aoide.data.Lyrics
import app.aoide.data.LyricLine
import app.aoide.data.Playlist
import app.aoide.data.Prefs
import app.aoide.data.Track
import app.aoide.data.formatLength
import app.aoide.data.formatTime
import app.aoide.player.StreamInfo
import app.aoide.ui.plural
import app.aoide.ui.screens.albumsFromTracks
import app.aoide.ui.screens.englishFirst
import app.aoide.ui.screens.lyricLines
import app.aoide.ui.screens.prettyDate
import app.aoide.ui.theme.Aoide
import app.aoide.ui.theme.Tint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Read-only audit probe. Nothing here changes the app; every test either measures a pure function
 * or exercises the in-memory registry. Results are printed with an AUDIT prefix so the run log can
 * be grepped.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AuditProbe {

    @Before fun setUp() {
        Prefs.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        // DEFAULTS holds shared mutable Instance objects, so reset() alone does not clear a bench.
        Instances.reset()
        Instances.DEFAULTS.forEach { Instances.reportSuccess(it.url, 100) }
        Instances.noteSource("mirror")
    }

    private fun say(s: String) = println("AUDIT $s")

    /* ---------- colour helpers, mirroring WCAG exactly ---------- */
    private fun lin(v: Float): Double { val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4) }
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun ratio(a: Color, b: Color): Double {
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
    /** Composite a translucent ink over an opaque ground. */
    private fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(a * fg.red + (1 - a) * bg.red, a * fg.green + (1 - a) * bg.green, a * fg.blue + (1 - a) * bg.blue)
    }
    private fun hex(c: Color) = String.format("#%02x%02x%02x", (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        fun f(n: Int): Float {
            val k = (n + h * 12f) % 12f
            val a = s * minOf(l, 1f - l)
            return l - a * maxOf(-1f, minOf(minOf(k - 3f, 9f - k), 1f))
        }
        return Color(f(0), f(8), f(4))
    }

    /** 12 hues x 4 saturations x 3 lightnesses = 144 synthetic artwork tints. */
    private fun sweep(): List<Pair<String, Color>> = buildList {
        for (hi in 0 until 12) for (s in listOf(0.25f, 0.5f, 0.75f, 1.0f)) for (l in listOf(0.3f, 0.5f, 0.7f)) {
            val h = hi / 12f
            add("h${hi * 30} s${(s * 100).toInt()} l${(l * 100).toInt()}" to hslToColor(h, s, l))
        }
    }

    /* ================= 1. the tint solver ================= */

    @Test fun tintSweep144() {
        val tints = sweep()
        assertEquals(144, tints.size)
        var worstFull = 99.0; var worstFullName = ""
        var worstSoft = 99.0; var worstSoftName = ""
        var worstFaint = 99.0; var worstFaintName = ""
        var belowFull = 0; var belowSoft = 0; var belowFaint = 0
        var darkInk = 0
        for ((name, c) in tints) {
            val t = Tint.from(c)
            val rf = ratio(t.ink, t.accent)
            val rs = ratio(over(t.soft, t.accent), t.accent)
            val rr = ratio(over(t.faint, t.accent), t.accent)
            if (t.isDarkInk) darkInk++
            if (rf < worstFull) { worstFull = rf; worstFullName = "$name ${hex(t.accent)} ink ${hex(t.ink)}" }
            if (rs < worstSoft) { worstSoft = rs; worstSoftName = "$name ${hex(t.accent)}" }
            if (rr < worstFaint) { worstFaint = rr; worstFaintName = "$name ${hex(t.accent)}" }
            if (rf < 4.5) belowFull++
            if (rs < 4.5) belowSoft++
            if (rr < 3.0) belowFaint++
        }
        say("TINT full  worst %.2f  (%s)  below-4.5: %d/144".format(worstFull, worstFullName, belowFull))
        say("TINT soft  worst %.2f  (%s)  below-4.5: %d/144".format(worstSoft, worstSoftName, belowSoft))
        say("TINT faint worst %.2f  (%s)  below-3.0: %d/144".format(worstFaint, worstFaintName, belowFaint))
        say("TINT dark-ink tints: $darkInk/144")
        assertEquals("full ink below AA", 0, belowFull)
        assertEquals("soft ink below AA", 0, belowSoft)
        assertEquals("faint ink below 3:1", 0, belowFaint)
    }

    /**
     * The now-playing lyrics card paints tint.accent, then black at 22%, then text in Aoide.fg
     * (pure white) and fg@85% - it never consults the solved ink. Measure what that costs.
     */
    @Test fun lyricsCardUsesFlatWhiteNotSolvedInk() {
        var worst = 99.0; var worstName = ""
        var belowLarge = 0; var belowBody = 0
        var worstBody = 99.0
        for ((name, c) in sweep()) {
            val t = Tint.from(c)
            val ground = over(Color.Black.copy(alpha = .22f), t.accent)
            val r = ratio(Aoide.fg, ground)                       // "Lyrics" 14sp bold + lines 18sp bold
            val rb = ratio(over(Aoide.fg.copy(alpha = .85f), ground), ground) // fallback copy, 14sp regular
            if (r < worst) { worst = r; worstName = "$name tint ${hex(t.accent)} card ${hex(ground)}" }
            if (rb < worstBody) worstBody = rb
            if (r < 3.0) belowLarge++
            if (rb < 4.5) belowBody++
        }
        say("LYRICS-CARD white-on-card worst %.2f (%s); below 3:1 (large): %d/144".format(worst, worstName, belowLarge))
        say("LYRICS-CARD fallback body worst %.2f; below 4.5:1: %d/144".format(worstBody, belowBody))
        // What it would be if the card used the solved ink instead.
        var worstFixed = 99.0
        for ((_, c) in sweep()) {
            val t = Tint.from(c)
            val ground = over(Color.Black.copy(alpha = .22f), t.accent)
            worstFixed = minOf(worstFixed, ratio(t.ink, ground))
        }
        say("LYRICS-CARD if it used tint.ink instead: worst %.2f".format(worstFixed))
    }

    /** The mini player is the same shape but with a 60% scrim; check the port kept P1 fixed. */
    @Test fun miniPlayerScrimHoldsAcrossTints() {
        var worstTitle = 99.0; var worstSub = 99.0; var worstName = ""
        var below = 0
        for ((name, c) in sweep()) {
            val t = Tint.from(c)
            val ground = over(Color.Black.copy(alpha = .6f), t.accent)
            val title = ratio(Aoide.fg, ground)                                   // 14sp bold -> 3:1
            val sub = ratio(over(Color.White.copy(alpha = .84f), ground), ground) // 12sp -> 4.5:1
            if (sub < worstSub) { worstSub = sub; worstName = "$name ${hex(ground)}" }
            worstTitle = minOf(worstTitle, title)
            if (sub < 4.5) below++
        }
        say("MINI title worst %.2f ; sub worst %.2f (%s) ; sub below 4.5: %d/144".format(worstTitle, worstSub, worstName, below))
    }

    /** The PREVIEW chip: accent text on accent@20% over the blurred stage. Worst case is a dark stage. */
    @Test fun previewBadgeContrast() {
        for (stage in listOf(Color(0xFF121212), Color(0xFF3F2B19), Color(0xFF656328), Color(0xFF8A8A8A))) {
            val bg = over(Aoide.accent.copy(alpha = .2f), stage)
            say("PREVIEW-BADGE on stage ${hex(stage)} -> chip ${hex(bg)} accent text %.2f:1 (10sp bold needs 4.5)".format(ratio(Aoide.accent, bg)))
        }
        say("PREVIEW-BADGE accent on plain ground %.2f:1".format(ratio(Aoide.accent, Aoide.ground)))
    }

    /** Header furniture painted with fixed alphas on the blurred stage. */
    @Test fun stageFurnitureContrast() {
        for (stage in listOf(Color(0xFF656328), Color(0xFF3A2614), Color(0xFF9A9A60))) {
            say("STAGE ${hex(stage)}: 'PLAYING FROM' fg@75%% %.2f ; times fg@70%% %.2f ; artist fg@78%% %.2f ; icons fg@70%% %.2f".format(
                ratio(over(Aoide.fg.copy(alpha = .75f), stage), stage),
                ratio(over(Aoide.fg.copy(alpha = .70f), stage), stage),
                ratio(over(Aoide.fg.copy(alpha = .78f), stage), stage),
                ratio(over(Aoide.fg.copy(alpha = .70f), stage), stage),
            ))
        }
    }

    /** Flat palette pairs used all over the dark screens. */
    @Test fun palettePairs() {
        val pairs = listOf(
            "fg on ground" to (Aoide.fg to Aoide.ground),
            "subdued on ground" to (Aoide.subdued to Aoide.ground),
            "muted on ground (disabled)" to (Aoide.muted to Aoide.ground),
            "subdued on elevated" to (Aoide.subdued to Aoide.elevated),
            "subdued on elevated2" to (Aoide.subdued to Aoide.elevated2),
            "muted on elevated2 (disabled)" to (Aoide.muted to Aoide.elevated2),
            "accent on ground" to (Aoide.accent to Aoide.ground),
            "accent on elevated2" to (Aoide.accent to Aoide.elevated2),
            "accentInk on accent" to (Aoide.accentInk to Aoide.accent),
        )
        pairs.forEach { (n, p) -> say("PALETTE %-30s %.2f:1".format(n, ratio(p.first, p.second))) }
    }

    @Test fun tintOfRejectsJunk() {
        assertEquals(Tint.FALLBACK.accent, Tint.of(null).accent)
        assertEquals(Tint.FALLBACK.accent, Tint.of("nope").accent)
        assertEquals(Tint.FALLBACK.accent, Tint.of("#12345").accent)
        assertEquals(Tint.FALLBACK.accent, Tint.of("#zzzzzz").accent)
        say("TINT junk input falls back cleanly")
        // A pure grey record: the solver should not invent saturation out of nothing.
        val grey = Tint.of("#808080")
        say("TINT grey #808080 -> ${hex(grey.accent)} ink ${hex(grey.ink)} %.2f:1".format(ratio(grey.ink, grey.accent)))
    }

    /* ================= 2. the mirror registry ================= */

    @Test fun benchingAndFailover() {
        Instances.reset()
        val a = Instances.DEFAULTS[0].url
        val b = Instances.DEFAULTS[1].url
        // three soft failures bench a mirror; one hard failure benches it at once
        Instances.reportFailure(a); Instances.reportFailure(a)
        assertFalse("2 soft fails should not bench", Instances.list.value.first { it.url == a }.isCooling)
        Instances.reportFailure(a)
        assertTrue("3 soft fails should bench", Instances.list.value.first { it.url == a }.isCooling)
        Instances.reportFailure(b, hard = true)
        assertTrue("one hard fail should bench", Instances.list.value.first { it.url == b }.isCooling)
        assertTrue("all benched -> allCooling", Instances.allCooling())
        assertTrue("all benched -> mirrorsDown", Instances.mirrorsDown())
        Instances.reportSuccess(a, 120)
        assertFalse(Instances.list.value.first { it.url == a }.isCooling)
        assertFalse(Instances.allCooling())
        say("INSTANCES benching thresholds hold (3 soft / 1 hard, 90 s)")

        // mirrorsDown is sticky on source: one track that fell back to TIDAL flips the whole UI
        Instances.reportSuccess(b, 90)
        Instances.noteSource("mirror")
        assertFalse(Instances.mirrorsDown())
        Instances.noteSource("tidal")
        say("INSTANCES after a single tidal-sourced answer, mirrorsDown()=${Instances.mirrorsDown()} while both mirrors are healthy")
        Instances.noteSource("mirror")
    }

    @Test fun orderingPrefersUserThenFastestThenNotBenched() {
        Instances.reset()
        Instances.add("https://mine.example")
        val fast = Instances.DEFAULTS[1].url
        Instances.reportSuccess(fast, 20)
        Instances.reportSuccess(Instances.DEFAULTS[0].url, 900)
        var order = Instances.ordered().map { it.host }
        say("INSTANCES order (healthy): $order")
        assertEquals("mine.example", order.first())
        Instances.reportFailure("https://mine.example", hard = true)
        order = Instances.ordered().map { it.host }
        say("INSTANCES order (user benched): $order")
        assertEquals("a benched mirror sorts last", "mine.example", order.last())
        Instances.remove("https://mine.example")
        Instances.reset()
    }

    @Test fun normaliseRejectsBadOrigins() {
        assertEquals("https://a.example", Instances.normalize(" https://a.example/ "))
        assertNull(Instances.normalize("https://a.example/path"))
        assertNull(Instances.normalize("ftp://a.example"))
        assertNull(Instances.normalize("a.example"))
        assertNull(Instances.normalize("https://a b.example"))
        // http is accepted even though the app ships usesCleartextTraffic=false
        say("INSTANCES normalize('http://a.example') = ${Instances.normalize("http://a.example")}  (manifest sets usesCleartextTraffic=false)")
        Instances.reset()
        assertTrue(Instances.add("https://x.example"))
        assertFalse("duplicates rejected", Instances.add("https://x.example/"))
        Instances.remove("https://x.example")
        Instances.DEFAULTS.forEach { Instances.remove(it.url) }
        assertTrue("removing everything restores the defaults", Instances.list.value.isNotEmpty())
        Instances.reset()
        say("INSTANCES add/remove/reset behave")
    }

    /* ================= 3. formatting and list logic ================= */

    @Test fun formattingIsHonest() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:09", formatTime(9))
        assertEquals("3:45", formatTime(225))
        assertEquals("1:00:00", formatTime(3600))
        assertEquals("0:00", formatTime(-5))
        assertEquals("1 min", formatLength(45))
        assertEquals("43 min", formatLength(2580))
        assertEquals("1 hr 1 min", formatLength(3660))
        assertEquals("1 song", plural(1, "song"))
        assertEquals("0 songs", plural(0, "song"))
        assertEquals("2 albums", plural(2, "album"))
        assertEquals("7 October 2007", prettyDate("2007-10-07"))
        assertEquals("bad date passes through", "not-a-date", prettyDate("not-a-date"))
        say("FORMAT time/length/plural/date all correct")
    }

    @Test fun streamInfoLabels() {
        fun l(p: Boolean, q: String, d: Int?, r: Int?) = StreamInfo(1, p, q, d, r, "mirror").label
        assertEquals("PREVIEW", l(true, "LOSSLESS", 24, 96000))
        assertEquals("FLAC 24/96", l(false, "HI_RES_LOSSLESS", 24, 96000))
        assertEquals("FLAC", l(false, "LOSSLESS", null, null))
        assertEquals("HIGH", l(false, "HIGH", null, null))
        assertEquals("", l(false, "", null, null))
        say("STREAM labels correct; note FLAC 16/44 and FLAC 24/192 both read 'Lossless' in the now-playing badge")
    }

    @Test fun lyricCardAndScreenAgree() {
        // P3 from round 3: the card must not claim lyrics the screen will not show, and vice versa.
        val plainOnly = Lyrics("one\n\ntwo", null)
        val syncedOnly = Lyrics(null, listOf(LyricLine(0.0, "a"), LyricLine(1.0, "b")))
        val emptySynced = Lyrics("fallback", emptyList())
        val blankPlain = Lyrics("   \n  ", null)
        assertEquals(listOf("one", "two"), lyricLines(plainOnly))
        assertEquals(listOf("a", "b"), lyricLines(syncedOnly))
        assertEquals("empty synced list must fall through to plain", listOf("fallback"), lyricLines(emptySynced))
        assertTrue("blank plain yields nothing", lyricLines(blankPlain).isEmpty())
        assertTrue(lyricLines(null).isEmpty())
        assertTrue(lyricLines(Lyrics(null, null)).isEmpty())
        say("LYRICS card/screen rule agrees on all six shapes (P3 held)")
    }

    @Test fun albumRollupAndMoodFilter() {
        fun t(id: Long, alb: Long, title: String) = Track(id = id, title = title, duration = 100, album = AlbumRef(alb, "Album $alb"), artist = ArtistRef(7, "Someone"))
        val rolled = albumsFromTracks(listOf(t(1, 10, "a"), t(2, 10, "b"), t(3, 11, "c")))
        assertEquals(2, rolled.size)
        assertEquals(listOf(10L, 11L), rolled.map { it.id })
        assertTrue("tracks with no album are skipped", albumsFromTracks(listOf(Track(id = 9, title = "x"))).isEmpty())
        val pls = listOf(
            Playlist("u1", "Hygge på dansk"), Playlist("u2", "Late night jazz"), Playlist("u3", "Café"),
        )
        assertEquals(listOf("u2", "u1", "u3"), englishFirst(pls).map { it.uuid })
        say("HOME album roll-up de-dupes; englishFirst prefers ASCII titles")
    }

    /* ================= 4. live catalogue spot checks ================= */

    @Test fun lrcMetadataTagsAreStripped() {
        // lrclib returns LRC files that open with [ar:]/[ti:]/[al:]/[by:] tags. They are not lyrics.
        val track = Track(id = 61799589, title = "15 Step", duration = 237, artist = ArtistRef(3996865, "Radiohead"), album = AlbumRef(61799588, "In Rainbows"))
        val res = runCatching { runBlocking { Catalog.lyrics(track) } }
        val l = res.getOrNull()
        if (res.isFailure || l == null) { say("LRC live fetch unavailable (${res.exceptionOrNull()}) - skipped"); return }
        val lines = (l.synced?.map { it.line } ?: l.plain?.lines() ?: emptyList())
        val tagLike = lines.filter { Regex("^\\[[a-zA-Z]{2,}:.*]\\s*$").matches(it.trim()) }
        say("LRC ${lines.size} lines, ${l.synced?.size ?: 0} synced; metadata tags surviving: ${tagLike.size} $tagLike")
        assertTrue("metadata tags must not render as lyrics", tagLike.isEmpty())
    }

    @Test fun searchMergesAlbumsAndPlaylists() {
        val res = runCatching { runBlocking { Catalog.searchAll("radiohead", 8) } }
        val a = res.getOrNull()
        if (a == null) { say("SEARCH live fetch unavailable (${res.exceptionOrNull()}) - skipped"); return }
        say("SEARCH artists=${a.artists?.items?.size ?: 0} albums=${a.albums?.items?.size ?: 0} playlists=${a.playlists?.items?.size ?: 0} tracks=${a.tracks?.items?.size ?: 0} topHits=${a.topHits.size}")
        say("SEARCH topHit resolves to ${Catalog.topHit(a)?.javaClass?.simpleName}")
        assertTrue("albums tab must not be empty on a mirror answer", !a.albums?.items.isNullOrEmpty())
        assertTrue("playlists tab must not be empty on a mirror answer", !a.playlists?.items.isNullOrEmpty())
    }

    @Test fun albumTracksCarryTheirAlbumRef() {
        val res = runCatching { runBlocking { Catalog.album(61799588L) } }
        val pair = res.getOrNull()
        if (pair == null) { say("ALBUM live fetch unavailable (${res.exceptionOrNull()}) - skipped"); return }
        val (album, tracks) = pair
        say("ALBUM '${album.title}' ${tracks.size} tracks, vibrant=${album.vibrantColor}, quality=${album.audioQuality}")
        assertTrue(tracks.isNotEmpty())
        assertTrue("every track must carry an album ref for artwork and tint", tracks.all { it.album != null })
        assertTrue("every track must have a real duration", tracks.all { it.duration > 0 })
        val t = Tint.of(album.vibrantColor)
        say("ALBUM tint ${hex(t.accent)} ink ${hex(t.ink)} full %.2f:1 ; white on lyrics card %.2f:1".format(
            ratio(t.ink, t.accent), ratio(Aoide.fg, over(Color.Black.copy(alpha = .22f), t.accent))))
    }

    @Test fun realAlbumTintsSurviveTheSolver() {
        // A spread of real catalogue colours, including the bright ones that broke round 3.
        val real = listOf("#DEDD3B", "#8F8F8F", "#00ADAD", "#57AD00", "#9B9B4B", "#B68F68", "#FF1FFF", "#0B0B0B", "#FFFFFF", "#1A3C8F", "#C81E1E", "#FFD400")
        var below = 0
        var cardBelow = 0
        for (h in real) {
            val t = Tint.of(h)
            val full = ratio(t.ink, t.accent)
            val card = ratio(Aoide.fg, over(Color.Black.copy(alpha = .22f), t.accent))
            if (full < 4.5) below++
            if (card < 3.0) cardBelow++
            say("REAL %-8s -> %s ink %s  solved %.2f  lyrics-card-white %.2f".format(h, hex(t.accent), hex(t.ink), full, card))
        }
        assertEquals("solved ink must clear AA on every real tint", 0, below)
        say("REAL lyrics card below 3:1 on $cardBelow/${real.size} real tints")
    }
}
