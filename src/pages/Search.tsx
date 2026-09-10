import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { artistPicture, cover, playlistImage, searchAll, searchTracks } from '../lib/api/catalog'
import type { SearchAll, Track } from '../lib/api/types'
import { useDebounced, useDocumentTitle, useResource } from '../lib/hooks'
import { useLibrary } from '../store/library'
import { usePlayer } from '../store/player'
import { Empty, ErrorState, Section, SkeletonRows } from '../components/Common'
import { TrackRow } from '../components/TrackRow'
import { IClose, ISearch } from '../components/Icons'

type Tab = 'all' | 'tracks' | 'albums' | 'artists' | 'playlists'
const TABS: Array<[Tab, string]> = [['all', 'All'], ['tracks', 'Songs'], ['albums', 'Albums'], ['artists', 'Artists'], ['playlists', 'Playlists']]
const BROWSE: Array<[string, string, string]> = [
  ['pop', 'Pop', '#8d67ab'], ['hip hop', 'Hip-Hop', '#ba5d07'], ['rock', 'Rock', '#e61e32'], ['indie', 'Indie', '#608108'],
  ['electronic', 'Electronic', '#0d73ec'], ['jazz', 'Jazz', '#7358ff'], ['r&b', 'R&B', '#dc148c'], ['classical', 'Classical', '#1e3264'],
  ['metal', 'Metal', '#777777'], ['ambient', 'Ambient', '#148a08'], ['soul', 'Soul', '#e13300'], ['latin', 'Latin', '#e1118c'],
  ['sleep', 'Sleep', '#1e3264'], ['workout', 'Workout', '#503750'], ['chill', 'Chill', '#27856a'], ['focus', 'Focus', '#d84000'],
]

export function Search() {
  const [params, setParams] = useSearchParams()
  const [q, setQ] = useState(params.get('q') ?? '')
  const tab = (params.get('tab') as Tab) || 'all'
  const term = useDebounced(q.trim(), 260)
  const recent = useLibrary((s) => s.recentSearches)
  const recordSearch = useLibrary((s) => s.recordSearch)
  const ref = useRef<HTMLInputElement>(null)
  useDocumentTitle(term ? `“${term}”` : 'Search')
  useEffect(() => {
    const next = new URLSearchParams(params)
    if (term) next.set('q', term)
    else next.delete('q')
    if (next.toString() !== params.toString()) setParams(next, { replace: true })
    if (term.length > 1) recordSearch(term)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [term])
  useEffect(() => { const pq = params.get('q') ?? ''; if (pq && pq !== q && !q) setQ(pq) }, [params]) // eslint-disable-line react-hooks/exhaustive-deps
  const setTab = (t: Tab) => { const next = new URLSearchParams(params); if (t === 'all') next.delete('tab'); else next.set('tab', t); setParams(next, { replace: true }) }
  return (
    <div className="page" data-testid="search">
      <div className="page__head" style={{ paddingBottom: 4 }}><h1 className="large-title">Search</h1></div>
      <div className="searchbox">
        <ISearch />
        <input ref={ref} type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="What do you want to listen to?" aria-label="Search" autoComplete="off" spellCheck={false} enterKeyHint="search" data-testid="search_field" />
        {q && <button className="iconbtn" aria-label="Clear search" onClick={() => { setQ(''); ref.current?.focus() }}><IClose size={20} /></button>}
      </div>
      {!term ? (
        <>
          {recent.length > 0 && (
            <Section title="Recent searches">
              <div className="chips">{recent.map((r) => <button key={r} className="chip" onClick={() => setQ(r)}>{r}</button>)}</div>
            </Section>
          )}
          <Section title="Browse all">
            <div className="browse">{BROWSE.map(([t, label, color]) => <Link key={t} to={`/search?q=${encodeURIComponent(t)}&tab=playlists`} className="browse__tile" style={{ background: color }} data-testid="browse_tile" onClick={() => setQ(t)}>{label}</Link>)}</div>
          </Section>
        </>
      ) : (
        <Results term={term} tab={tab} setTab={setTab} />
      )}
    </div>
  )
}

function Results({ term, tab, setTab }: { term: string; tab: Tab; setTab: (t: Tab) => void }) {
  const all = useResource<SearchAll>((signal) => searchAll(term, 12, { signal }), [term])
  const songs = useResource<Track[]>((signal) => searchTracks(term, tab === 'tracks' ? 50 : 8, 0, { signal }).then((r) => r.items), [term, tab === 'tracks'])
  const playTracks = usePlayer((s) => s.playTracks)
  const hit = useMemo(() => {
    const h = all.data?.topHits?.[0]
    if (!h) return null
    const v = h.value as { id: number; name?: string; title?: string; picture?: string | null; cover?: string | null; artist?: { name: string }; album?: { id: number; cover?: string | null } }
    if (h.type === 'ARTISTS') return { to: `/artist/${v.id}`, img: artistPicture(v.picture, 320), title: v.name ?? '', sub: 'Artist', round: true }
    if (h.type === 'ALBUMS') return { to: `/album/${v.id}`, img: cover(v.cover, 320), title: v.title ?? '', sub: `Album · ${v.artist?.name ?? ''}`, round: false }
    if (h.type === 'TRACKS') return { to: v.album ? `/album/${v.album.id}` : '', img: cover(v.album?.cover, 320), title: v.title ?? '', sub: `Song · ${v.artist?.name ?? ''}`, round: false, track: h.value as Track }
    return null
  }, [all.data])
  const artists = all.data?.artists?.items ?? []
  const albums = all.data?.albums?.items ?? []
  const playlists = all.data?.playlists?.items ?? []
  const nothing = !all.loading && !all.error && !songs.loading && !songs.data?.length && !artists.length && !albums.length && !playlists.length
  const ctx = { kind: 'search', title: `“${term}”` }
  return (
    <div data-testid="results">
      <div className="chips" role="tablist">{TABS.map(([id, label]) => <button key={id} role="tab" aria-selected={tab === id} className={`chip${tab === id ? ' is-on' : ''}`} onClick={() => setTab(id)}>{label}</button>)}</div>
      {all.error && <ErrorState error={all.error} retry={() => { all.reload(); songs.reload() }} />}
      {nothing && <Empty title={`No results for “${term}”`} body="Check the spelling, or try fewer or different words." />}
      {tab === 'all' && hit && (
        <Section title="Top result">
          {hit.track ? (
            <button className={`tophit${hit.round ? ' tophit--round' : ''}`} data-testid="top_hit" onClick={() => playTracks(songs.data?.length ? songs.data : [hit.track!], 0, ctx)}><img src={hit.img} alt="" /><span><span className="tophit__title">{hit.title}</span><span className="tophit__sub">{hit.sub}</span></span></button>
          ) : (
            <Link to={hit.to} className={`tophit${hit.round ? ' tophit--round' : ''}`} data-testid="top_hit"><img src={hit.img} alt="" /><span><span className="tophit__title">{hit.title}</span><span className="tophit__sub">{hit.sub}</span></span></Link>
          )}
        </Section>
      )}
      {(tab === 'all' || tab === 'tracks') && !all.error && (
        <Section title="Songs">
          {songs.loading ? <SkeletonRows n={4} /> : (songs.data ?? []).map((t, i) => <TrackRow key={t.id} track={t} onPlay={() => playTracks(songs.data!, i, ctx)} sub={`${t.artists?.map((a) => a.name).join(', ') ?? t.artist?.name ?? ''}${t.album ? ` · ${t.album.title}` : ''}`} />)}
          {!songs.loading && !songs.data?.length && <Empty title={`No songs for “${term}”`} />}
        </Section>
      )}
      {(tab === 'all' || tab === 'artists') && artists.length > 0 && (
        <Section title="Artists">{artists.slice(0, tab === 'artists' ? 30 : 4).map((a) => <ResultRow key={a.id} to={`/artist/${a.id}`} img={artistPicture(a.picture, 160)} title={a.name} sub="Artist" round />)}</Section>
      )}
      {(tab === 'all' || tab === 'albums') && albums.length > 0 && (
        <Section title="Albums">{albums.slice(0, tab === 'albums' ? 30 : 4).map((a) => <ResultRow key={a.id} to={`/album/${a.id}`} img={cover(a.cover, 160)} title={a.title} sub={[a.type && a.type !== 'ALBUM' ? a.type.toLowerCase().replace(/^./, (c) => c.toUpperCase()) : 'Album', a.releaseDate?.slice(0, 4), (a.artist ?? a.artists?.[0])?.name].filter(Boolean).join(' · ')} />)}</Section>
      )}
      {(tab === 'all' || tab === 'playlists') && playlists.length > 0 && (
        <Section title="Playlists">{playlists.slice(0, tab === 'playlists' ? 30 : 4).map((p) => <ResultRow key={p.uuid} to={`/playlist/${p.uuid}`} img={playlistImage(p, 160)} title={p.title} sub={`Playlist${p.numberOfTracks ? ` · ${p.numberOfTracks} songs` : ''}`} />)}</Section>
      )}
      {all.loading && tab !== 'tracks' && <SkeletonRows n={4} />}
    </div>
  )
}

function ResultRow({ to, img, title, sub, round }: { to: string; img?: string; title: string; sub: string; round?: boolean }) {
  return (
    <Link to={to} className="row" data-testid="result_row">
      <img className={`row__art${round ? ' row__art--round' : ''}`} src={img} alt="" loading="lazy" width={48} height={48} />
      <span className="row__text"><span className="row__title">{title}</span><span className="row__sub"><span>{sub}</span></span></span>
    </Link>
  )
}
