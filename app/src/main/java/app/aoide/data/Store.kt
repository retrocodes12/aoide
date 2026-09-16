package app.aoide.data

import java.io.File
import java.io.FileOutputStream

/**
 * Small JSON files written so that a kill mid-write can never leave them empty: the new text
 * goes to a sibling `.tmp`, is flushed to disk, and is then renamed over the old file, which
 * is kept once more as `.bak`. A read that fails to decode the file falls back to the backup.
 */
object Store {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun writeAtomic(target: File, text: String) = synchronized(locks.getOrPut(target.path) { Any() }) {
        val tmp = File(target.path + ".tmp")
        val bak = File(target.path + ".bak")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (target.exists()) {
            bak.delete()
            target.renameTo(bak)
        }
        if (!tmp.renameTo(target)) {
            // A rename across the same directory should not fail; if it does, fall back to a copy.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** The first of the file and its backup that decodes, or null when neither does. */
    fun <T> read(target: File, decode: (String) -> T): T? {
        for (f in listOf(target, File(target.path + ".bak"))) {
            if (!f.exists()) continue
            val v = runCatching { decode(f.readText()) }.getOrNull()
            if (v != null) return v
        }
        return null
    }
}
