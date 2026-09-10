import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Album, Artist, Playlist, Track } from '../lib/api/types'

export interface LocalPlaylist {
  id: string
  title: string
  tracks: Track[]
  createdAt: number
}

interface LibraryState {
  liked: Record<number, Track>
  likedOrder: number[]
  albums: Record<number, Album>
  albumOrder: number[]
  artists: Record<number, Artist>
  followedPlaylists: Record<string, Playlist>
  playlists: LocalPlaylist[]
  recentTracks: Track[]
  recentAlbums: Album[]
  recentSearches: string[]

  isLiked: (id: number) => boolean
  toggleLike: (t: Track) => boolean
  hasAlbum: (id: number) => boolean
  toggleAlbum: (a: Album) => boolean
  isFollowing: (id: number) => boolean
  toggleArtist: (a: Artist) => boolean
  hasPlaylist: (uuid: string) => boolean
  togglePlaylist: (p: Playlist) => boolean
  createPlaylist: (title: string, tracks?: Track[]) => LocalPlaylist
  addToPlaylist: (id: string, t: Track) => void
  removeFromPlaylist: (id: string, index: number) => void
  deletePlaylist: (id: string) => void
  renamePlaylist: (id: string, title: string) => void
  recordPlay: (t: Track) => void
  recordAlbum: (a: Album) => void
  recordSearch: (term: string) => void
}

const cap = <T,>(arr: T[], n: number) => (arr.length > n ? arr.slice(0, n) : arr)

export const useLibrary = create<LibraryState>()(
  persist(
    (set, get) => ({
      liked: {},
      likedOrder: [],
      albums: {},
      albumOrder: [],
      artists: {},
      followedPlaylists: {},
      playlists: [],
      recentTracks: [],
      recentAlbums: [],
      recentSearches: [],

      isLiked: (id) => Boolean(get().liked[id]),
      toggleLike: (t) => {
        const { liked, likedOrder } = get()
        if (liked[t.id]) {
          const next = { ...liked }
          delete next[t.id]
          set({ liked: next, likedOrder: likedOrder.filter((i) => i !== t.id) })
          return false
        }
        set({ liked: { ...liked, [t.id]: t }, likedOrder: [t.id, ...likedOrder] })
        return true
      },
      hasAlbum: (id) => Boolean(get().albums[id]),
      toggleAlbum: (a) => {
        const { albums, albumOrder } = get()
        if (albums[a.id]) {
          const next = { ...albums }
          delete next[a.id]
          set({ albums: next, albumOrder: albumOrder.filter((i) => i !== a.id) })
          return false
        }
        const slim: Album = { id: a.id, title: a.title, cover: a.cover, vibrantColor: a.vibrantColor, artist: a.artist ?? a.artists?.[0], releaseDate: a.releaseDate, numberOfTracks: a.numberOfTracks, type: a.type }
        set({ albums: { ...albums, [a.id]: slim }, albumOrder: [a.id, ...albumOrder] })
        return true
      },
      isFollowing: (id) => Boolean(get().artists[id]),
      toggleArtist: (a) => {
        const { artists } = get()
        if (artists[a.id]) {
          const next = { ...artists }
          delete next[a.id]
          set({ artists: next })
          return false
        }
        set({ artists: { ...artists, [a.id]: { id: a.id, name: a.name, picture: a.picture } } })
        return true
      },
      hasPlaylist: (uuid) => Boolean(get().followedPlaylists[uuid]),
      togglePlaylist: (p) => {
        const { followedPlaylists } = get()
        if (followedPlaylists[p.uuid]) {
          const next = { ...followedPlaylists }
          delete next[p.uuid]
          set({ followedPlaylists: next })
          return false
        }
        set({ followedPlaylists: { ...followedPlaylists, [p.uuid]: { uuid: p.uuid, title: p.title, image: p.image, squareImage: p.squareImage, numberOfTracks: p.numberOfTracks, description: p.description } } })
        return true
      },
      createPlaylist: (title, tracks = []) => {
        const pl: LocalPlaylist = { id: `local-${Date.now().toString(36)}`, title: title.trim() || 'Untitled', tracks, createdAt: Date.now() }
        set({ playlists: [pl, ...get().playlists] })
        return pl
      },
      addToPlaylist: (id, t) => set({ playlists: get().playlists.map((p) => (p.id === id ? { ...p, tracks: [...p.tracks, t] } : p)) }),
      removeFromPlaylist: (id, index) => set({ playlists: get().playlists.map((p) => (p.id === id ? { ...p, tracks: p.tracks.filter((_, i) => i !== index) } : p)) }),
      deletePlaylist: (id) => set({ playlists: get().playlists.filter((p) => p.id !== id) }),
      renamePlaylist: (id, title) => set({ playlists: get().playlists.map((p) => (p.id === id ? { ...p, title } : p)) }),
      recordPlay: (t) => set({ recentTracks: cap([t, ...get().recentTracks.filter((x) => x.id !== t.id)], 50) }),
      recordAlbum: (a) => {
        const slim: Album = { id: a.id, title: a.title, cover: a.cover, vibrantColor: a.vibrantColor, artist: a.artist ?? a.artists?.[0], releaseDate: a.releaseDate, type: a.type }
        set({ recentAlbums: cap([slim, ...get().recentAlbums.filter((x) => x.id !== a.id)], 24) })
      },
      recordSearch: (term) => {
        const t = term.trim()
        if (!t) return
        set({ recentSearches: cap([t, ...get().recentSearches.filter((x) => x.toLowerCase() !== t.toLowerCase())], 8) })
      },
    }),
    { name: 'aoide:library:v1' },
  ),
)
