/** Turn TIDAL's vibrantColor into a usable accent: keep the hue, guarantee contrast on the dark ground. */
export function accentFrom(hex: string | null | undefined, fallback = '#F1F0EC'): { accent: string; ink: string } {
  const rgb = parse(hex)
  if (!rgb) return { accent: fallback, ink: '#151517' }
  let [h, s, l] = rgbToHsl(rgb)
  // Header tints read best mid-lightness and clearly saturated.
  if (l < 0.28) l = 0.34
  if (l > 0.62) l = 0.56
  if (s > 0.05 && s < 0.3) s = 0.38
  const out = hslToHex(h, s, l)
  const lum = relLum(parse(out)!)
  return { accent: out, ink: lum > 0.35 ? '#151517' : '#F1F0EC' }
}

function parse(hex: string | null | undefined): [number, number, number] | null {
  if (!hex) return null
  const m = hex.trim().match(/^#?([0-9a-f]{6})$/i)
  if (!m) return null
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}
function rgbToHsl([r, g, b]: [number, number, number]): [number, number, number] {
  r /= 255; g /= 255; b /= 255
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h = 0
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0)
  else if (max === g) h = (b - r) / d + 2
  else h = (r - g) / d + 4
  return [h / 6, s, l]
}
function hslToHex(h: number, s: number, l: number): string {
  const f = (n: number) => {
    const k = (n + h * 12) % 12
    const a = s * Math.min(l, 1 - l)
    const c = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    return Math.round(c * 255).toString(16).padStart(2, '0')
  }
  return `#${f(0)}${f(8)}${f(4)}`
}
function relLum([r, g, b]: [number, number, number]): number {
  const c = [r, g, b].map((v) => {
    v /= 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}

const imageTintCache = new Map<string, Promise<string | null>>()
/** Average the saturated pixels of an image (CORS-enabled) into one tint. */
export function tintFromImage(url: string): Promise<string | null> {
  if (!url) return Promise.resolve(null)
  let p = imageTintCache.get(url)
  if (p) return p
  p = new Promise<string | null>((resolve) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => {
      try {
        const c = document.createElement('canvas')
        c.width = c.height = 24
        const ctx = c.getContext('2d', { willReadFrequently: true })!
        ctx.drawImage(img, 0, 0, 24, 24)
        const d = ctx.getImageData(0, 0, 24, 24).data
        let r = 0, g = 0, b = 0, n = 0
        for (let i = 0; i < d.length; i += 4) {
          const [hh, ss, ll] = rgbToHsl([d[i], d[i + 1], d[i + 2]])
          void hh
          const w = ss * (1 - Math.abs(ll - 0.5) * 1.6) + 0.05
          if (w <= 0) continue
          r += d[i] * w; g += d[i + 1] * w; b += d[i + 2] * w; n += w
        }
        if (!n) return resolve(null)
        resolve('#' + [r / n, g / n, b / n].map((v) => Math.round(v).toString(16).padStart(2, '0')).join(''))
      } catch {
        resolve(null)
      }
    }
    img.onerror = () => resolve(null)
    img.src = url
  })
  imageTintCache.set(url, p)
  return p
}
