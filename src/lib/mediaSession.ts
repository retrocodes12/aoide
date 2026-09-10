import { Capacitor } from '@capacitor/core'
import type { Track } from './api/types'
import { cover, trackArtists } from './api/catalog'

/**
 * One face over two media-session implementations: the browser's Media Session API on the
 * web, and Capgo's plugin inside the Android app, where it drives a foreground service with
 * the notification, lock-screen and headset controls.
 */
type Handlers = { play: () => void; pause: () => void; next: () => void; prev: () => void; seek: (s: number) => void; stop?: () => void }

let plugin: typeof import('@capgo/capacitor-media-session').MediaSession | null = null
const native = Capacitor.isNativePlatform()
async function p() {
  if (!native) return null
  return (plugin ??= (await import('@capgo/capacitor-media-session')).MediaSession)
}

export async function msSetHandlers(h: Handlers) {
  const m = await p()
  if (m) {
    await m.setActionHandler({ action: 'play' }, () => h.play())
    await m.setActionHandler({ action: 'pause' }, () => h.pause())
    await m.setActionHandler({ action: 'nexttrack' }, () => h.next())
    await m.setActionHandler({ action: 'previoustrack' }, () => h.prev())
    await m.setActionHandler({ action: 'seekto' }, (d) => { if (d?.seekTime != null) h.seek(d.seekTime) })
    await m.setActionHandler({ action: 'stop' }, () => h.stop?.())
    return
  }
  if (!('mediaSession' in navigator)) return
  const ms = navigator.mediaSession
  ms.setActionHandler('play', () => h.play())
  ms.setActionHandler('pause', () => h.pause())
  ms.setActionHandler('nexttrack', () => h.next())
  ms.setActionHandler('previoustrack', () => h.prev())
  ms.setActionHandler('seekto', (d) => { if (d.seekTime !== undefined) h.seek(d.seekTime) })
}

export async function msSetTrack(t: Track) {
  const artwork = ([160, 320, 640] as const).map((s) => ({ src: cover(t.album?.cover, s) ?? '', sizes: `${s}x${s}`, type: 'image/jpeg' })).filter((a) => a.src)
  const m = await p()
  if (m) return m.setMetadata({ title: t.title, artist: trackArtists(t), album: t.album?.title ?? '', artwork })
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: trackArtists(t), album: t.album?.title ?? '', artwork })
}

export async function msSetState(state: 'playing' | 'paused' | 'none') {
  const m = await p()
  if (m) return m.setPlaybackState({ playbackState: state })
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = state
}

export async function msSetPosition(position: number, duration: number) {
  if (!Number.isFinite(duration) || duration <= 0) return
  const pos = Math.min(Math.max(0, position), duration)
  const m = await p()
  if (m) return m.setPositionState({ duration, position: pos, playbackRate: 1 })
  if ('mediaSession' in navigator && 'setPositionState' in navigator.mediaSession) navigator.mediaSession.setPositionState({ duration, position: pos, playbackRate: 1 })
}
