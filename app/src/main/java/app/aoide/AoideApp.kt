package app.aoide

import android.app.Application
import app.aoide.data.Downloads
import app.aoide.player.AudioEffects
import app.aoide.ui.theme.Aoide
import app.aoide.data.Library
import app.aoide.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AoideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        Downloads.init(this)
        app.aoide.data.Legacy.init(this)
        app.aoide.data.Legacy.migrate()
        AudioEffects.load()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { app.aoide.data.HiRate.prune(); app.aoide.player.Positions.prune() }
        Aoide.apply(Prefs.accent.value, Prefs.pureBlack.on)
        if (BuildConfig.DEBUG) runCatching {
            Class.forName("app.aoide.debug.DebugInit").getMethod("init", Application::class.java).invoke(null, this)
        }
    }
}
