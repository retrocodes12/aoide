import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { artistPicture, cover, playlistImage } from '../lib/api/catalog'
import { useDocumentTitle } from '../lib/hooks'
import { useLibrary } from '../store/library'
import { useUI } from '../store/ui'
import { Empty } from '../components/Common'
import { IHeart, IPlus } from '../components/Icons'

type Filter = 'all' | 'playlists' | 'albums' | 'artists'

export function Library() {
  useDocumentTitle('Your Library')
  const lib = useLibrary()
  const toast = useUI((s) => s.toast)
  const nav = useNavigate()
  const [filter, setFilter] = useState<Filter>('all')
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const rows: Array<{ key: string; to: string; img?: string; title: string; sub: string; round?: boolean; liked?: boolean }> = []
  if (filter === 'all' || filter === 'playlists') {
    rows.push({ key: 'liked', to: '/liked', title: 'Liked Songs', sub: `Playlist · ${lib.likedOrder.length} songs`, liked: true })
    for (const p of lib.playlists) rows.push({ key: p.id, to: `/local/${p.id}`, img: cover(p.tracks[0]?.album?.cover, 160), title: p.title, sub: `Playlist · ${p.tracks.length} songs` })
    for (const p of Object.values(lib.followedPlaylists)) rows.push({ key: p.uuid, to: `/playlist/${p.uuid}`, img: playlistImage(p, 160), title: p.title, sub: `Playlist${p.numberOfTracks ? ` · ${p.numberOfTracks} songs` : ''}` })
  }
  if (filter === 'all' || filter === 'albums') for (const id of lib.albumOrder) { const a = lib.albums[id]; if (a) rows.push({ key: `a${a.id}`, to: `/album/${a.id}`, img: cover(a.cover, 160), title: a.title, sub: `Album · ${a.artist?.name ?? ''}` }) }
  if (filter === 'all' || filter === 'artists') for (const a of Object.values(lib.artists)) rows.push({ key: `r${a.id}`, to: `/artist/${a.id}`, img: artistPicture(a.picture, 160), title: a.name, sub: 'Artist', round: true })
  return (
    <div className="page" data-testid="library">
      <div className="page__head">
        <h1 className="large-title">Your Library</h1>
        <button className="iconbtn" aria-label="Create playlist" data-testid="create_playlist" onClick={() => { setName(`My Playlist #${lib.playlists.length + 1}`); setCreating(true) }}><IPlus size={28} /></button>
      </div>
      <div className="chips" role="tablist">{(['playlists', 'albums', 'artists'] as const).map((f) => <button key={f} role="tab" aria-selected={filter === f} className={`chip${filter === f ? ' is-on' : ''}`} onClick={() => setFilter(filter === f ? 'all' : f)}>{f[0].toUpperCase() + f.slice(1)}</button>)}</div>
      {creating && (
        <div className="sheet__row" style={{ padding: '8px 16px' }}>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} aria-label="Playlist name" data-testid="playlist_name" autoFocus />
          <button className="pill pill--filled" style={{ height: 44 }} data-testid="playlist_create" onClick={() => { const p = lib.createPlaylist(name); setCreating(false); toast(`Created ${p.title}`); nav(`/local/${p.id}`) }}>Create</button>
        </div>
      )}
      <div className="lib__rows">
        {rows.map((r) => (
          <Link key={r.key} to={r.to} className="row" data-testid="library_row">
            {r.liked ? <span className="row__art row__art--lg quick__tile"><IHeart filled /></span> : <img className={`row__art row__art--lg${r.round ? ' row__art--round' : ''}`} src={r.img} alt="" loading="lazy" width={64} height={64} />}
            <span className="row__text"><span className="row__title">{r.title}</span><span className="row__sub"><span>{r.sub}</span></span></span>
          </Link>
        ))}
      </div>
      {rows.length <= 1 && filter === 'all' && <Empty title="Start your library" body="Save albums and follow artists, or make a playlist with +." />}
      {rows.length === 0 && <Empty title="Nothing here yet" />}
    </div>
  )
}
