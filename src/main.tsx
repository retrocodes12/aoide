import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import { App } from './App'
import { Home } from './pages/Home'
import { Search } from './pages/Search'
import { AlbumPage } from './pages/Album'
import { ArtistPage } from './pages/Artist'
import { LikedPage, LocalPlaylistPage, PlaylistPage } from './pages/Playlist'
import { Library } from './pages/Library'
import { Settings } from './pages/Settings'
import { NotFound } from './pages/NotFound'
import { usePlayer } from './store/player'
import { useUI } from './store/ui'
import './styles/tokens.css'
import './styles/shell.css'
import './styles/components.css'
import './styles/pages.css'

const router = createBrowserRouter(
  [
    {
      path: '/',
      element: <App />,
      children: [
        { index: true, element: <Home /> },
        { path: 'search', element: <Search /> },
        { path: 'album/:id', element: <AlbumPage /> },
        { path: 'artist/:id', element: <ArtistPage /> },
        { path: 'playlist/:id', element: <PlaylistPage /> },
        { path: 'local/:id', element: <LocalPlaylistPage /> },
        { path: 'liked', element: <LikedPage /> },
        { path: 'library', element: <Library /> },
        { path: 'settings', element: <Settings /> },
        { path: '*', element: <NotFound /> },
      ],
    },
  ],
  { basename: import.meta.env.BASE_URL.replace(/\/$/, '') || '/' },
)

// Test hook: lets browser automation read player state without poking the DOM.
;(window as unknown as { __aoide: unknown }).__aoide = { player: usePlayer, ui: useUI }

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
