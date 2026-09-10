package app.aoide.debug

import android.app.Application

/** Called from AoideApp in debug builds only (via reflection, so release has no reference). */
@Suppress("unused")
object DebugInit {
    @JvmStatic
    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(Inspector.Tracker)
    }
}
