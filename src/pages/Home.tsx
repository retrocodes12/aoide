import { Link } from 'react-router-dom'
import { cover, getPlaylist, getRecommendations, playlistImage, searchPlaylists } from '../lib/api/catalog'
import type { Album, Track } from '../lib/api/types'
import { useDocumentTitle, useResource } from '../lib/hooks'
import { useLibrary } from '../store/library'
import { usePlayer } from '../store/player'
import { Card, Carousel, ErrorState, Hero, Section, SkeletonCards } from '../components/Common'
import { IHeart, ISettings } from '../components/Icons'

export const NEW_ARRIVALS = '1b418bb8-90a7-4f87-901d-707993838346'

export function albumsFromTracks(tracks: Track[]): Album[] {
  const seen = new Map<number, Album>()
  for (const t of tracks) {
    const a = t.album
    if (!a || seen.has(a.id)) continue
    seen.set(a.id, { ...a, artist: t.artist ?? t.artists?.[0], releaseDate: undefined })
  }
  return [...seen.values()]
}

export function Home() {
  useDocumentTitle('')
  const recentTracks = useLibrary((s) => s.recentTracks)
  const recentAlbums = useLibrary((s) => s.recentAlbums)
  const likedOrder = useLibrary((s) => s.likedOrder)
  const playlists = useLibrary((s) => s.playlists)
  const followedMap = useLibrary((s) => s.followedPlaylists)
  const playTracks = usePlayer((s) => s.playTracks)
  const seed = recentTracks[0]
  const arrivals = useResource((signal) => getPlaylist(NEW_ARRIVALS, { signal }), [])
  const because = useResource((signal) => (seed ? getRecommendations(seed.id, { signal }) : Promise.resolve([] as Track[])), [seed?.id])
  const hour = new Date().getHours()
  const greeting = hour < 5 ? 'Good night' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
  const followed = Object.values(followedMap)
  const quick: Array<{ key: string; to: string; title: string; image?: string; liked?: boolean }> = [{ key: 'liked', to: '/liked', title: 'Liked Songs', liked: true }]
  for (const p of playlists.slice(0, 2)) quick.push({ key: p.id, to: `/local/${p.id}`, title: p.title, image: cover(p.tracks[0]?.album?.cover, 160) })
  for (const p of followed.slice(0, 2)) quick.push({ key: p.uuid, to: `/playlist/${p.uuid}`, title: p.title, image: playlistImage(p, 160) })
  for (const a of recentAlbums) {
    if (quick.length >= 8) break
    if (quick.some((q) => q.to === `/album/${a.id}`)) continue
    quick.push({ key: `a${a.id}`, to: `/album/${a.id}`, title: a.title, image: cover(a.cover, 160) })
  }
  const arrivalAlbums = arrivals.data ? albumsFromTracks(arrivals.data.tracks) : []
  return (
    <div className="page" data-testid="home">
      <div className="page__head">
        <h1 className="large-title">{greeting}</h1>
        <Link to="/settings" className="iconbtn" aria-label="Settings" data-testid="settings_button"><ISettings /></Link>
      </div>
      <div className="quick">
        {quick.map((q) => (
          <Link key={q.key} to={q.to} className="quick__item" data-testid={`quick_${q.key}`}>
            {q.liked ? <span className="quick__tile"><IHeart filled /></span> : <img src={q.image} alt="" width={56} height={56} />}
            <span className="quick__title">{q.title}</span>
          </Link>
        ))}
      </div>
      {quick.length < 3 && <p className="quick__hint">Albums and playlists you open land here.{likedOrder.length ? '' : ' Tap + on any song to like it.'}</p>}

      <Section title="New releases" to={`/playlist/${NEW_ARRIVALS}`}>
        {arrivals.error ? <ErrorState error={arrivals.error} retry={arrivals.reload} /> : arrivals.loading ? <SkeletonCards /> : (
          <>
            {arrivalAlbums[0] && <Hero to={`/album/${arrivalAlbums[0].id}`} image={cover(arrivalAlbums[0].cover, 640)} eyebrow="Just added" title={arrivalAlbums[0].title} sub={arrivalAlbums[0].artist?.name} />}
            <Carousel>{arrivalAlbums.slice(1, 13).map((a, i) => <Card key={a.id} to={`/album/${a.id}`} image={cover(a.cover, 320)} title={a.title} sub={a.artist?.name} eager={i < 3} />)}</Carousel>
          </>
        )}
      </Section>

      {seed && because.data && because.data.length > 0 && (
        <Section title={`More like ${seed.title}`}>
          <Carousel>{albumsFromTracks(because.data).slice(0, 12).map((a) => <Card key={a.id} to={`/album/${a.id}`} image={cover(a.cover, 320)} title={a.title} sub={a.artist?.name} />)}</Carousel>
        </Section>
      )}

      {recentTracks.length > 0 && (
        <Section title="Recently played" to="/library">
          <Carousel>{recentTracks.slice(0, 12).map((t) => <Card key={t.id} image={cover(t.album?.cover, 320)} title={t.title} sub={t.artist?.name ?? t.artists?.[0]?.name} tag="recent_card" onClick={() => playTracks(recentTracks, recentTracks.findIndex((x) => x.id === t.id), { kind: 'recent', title: 'Recently played' })} />)}</Carousel>
        </Section>
      )}

      {[['chill', 'Chill'], ['focus', 'Focus'], ['workout', 'Workout'], ['party', 'Party']].map(([term, title]) => <MoodRow key={term} term={term} title={title} />)}
      <p className="quick__hint" style={{ marginTop: 28 }}>Catalogue from Monochrome mirrors. Lyrics from lrclib. <Link to="/settings" style={{ color: 'var(--fg)', fontWeight: 700 }}>Settings</Link></p>
    </div>
  )
}

/** TIDAL's editorial search mixes markets; keep the rows in the app's language unless that leaves too few. */
function englishFirst<T extends { title: string }>(items: T[]): T[] {
  const ascii = items.filter((p) => /^[\x20-\x7E\u2018-\u201D\u2026]+$/.test(p.title))
  return ascii.length >= 6 ? ascii : items
}

function MoodRow({ term, title }: { term: string; title: string }) {
  const res = useResource((signal) => searchPlaylists(term, 20, { signal }), [term])
  if (res.error || (!res.loading && !res.data?.items.length)) return null
  return (
    <Section title={title} to={`/search?q=${encodeURIComponent(term)}&tab=playlists`}>
      {res.loading ? <SkeletonCards /> : <Carousel>{englishFirst(res.data!.items).slice(0, 12).map((p) => <Card key={p.uuid} to={`/playlist/${p.uuid}`} image={playlistImage(p, 320)} title={p.title} sub={p.description?.replace(/\s*\(Cover:.*$/, '') || (p.numberOfTracks ? `${p.numberOfTracks} songs` : 'Playlist')} />)}</Carousel>}
    </Section>
  )
}
