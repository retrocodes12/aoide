package app.aoide

import android.app.Application
import app.aoide.data.Instances
import app.aoide.data.Library
import app.aoide.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AoideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Instances.load()
        Library.init(this)
        CoroutineScope(Dispatchers.IO).launch { Instances.refreshFromUptime() }
        if (BuildConfig.DEBUG) runCatching {
            Class.forName("app.aoide.debug.DebugInit").getMethod("init", Application::class.java).invoke(null, this)
        }
    }
}
