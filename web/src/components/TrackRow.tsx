import { Link } from 'react-router-dom'
import { artOf, Eq } from './Common'
import { IMore } from './Icons'
import type { Track } from '../lib/api/types'
import { fmtTime } from '../lib/format'
import { usePlayer } from '../store/player'
import { useUI } from '../store/ui'

interface Props {
  track: Track
  number?: number
  showArt?: boolean
  sub?: string
  showDuration?: boolean
  onPlay: () => void
  onRemove?: () => void
}

/** One song row in the Spotify mobile shape: art or number, title, "E · artist", and ··· */
export function TrackRow({ track, number, showArt = true, sub, showDuration, onPlay, onRemove }: Props) {
  const isCurrent = usePlayer((s) => s.queue[s.index]?.id === track.id)
  const playing = usePlayer((s) => s.queue[s.index]?.id === track.id && (s.status === 'playing' || s.status === 'buffering' || s.status === 'loading'))
  const openMenu = useUI((s) => s.openMenu)
  return (
    <div className={`row${isCurrent ? ' is-current' : ''}`} data-testid="track_row" data-track={track.id}>
      <button className="row__hit" style={{ display: 'contents' }} onClick={onPlay} aria-label={`Play ${track.title}`}>
        {number !== undefined && <span className="row__num num">{playing ? <Eq /> : number}</span>}
        {showArt && <img className="row__art" src={artOf(track, 160)} alt="" loading="lazy" width={48} height={48} />}
        <span className="row__text">
          <span className="row__title">{track.title}{track.version ? <span style={{ color: 'var(--subdued)' }}> - {track.version}</span> : null}</span>
          <span className="row__sub">
            {number === undefined && playing && <Eq />}
            {track.explicit && <span className="row__flag" title="Explicit">E</span>}
            <span>{sub ?? track.artists?.map((a) => a.name).join(', ') ?? track.artist?.name ?? ''}</span>
          </span>
        </span>
        {showDuration && <span className="row__dur num">{fmtTime(track.duration)}</span>}
      </button>
      <button className="iconbtn iconbtn--sub row__more" aria-label={`More options for ${track.title}`} data-testid="track_more" onClick={() => openMenu(track, onRemove)}><IMore /></button>
    </div>
  )
}

/** Artist links for a track, used where names should be tappable. */
export function ArtistLinks({ t }: { t: Track }) {
  const list = t.artists?.length ? t.artists : t.artist ? [t.artist] : []
  return (
    <>
      {list.map((a, i) => (
        <span key={a.id}>{i > 0 && ', '}<Link to={`/artist/${a.id}`}>{a.name}</Link></span>
      ))}
    </>
  )
}
