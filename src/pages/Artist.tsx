import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { artistPicture, cover, getArtist, getArtistBio, getDiscography, getSimilarArtists, getTopTracks } from '../lib/api/catalog'
import { useDocumentTitle, useResource } from '../lib/hooks'
import { useLibrary } from '../store/library'
import { usePlayer } from '../store/player'
import { useUI } from '../store/ui'
import { TrackRow } from '../components/TrackRow'
import { Card, Carousel, ErrorState, Section, SkeletonCards, SkeletonRows } from '../components/Common'
import { IChevronLeft, IPause, IPlay, IShuffle } from '../components/Icons'

export function ArtistPage() {
  const { id = '' } = useParams()
  const nav = useNavigate()
  const artist = useResource((signal) => getArtist(id, { signal }), [id])
  const disco = useResource((signal) => getDiscography(id, { signal }), [id])
  const top = useResource((signal) => (artist.data ? getTopTracks(id, artist.data.name, { signal }) : Promise.resolve([])), [id, artist.data?.name])
  const similar = useResource((signal) => getSimilarArtists(id, { signal }), [id])
  const bio = useResource((signal) => getArtistBio(id, { signal }), [id])
  const playTracks = usePlayer((s) => s.playTracks)
  const toggle = usePlayer((s) => s.toggle)
  const shuffle = usePlayer((s) => s.shuffle)
  const toggleShuffle = usePlayer((s) => s.toggleShuffle)
  const context = usePlayer((s) => s.context)
  const status = usePlayer((s) => s.status)
  const index = usePlayer((s) => s.index)
  const following = useLibrary((s) => Boolean(s.artists[Number(id)]))
  const toggleArtist = useLibrary((s) => s.toggleArtist)
  const toast = useUI((s) => s.toast)
  const setTintFrom = useUI((s) => s.setTintFrom)
  const [filter, setFilter] = useState<'ALL' | 'ALBUM' | 'EP' | 'SINGLE'>('ALL')
  const [moreTop, setMoreTop] = useState(false)
  const [moreBio, setMoreBio] = useState(false)
  const a = artist.data
  useDocumentTitle(a?.name ?? 'Artist')
  // Artist pictures are served without CORS, so the page borrows the vibrant colour of the top song's record.
  useEffect(() => setTintFrom(top.data?.[0]?.album?.vibrantColor ?? '#4a4a4a'), [top.data, setTintFrom])
  const albums = useMemo(() => [...(disco.data?.albums ?? []).filter((al) => filter === 'ALL' || (al.type ?? 'ALBUM') === filter)].sort((x, y) => (y.releaseDate ?? '').localeCompare(x.releaseDate ?? '')), [disco.data, filter])
  const counts = { ALBUM: 0, EP: 0, SINGLE: 0 } as Record<string, number>
  for (const al of disco.data?.albums ?? []) counts[al.type ?? 'ALBUM'] = (counts[al.type ?? 'ALBUM'] ?? 0) + 1
  if (artist.error) return <div className="page"><div className="topbar"><Link to="/" className="iconbtn" aria-label="Home"><IChevronLeft /></Link></div><ErrorState error={artist.error} retry={artist.reload} what="artist" /></div>
  const tracks = top.data ?? []
  const ctx = { kind: 'artist', title: a?.name ?? '', href: `/artist/${id}` }
  const thisPlaying = context?.href === ctx.href && (status === 'playing' || status === 'buffering' || status === 'loading')
  const countsLabel = [counts.ALBUM && `${counts.ALBUM} albums`, counts.EP && `${counts.EP} EPs`, counts.SINGLE && `${counts.SINGLE} singles`].filter(Boolean).join(' · ')
  return (
    <div className="page" data-testid="artist_screen">
      <div className={`banner${a && !a.picture ? ' banner--blank' : ''}`}>
        {a?.picture ? <img src={artistPicture(a.picture, 750)} alt="" fetchPriority="high" /> : <div className="banner__blank" aria-hidden><span>{(a?.name ?? '?').slice(0, 1).toUpperCase()}</span></div>}
        <div className="banner__scrim" />
        <div className="topbar"><button className="iconbtn" aria-label="Back" data-testid="back" onClick={() => (window.history.length > 1 ? nav(-1) : nav('/'))}><IChevronLeft /></button></div>
        <h1 className="banner__name" data-testid="detail_title">{a?.name ?? ''}</h1>
      </div>
      <div className="artist__actions">
        {a && <button className={`pill pill--outline${following ? ' is-on' : ''}`} aria-pressed={following} data-testid="follow" onClick={() => toast(toggleArtist(a) ? `Following ${a.name}` : `Unfollowed ${a.name}`)}>{following ? 'Following' : 'Follow'}</button>}
        {countsLabel && <span style={{ fontSize: 12, color: 'var(--subdued)', marginLeft: 4 }}>{countsLabel}</span>}
        <span className="spacer" />
        <button className="iconbtn iconbtn--sub" aria-label="Shuffle play" data-testid="shuffle" disabled={!tracks.length} onClick={() => { if (!shuffle) toggleShuffle(); playTracks(tracks, Math.floor(Math.random() * tracks.length), ctx) }}><IShuffle size={26} /></button>
        <button className="pill pill--filled" style={{ width: 56, height: 56, padding: 0, borderRadius: '50%' }} aria-label={thisPlaying ? 'Pause' : 'Play popular songs'} data-testid="play_fab" disabled={!tracks.length} onClick={() => (context?.href === ctx.href && index >= 0 ? toggle() : playTracks(tracks, 0, ctx))}>{thisPlaying ? <IPause size={26} /> : <IPlay size={26} />}</button>
      </div>
      <Section title="Popular">
        {top.loading || artist.loading ? <SkeletonRows n={5} /> : tracks.slice(0, moreTop ? 10 : 5).map((t, i) => <TrackRow key={t.id} track={t} number={i + 1} sub={t.album?.title} onPlay={() => playTracks(tracks, i, ctx)} />)}
        {tracks.length > 5 && <button className="linkbtn" onClick={() => setMoreTop((v) => !v)}>{moreTop ? 'Show less' : 'See more'}</button>}
      </Section>
      {(disco.loading || disco.error || (disco.data?.albums.length ?? 0) > 0) && (
      <Section title="Discography">
        {disco.error ? <ErrorState error={disco.error} retry={disco.reload} /> : (
          <>
        <div className="chips" role="tablist">{(['ALL', 'ALBUM', 'EP', 'SINGLE'] as const).filter((f) => f === 'ALL' || disco.loading || (counts[f] ?? 0) > 0).map((f) => <button key={f} role="tab" aria-selected={filter === f} className={`chip${filter === f ? ' is-on' : ''}`} onClick={() => setFilter(f)}>{f === 'ALL' ? 'All releases' : f === 'ALBUM' ? 'Albums' : f === 'EP' ? 'EPs' : 'Singles'}</button>)}</div>
        {disco.loading ? <SkeletonCards /> : <Carousel>{albums.map((al) => <Card key={al.id} to={`/album/${al.id}`} image={cover(al.cover, 320)} title={al.title} sub={[al.releaseDate?.slice(0, 4), al.type && al.type !== 'ALBUM' ? al.type.toLowerCase().replace(/^./, (c) => c.toUpperCase()) : 'Album'].filter(Boolean).join(' · ')} />)}</Carousel>}
          </>
        )}
      </Section>
      )}
      {similar.data && similar.data.length > 0 && (
        <Section title="Fans also like"><Carousel>{similar.data.slice(0, 12).map((s) => <Card key={s.id} to={`/artist/${s.id}`} image={artistPicture(s.picture, 320)} title={s.name} sub="Artist" round />)}</Carousel></Section>
      )}
      {bio.data && (
        <Section title="About">
          <button className={`about${moreBio ? ' is-open' : ''}`} style={{ display: 'block', width: 'calc(100% - 32px)', textAlign: 'left' }} onClick={() => setMoreBio((v) => !v)}>
            {a?.picture && <img src={artistPicture(a.picture, 480)} alt="" loading="lazy" />}
            <div className="about__text"><p>{bio.data}</p></div>
          </button>
        </Section>
      )}
    </div>
  )
}
