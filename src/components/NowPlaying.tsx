import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { artOf, Eq, Like } from './Common'
import { streamBadge } from './Shell'
import { IChevronDown, IClose, IDrag, ILyrics, IMore, INext, IPause, IPlay, IPrev, IQueue, IRemove, IRepeat, IRepeatOne, IShuffle } from './Icons'
import { cover, getLyrics, trackArtists } from '../lib/api/catalog'
import type { Track } from '../lib/api/types'
import { fmtTime } from '../lib/format'
import { useResource } from '../lib/hooks'
import { usePlayer } from '../store/player'
import { useUI } from '../store/ui'

/**
 * Full-screen player. Apple Music's stage: the artwork blurred and darkened behind everything,
 * the cover large with a deep shadow, shrinking when paused, a lossless badge under the title.
 * Spotify's furniture: the white play disc, orange shuffle/repeat, the lyrics card below.
 */
export function NowPlaying() {
  const open = useUI((s) => s.nowPlayingOpen)
  const { closeNowPlaying, openLyrics, openQueue, openMenu } = useUI.getState()
  const track = usePlayer((s) => s.queue[s.index] ?? null)
  const status = usePlayer((s) => s.status)
  const error = usePlayer((s) => s.error)
  const position = usePlayer((s) => s.position)
  const duration = usePlayer((s) => s.duration)
  const stream = usePlayer((s) => s.stream)
  const shuffle = usePlayer((s) => s.shuffle)
  const repeat = usePlayer((s) => s.repeat)
  const context = usePlayer((s) => s.context)
  const { toggle, next, prev, seek, toggleShuffle, cycleRepeat } = usePlayer.getState()
  const nav = useNavigate()
  const [scrub, setScrub] = useState<number | null>(null)
  const lyrics = useResource((signal) => (track ? getLyrics(track, { signal }) : Promise.resolve(null)), [track?.id])
  const sheet = useRef<HTMLElement>(null)
  const body = useRef<HTMLDivElement>(null)
  const hasTrack = track !== null
  // Pull-down-to-dismiss. Native listeners so touchmove can be non-passive: once the sheet
  // is following the finger we cancel the browser's own scroll, otherwise pointer events
  // would be cancelled the moment the body starts panning.
  useEffect(() => {
    const el = body.current
    const sh = sheet.current
    if (!el || !sh) return
    let d: { y0: number; t0: number; dy: number } | null = null
    const reset = () => {
      sh.style.transition = ''
      sh.style.transform = ''
    }
    const down = (e: PointerEvent) => {
      if ((e.target as HTMLElement).closest('input, .np__ctl, .np__lyrics, .np__row--actions')) return
      if (el.scrollTop > 2 || e.clientY > window.innerHeight * 0.6) return
      d = { y0: e.clientY, t0: performance.now(), dy: 0 }
    }
    const move = (e: PointerEvent) => {
      if (!d) return
      d.dy = e.clientY - d.y0
      if (d.dy > 6) {
        sh.style.transition = 'none'
        sh.style.transform = `translateY(${d.dy}px)`
      } else if (d.dy < -6) {
        d = null
        reset()
      }
    }
    const touchMove = (e: TouchEvent) => {
      if (d && d.dy > 0) e.preventDefault()
    }
    const up = () => {
      if (!d) return
      const { dy, t0 } = d
      d = null
      reset()
      const v = dy / Math.max(1, performance.now() - t0)
      if (dy > 120 || (dy > 40 && v > 0.6)) useUI.getState().closeNowPlaying()
    }
    const cancel = () => {
      d = null
      reset()
    }
    el.addEventListener('pointerdown', down)
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
    el.addEventListener('pointercancel', cancel)
    el.addEventListener('touchmove', touchMove, { passive: false })
    return () => {
      el.removeEventListener('pointerdown', down)
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
      el.removeEventListener('pointercancel', cancel)
      el.removeEventListener('touchmove', touchMove)
    }
  }, [hasTrack])
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && closeNowPlaying()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, closeNowPlaying])
  if (!track) return <div className="overlay" aria-hidden />
  const playing = status === 'playing' || status === 'buffering' || status === 'loading'
  const failed = status === 'error'
  // Only the media's own length drives the slider. Until it is known the transport waits rather than guessing.
  const dur = duration || 0
  const pos = scrub ?? position
  const badge = streamBadge(track, stream)
  const go = (r: string) => { closeNowPlaying(); nav(r) }
  // Same rule as the lyrics screen: synced lines when there are any, else plain text, else nothing.
  const lines = (lyrics.data?.synced?.length ? lyrics.data.synced.map((l) => l.line) : lyrics.data?.plain?.split('\n') ?? []).filter(Boolean)
  return (
    <section ref={sheet} className={`overlay${open ? ' is-open' : ''}`} aria-hidden={!open} inert={!open} aria-label="Now playing" data-testid="now_playing">
      <div className="np">
        <div className="np__bg" style={{ backgroundImage: `url(${artOf(track, 320)})` }} aria-hidden />
        <div className="np__scrim" aria-hidden />
        <div className="np__body" ref={body}>
          <div className="topbar">
            <button className="iconbtn" aria-label="Close now playing" data-testid="np_close" onClick={closeNowPlaying}><IChevronDown size={28} /></button>
            <div className="np__from">
              <div className="np__from-label">{`PLAYING FROM ${(context?.kind ?? 'queue').toUpperCase()}`}</div>
              <button className="np__from-title" style={{ maxWidth: '100%' }} onClick={() => context?.href && go(context.href)}>{context?.title ?? track.album?.title ?? ''}</button>
            </div>
            <button className="iconbtn" aria-label="More options" onClick={() => openMenu(track)}><IMore /></button>
          </div>
          <div className="np__stage">
            <img className={`np__art${playing ? '' : ' is-paused'}`} src={artOf(track, 640)} alt={track.album?.title ?? ''} width={640} height={640} />
          </div>
          <div className="np__meta">
            <div className="np__text">
              <div className="np__title" data-testid="np_title">{track.title}</div>
              <button className="np__artist" style={{ display: 'block', maxWidth: '100%', textAlign: 'left' }} onClick={() => (track.artist ?? track.artists?.[0]) && go(`/artist/${(track.artist ?? track.artists![0]).id}`)}>{trackArtists(track)}</button>
              {badge && <div className="np__badge"><span className={`badge${stream?.isPreview ? ' badge--accent' : ''}`}>{badge}</span></div>}
            </div>
            <Like track={track} />
          </div>
          {failed && (
            <div className="np__error" role="alert" data-testid="np_error">
              <span>{error ?? "Couldn't play this song"}</span>
              <button className="pill pill--soft" onClick={toggle} data-testid="np_retry">Try again</button>
            </div>
          )}
          <div className="np__seek">
            <input
              className="seek" type="range" min={0} max={dur || 1} step={0.25} value={dur ? pos : 0} disabled={!dur || failed}
              style={{ '--p': `${dur ? (pos / dur) * 100 : 0}%` } as React.CSSProperties}
              aria-label="Seek" data-testid="np_seek"
              onChange={(e) => setScrub(Number(e.target.value))}
              onPointerUp={() => { if (scrub !== null) seek(scrub); setScrub(null) }}
              onKeyUp={() => { if (scrub !== null) seek(scrub); setScrub(null) }}
            />
          </div>
          <div className="np__times num"><span data-testid="np_position">{fmtTime(pos)}</span><span data-testid="np_remaining">{dur ? `-${fmtTime(Math.max(0, dur - pos))}` : '-:--'}</span></div>
          <div className="np__ctl">
            <button className={`iconbtn${shuffle ? ' is-on' : ' iconbtn--sub'}`} aria-pressed={shuffle} aria-label="Shuffle" data-testid="np_shuffle" onClick={toggleShuffle}><IShuffle size={26} /></button>
            <button className="iconbtn iconbtn--big" aria-label="Previous" data-testid="np_prev" onClick={prev}><IPrev /></button>
            <button className="np__play" aria-label={failed ? 'Retry' : playing ? 'Pause' : 'Play'} data-testid="np_toggle" onClick={toggle}>{playing ? <IPause /> : <IPlay />}</button>
            <button className="iconbtn iconbtn--big" aria-label="Next" data-testid="np_next" onClick={next}><INext /></button>
            <button className={`iconbtn${repeat !== 'off' ? ' is-on' : ' iconbtn--sub'}`} aria-pressed={repeat !== 'off'} aria-label={`Repeat ${repeat}`} data-testid="np_repeat" onClick={cycleRepeat}>{repeat === 'one' ? <IRepeatOne size={26} /> : <IRepeat size={26} />}</button>
          </div>
          <div className="np__foot">
            <button className="iconbtn iconbtn--sub" aria-label="Lyrics" data-testid="np_lyrics" onClick={openLyrics}><ILyrics /></button>
            <span className="note" />
            <button className="iconbtn iconbtn--sub" aria-label="Queue" data-testid="np_queue" onClick={openQueue}><IQueue /></button>
          </div>
          <button className="np__lyrics" data-testid="lyrics_card" onClick={openLyrics}>
            <h3>Lyrics</h3>
            {lyrics.loading ? <p className="muted">Looking for lyrics…</p> : lines.length ? lines.slice(0, 4).map((l, i) => <p key={i}>{l}</p>) : <p className="muted">We don't have lyrics for this one.</p>}
          </button>
        </div>
      </div>
    </section>
  )
}

export function LyricsScreen() {
  const open = useUI((s) => s.lyricsOpen)
  const closeLyrics = useUI((s) => s.closeLyrics)
  const track = usePlayer((s) => s.queue[s.index] ?? null)
  const status = usePlayer((s) => s.status)
  const position = usePlayer((s) => s.position)
  const { toggle, seek } = usePlayer.getState()
  const res = useResource((signal) => (track ? getLyrics(track, { signal }) : Promise.resolve(null)), [track?.id])
  const ref = useRef<HTMLDivElement>(null)
  const duration = usePlayer((s) => s.duration)
  const isPreview = usePlayer((s) => Boolean(s.stream?.isPreview))
  // On a 30-second preview the lyrics still cover the whole song; lines past the clip cannot be reached.
  const reachable = (t: number) => !isPreview || !duration || t < duration - 0.5
  // lrclib sometimes answers with an empty synced list; treat that as no synced lyrics, not as a blank screen.
  const synced = res.data?.synced?.length ? res.data.synced : null
  const plain = res.data?.plain?.trim() ? res.data.plain : null
  const active = useMemo(() => {
    if (!synced?.length) return -1
    let i = -1
    for (let k = 0; k < synced.length; k++) if (synced[k].t <= position + 0.25) i = k
    return i
  }, [synced, position])
  useEffect(() => {
    if (!open) return
    ref.current?.querySelector<HTMLElement>('.lyrics__line.is-active')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [active, open])
  const playing = status === 'playing' || status === 'buffering' || status === 'loading'
  return (
    <section className={`overlay overlay--above${open ? ' is-open' : ''}`} aria-hidden={!open} inert={!open} aria-label="Lyrics" data-testid="lyrics_screen">
      {track && (
        <div className="lyrics">
          <div className="topbar">
            <div style={{ flex: 1, minWidth: 0, paddingLeft: 8 }}>
              <div className="mini__title">{track.title}</div>
              <div className="mini__sub">{trackArtists(track)}</div>
            </div>
            <button className="iconbtn" aria-label="Close lyrics" data-testid="lyrics_close" onClick={closeLyrics}><IClose /></button>
          </div>
          {res.loading ? <div className="lyrics__empty">Looking for lyrics…</div> : !synced && !plain ? <div className="lyrics__empty">We don't have lyrics for this one.</div> : (
            <div className="lyrics__list" ref={ref}>
              {synced ? synced.map((l, i) => (
                <button key={i} className={`lyrics__line${i === active ? ' is-active' : i < active ? ' is-past' : ''}${reachable(l.t) ? '' : ' is-beyond'}`} data-testid="lyric_line" disabled={!reachable(l.t)} aria-disabled={!reachable(l.t)} onClick={() => reachable(l.t) && seek(l.t)}>{l.line || '♪'}</button>
              )) : plain!.split('\n').map((l, i) => <div key={i} className="lyrics__line lyrics__line--plain">{l || ' '}</div>)}
              {isPreview && synced && synced.some((l) => !reachable(l.t)) && <div className="lyrics__note">Only the first 30 seconds play on this mirror; the rest of the lyrics are shown for reading.</div>}
            </div>
          )}
          <div className="lyrics__foot">
            <span>Lyrics provided by lrclib</span>
            <button className="lyrics__play" aria-label={playing ? 'Pause' : 'Play'} onClick={toggle}>{playing ? <IPause /> : <IPlay />}</button>
          </div>
        </div>
      )}
    </section>
  )
}

/** The queue, with a drag handle to reorder upcoming songs. */
export function QueueScreen() {
  const open = useUI((s) => s.queueOpen)
  const closeQueue = useUI((s) => s.closeQueue)
  const queue = usePlayer((s) => s.queue)
  const index = usePlayer((s) => s.index)
  const status = usePlayer((s) => s.status)
  const context = usePlayer((s) => s.context)
  const { jumpTo, removeAt, moveInQueue, clearUpcoming, toggle } = usePlayer.getState()
  const [lifted, setLifted] = useState<number | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const current = queue[index]
  const upcoming = queue.slice(index + 1)
  const playing = status === 'playing' || status === 'buffering'
  const startDrag = (from: number) => (e: React.PointerEvent) => {
    e.preventDefault()
    const target = e.currentTarget as HTMLElement
    target.setPointerCapture(e.pointerId)
    setLifted(from)
    const rows = () => Array.from(listRef.current?.querySelectorAll<HTMLElement>('[data-qi]') ?? [])
    const move = (ev: PointerEvent) => {
      const r = rows().find((el) => { const b = el.getBoundingClientRect(); return ev.clientY >= b.top && ev.clientY <= b.bottom })
      const to = r ? Number(r.dataset.qi) : null
      if (to !== null && to !== from) { moveInQueue(from, to); from = to; setLifted(to) }
    }
    const up = () => { setLifted(null); window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up) }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }
  return (
    <section className={`overlay overlay--above${open ? ' is-open' : ''}`} aria-hidden={!open} inert={!open} aria-label="Queue" data-testid="queue_screen">
      <div className="queue">
        <div className="topbar">
          <button className="iconbtn" aria-label="Close queue" data-testid="queue_close" onClick={closeQueue}><IChevronDown size={28} /></button>
          <span className="topbar__title">Queue</span>
          <span style={{ width: 44 }} />
        </div>
        <div className="queue__list" ref={listRef}>
          {current ? (
            <>
              <div className="queue__label"><span>Now playing</span></div>
              <QRow t={current} current onClick={toggle} playing={playing} />
              {upcoming.length ? (
                <>
                  <div className="queue__label"><span>Next {context ? <b>from: {context.title}</b> : null}</span><button className="linkbtn" data-testid="queue_clear" onClick={clearUpcoming}>Clear queue</button></div>
                  {upcoming.map((t, k) => {
                    const i = index + 1 + k
                    return <QRow key={`${t.id}-${i}`} t={t} qi={i} lifted={lifted === i} onClick={() => jumpTo(i)} onRemove={() => removeAt(i)} onGrip={startDrag(i)} />
                  })}
                </>
              ) : <div className="queue__label"><span style={{ color: 'var(--subdued)', fontWeight: 500 }}>End of queue.</span></div>}
            </>
          ) : <Empty />}
        </div>
      </div>
    </section>
  )
}

function Empty() {
  return <div className="empty"><div className="empty__title">Add to your queue</div><p className="empty__body">Tap ··· on any song to add it here.</p></div>
}

function QRow({ t, current, playing, qi, lifted, onClick, onRemove, onGrip }: { t: Track; current?: boolean; playing?: boolean; qi?: number; lifted?: boolean; onClick: () => void; onRemove?: () => void; onGrip?: (e: React.PointerEvent) => void }) {
  return (
    <div className={`row qrow${current ? ' is-current' : ''}${lifted ? ' is-lifted' : ''}`} data-testid="queue_row" data-qi={qi}>
      <button style={{ display: 'contents' }} onClick={onClick} aria-label={current ? 'Play or pause' : `Play ${t.title} now`}>
        <img className="row__art" src={cover(t.album?.cover, 80)} alt="" width={48} height={48} loading="lazy" />
        <span className="row__text">
          <span className="row__title">{t.title}</span>
          <span className="row__sub">{current && playing && <Eq />}<span>{trackArtists(t)}</span></span>
        </span>
      </button>
      {onRemove && <button className="iconbtn iconbtn--sub" aria-label={`Remove ${t.title} from queue`} data-testid="queue_remove" onClick={onRemove}><IRemove /></button>}
      {onGrip && <span className="qrow__grip" role="button" aria-label="Drag to reorder" data-testid="queue_grip" onPointerDown={onGrip}><IDrag /></span>}
    </div>
  )
}
