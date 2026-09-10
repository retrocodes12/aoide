import { getManifest } from '../api/catalog'
import { nativeManifest } from '../api/client'
import type { Quality, ResolvedStream, Track } from '../api/types'
import type shaka from 'shaka-player'

type ShakaNS = typeof shaka
let shakaPromise: Promise<ShakaNS> | null = null
function loadShaka() {
  return (shakaPromise ??= import('shaka-player').then((m) => {
    const mod = m as unknown as { default?: ShakaNS }
    const s = mod.default ?? (m as unknown as ShakaNS)
    s.polyfill.installAll()
    return s
  }))
}

/**
 * Resolve something playable for a track. Order:
 *   1. hifi-api mirrors (`/track/?id=&quality=`) -> inline DASH or JSON manifest
 *   2. TIDAL's own manifest endpoint (preview unless subscribed)
 */
export async function resolveStream(track: Track, quality: Quality, signal?: AbortSignal): Promise<ResolvedStream> {
  try {
    const m = await getManifest(track.id, quality, { signal })
    const decoded = safeAtob(m.manifest)
    const isPreview = m.assetPresentation === 'PREVIEW'
    if (decoded.includes('<MPD')) {
      const blob = new Blob([decoded], { type: 'application/dash+xml' })
      const url = URL.createObjectURL(blob)
      return { url, mimeType: 'application/dash+xml', isPreview, quality: m.audioQuality, bitDepth: m.bitDepth, sampleRate: m.sampleRate, source: 'mirror', revoke: () => URL.revokeObjectURL(url) }
    }
    try {
      const json = JSON.parse(decoded) as { urls?: string[]; mimeType?: string }
      const direct = json.urls?.[0]
      if (direct) return { url: direct, mimeType: json.mimeType ?? 'audio/flac', isPreview, quality: m.audioQuality, source: 'mirror' }
    } catch {
      /* not JSON */
    }
    const match = decoded.match(/https?:\/\/[^\s"'<>]+/)
    if (match) return { url: match[0], mimeType: '', isPreview, quality: m.audioQuality, source: 'mirror' }
    throw new Error('Unreadable manifest')
  } catch (mirrorErr) {
    if (signal?.aborted) throw mirrorErr
    const n = await nativeManifest(track.id, signal, quality)
    const fmt = n.formats?.[0] ?? quality
    return { url: n.uri, mimeType: 'application/dash+xml', isPreview: n.trackPresentation === 'PREVIEW', quality: fmt === 'FLAC' ? 'LOSSLESS' : fmt.startsWith('AAC') || fmt.startsWith('HEAAC') ? 'HIGH' : fmt, source: 'tidal' }
  }
}

function safeAtob(s: string): string {
  try {
    return atob(s)
  } catch {
    return s
  }
}

export interface EngineEvents {
  time: (t: number, d: number) => void
  ended: () => void
  state: (s: 'loading' | 'playing' | 'paused' | 'buffering' | 'error', message?: string) => void
  loaded: (s: ResolvedStream) => void
}

/** Thin wrapper over one <audio> element and one shaka.Player. */
export class Engine {
  readonly audio: HTMLAudioElement
  private player: shaka.Player | null = null
  private current: ResolvedStream | null = null
  private loadSeq = 0
  private listeners: { [K in keyof EngineEvents]: Set<EngineEvents[K]> } = { time: new Set(), ended: new Set(), state: new Set(), loaded: new Set() }
  private raf = 0

  constructor() {
    this.audio = new Audio()
    this.audio.preload = 'auto'
    this.audio.crossOrigin = 'anonymous'
    this.audio.addEventListener('ended', () => this.emit('ended'))
    this.audio.addEventListener('play', () => this.emit('state', 'playing'))
    this.audio.addEventListener('pause', () => this.emit('state', 'paused'))
    this.audio.addEventListener('waiting', () => this.emit('state', 'buffering'))
    this.audio.addEventListener('playing', () => this.emit('state', 'playing'))
    this.audio.addEventListener('error', () => this.emit('state', 'error', 'Playback failed'))
    const tick = () => {
      if (!this.audio.paused) this.emit('time', this.audio.currentTime, this.audio.duration || 0)
      this.raf = requestAnimationFrame(tick)
    }
    this.raf = requestAnimationFrame(tick)
  }

  on<K extends keyof EngineEvents>(ev: K, fn: EngineEvents[K]): () => void {
    ;(this.listeners[ev] as Set<EngineEvents[K]>).add(fn)
    return () => (this.listeners[ev] as Set<EngineEvents[K]>).delete(fn)
  }
  private emit<K extends keyof EngineEvents>(ev: K, ...args: Parameters<EngineEvents[K]>) {
    for (const fn of this.listeners[ev]) (fn as (...a: Parameters<EngineEvents[K]>) => void)(...args)
  }

  private async ensurePlayer() {
    if (this.player) return this.player
    const shaka = await loadShaka()
    const p = new shaka.Player()
    await p.attach(this.audio)
    p.configure({
      streaming: { bufferingGoal: 60, rebufferingGoal: 2, retryParameters: { maxAttempts: 4, baseDelay: 400 } },
      manifest: { retryParameters: { maxAttempts: 3 } },
    })
    p.addEventListener('error', (e: Event) => {
      const d = (e as unknown as { detail?: { code?: number; message?: string } }).detail
      this.emit('state', 'error', `Stream error${d?.code ? ' ' + d.code : ''}`)
    })
    this.player = p
    return p
  }

  async load(track: Track, quality: Quality, autoplay: boolean, signal?: AbortSignal): Promise<ResolvedStream> {
    const seq = ++this.loadSeq
    this.emit('state', 'loading')
    const stream = await resolveStream(track, quality, signal)
    if (seq !== this.loadSeq) {
      stream.revoke?.()
      throw new DOMException('superseded', 'AbortError')
    }
    this.current?.revoke?.()
    this.current = stream
    if (stream.mimeType.includes('dash') || stream.url.endsWith('.mpd')) {
      const p = await this.ensurePlayer()
      if (seq !== this.loadSeq) throw new DOMException('superseded', 'AbortError')
      await p.load(stream.url, 0, 'application/dash+xml')
    } else {
      await this.player?.unload()
      this.audio.src = stream.url
    }
    if (seq !== this.loadSeq) throw new DOMException('superseded', 'AbortError')
    this.emit('loaded', stream)
    this.emit('time', 0, this.audio.duration || track.duration)
    if (autoplay) await this.play()
    else this.emit('state', 'paused')
    return stream
  }

  async play() {
    try {
      await this.audio.play()
    } catch (e) {
      this.emit('state', 'paused', (e as Error).message)
    }
  }
  pause() {
    this.audio.pause()
  }
  seek(t: number) {
    const d = this.audio.duration
    this.audio.currentTime = Math.max(0, Number.isFinite(d) && d > 0 ? Math.min(t, d - 0.3) : t)
    this.emit('time', this.audio.currentTime, this.audio.duration || 0)
  }
  setVolume(v: number) {
    this.audio.volume = Math.max(0, Math.min(1, v))
  }
  get duration() {
    return this.audio.duration || 0
  }
  get currentTime() {
    return this.audio.currentTime
  }
  async stop() {
    this.loadSeq++
    this.audio.pause()
    await this.player?.unload().catch(() => {})
    this.current?.revoke?.()
    this.current = null
    this.audio.removeAttribute('src')
  }
  destroy() {
    cancelAnimationFrame(this.raf)
    void this.stop()
    void this.player?.destroy()
  }
}

let engine: Engine | null = null
export function getEngine() {
  return (engine ??= new Engine())
}
