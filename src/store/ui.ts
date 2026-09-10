import { create } from 'zustand'
import { accentFrom, tintFromImage } from '../lib/color'
import type { Track } from '../lib/api/types'

interface Toast {
  id: number
  text: string
}

interface UIState {
  nowPlayingOpen: boolean
  lyricsOpen: boolean
  queueOpen: boolean
  menu: { track: Track; onRemove?: () => void } | null
  confirm: { title: string; body?: string; action: string; onConfirm: () => void } | null
  toasts: Toast[]
  tint: string
  previewNoted: boolean
  openNowPlaying: () => void
  closeNowPlaying: () => void
  openLyrics: () => void
  closeLyrics: () => void
  openQueue: () => void
  closeQueue: () => void
  openMenu: (track: Track, onRemove?: () => void) => void
  closeMenu: () => void
  ask: (title: string, action: string, onConfirm: () => void, body?: string) => void
  closeConfirm: () => void
  closeOverlays: () => void
  toast: (text: string) => void
  dismiss: (id: number) => void
  setTintFrom: (hex?: string | null) => void
  setTintFromImage: (url: string, fallback?: string | null) => void
  notePreview: () => void
}

let seq = 0
export const useUI = create<UIState>((set, get) => ({
  nowPlayingOpen: false,
  lyricsOpen: false,
  queueOpen: false,
  menu: null,
  confirm: null,
  toasts: [],
  tint: '#4a4a4a',
  previewNoted: localStorage.getItem('aoide:previewNoted') === '1',
  openNowPlaying: () => set({ nowPlayingOpen: true }),
  closeNowPlaying: () => set({ nowPlayingOpen: false, lyricsOpen: false, queueOpen: false }),
  openLyrics: () => set({ lyricsOpen: true }),
  closeLyrics: () => set({ lyricsOpen: false }),
  openQueue: () => set({ queueOpen: true }),
  closeQueue: () => set({ queueOpen: false }),
  openMenu: (track, onRemove) => set({ menu: { track, onRemove } }),
  closeMenu: () => set({ menu: null }),
  ask: (title, action, onConfirm, body) => set({ confirm: { title, action, onConfirm, body } }),
  closeConfirm: () => set({ confirm: null }),
  closeOverlays: () => set({ nowPlayingOpen: false, lyricsOpen: false, queueOpen: false, menu: null, confirm: null }),
  toast: (text) => {
    const id = ++seq
    set({ toasts: [...get().toasts.slice(-1), { id, text }] })
    setTimeout(() => get().dismiss(id), 3200)
  },
  dismiss: (id) => set({ toasts: get().toasts.filter((t) => t.id !== id) }),
  setTintFrom: (hex) => {
    const { accent, ink, soft, faint } = accentFrom(hex, '#4a4a4a')
    const root = document.documentElement.style
    root.setProperty('--tint', accent)
    root.setProperty('--tint-ink', ink)
    root.setProperty('--tint-ink-soft', soft)
    root.setProperty('--tint-ink-faint', faint)
    set({ tint: accent })
  },
  setTintFromImage: (url, fallback) => {
    get().setTintFrom(fallback ?? null)
    if (!url) return
    void tintFromImage(url).then((hex) => {
      if (hex) get().setTintFrom(hex)
    })
  },
  notePreview: () => {
    if (get().previewNoted) return
    localStorage.setItem('aoide:previewNoted', '1')
    set({ previewNoted: true })
    get().toast('This mirror serves 30-second previews. Add a subscribed instance in Settings for full songs.')
  },
}))
