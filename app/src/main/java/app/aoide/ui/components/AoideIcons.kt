package app.aoide.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The tab bar's own glyphs, drawn in Spotify's proportions: a chunky house, a round magnifier and
 * three tilted spines for the library. Material's stock set reads as "Android app"; these do not.
 */
object AoideIcons {
    private fun vector(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    val Home: ImageVector = vector("aoide.home") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
            moveTo(12f, 2.6f); lineTo(2.5f, 10.2f); lineTo(2.5f, 21.5f); lineTo(9.6f, 21.5f); lineTo(9.6f, 14.6f)
            lineTo(14.4f, 14.6f); lineTo(14.4f, 21.5f); lineTo(21.5f, 21.5f); lineTo(21.5f, 10.2f); close()
        }
    }
    val HomeOutline: ImageVector = vector("aoide.home.outline") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 3.2f); lineTo(3.4f, 10.1f); lineTo(3.4f, 20.6f); lineTo(9.4f, 20.6f); lineTo(9.4f, 14.3f)
            lineTo(14.6f, 14.3f); lineTo(14.6f, 20.6f); lineTo(20.6f, 20.6f); lineTo(20.6f, 10.1f); close()
        }
    }
    val Search: ImageVector = vector("aoide.search") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round) {
            // a circle from four arcs
            moveTo(10.4f, 3.4f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 10.39f, y1 = 3.4f)
            moveTo(15.6f, 15.6f); lineTo(21f, 21f)
        }
    }
    val SearchBold: ImageVector = vector("aoide.search.bold") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 3.2f, strokeLineCap = StrokeCap.Round) {
            moveTo(10.4f, 3.6f)
            arcTo(6.8f, 6.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 10.39f, y1 = 3.6f)
            moveTo(15.6f, 15.6f); lineTo(21f, 21f)
        }
    }
    val Library: ImageVector = vector("aoide.library") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 3f); lineTo(6f, 3f); lineTo(6f, 21f); lineTo(3f, 21f); close()
            moveTo(8.5f, 3f); lineTo(11.5f, 3f); lineTo(11.5f, 21f); lineTo(8.5f, 21f); close()
            moveTo(13.2f, 4.1f); lineTo(16.1f, 3.3f); lineTo(21.2f, 20.4f); lineTo(18.3f, 21.2f); close()
        }
    }
    val LibraryOutline: ImageVector = vector("aoide.library.outline") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.2f, 3.8f); lineTo(4.2f, 20.2f)
            moveTo(9.5f, 3.8f); lineTo(9.5f, 20.2f)
            moveTo(14.2f, 4.4f); lineTo(19.2f, 19.9f)
        }
    }
}
