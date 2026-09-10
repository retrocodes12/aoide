import { NavLink, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { artOf, Like } from './Common'
import { IAlbum, IHome, ILibrary, INext, IPause, IPerson, IPlay, IPlaylistAdd, IQueue, IRemove, ISearch, IHeart, IPlus } from './Icons'
import { trackArtists, qualityLabel } from '../lib/api/catalog'
import { useLibrary } from '../store/library'
import { usePlayer } from '../store/player'
import { useUI } from '../store/ui'

export function TabBar() {
  return (
    <nav className="tabbar" aria-label="Primary" data-testid="tab_bar">
      <NavLink to="/" end className="tab" data-testid="tab_home"><IHome />Home</NavLink>
      <NavLink to="/search" className="tab" data-testid="tab_search"><ISearch />Search</NavLink>
      <NavLink to="/library" className="tab" data-testid="tab_library"><ILibrary />Your Library</NavLink>
    </nav>
  )
}

/** Floating capsule above the tab bar, tinted like the record. */
export function MiniPlayer() {
  const track = usePlayer((s) => s.queue[s.index] ?? null)
  const status = usePlayer((s) => s.status)
  const position = usePlayer((s) => s.position)
  const duration = usePlayer((s) => s.duration)
  const stream = usePlayer((s) => s.stream)
  const error = usePlayer((s) => s.error)
  const { toggle, next } = usePlayer.getState()
  const openNowPlaying = useUI((s) => s.openNowPlaying)
  if (!track) return null
  const playing = status === 'playing' || status === 'buffering' || status === 'loading'
  const failed = status === 'error'
  const dur = duration || 0
  const sub = failed ? error ?? "Couldn't play this song" : status === 'loading' ? 'Loading…' : stream?.isPreview ? `${trackArtists(track)} · Preview` : trackArtists(track)
  return (
    <div className="mini" data-testid="mini_player" role="button" tabIndex={0} onClick={openNowPlaying} onKeyDown={(e) => e.key === 'Enter' && openNowPlaying()}>
      <img className="mini__art" src={artOf(track, 160)} alt="" width={42} height={42} />
      <div className="mini__text">
        <div className="mini__title" data-testid="mini_title">{track.title}</div>
        <div className={`mini__sub${failed ? ' is-error' : stream?.isPreview ? ' is-preview' : ''}`} data-testid="mini_sub">{sub}</div>
      </div>
      <Like track={track} />
      <button className="iconbtn" aria-label={failed ? 'Retry' : playing ? 'Pause' : 'Play'} data-testid="mini_toggle" onClick={(e) => { e.stopPropagation(); toggle() }}>{playing ? <IPause size={26} /> : <IPlay size={26} />}</button>
      <button className="iconbtn" aria-label="Next" data-testid="mini_next" onClick={(e) => { e.stopPropagation(); next() }}><INext size={24} /></button>
      <div className="mini__bar" aria-hidden><i style={{ width: `${dur ? Math.min(100, (position / dur) * 100) : 0}%` }} /></div>
    </div>
  )
}

export function Toasts() {
  const toasts = useUI((s) => s.toasts)
  const npOpen = useUI((s) => s.nowPlayingOpen)
  return (
    <div className={`toasts${npOpen ? ' is-np' : ''}`} aria-live="polite">
      {toasts.slice(-1).map((t) => <div key={t.id} className="toast" data-testid="toast">{t.text}</div>)}
    </div>
  )
}

/** The ··· sheet for a song. */
export function TrackMenu() {
  const menu = useUI((s) => s.menu)
  const closeMenu = useUI((s) => s.closeMenu)
  const closeOverlays = useUI((s) => s.closeOverlays)
  const toast = useUI((s) => s.toast)
  const lib = useLibrary()
  const nav = useNavigate()
  const [pick, setPick] = useState(false)
  const [name, setName] = useState('')
  useEffect(() => { if (!menu) { setPick(false); setName('') } }, [menu])
  const t = menu?.track
  const liked = t ? Boolean(lib.liked[t.id]) : false
  const go = (r: string) => { closeOverlays(); nav(r) }
  return (
    <>
      <div className={`sheet-scrim${menu ? ' is-open' : ''}`} onClick={closeMenu} />
      <div className={`sheet${menu ? ' is-open' : ''}`} data-testid="track_menu" aria-hidden={!menu} inert={!menu} role="dialog">
        <div className="sheet__grab" />
        {t && (
          <>
            <div className="sheet__head">
              <img src={artOf(t, 160)} alt="" />
              <div style={{ minWidth: 0 }}>
                <div className="sheet__title">{t.title}</div>
                <div className="sheet__sub">{trackArtists(t)}</div>
              </div>
            </div>
            {!pick ? (
              <>
                <button className={`sheet__item${liked ? ' is-on' : ''}`} data-testid="menu_like" onClick={() => { toast(lib.toggleLike(t) ? 'Added to Liked Songs' : 'Removed from Liked Songs'); closeMenu() }}>{liked ? <IHeart filled /> : <IPlus />}{liked ? 'Remove from Liked Songs' : 'Add to Liked Songs'}</button>
                <button className="sheet__item" data-testid="menu_playlist" onClick={() => setPick(true)}><IPlaylistAdd />Add to playlist</button>
                <button className="sheet__item" data-testid="menu_queue" onClick={() => { usePlayer.getState().enqueueLast(t); toast('Added to queue'); closeMenu() }}><IQueue />Add to queue</button>
                <button className="sheet__item" data-testid="menu_next" onClick={() => { usePlayer.getState().enqueueNext(t); toast('Playing next'); closeMenu() }}><INext />Play next</button>
                {t.album && <button className="sheet__item" onClick={() => go(`/album/${t.album!.id}`)}><IAlbum />Go to album</button>}
                {(t.artist ?? t.artists?.[0]) && <button className="sheet__item" onClick={() => go(`/artist/${(t.artist ?? t.artists![0]).id}`)}><IPerson />Go to artist</button>}
                {menu?.onRemove && <button className="sheet__item" onClick={() => { menu.onRemove?.(); closeMenu() }}><IRemove />Remove from this playlist</button>}
              </>
            ) : (
              <>
                <div className="sheet__label">Add to playlist</div>
                {lib.playlists.map((p) => (
                  <button key={p.id} className="sheet__item" onClick={() => { lib.addToPlaylist(p.id, t); toast(`Added to ${p.title}`); closeMenu() }}><IPlaylistAdd />{p.title}<small style={{ marginLeft: 'auto' }}>{p.tracks.length} songs</small></button>
                ))}
                <div className="sheet__row">
                  <input className="input" placeholder="New playlist name" value={name} onChange={(e) => setName(e.target.value)} data-testid="new_playlist_name" />
                  <button className="pill pill--filled" style={{ height: 44, padding: '0 16px' }} data-testid="new_playlist_create" onClick={() => { const p = lib.createPlaylist(name.trim() || `My Playlist #${lib.playlists.length + 1}`, [t]); toast(`Added to ${p.title}`); closeMenu() }}>Create</button>
                </div>
              </>
            )}
          </>
        )}
      </div>
    </>
  )
}

export function streamBadge(track: { id: number } | null, stream: { isPreview: boolean; quality: string; bitDepth?: number | null; sampleRate?: number | null } | null): string {
  if (!track || !stream) return ''
  if (stream.isPreview) return 'Preview'
  const l = qualityLabel(null, { assetPresentation: 'FULL', audioQuality: stream.quality, bitDepth: stream.bitDepth, sampleRate: stream.sampleRate, manifest: '', manifestMimeType: '', trackId: track.id })
  if (l.startsWith('FLAC 24')) return 'Hi-Res Lossless'
  if (l.startsWith('FLAC')) return 'Lossless'
  return l
}

/** Bottom-sheet confirmation, so destructive actions never fall back to the browser's dialog. */
export function ConfirmSheet() {
  const confirm = useUI((s) => s.confirm)
  const closeConfirm = useUI((s) => s.closeConfirm)
  return (
    <>
      <div className={`sheet-scrim${confirm ? ' is-open' : ''}`} onClick={closeConfirm} />
      <div className={`sheet sheet--confirm${confirm ? ' is-open' : ''}`} role="alertdialog" aria-hidden={!confirm} inert={!confirm} data-testid="confirm_sheet">
        <div className="sheet__grab" />
        {confirm && (
          <div className="confirm">
            <div className="confirm__title">{confirm.title}</div>
            {confirm.body && <p className="confirm__body">{confirm.body}</p>}
            <div className="confirm__row">
              <button className="pill pill--soft" onClick={closeConfirm} data-testid="confirm_cancel">Cancel</button>
              <button className="pill pill--filled" onClick={() => { confirm.onConfirm(); closeConfirm() }} data-testid="confirm_ok">{confirm.action}</button>
            </div>
          </div>
        )}
      </div>
    </>
  )
}
