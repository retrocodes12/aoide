package app.aoide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.aoide.player.Status
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.aoide.player.PlayerController
import app.aoide.ui.AppRoot
import app.aoide.ui.theme.AoideTheme

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** A launcher shortcut names a route; a shared playlist link opens the importer with it. */
    private fun route(intent: android.content.Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { app.aoide.ui.TestNav.go(it); intent.removeExtra(EXTRA_ROUTE); return }
        val shared = intent.dataString ?: intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        if (shared != null && app.aoide.data.Importer.recognise(shared) != null) {
            app.aoide.ui.TestNav.go("import?link=${android.net.Uri.encode(shared)}")
            intent.removeExtra(android.content.Intent.EXTRA_TEXT)
            intent.data = null
        }
    }

    companion object { const val EXTRA_ROUTE = "app.aoide.route" }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the media notification appears on the next play either way */ }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerController.connect(applicationContext)
        setContent { AoideTheme { AppRoot() } }
        route(intent)
        // Android 13+ shows no media notification, and so no lock-screen transport, until the listener allows it.
        // Ask the first time something actually plays, not at launch, the way Spotify does.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                PlayerController.state.filter { it.status == Status.PLAYING || it.status == Status.LOADING }.first()
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
