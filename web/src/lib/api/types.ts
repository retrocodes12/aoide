/** Shapes returned by hifi-api compatible instances (Monochrome's catalogue layer). */

export interface ArtistRef {
  id: number
  name: string
  type?: string
  picture?: string | null
}

export interface AlbumRef {
  id: number
  title: string
  cover?: string | null
  vibrantColor?: string | null
  videoCover?: string | null
  releaseDate?: string
}

export interface Track {
  id: number
  title: string
  version?: string | null
  duration: number
  trackNumber?: number
  volumeNumber?: number
  explicit?: boolean
  isrc?: string
  popularity?: number
  audioQuality?: string
  mediaMetadata?: { tags?: string[] }
  streamReady?: boolean
  allowStreaming?: boolean
  artist?: ArtistRef
  artists?: ArtistRef[]
  album?: AlbumRef
  replayGain?: number
  peak?: number
}

export interface Album extends AlbumRef {
  duration?: number
  numberOfTracks?: number
  numberOfVolumes?: number
  releaseDate?: string
  copyright?: string
  type?: 'ALBUM' | 'EP' | 'SINGLE' | string
  explicit?: boolean
  popularity?: number
  audioQuality?: string
  mediaMetadata?: { tags?: string[] }
  artist?: ArtistRef
  artists?: ArtistRef[]
  upc?: string
}

export interface AlbumWithTracks extends Album {
  items?: Array<{ item: Track; type: string }>
}

export interface Artist {
  id: number
  name: string
  picture?: string | null
  popularity?: number
  artistTypes?: string[]
  artistRoles?: Array<{ category: string }>
  mixes?: Record<string, string>
}

export interface Playlist {
  uuid: string
  title: string
  description?: string
  numberOfTracks?: number
  duration?: number
  image?: string | null
  squareImage?: string | null
  creator?: { id?: number; name?: string }
  lastUpdated?: string
  type?: string
  promotedArtists?: ArtistRef[]
}

export interface Paged<T> {
  limit: number
  offset: number
  totalNumberOfItems: number
  items: T[]
}

export interface SearchAll {
  artists?: Paged<Artist>
  albums?: Paged<Album>
  playlists?: Paged<Playlist>
  tracks?: Paged<Track>
  topHits?: Array<{ value: Artist | Album | Track | Playlist; type: string }>
}

export type Quality = 'LOW' | 'HIGH' | 'LOSSLESS' | 'HI_RES_LOSSLESS'

export interface ManifestInfo {
  trackId: number
  assetPresentation: 'FULL' | 'PREVIEW' | string
  audioQuality: string
  audioMode?: string
  manifestMimeType: string
  manifest: string
  bitDepth?: number | null
  sampleRate?: number | null
  trackReplayGain?: number
  trackPeakAmplitude?: number
}

export interface ResolvedStream {
  /** Something shaka can load: a blob: URL for inline manifests, or a signed https manifest URI. */
  url: string
  mimeType: string
  isPreview: boolean
  quality: string
  bitDepth?: number | null
  sampleRate?: number | null
  source: string
  revoke?: () => void
}

export interface Lyrics {
  plain?: string
  synced?: Array<{ t: number; line: string }>
  source: 'lrclib'
}
