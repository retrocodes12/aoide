import { useEffect } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ConfirmSheet, MiniPlayer, TabBar, Toasts, TrackMenu } from './components/Shell'
import { LyricsScreen, NowPlaying, QueueScreen } from './components/NowPlaying'
import { probeMirrors, refreshFromUptime } from './lib/api/instances'
import { usePlayer } from './store/player'
import { useUI } from './store/ui'

export function App() {
  const location = useLocation()
  const navigate = useNavigate()
  const hasTrack = usePlayer((s) => s.index >= 0)
  const stream = usePlayer((s) => s.stream)
  const notePreview = useUI((s) => s.notePreview)

  useEffect(() => { const c = new AbortController(); void probeMirrors(); void refreshFromUptime(c.signal); return () => c.abort() }, [])
  useEffect(() => { if (stream?.isPreview) notePreview() }, [stream, notePreview])

  // GitHub Pages deep-link restore (see public/404.html)
  useEffect(() => {
    const r = sessionStorage.getItem('aoide:redirect')
    if (r) {
      sessionStorage.removeItem('aoide:redirect')
      const base = import.meta.env.BASE_URL.replace(/\/$/, '')
      navigate(r.startsWith(base) ? r.slice(base.length) || '/' : r, { replace: true })
    }
  }, [navigate])

  useEffect(() => {
    document.querySelector('.view')?.scrollTo({ top: 0 })
    useUI.getState().closeMenu()
  }, [location.pathname])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) return
      const p = usePlayer.getState()
      const ui = useUI.getState()
      if (e.key === ' ') { e.preventDefault(); p.toggle() }
      else if (e.key === 'Escape') ui.closeOverlays()
      else if (e.key === 'n' || e.key === 'N') { if (p.index >= 0) ui.openNowPlaying() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <div className={`app${hasTrack ? ' has-track' : ''}`}>
      <main className="view" id="main"><Outlet /></main>
      <div className="bottom">
        <MiniPlayer />
        <TabBar />
      </div>
      <NowPlaying />
      <LyricsScreen />
      <QueueScreen />
      <TrackMenu />
      <ConfirmSheet />
      <Toasts />
    </div>
  )
}
