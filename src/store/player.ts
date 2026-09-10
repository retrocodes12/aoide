import { create } from 'zustand'
import { getEngine } from '../lib/player/engine'
import { msSetHandlers, msSetPosition, msSetState, msSetTrack } from '../lib/mediaSession'
import type { Quality, ResolvedStream, Track } from '../lib/api/types'
import { useLibrary } from './library'
import { useUI } from './ui'

export type Status = 'idle' | 'loading' | 'playing' | 'paused' | 'buffering' | 'error'
export type Repeat = 'off' | 'all' | 'one'

interface PlayerState {
  queue: Track[]
  index: number
  status: Status
  error: string | null
  position: number
  duration: number
  volume: number
  muted: boolean
  shuffle: boolean
  repeat: Repeat
  quality: Quality
  stream: ResolvedStream | null
  /** Where the queue came from, for the "playing from" label. */
  context: { kind: string; title: string; href?: string } | null
  history: number[]

  current: () => Track | null
  playTracks: (tracks: Track[], start: number, context?: PlayerState['context']) => void
  playTrack: (track: Track, context?: PlayerState['context']) => void
  toggle: () => void
  next: () => void
  prev: () => void
  seek: (t: number) => void
  setVolume: (v: number) => void
  toggleMute: () => void
  toggleShuffle: () => void
  cycleRepeat: () => void
  setQuality: (q: Quality) => void
  enqueueNext: (t: Track) => void
  enqueueLast: (t: Track) => void
  removeAt: (i: number) => void
  moveInQueue: (from: number, to: number) => void
  clearUpcoming: () => void
  jumpTo: (i: number) => void
}

const VOL_KEY = 'aoide:volume'
const Q_KEY = 'aoide:quality'
const savedVol = Number(localStorage.getItem(VOL_KEY) ?? '0.8')
const savedQ = (localStorage.getItem(Q_KEY) as Quality | null) ?? 'LOSSLESS'

let loadAbort: AbortController | null = null

export const usePlayer = create<PlayerState>((set, get) => {
  const engine = getEngine()
  engine.setVolume(Number.isFinite(savedVol) ? savedVol : 0.8)

  engine.on('time', (position, duration) => {
    const dur = duration || get().current()?.duration || 0
    set({ position, duration: dur })
    const now = Date.now()
    if (now - lastPosPush > 4000) {
      lastPosPush = now
      void msSetPosition(position, dur)
    }
  })
  engine.on('state', (s, message) => {
    if (s === 'error') set({ status: 'error', error: message ?? 'Playback failed' })
    else set({ status: s, error: null })
    if (s === 'playing') void msSetState('playing')
    else if (s === 'paused') void msSetState('paused')
  })
  engine.on('loaded', (stream) => set({ stream }))
  engine.on('ended', () => {
    const { repeat } = get()
    if (repeat === 'one') {
      engine.seek(0)
      void engine.play()
      return
    }
    get().next()
  })

  async function loadIndex(i: number, autoplay = true) {
    const { queue, quality } = get()
    const track = queue[i]
    if (!track) return
    loadAbort?.abort()
    loadAbort = new AbortController()
    set({ index: i, status: 'loading', error: null, position: 0, duration: track.duration, stream: null })
    useLibrary.getState().recordPlay(track)
    useUI.getState().setTintFrom(track.album?.vibrantColor)
    updateMediaSession(track)
    try {
      const stream = await engine.load(track, quality, autoplay, loadAbort.signal)
      if (stream.isPreview) useUI.getState().notePreview()
    } catch (e) {
      if ((e as Error).name === 'AbortError') return
      set({ status: 'error', error: (e as Error).message || 'Could not load this track' })
      useUI.getState().toast(`Couldn't play "${track.title}". Trying the next one.`)
      // auto-advance after a beat so a dead track doesn't stall the queue
      setTimeout(() => {
        if (get().index === i && get().status === 'error') get().next()
      }, 1200)
    }
  }

  let handlersSet = false
  function updateMediaSession(track: Track) {
    void msSetTrack(track)
    if (!handlersSet) {
      handlersSet = true
      void msSetHandlers({ play: () => get().toggle(), pause: () => get().toggle(), next: () => get().next(), prev: () => get().prev(), seek: (t) => get().seek(t), stop: () => engine.pause() })
    }
  }
  let lastPosPush = 0

  return {
    queue: [],
    index: -1,
    status: 'idle',
    error: null,
    position: 0,
    duration: 0,
    volume: Number.isFinite(savedVol) ? savedVol : 0.8,
    muted: false,
    shuffle: false,
    repeat: 'off',
    quality: savedQ,
    stream: null,
    context: null,
    history: [],

    current: () => get().queue[get().index] ?? null,

    playTracks: (tracks, start, context = null) => {
      const playable = tracks.filter((t) => t && t.duration !== undefined)
      if (!playable.length) return
      let queue = playable
      let index = Math.max(0, Math.min(start, playable.length - 1))
      if (get().shuffle) {
        const first = playable[index]
        const rest = playable.filter((_, i) => i !== index)
        shuffleInPlace(rest)
        queue = [first, ...rest]
        index = 0
      }
      set({ queue, context, history: [] })
      void loadIndex(index)
    },
    playTrack: (track, context) => get().playTracks([track], 0, context ?? { kind: 'track', title: track.title }),

    toggle: () => {
      const { status, index } = get()
      if (index < 0) return
      if (status === 'playing' || status === 'buffering') engine.pause()
      else if (status === 'paused') void engine.play()
      else if (status === 'error') void loadIndex(index)
    },
    next: () => {
      const { index, queue, repeat } = get()
      if (index + 1 < queue.length) return void loadIndex(index + 1)
      if (repeat === 'all' && queue.length) return void loadIndex(0)
      engine.pause()
      set({ status: 'paused', position: 0 })
    },
    prev: () => {
      const { index, position } = get()
      if (position > 3 || index <= 0) return get().seek(0)
      void loadIndex(index - 1)
    },
    seek: (t) => {
      // Never seek past what the media actually holds (previews end at 30 s); a seek past the end wedges the loader.
      const max = engine.duration || get().duration
      engine.seek(max ? Math.min(t, Math.max(0, max - 1)) : t)
    },
    setVolume: (v) => {
      engine.setVolume(v)
      localStorage.setItem(VOL_KEY, String(v))
      set({ volume: v, muted: false })
    },
    toggleMute: () => {
      const { muted, volume } = get()
      engine.setVolume(muted ? volume : 0)
      set({ muted: !muted })
    },
    toggleShuffle: () => {
      const { shuffle, queue, index } = get()
      if (!shuffle && queue.length > 1) {
        const cur = queue[index]
        const rest = queue.filter((_, i) => i !== index)
        shuffleInPlace(rest)
        set({ shuffle: true, queue: [cur, ...rest], index: 0 })
      } else set({ shuffle: false })
    },
    cycleRepeat: () => set({ repeat: get().repeat === 'off' ? 'all' : get().repeat === 'all' ? 'one' : 'off' }),
    setQuality: (q) => {
      localStorage.setItem(Q_KEY, q)
      set({ quality: q })
      const { index, status } = get()
      if (index >= 0 && status !== 'idle') {
        const pos = engine.currentTime
        void loadIndex(index, status === 'playing').then(() => engine.seek(pos))
      }
    },
    enqueueNext: (t) => {
      const { queue, index } = get()
      const q = [...queue]
      q.splice(index + 1, 0, t)
      set({ queue: q })
      if (index < 0) void loadIndex(0)
    },
    enqueueLast: (t) => {
      const { queue, index } = get()
      set({ queue: [...queue, t] })
      if (index < 0) void loadIndex(0)
    },
    removeAt: (i) => {
      const { queue, index } = get()
      if (i === index) return
      const q = queue.filter((_, k) => k !== i)
      set({ queue: q, index: i < index ? index - 1 : index })
    },
    moveInQueue: (from, to) => {
      const { queue, index } = get()
      if (from === to || from === index || to === index) return
      const q = [...queue]
      const [item] = q.splice(from, 1)
      q.splice(to, 0, item)
      let idx = index
      if (from < index && to >= index) idx--
      else if (from > index && to <= index) idx++
      set({ queue: q, index: idx })
    },
    clearUpcoming: () => {
      const { queue, index } = get()
      set({ queue: queue.slice(0, index + 1) })
    },
    jumpTo: (i) => void loadIndex(i),
  }
})

function shuffleInPlace<T>(a: T[]) {
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[a[i], a[j]] = [a[j], a[i]]
  }
}
