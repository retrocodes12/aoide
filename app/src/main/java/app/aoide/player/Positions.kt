package app.aoide.player

import app.aoide.data.Prefs
import app.aoide.data.Track

/**
 * Where a long recording was left, so a mix, a set or a live album picks up where it stopped
 * rather than from the top. Only songs over twenty minutes are noted, only between 2 % and 95 %
 * of the way through, and the notes live in their own small file, pruned after thirty days.
 */
object Positions {
    private const val MIN_SECONDS = 20 * 60
    private const val TTL = 30L * 86_400_000L
    private var lastWrite = 0L

    fun isLong(t: Track?): Boolean = t != null && !t.isLocal && t.duration >= MIN_SECONDS

    /** Called by the ticker; writes at most every four seconds. */
    fun note(t: Track?, positionMs: Long, durationMs: Long) {
        if (!isLong(t) || durationMs <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastWrite < 4000) return
        lastWrite = now
        val sp = Prefs.positions ?: return
        val frac = positionMs.toDouble() / durationMs
        if (frac < 0.02 || frac > 0.95) sp.edit().remove(t!!.id).apply()
        else sp.edit().putString(t!!.id, "$positionMs|$now").apply()
    }

    /** The saved position for a long recording, or 0 to start from the top. */
    fun resumeAt(t: Track): Long = saved(t)?.first ?: 0L

    fun saved(t: Track?): Pair<Long, Long>? {
        if (!isLong(t)) return null
        val s = Prefs.positions?.getString(t!!.id, null) ?: return null
        val pos = s.substringBefore('|').toLongOrNull() ?: return null
        val at = s.substringAfter('|').toLongOrNull() ?: return null
        return if (System.currentTimeMillis() - at < TTL) pos to at else null
    }

    fun forget(t: Track) { Prefs.positions?.edit()?.remove(t.id)?.apply() }

    fun prune() {
        val sp = Prefs.positions ?: return
        val now = System.currentTimeMillis()
        val stale = sp.all.filter { (_, v) -> (v as? String)?.substringAfter('|')?.toLongOrNull()?.let { now - it >= TTL } ?: true }.keys
        if (stale.isNotEmpty()) sp.edit().apply { stale.forEach(::remove) }.apply()
    }
}
