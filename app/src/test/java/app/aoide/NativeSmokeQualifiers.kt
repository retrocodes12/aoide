package app.aoide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Same pixel test, but with the Pixel 5 qualifiers the screenshot suite uses. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class NativeSmokeQualifiers {
    @Test fun drawsAPixel() {
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 4f, 4f, Paint().apply { color = 0xFFFF0000.toInt() })
        assertEquals(0xFFFF0000.toInt(), b.getPixel(1, 1))
    }
}
