import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { IChevronLeft, IHeart, IPause, IPlay, IShuffle, IAlbum } from './Icons'

/**
 * Album / playlist head: Spotify's tinted ground, Apple Music's composition. Centred artwork
 * with a deep shadow, title, artist in the accent, meta with a quality badge, then Play and
 * Shuffle as a pair of wide pills.
 */
export function DetailHead({ image, title, artist, meta, badge, description, liked, actions, playing, canPlay, onPlay, onShuffle }: {
  image?: string
  title: string
  artist?: ReactNode
  meta: string
  badge?: string
  description?: string
  liked?: boolean
  actions?: ReactNode
  playing: boolean
  canPlay: boolean
  onPlay: () => void
  onShuffle?: () => void
}) {
  const nav = useNavigate()
  return (
    <header className="head">
      <div className="topbar">
        <button className="iconbtn" aria-label="Back" data-testid="back" onClick={() => (window.history.length > 1 ? nav(-1) : nav('/'))}><IChevronLeft /></button>
        <span className="topbar__spacer" />
        {actions}
      </div>
      {liked ? <div className="head__liked"><IHeart filled /></div> : image ? <img className="head__art" src={image} alt="" width={248} height={248} fetchPriority="high" /> : <div className="head__art head__art--blank"><IAlbum /></div>}
      <div className="head__copy">
        <h1 className="head__title" data-testid="detail_title">{title || ' '}</h1>
        {artist && <div className="head__artist">{artist}</div>}
        <div className="head__meta">
          <span>{meta}</span>
          {badge && <span className="badge">{badge}</span>}
        </div>
        {description && <p className="head__desc">{description}</p>}
      </div>
      <div className="pill-row">
        <button className="pill pill--filled" data-testid="play_fab" disabled={!canPlay} onClick={onPlay}>{playing ? <IPause /> : <IPlay />}{playing ? 'Pause' : 'Play'}</button>
        <button className="pill pill--soft" data-testid="shuffle" disabled={!canPlay || !onShuffle} onClick={onShuffle}><IShuffle />Shuffle</button>
      </div>
    </header>
  )
}
