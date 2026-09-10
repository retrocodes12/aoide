import type { Track } from './api/types'
import { cover, trackArtists } from './api/catalog'

/**
 * Lock-screen and headset controls through the browser's Media Session API. The native Android
 * app (the Kotlin build at the repo root) gets the same through Media3; this web preview only has
 * what the browser offers.
 */
type Handlers = { play: () => void; pause: () => void; next: () => void; prev: () => void; seek: (t: number) => void; stop: () => void }

const ms = () => (typeof navigator !== 'undefined' && 'mediaSession' in navigator ? navigator.mediaSession : null)

export async function msSetHandlers(h: Handlers) {
  const m = ms()
  if (!m) return
  const set = (action: MediaSessionAction, fn: MediaSessionActionHandler | null) => {
    try {
      m.setActionHandler(action, fn)
    } catch {
      /* action unsupported in this browser */
    }
  }
  set('play', () => h.play())
  set('pause', () => h.pause())
  set('nexttrack', () => h.next())
  set('previoustrack', () => h.prev())
  set('seekto', (d) => { if (d.seekTime !== undefined) h.seek(d.seekTime) })
  set('stop', () => h.stop())
}

export async function msSetTrack(track: Track) {
  const m = ms()
  if (!m) return
  const art = ([160, 320, 640] as const).map((s) => ({ src: cover(track.album?.cover, s) ?? '', sizes: `${s}x${s}`, type: 'image/jpeg' })).filter((a) => a.src)
  m.metadata = new MediaMetadata({ title: track.title, artist: trackArtists(track), album: track.album?.title ?? '', artwork: art })
}

export async function msSetState(state: 'playing' | 'paused') {
  const m = ms()
  if (m) m.playbackState = state
}

export async function msSetPosition(position: number, duration: number) {
  const m = ms()
  if (!m || !duration || !Number.isFinite(duration)) return
  try {
    m.setPositionState({ duration, position: Math.min(position, duration), playbackRate: 1 })
  } catch {
    /* invalid state in some browsers */
  }
}
