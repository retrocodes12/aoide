package app.aoide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The smallest thing that exercises Robolectric's native graphics: draw one red pixel and read it back. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NativeSmoke {
    @Test fun drawsAPixel() {
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 4f, 4f, Paint().apply { color = 0xFFFF0000.toInt() })
        assertEquals(0xFFFF0000.toInt(), b.getPixel(1, 1))
    }
}
