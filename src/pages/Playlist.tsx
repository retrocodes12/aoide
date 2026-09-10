import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { cover, getPlaylist, playlistImage } from '../lib/api/catalog'
import { useDocumentTitle, useResource } from '../lib/hooks'
import { fmtLength, plural } from '../lib/format'
import { useLibrary } from '../store/library'
import { usePlayer } from '../store/player'
import { useUI } from '../store/ui'
import { DetailHead } from '../components/DetailHead'
import { TrackRow } from '../components/TrackRow'
import { Empty, ErrorState, SkeletonRows } from '../components/Common'
import { ICheck, IPencil, IPlus, ITrash } from '../components/Icons'

export function PlaylistPage() {
  const { id = '' } = useParams()
  const res = useResource((signal) => getPlaylist(id, { signal }), [id])
  const playTracks = usePlayer((s) => s.playTracks)
  const toggle = usePlayer((s) => s.toggle)
  const shuffle = usePlayer((s) => s.shuffle)
  const toggleShuffle = usePlayer((s) => s.toggleShuffle)
  const context = usePlayer((s) => s.context)
  const status = usePlayer((s) => s.status)
  const index = usePlayer((s) => s.index)
  const following = useLibrary((s) => Boolean(s.followedPlaylists[id]))
  const togglePlaylist = useLibrary((s) => s.togglePlaylist)
  const toast = useUI((s) => s.toast)
  const setTintFromImage = useUI((s) => s.setTintFromImage)
  const p = res.data?.playlist
  useDocumentTitle(p?.title ?? 'Playlist')
  useEffect(() => { if (p) setTintFromImage(playlistImage(p, 160) ?? '', res.data?.tracks[0]?.album?.vibrantColor ?? '#5a5a5a') }, [p, res.data, setTintFromImage])
  if (res.error) return <div className="page"><div className="topbar"><Link to="/" className="iconbtn" aria-label="Home">‹</Link></div><ErrorState error={res.error} retry={res.reload} /></div>
  const tracks = res.data?.tracks ?? []
  const total = tracks.reduce((a, t) => a + t.duration, 0)
  const ctx = { kind: 'playlist', title: p?.title ?? '', href: `/playlist/${id}` }
  const thisPlaying = context?.href === ctx.href && (status === 'playing' || status === 'buffering' || status === 'loading')
  return (
    <div className="page" data-testid="playlist_screen">
      <DetailHead
        image={p ? playlistImage(p, 640) : undefined}
        title={p?.title ?? ''}
        description={p?.description?.replace(/\s*\(Cover:.*$/, '')}
        meta={p ? [p.creator?.name ?? (p.type === 'EDITORIAL' ? 'Editorial' : 'Playlist'), plural(tracks.length || p.numberOfTracks || 0, 'song'), total ? fmtLength(total) : ''].filter(Boolean).join(' · ') : ' '}
        actions={p && <button className={`iconbtn${following ? ' is-on' : ' iconbtn--sub'}`} aria-pressed={following} aria-label={following ? 'Remove from Your Library' : 'Add to Your Library'} data-testid="save_playlist" onClick={() => toast(togglePlaylist(p) ? 'Added to Your Library' : 'Removed from Your Library')}>{following ? <ICheck /> : <IPlus />}</button>}
        playing={thisPlaying}
        canPlay={tracks.length > 0}
        onPlay={() => (context?.href === ctx.href && index >= 0 ? toggle() : playTracks(tracks, 0, ctx))}
        onShuffle={() => { if (!shuffle) toggleShuffle(); playTracks(tracks, Math.floor(Math.random() * tracks.length), ctx) }}
      />
      <div className="tracks">{res.loading ? <SkeletonRows n={12} /> : tracks.map((t, i) => <TrackRow key={`${t.id}-${i}`} track={t} onPlay={() => playTracks(tracks, i, ctx)} />)}</div>
    </div>
  )
}

export function LocalPlaylistPage() {
  const { id = '' } = useParams()
  const nav = useNavigate()
  const pl = useLibrary((s) => s.playlists.find((p) => p.id === id))
  const { removeFromPlaylist, deletePlaylist, renamePlaylist } = useLibrary.getState()
  const playTracks = usePlayer((s) => s.playTracks)
  const toggle = usePlayer((s) => s.toggle)
  const context = usePlayer((s) => s.context)
  const status = usePlayer((s) => s.status)
  const index = usePlayer((s) => s.index)
  const toast = useUI((s) => s.toast)
  const setTintFrom = useUI((s) => s.setTintFrom)
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState('')
  useDocumentTitle(pl?.title ?? 'Playlist')
  useEffect(() => setTintFrom(pl?.tracks[0]?.album?.vibrantColor ?? '#5a5a5a'), [pl, setTintFrom])
  if (!pl) return <div className="page"><div className="topbar"><Link to="/library" className="iconbtn" aria-label="Library">‹</Link></div><Empty title="That playlist is gone" /></div>
  const total = pl.tracks.reduce((a, t) => a + t.duration, 0)
  const ctx = { kind: 'playlist', title: pl.title, href: `/local/${pl.id}` }
  const thisPlaying = context?.href === ctx.href && (status === 'playing' || status === 'buffering' || status === 'loading')
  return (
    <div className="page" data-testid="local_playlist_screen">
      <DetailHead
        image={cover(pl.tracks[0]?.album?.cover, 640)}
        title={pl.title}
        meta={`You · ${plural(pl.tracks.length, 'song')}${total ? ` · ${fmtLength(total)}` : ''}`}
        actions={<>
          <button className="iconbtn iconbtn--sub" aria-label="Rename playlist" data-testid="rename_playlist" onClick={() => { setName(pl.title); setRenaming(true) }}><IPencil /></button>
          <button className="iconbtn iconbtn--sub" aria-label="Delete playlist" data-testid="delete_playlist" onClick={() => { if (window.confirm(`Delete “${pl.title}”?`)) { deletePlaylist(pl.id); toast('Playlist deleted'); nav('/library') } }}><ITrash /></button>
        </>}
        playing={thisPlaying}
        canPlay={pl.tracks.length > 0}
        onPlay={() => (context?.href === ctx.href && index >= 0 ? toggle() : playTracks(pl.tracks, 0, ctx))}
        onShuffle={() => playTracks(pl.tracks, Math.floor(Math.random() * pl.tracks.length), ctx)}
      />
      {renaming && (
        <div className="sheet__row" style={{ padding: '0 16px 8px' }}>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} aria-label="Playlist name" data-testid="rename_input" />
          <button className="pill pill--filled" style={{ height: 44 }} onClick={() => { renamePlaylist(pl.id, name); setRenaming(false); toast('Renamed') }}>Save</button>
        </div>
      )}
      <div className="tracks">
        {pl.tracks.length ? pl.tracks.map((t, i) => <TrackRow key={`${t.id}-${i}`} track={t} onPlay={() => playTracks(pl.tracks, i, ctx)} onRemove={() => { removeFromPlaylist(pl.id, i); toast(`Removed from ${pl.title}`) }} />) : <Empty title="Let's find something for your playlist" body="Use ··· on any song to add it here." />}
      </div>
    </div>
  )
}

export function LikedPage() {
  const likedOrder = useLibrary((s) => s.likedOrder)
  const likedMap = useLibrary((s) => s.liked)
  const liked = useMemo(() => likedOrder.map((id) => likedMap[id]).filter(Boolean), [likedOrder, likedMap])
  const playTracks = usePlayer((s) => s.playTracks)
  const toggle = usePlayer((s) => s.toggle)
  const context = usePlayer((s) => s.context)
  const status = usePlayer((s) => s.status)
  const index = usePlayer((s) => s.index)
  const setTintFrom = useUI((s) => s.setTintFrom)
  useDocumentTitle('Liked Songs')
  useEffect(() => setTintFrom('#ff7a1f'), [setTintFrom])
  const ctx = { kind: 'playlist', title: 'Liked Songs', href: '/liked' }
  const thisPlaying = context?.href === ctx.href && (status === 'playing' || status === 'buffering' || status === 'loading')
  return (
    <div className="page" data-testid="liked_screen">
      <DetailHead liked title="Liked Songs" meta={`You · ${plural(liked.length, 'song')}`} playing={thisPlaying} canPlay={liked.length > 0}
        onPlay={() => (context?.href === ctx.href && index >= 0 ? toggle() : playTracks(liked, 0, ctx))}
        onShuffle={() => playTracks(liked, Math.floor(Math.random() * liked.length), ctx)} />
      <div className="tracks">
        {liked.length ? liked.map((t, i) => <TrackRow key={t.id} track={t} onPlay={() => playTracks(liked, i, ctx)} />) : <Empty title="Songs you like will appear here" body="Save songs by tapping + on a song." />}
      </div>
    </div>
  )
}
