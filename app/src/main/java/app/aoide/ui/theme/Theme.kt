@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.aoide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.aoide.R

/** Spotify's palette with orange where Spotify is green. */
object Aoide {
    /** Accent choices offered in Settings; orange is the house colour. */
    val ACCENTS = linkedMapOf(
        "orange" to Color(0xFFFF7A1F), "green" to Color(0xFF1ED760), "blue" to Color(0xFF3D8BFF), "pink" to Color(0xFFFF4F9A), "purple" to Color(0xFFA26BFF), "white" to Color(0xFFF1F0EC),
    )
    var ground by androidx.compose.runtime.mutableStateOf(Color(0xFF121212))
    val base = Color(0xFF000000)
    val elevated = Color(0xFF1F1F1F)
    val elevated2 = Color(0xFF2A2A2A)
    val highlight = Color(0x1AFFFFFF)
    val highlight2 = Color(0x33FFFFFF)
    val rule = Color(0x1AFFFFFF)
    val fg = Color(0xFFFFFFFF)
    val subdued = Color(0xFFB3B3B3)
    val muted = Color(0xFF7A7A7A)
    var accent by androidx.compose.runtime.mutableStateOf(Color(0xFFFF7A1F))
    val accentHover = Color(0xFFFF9550)
    val accentInk = Color(0xFF000000)
    val likedGradient: List<Color> get() = listOf(accent, androidx.compose.ui.graphics.lerp(accent, Color.White, 0.35f), androidx.compose.ui.graphics.lerp(accent, Color.White, 0.85f))

    /** Apply the listener's choices; called at launch and whenever Settings changes them. */
    fun apply(accentName: String, pureBlack: Boolean) {
        accent = ACCENTS[accentName] ?: ACCENTS.getValue("orange")
        ground = if (pureBlack) Color(0xFF000000) else Color(0xFF121212)
    }
}

private fun f(weight: FontWeight) = Font(R.font.figtree, weight, FontStyle.Normal, variationSettings = FontVariation.Settings(weight, FontStyle.Normal))

val Figtree = FontFamily(f(FontWeight.Normal), f(FontWeight.Medium), f(FontWeight.SemiBold), f(FontWeight.Bold), f(FontWeight.ExtraBold), f(FontWeight.Black))

val AoideTypography = Typography(
    displayLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-1.2).sp),
    headlineLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
)

private fun scheme() = darkColorScheme(
    primary = Aoide.accent,
    onPrimary = Aoide.accentInk,
    secondary = Aoide.subdued,
    background = Aoide.ground,
    onBackground = Aoide.fg,
    surface = Aoide.ground,
    onSurface = Aoide.fg,
    surfaceVariant = Aoide.elevated,
    onSurfaceVariant = Aoide.subdued,
    surfaceContainer = Aoide.elevated,
    surfaceContainerHigh = Aoide.elevated2,
    outline = Aoide.rule,
    error = Color(0xFFFF6B6B),
)

@Composable
fun AoideTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // always dark, by design
    // Reads Aoide.accent and Aoide.ground inside composition, so a new accent recolours every Material control.
    val scheme = androidx.compose.runtime.remember(Aoide.accent, Aoide.ground) { scheme() }
    MaterialTheme(colorScheme = scheme, typography = AoideTypography) {
        // Text without an explicit colour reads LocalContentColor, which only a Surface sets; pin it to the app's ink.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Aoide.fg, content = content)
    }
}

/** Parse "#rrggbb" from the catalogue into a header tint. Prefer [Tint.of] when the ink matters too. */
fun tintOf(hex: String?, fallback: Color = Color(0xFF4A4A4A)): Color = if (hex == null) Tint.from(fallback).accent else Tint.of(hex).accent

/** Header tints read best mid-lightness and clearly saturated; the ink is solved alongside. */
fun adjustTint(c: Color): Color = Tint.from(c).accent
