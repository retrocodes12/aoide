package app.aoide.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A surface colour derived from artwork, plus the ink that sits on it.
 *
 * The ink is chosen by measured contrast, never by a luminance threshold: light ink is preferred
 * (white on colour is the signature look) and the tint is darkened for it, but only down to a
 * lightness where the colour still reads; past that the tint keeps its brightness and takes dark
 * ink. Every ink here clears WCAG AA on [accent]; [soft] and [faint] are solved for the exact
 * opacity that still passes, so a yellow and a navy get different alphas.
 */
data class Tint(val accent: Color, val ink: Color, val soft: Color, val faint: Color) {
    val isDarkInk: Boolean get() = ink == DARK_INK

    companion object {
        val LIGHT_INK = Color(0xFFF1F0EC)
        val DARK_INK = Color(0xFF151517)
        val FALLBACK: Tint = from(Color(0xFF4A4A4A))
        private const val TARGET = 5.5

        /** Parse "#rrggbb" from the catalogue; anything unreadable gets the neutral fallback. */
        fun of(hex: String?): Tint {
            val h = hex?.trim()?.removePrefix("#") ?: return FALLBACK
            if (h.length != 6) return FALLBACK
            val rgb = h.toLongOrNull(16) ?: return FALLBACK
            return from(Color(0xFF000000 or rgb))
        }

        fun from(c: Color): Tint {
            val hsl = rgbToHsl(c)
            val h = hsl[0]
            var s = hsl[1]
            var l = hsl[2]
            // Header tints read best mid-lightness and clearly saturated.
            if (l < 0.28f) l = 0.34f
            if (l > 0.62f) l = 0.56f
            if (s > 0.05f && s < 0.3f) s = 0.38f
            // Light ink, darkening the tint as far as 0.36 lightness.
            var lt = l
            var tint = hslToColor(h, s, lt)
            while (contrast(LIGHT_INK, tint) < TARGET && lt > 0.36f) {
                lt = max(0.36f, lt - 0.02f)
                tint = hslToColor(h, s, lt)
            }
            var ink = LIGHT_INK
            if (contrast(LIGHT_INK, tint) < TARGET) {
                // Too bright a hue to darken without killing it: keep it bright and use dark ink.
                var ld = l
                tint = hslToColor(h, s, ld)
                while (contrast(DARK_INK, tint) < TARGET && ld < 0.72f) {
                    ld = min(0.72f, ld + 0.02f)
                    tint = hslToColor(h, s, ld)
                }
                ink = DARK_INK
                val deep = hslToColor(h, s, 0.3f)
                if (contrast(DARK_INK, tint) < contrast(LIGHT_INK, deep)) {
                    tint = deep
                    ink = LIGHT_INK
                }
            }
            return Tint(tint, ink, ink.copy(alpha = alphaFor(ink, tint, 4.5, 0.6f)), ink.copy(alpha = alphaFor(ink, tint, 3.0, 0.38f)))
        }

        /** WCAG contrast ratio between two opaque colours. */
        fun contrast(a: Color, b: Color): Double {
            val la = relLum(a)
            val lb = relLum(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** Smallest opacity, from [minAlpha] upward, at which [ink] over [bg] still clears [ratio]. */
        private fun alphaFor(ink: Color, bg: Color, ratio: Double, minAlpha: Float): Float {
            var a = minAlpha
            while (a < 1f) {
                val mixed = Color(a * ink.red + (1 - a) * bg.red, a * ink.green + (1 - a) * bg.green, a * ink.blue + (1 - a) * bg.blue)
                if (contrast(mixed, bg) >= ratio) return (Math.round(a * 100f) / 100f)
                a += 0.02f
            }
            return 1f
        }

        private fun relLum(c: Color): Double {
            fun lin(v: Float): Double { val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        }

        private fun rgbToHsl(c: Color): FloatArray {
            val r = c.red; val g = c.green; val b = c.blue
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val l = (mx + mn) / 2f
            if (mx == mn) return floatArrayOf(0f, 0f, l)
            val d = mx - mn
            val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            var h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h /= 6f
            return floatArrayOf(h, s, l)
        }

        private fun hslToColor(h: Float, s: Float, l: Float): Color {
            fun f(n: Int): Float {
                val k = (n + h * 12f) % 12f
                val a = s * min(l, 1f - l)
                return l - a * max(-1f, min(min(k - 3f, 9f - k), 1f))
            }
            return Color(f(0), f(8), f(4))
        }
    }
}
