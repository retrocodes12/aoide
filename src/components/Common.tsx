import { Link } from 'react-router-dom'
import type { ReactNode } from 'react'
import { cover } from '../lib/api/catalog'
import type { Track } from '../lib/api/types'
import { useLibrary } from '../store/library'
import { useUI } from '../store/ui'
import { IChevronRight, IHeart, IPlus } from './Icons'

/** Section header: title with a chevron when it leads somewhere. */
export function Section({ title, to, onSeeAll, children, className = '' }: { title: string; to?: string; onSeeAll?: () => void; children: ReactNode; className?: string }) {
  const head = (
    <>
      <span className="section__title">{title}</span>
      {(to || onSeeAll) && <IChevronRight />}
    </>
  )
  return (
    <section className={`section ${className}`}>
      <div className="section__head">
        {to ? <Link to={to}>{head}</Link> : onSeeAll ? <button onClick={onSeeAll}>{head}</button> : head}
      </div>
      {children}
    </section>
  )
}

export function Card({ to, image, title, sub, round, eager, tag = 'card', onClick }: { to?: string; image?: string; title: string; sub?: string; round?: boolean; eager?: boolean; tag?: string; onClick?: () => void }) {
  const body = (
    <>
      <img className="card__img" src={image} alt="" loading={eager ? 'eager' : 'lazy'} width={156} height={156} />
      <div className="card__title">{title}</div>
      {sub && <div className="card__sub">{sub}</div>}
    </>
  )
  const cls = `card${round ? ' card--round' : ''}`
  return to ? <Link to={to} className={cls} data-testid={tag} aria-label={title}>{body}</Link> : <button className={cls} data-testid={tag} aria-label={title} onClick={onClick} style={{ textAlign: 'left' }}>{body}</button>
}

export function Carousel({ children }: { children: ReactNode }) {
  return <div className="carousel">{children}</div>
}

export function Hero({ to, image, eyebrow, title, sub }: { to: string; image?: string; eyebrow: string; title: string; sub?: string }) {
  return (
    <Link to={to} className="hero" data-testid="hero_card" aria-label={title}>
      <img src={image} alt="" width={640} height={200} />
      <div className="hero__scrim" />
      <div className="hero__copy">
        <div className="hero__eyebrow">{eyebrow}</div>
        <div className="hero__title">{title}</div>
        {sub && <div className="hero__sub">{sub}</div>}
      </div>
    </Link>
  )
}

/** Save-to-Liked control: a plus that becomes an orange heart. */
export function Like({ track, className = '' }: { track: Track; className?: string }) {
  const liked = useLibrary((s) => Boolean(s.liked[track.id]))
  const toggleLike = useLibrary((s) => s.toggleLike)
  const toast = useUI((s) => s.toast)
  return (
    <button
      className={`iconbtn like${liked ? ' is-on' : ' iconbtn--sub'} ${className}`}
      aria-pressed={liked}
      aria-label={liked ? 'Remove from Liked Songs' : 'Add to Liked Songs'}
      data-testid="like"
      onClick={(e) => {
        e.stopPropagation()
        e.preventDefault()
        toast(toggleLike(track) ? 'Added to Liked Songs' : 'Removed from Liked Songs')
      }}
    >
      {liked ? <IHeart filled /> : <IPlus />}
    </button>
  )
}

export function Eq() {
  return <span className="eq" aria-hidden><i /><i /><i /><i /></span>
}

export function Empty({ title, body, action }: { title: string; body?: string; action?: ReactNode }) {
  return (
    <div className="empty" role="status">
      <div className="empty__title">{title}</div>
      {body && <p className="empty__body">{body}</p>}
      {action}
    </div>
  )
}

export function ErrorState({ error, retry }: { error: Error; retry?: () => void }) {
  const offline = typeof navigator !== 'undefined' && !navigator.onLine
  return (
    <Empty title={offline ? "You're offline" : 'Something went wrong'} body={offline ? 'Aoide needs a connection to reach the catalogue mirrors.' : `Every mirror failed for this request. ${error.message}`} action={retry && <button className="pill pill--white" onClick={retry}>Try again</button>} />
  )
}

export function SkeletonRows({ n = 8, art = true }: { n?: number; art?: boolean }) {
  return (
    <div aria-busy="true">
      {Array.from({ length: n }).map((_, i) => (
        <div className="row" key={i}>
          {art ? <span className="row__art skeleton" /> : <span className="row__num"><span className="skeleton" style={{ width: 14, height: 12 }} /></span>}
          <span className="row__text"><span className="skeleton" style={{ width: `${45 + ((i * 17) % 40)}%`, height: 14 }} /><br /><span className="skeleton" style={{ width: `${25 + ((i * 13) % 25)}%`, height: 11, marginTop: 6 }} /></span>
        </div>
      ))}
    </div>
  )
}

export function SkeletonCards({ n = 4 }: { n?: number }) {
  return (
    <div className="carousel" aria-busy="true">
      {Array.from({ length: n }).map((_, i) => (
        <div className="card" key={i}><span className="card__img skeleton" style={{ display: 'block' }} /><span className="skeleton" style={{ width: '70%', height: 12, marginTop: 10, display: 'block' }} /></div>
      ))}
    </div>
  )
}

export function artOf(t: Track, size: 80 | 160 | 320 | 640 = 160) {
  return cover(t.album?.cover, size)
}
