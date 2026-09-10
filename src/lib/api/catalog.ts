import { apiGet, nativeGet, ApiError } from './client'
import type { Album, AlbumWithTracks, Artist, Lyrics, ManifestInfo, Paged, Playlist, Quality, SearchAll, Track } from './types'

type Opts = { signal?: AbortSignal }

const q = (o: Record<string, string | number | undefined>) =>
  Object.entries(o)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join('&')

/* ---------- images ---------- */
export type CoverSize = 80 | 160 | 320 | 640 | 1280
export type ArtistSize = 160 | 320 | 480 | 750

export function cover(uuid: string | null | undefined, size: CoverSize = 320): string | undefined {
  if (!uuid) return undefined
  return `https://resources.tidal.com/images/${uuid.replace(/-/g, '/')}/${size}x${size}.jpg`
}
export function artistPicture(uuid: string | null | undefined, size: ArtistSize = 320): string | undefined {
  if (!uuid) return undefined
  return `https://resources.tidal.com/images/${uuid.replace(/-/g, '/')}/${size}x${size}.jpg`
}
export function playlistImage(p: Playlist, size: CoverSize = 320): string | undefined {
  return cover(p.squareImage ?? p.image, size)
}

/* ---------- search ---------- */
export async function searchTracks(term: string, limit = 25, offset = 0, o: Opts = {}): Promise<Paged<Track>> {
  const r = await apiGet<{ data: Paged<Track> }>(`/search/?${q({ s: term, limit, offset })}`, o)
  return r.data
}
export async function searchAll(term: string, limit = 12, o: Opts = {}): Promise<SearchAll> {
  // On a hifi-api mirror `a=` answers with artists, tracks and top hits only; albums and playlists
  // have their own parameters. Ask for all three at once and merge, so the Albums and Playlists
  // tabs are never empty just because a mirror is serving instead of the fallback.
  const [main, albums, playlists] = await Promise.allSettled([
    apiGet<{ data: SearchAll }>(`/search/?${q({ a: term, limit })}`, o),
    apiGet<{ data: SearchAll }>(`/search/?${q({ al: term, limit })}`, o),
    apiGet<{ data: SearchAll }>(`/search/?${q({ p: term, limit })}`, o),
  ])
  if (main.status === 'rejected') throw main.reason
  const out: SearchAll = { ...main.value.data }
  if (!out.albums?.items?.length && albums.status === 'fulfilled' && albums.value.data.albums) out.albums = albums.value.data.albums
  if (!out.playlists?.items?.length && playlists.status === 'fulfilled' && playlists.value.data.playlists) out.playlists = playlists.value.data.playlists
  return out
}
export async function searchAlbums(term: string, limit = 25, o: Opts = {}): Promise<Paged<Album>> {
  const r = await apiGet<{ data: SearchAll }>(`/search/?${q({ al: term, limit })}`, o)
  return r.data.albums ?? { limit, offset: 0, totalNumberOfItems: 0, items: [] }
}
export async function searchPlaylists(term: string, limit = 25, o: Opts = {}): Promise<Paged<Playlist>> {
  const r = await apiGet<{ data: SearchAll }>(`/search/?${q({ p: term, limit })}`, o)
  return r.data.playlists ?? { limit, offset: 0, totalNumberOfItems: 0, items: [] }
}

/* ---------- albums ---------- */
export async function getAlbum(id: number | string, o: Opts = {}): Promise<{ album: AlbumWithTracks; tracks: Track[] }> {
  const r = await apiGet<{ data: AlbumWithTracks }>(`/album/?id=${id}`, o)
  const album = r.data
  const tracks = (album.items ?? [])
    .filter((i) => i.type === 'track' || !i.type || i.item?.duration !== undefined)
    .map((i) => ({ ...i.item, album: i.item.album ?? { id: album.id, title: album.title, cover: album.cover, vibrantColor: album.vibrantColor } }))
  return { album, tracks }
}
export async function getSimilarAlbums(id: number | string, o: Opts = {}): Promise<Album[]> {
  try {
    const r = await apiGet<{ albums: Array<Album & { artists?: Array<{ id: number; name: string }> }> }>(`/album/similar/?id=${id}`, o)
    return (r.albums ?? []).map((a) => ({ ...a, artist: a.artist ?? a.artists?.[0] }))
  } catch {
    return []
  }
}

/* ---------- artists ---------- */
export async function getArtist(id: number | string, o: Opts = {}): Promise<Artist> {
  try {
    const r = await apiGet<{ artist: Artist }>(`/artist/?id=${id}`, o)
    return r.artist
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) throw e
    return nativeGet<Artist>(`/v1/artists/${id}`, o)
  }
}
export async function getDiscography(id: number | string, o: Opts = {}): Promise<{ albums: Album[]; tracks: Track[] }> {
  const r = await apiGet<{ albums: { items: Album[] }; tracks: Track[] }>(`/artist/?f=${id}`, o)
  return { albums: r.albums?.items ?? [], tracks: r.tracks ?? [] }
}
export async function getTopTracks(id: number | string, name: string, o: Opts = {}): Promise<Track[]> {
  try {
    const r = await nativeGet<Paged<Track>>(`/v1/artists/${id}/toptracks?limit=10`, o)
    if (r.items?.length) return r.items
  } catch {
    /* fall back to a popularity-sorted search */
  }
  const s = await searchTracks(name, 40, 0, o)
  return s.items
    .filter((t) => t.artists?.some((a) => String(a.id) === String(id)) || String(t.artist?.id) === String(id))
    .sort((a, b) => (b.popularity ?? 0) - (a.popularity ?? 0))
    .slice(0, 10)
}
export async function getSimilarArtists(id: number | string, o: Opts = {}): Promise<Artist[]> {
  try {
    const r = await apiGet<{ artists: Artist[] }>(`/artist/similar/?id=${id}`, o)
    return r.artists ?? []
  } catch {
    return []
  }
}
export async function getArtistBio(id: number | string, o: Opts = {}): Promise<string> {
  try {
    const r = await nativeGet<{ text?: string; source?: string }>(`/v1/artists/${id}/bio`, o)
    return (r.text ?? '').replace(/\[wimpLink[^\]]*\]/g, '').replace(/\[\/wimpLink\]/g, '').replace(/<br\s*\/?>/g, '\n')
  } catch {
    return ''
  }
}

/* ---------- playlists & mixes ---------- */
export async function getPlaylist(uuid: string, o: Opts = {}): Promise<{ playlist: Playlist; tracks: Track[] }> {
  const r = await apiGet<{ playlist: Playlist; items?: Array<{ item: Track; type: string }>; tracks?: { items?: Array<{ item: Track }> } }>(`/playlist/?id=${uuid}`, o)
  const raw = r.items ?? r.tracks?.items ?? []
  return { playlist: r.playlist, tracks: raw.map((i) => i.item).filter((t) => t && t.duration !== undefined) }
}
export async function getMix(id: string, o: Opts = {}): Promise<{ title: string; subTitle?: string; tracks: Track[] }> {
  const r = await apiGet<{ mix: { title: string; subTitle?: string }; items?: Array<{ item: Track }> }>(`/mix/?id=${id}`, o)
  return { title: r.mix?.title ?? 'Mix', subTitle: r.mix?.subTitle, tracks: (r.items ?? []).map((i) => i.item).filter(Boolean) }
}
export async function getRecommendations(trackId: number | string, o: Opts = {}): Promise<Track[]> {
  try {
    const r = await apiGet<{ data: { items: Array<{ track: Track }> } }>(`/recommendations/?id=${trackId}`, o)
    return (r.data?.items ?? []).map((i) => i.track).filter(Boolean)
  } catch {
    return []
  }
}

/* ---------- streaming ---------- */
export async function getManifest(id: number, quality: Quality, o: Opts = {}): Promise<ManifestInfo> {
  const r = await apiGet<{ data: ManifestInfo }>(`/track/?id=${id}&quality=${quality}`, { ...o, ttl: 20 * 60_000 })
  return r.data
}

/* ---------- lyrics (lrclib.net, open CORS, no key) ---------- */
export async function getLyrics(t: Track, o: Opts = {}): Promise<Lyrics | null> {
  const artist = t.artist?.name ?? t.artists?.[0]?.name ?? ''
  const params = q({ artist_name: artist, track_name: t.title, album_name: t.album?.title, duration: Math.round(t.duration) })
  try {
    let res = await fetch(`https://lrclib.net/api/get?${params}`, { signal: o.signal })
    if (res.status === 404) res = await fetch(`https://lrclib.net/api/get?${q({ artist_name: artist, track_name: t.title })}`, { signal: o.signal })
    if (!res.ok) return null
    const j = (await res.json()) as { plainLyrics?: string; syncedLyrics?: string; instrumental?: boolean }
    if (j.instrumental) return { plain: 'Instrumental', source: 'lrclib' }
    const synced = j.syncedLyrics
      ? j.syncedLyrics
          .split('\n')
          .map((l) => {
            const m = l.match(/^\[(\d+):(\d+(?:\.\d+)?)\](.*)$/)
            return m ? { t: Number(m[1]) * 60 + Number(m[2]), line: m[3].trim() } : null
          })
          .filter((x): x is { t: number; line: string } => Boolean(x))
      : undefined
    // A synced field that parses to no timed lines is no synced lyrics at all; hand back plain text only.
    return { plain: j.plainLyrics?.trim() ? j.plainLyrics : undefined, synced: synced?.length ? synced : undefined, source: 'lrclib' }
  } catch {
    return null
  }
}

/* ---------- helpers ---------- */
export function trackArtists(t: Track): string {
  return (t.artists?.length ? t.artists : t.artist ? [t.artist] : []).map((a) => a.name).join(', ')
}
export function primaryArtist(t: Track | Album) {
  return t.artist ?? t.artists?.[0]
}
export function qualityLabel(t?: Track | Album | null, m?: ManifestInfo | null): string {
  if (m?.assetPresentation === 'PREVIEW') return 'Preview'
  if (m?.bitDepth && m.sampleRate) return `FLAC ${m.bitDepth}/${Math.round(m.sampleRate / 1000)}`
  const tags = t?.mediaMetadata?.tags ?? []
  if (tags.includes('HIRES_LOSSLESS')) return 'Hi-Res'
  if (tags.includes('LOSSLESS') || t?.audioQuality === 'LOSSLESS') return 'FLAC'
  return t?.audioQuality ? t.audioQuality.toLowerCase() : ''
}
