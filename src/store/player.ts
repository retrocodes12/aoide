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
  /** True once the engine holds media for the current index; false while loading or on a restored session. */
  loaded: boolean
  /** The queue as it was before shuffle, so turning shuffle off restores the order. */
  unshuffled: Track[] | null
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
const SESSION_KEY = 'aoide:session:v1'
const savedVol = Number(localStorage.getItem(VOL_KEY) ?? '0.8')
const savedQ = (localStorage.getItem(Q_KEY) as Quality | null) ?? 'LOSSLESS'

export type PlayContext = { kind: string; title: string; href?: string }

interface Session {
  queue: Track[]
  index: number
  position: number
  context: PlayContext | null
  shuffle: boolean
  repeat: Repeat
}
/** The last queue, paused where it was, so a relaunch comes back on the same song. */
function readSession(): Session | null {
  try {
    const s = JSON.parse(localStorage.getItem(SESSION_KEY) ?? 'null') as Session | null
    if (!s || !Array.isArray(s.queue) || !s.queue.length || s.index < 0 || s.index >= s.queue.length) return null
    return s
  } catch {
    return null
  }
}
const session = readSession()

let loadAbort: AbortController | null = null
/** False until the engine actually holds media; a hydrated session starts paused with nothing loaded. */
let engineLoaded = false
let lastSave = 0
/** Consecutive songs that failed to load; after three we stop skipping ahead and ask the listener. */
let failStreak = 0

export const usePlayer = create<PlayerState>((set, get) => {
  const engine = getEngine()
  engine.setVolume(Number.isFinite(savedVol) ? savedVol : 0.8)

  engine.on('time', (position, duration) => {
    // Old media can still tick while the next song is loading or has failed; only the loaded song may drive the transport.
    if (!get().loaded) return
    const dur = duration || get().duration || 0
    set({ position, duration: dur })
    const now = Date.now()
    if (now - lastPosPush > 4000) {
      lastPosPush = now
      void msSetPosition(position, dur)
      saveSession()
    }
  })
  engine.on('state', (s, message) => {
    if (s === 'error') set({ status: 'error', error: message ?? "Couldn't play this song" })
    else set({ status: s, error: null })
    if (s === 'playing') {
      failStreak = 0
      void msSetState('playing')
    }
    else if (s === 'paused') {
      void msSetState('paused')
      saveSession(true)
    }
  })
  engine.on('loaded', (stream) => set({ stream, loaded: true }))
  engine.on('ended', () => {
    const { repeat } = get()
    if (repeat === 'one') {
      engine.seek(0)
      void engine.play()
      return
    }
    get().next()
  })

  function saveSession(force = false) {
    const s = get()
    const now = Date.now()
    if (!force && now - lastSave < 4000) return
    lastSave = now
    try {
      if (s.index < 0) localStorage.removeItem(SESSION_KEY)
      else localStorage.setItem(SESSION_KEY, JSON.stringify({ queue: s.queue.slice(0, 300), index: s.index, position: s.position, context: s.context, shuffle: s.shuffle, repeat: s.repeat } satisfies Session))
    } catch {
      /* storage full or unavailable */
    }
  }

  async function loadIndex(i: number, autoplay = true, startAt = 0) {
    const { queue, quality } = get()
    const track = queue[i]
    if (!track) return
    loadAbort?.abort()
    loadAbort = new AbortController()
    engineLoaded = false
    set({ index: i, status: 'loading', error: null, position: startAt, duration: 0, stream: null, loaded: false })
    useLibrary.getState().recordPlay(track)
    useUI.getState().setPlayerTint(track.album?.vibrantColor)
    updateMediaSession(track)
    saveSession(true)
    try {
      const stream = await engine.load(track, quality, autoplay, loadAbort.signal)
      engineLoaded = true
      if (startAt > 0) engine.seek(startAt)
      if (stream.isPreview) useUI.getState().notePreview()
    } catch (e) {
      if ((e as Error).name === 'AbortError') return
      failStreak++
      if (failStreak >= 3 || i + 1 >= get().queue.length) {
        // Three dead songs in a row is not a bad song, it is a dead connection or a dead mirror. Stop and say so.
        set({ status: 'error', error: "Playback isn't working right now. Check your connection, then try again." })
        return
      }
      set({ status: 'error', error: friendly((e as Error).message) })
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

  if (session) {
    queueMicrotask(() => {
      const t = session.queue[session.index]
      useUI.getState().setPlayerTint(t.album?.vibrantColor)
      updateMediaSession(t)
    })
  }

  return {
    queue: session?.queue ?? [],
    index: session?.index ?? -1,
    status: session ? 'paused' : 'idle',
    error: null,
    position: session?.position ?? 0,
    duration: session ? session.queue[session.index].duration : 0,
    volume: Number.isFinite(savedVol) ? savedVol : 0.8,
    muted: false,
    shuffle: session?.shuffle ?? false,
    repeat: session?.repeat ?? 'off',
    quality: savedQ,
    stream: null,
    loaded: false,
    unshuffled: null,
    context: session?.context ?? null,
    history: [],

    current: () => get().queue[get().index] ?? null,

    playTracks: (tracks, start, context = null) => {
      const playable = tracks.filter((t) => t && t.duration !== undefined)
      if (!playable.length) return
      let queue = playable
      let index = Math.max(0, Math.min(start, playable.length - 1))
      let unshuffled: Track[] | null = null
      if (get().shuffle) {
        unshuffled = playable
        const first = playable[index]
        const rest = playable.filter((_, i) => i !== index)
        shuffleInPlace(rest)
        queue = [first, ...rest]
        index = 0
      }
      failStreak = 0
      set({ queue, context, history: [], unshuffled })
      void loadIndex(index)
    },
    playTrack: (track, context) => get().playTracks([track], 0, context ?? { kind: 'track', title: track.title }),

    toggle: () => {
      const { status, index, position } = get()
      if (index < 0) return
      if (!engineLoaded) return void loadIndex(index, true, position)
      if (status === 'playing' || status === 'buffering') engine.pause()
      else if (status === 'paused') void engine.play()
      else if (status === 'error') {
        failStreak = 0
        void loadIndex(index)
      }
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
      const target = max ? Math.min(t, Math.max(0, max - 1)) : t
      if (!engineLoaded) return set({ position: Math.max(0, target) })
      engine.seek(target)
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
        set({ shuffle: true, queue: [cur, ...rest], index: 0, unshuffled: queue })
      } else {
        // Back to the order the listener started with, keeping the current song where it is.
        const { unshuffled } = get()
        const cur = queue[index]
        if (unshuffled && cur) {
          const known = new Set(unshuffled.map((t) => t.id))
          const restored = [...unshuffled, ...queue.filter((t) => !known.has(t.id))].filter((t) => queue.some((q) => q.id === t.id))
          const idx = Math.max(0, restored.findIndex((t) => t.id === cur.id))
          set({ shuffle: false, queue: restored, index: idx, unshuffled: null })
        } else set({ shuffle: false, unshuffled: null })
      }
      saveSession(true)
    },
    cycleRepeat: () => {
      set({ repeat: get().repeat === 'off' ? 'all' : get().repeat === 'all' ? 'one' : 'off' })
      saveSession(true)
    },
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
      saveSession(true)
    },
    enqueueLast: (t) => {
      const { queue, index } = get()
      set({ queue: [...queue, t] })
      if (index < 0) void loadIndex(0)
      saveSession(true)
    },
    removeAt: (i) => {
      const { queue, index } = get()
      if (i === index) return
      const q = queue.filter((_, k) => k !== i)
      set({ queue: q, index: i < index ? index - 1 : index })
      saveSession(true)
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
      saveSession(true)
    },
    clearUpcoming: () => {
      const { queue, index } = get()
      set({ queue: queue.slice(0, index + 1), unshuffled: null })
      saveSession(true)
    },
    jumpTo: (i) => void loadIndex(i),
  }
})

/** Strip transport jargon out of a thrown message before it reaches the mini player. */
function friendly(msg: string): string {
  if (!msg || /shaka|error \d|manifest|fetch|network/i.test(msg)) return "Couldn't play this song"
  return msg
}

function shuffleInPlace<T>(a: T[]) {
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[a[i], a[j]] = [a[j], a[i]]
  }
}
