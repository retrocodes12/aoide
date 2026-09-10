import { useEffect } from 'react'
import { Link, useParams } from 'react-router-dom'
import { cover, getAlbum, getSimilarAlbums, primaryArtist } from '../lib/api/catalog'
import { useDocumentTitle, useResource } from '../lib/hooks'
import { fmtLength, plural, year } from '../lib/format'
import { useLibrary } from '../store/library'
import { usePlayer } from '../store/player'
import { useUI } from '../store/ui'
import { DetailHead } from '../components/DetailHead'
import { TrackRow } from '../components/TrackRow'
import { Card, Carousel, ErrorState, Section, SkeletonRows } from '../components/Common'
import { ICheck, IPlus } from '../components/Icons'

export function AlbumPage() {
  const { id = '' } = useParams()
  const res = useResource((signal) => getAlbum(id, { signal }), [id])
  const similar = useResource((signal) => getSimilarAlbums(id, { signal }), [id])
  const playTracks = usePlayer((s) => s.playTracks)
  const toggle = usePlayer((s) => s.toggle)
  const toggleShuffle = usePlayer((s) => s.toggleShuffle)
  const shuffle = usePlayer((s) => s.shuffle)
  const context = usePlayer((s) => s.context)
  const status = usePlayer((s) => s.status)
  const index = usePlayer((s) => s.index)
  const recordAlbum = useLibrary((s) => s.recordAlbum)
  const saved = useLibrary((s) => Boolean(s.albums[Number(id)]))
  const toggleAlbum = useLibrary((s) => s.toggleAlbum)
  const toast = useUI((s) => s.toast)
  const setTintFrom = useUI((s) => s.setTintFrom)
  const album = res.data?.album
  useDocumentTitle(album?.title ?? 'Album')
  useEffect(() => { if (album) { recordAlbum(album); setTintFrom(album.vibrantColor) } }, [album, recordAlbum, setTintFrom])
  if (res.error) return <div className="page"><div className="topbar"><Link to="/" className="iconbtn" aria-label="Home">‹</Link></div><ErrorState error={res.error} retry={res.reload} what="album" /></div>
  const tracks = res.data?.tracks ?? []
  const artist = album ? primaryArtist(album) : undefined
  const total = tracks.reduce((a, t) => a + t.duration, 0)
  const ctx = { kind: 'album', title: album?.title ?? '', href: `/album/${id}` }
  const thisPlaying = context?.href === ctx.href && (status === 'playing' || status === 'buffering' || status === 'loading')
  // Apple Music's album view: the artist line only appears when a track's credits differ from the album's.
  const rowSub = (t: typeof tracks[number]) => {
    const names = (t.artists?.length ? t.artists : t.artist ? [t.artist] : []).map((a) => a.name)
    return names.length === 1 && names[0] === artist?.name ? '' : names.join(', ')
  }
  const badge = album?.mediaMetadata?.tags?.includes('HIRES_LOSSLESS') ? 'Hi-Res Lossless' : album?.audioQuality === 'LOSSLESS' ? 'Lossless' : undefined
  return (
    <div className="page" data-testid="album_screen">
      <DetailHead
        image={cover(album?.cover, 640)}
        title={album?.title ?? ''}
        artist={artist && <Link to={`/artist/${artist.id}`}>{artist.name}</Link>}
        meta={album ? [album.type && album.type !== 'ALBUM' ? album.type.toLowerCase().replace(/^./, (c) => c.toUpperCase()) : 'Album', year(album.releaseDate), plural(tracks.length || album.numberOfTracks || 0, 'song'), total ? fmtLength(total) : ''].filter(Boolean).join(' · ') : 'Album'}
        badge={badge}
        actions={album && <button className={`iconbtn${saved ? ' is-on' : ' iconbtn--sub'}`} aria-pressed={saved} aria-label={saved ? 'Remove from Your Library' : 'Save to Your Library'} data-testid="save_album" onClick={() => toast(toggleAlbum(album) ? 'Added to Your Library' : 'Removed from Your Library')}>{saved ? <ICheck /> : <IPlus />}</button>}
        playing={thisPlaying}
        canPlay={tracks.length > 0}
        onPlay={() => (context?.href === ctx.href && index >= 0 ? toggle() : playTracks(tracks, 0, ctx))}
        onShuffle={() => { if (!shuffle) toggleShuffle(); playTracks(tracks, Math.floor(Math.random() * tracks.length), ctx) }}
      />
      <div className="tracks">
        {res.loading ? <SkeletonRows n={10} art={false} /> : tracks.map((t, i) => <TrackRow key={t.id} track={t} number={i + 1} showArt={false} sub={rowSub(t)} onPlay={() => playTracks(tracks, i, ctx)} />)}
      </div>
      {album && (
        <div className="album__foot">
          {album.releaseDate && <p>{new Date(album.releaseDate).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' })}</p>}
          {album.copyright && <p>{/^[©(]/.test(album.copyright) ? album.copyright : `© ${album.copyright}`}</p>}
        </div>
      )}
      {similar.data && similar.data.length > 0 && (
        <Section title="You might also like"><Carousel>{similar.data.slice(0, 12).map((a) => <Card key={a.id} to={`/album/${a.id}`} image={cover(a.cover, 320)} title={a.title} sub={(a.artist ?? a.artists?.[0])?.name} />)}</Carousel></Section>
      )}
    </div>
  )
}
