package app.aoide.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import app.aoide.data.Download
import app.aoide.data.Track
import java.io.File

/** Things a song can be put to beyond playing: sharing it, or making it the ringtone. */
object TrackActions {
    /** A song.link page resolves the TIDAL id to whatever service the friend uses. */
    fun shareText(t: Track): String {
        val link = if (t.id < 0) null else "https://song.link/https://tidal.com/browse/track/${t.id}"
        return listOfNotNull("${t.title} — ${t.artistNames}", link).joinToString("\n")
    }

    fun share(context: Context, t: Track) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(t)).putExtra(Intent.EXTRA_SUBJECT, t.title)
        runCatching { context.startActivity(Intent.createChooser(send, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Copies a downloaded song into the phone's ringtones and makes it the default. Returns what to tell the listener. */
    fun setRingtone(context: Context, d: Download): String {
        if (Build.VERSION.SDK_INT < 29) return "Ringtones need Android 10 or newer."
        if (!Settings.System.canWrite(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return "Allow Aoide to modify system settings, then try again."
        }
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${d.track.title} - ${d.track.artistNames}.${File(d.file).extension}")
                put(MediaStore.MediaColumns.MIME_TYPE, d.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                put(MediaStore.Audio.Media.IS_RINGTONE, true)
                put(MediaStore.Audio.Media.TITLE, d.track.title)
                put(MediaStore.Audio.Media.ARTIST, d.track.artistNames)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("could not create the ringtone")
            context.contentResolver.openOutputStream(uri)!!.use { out -> File(d.file).inputStream().use { it.copyTo(out) } }
            RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri)
            "“${d.track.title}” is now your ringtone."
        }.getOrElse { "Couldn't set the ringtone: ${it.message}" }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
