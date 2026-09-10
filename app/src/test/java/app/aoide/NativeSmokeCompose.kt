package app.aoide

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Compose rule alone, no qualifiers, one Text, one capture. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NativeSmokeCompose {
    @get:Rule
    val rule = createComposeRule()

    @Test fun rendersText() {
        rule.setContent { Text("Aoide") }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/smoke-compose.png")
    }
}
