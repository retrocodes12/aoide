import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { addInstance, getInstances, removeInstance, resetInstances, refreshFromUptime, subscribe, type Instance } from '../lib/api/instances'
import { clearApiCache } from '../lib/api/client'
import type { Quality } from '../lib/api/types'
import { useDocumentTitle, useMirrorsDown } from '../lib/hooks'
import { usePlayer } from '../store/player'
import { useUI } from '../store/ui'
import { IChevronLeft, ITrash } from '../components/Icons'

const QUALITIES: Array<{ id: Quality; label: string; note: string }> = [
  { id: 'HI_RES_LOSSLESS', label: 'Hi-Res Lossless', note: 'FLAC up to 24-bit / 192 kHz when the mirror has it' },
  { id: 'LOSSLESS', label: 'Lossless', note: 'FLAC 16-bit / 44.1 kHz. The default.' },
  { id: 'HIGH', label: 'High', note: 'AAC 320 kbps' },
  { id: 'LOW', label: 'Low', note: 'AAC 96 kbps for thin connections' },
]

export function Settings() {
  useDocumentTitle('Settings')
  const nav = useNavigate()
  const [instances, setInstances] = useState<Instance[]>(getInstances())
  const [url, setUrl] = useState('')
  const [checking, setChecking] = useState<string | null>(null)
  const [status, setStatus] = useState<Record<string, string | undefined>>({})
  const quality = usePlayer((s) => s.quality)
  const setQuality = usePlayer((s) => s.setQuality)
  const toast = useUI((s) => s.toast)
  const setTintFrom = useUI((s) => s.setTintFrom)
  const down = useMirrorsDown()
  const hasTrack = usePlayer((s) => s.index >= 0)
  const granted = usePlayer((s) => s.stream?.quality ?? null)
  const grantedLabel = granted ? (QUALITIES.find((x) => x.id === granted)?.label ?? granted) : null
  useEffect(() => subscribe((l) => setInstances([...l])), [])
  useEffect(() => setTintFrom('#4a4a4a'), [setTintFrom])

  async function check(inst: Instance) {
    setChecking(inst.url)
    const t0 = performance.now()
    let last = 'no answer'
    try {
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const c = new AbortController()
          const t = setTimeout(() => c.abort(), 9000)
          const res = await fetch(`${inst.url}/track/?id=58990486&quality=LOSSLESS`, { signal: c.signal, cache: 'no-store' })
          clearTimeout(t)
          if (res.ok) {
            const j = (await res.json()) as { data?: { assetPresentation?: string } }
            setStatus((s) => ({ ...s, [inst.url]: `${j.data?.assetPresentation === 'FULL' ? 'full songs' : '30 s previews'} · ${Math.round(performance.now() - t0)} ms` }))
            return
          }
          last = `HTTP ${res.status}`
        } catch { last = 'no answer' }
        await new Promise((r) => setTimeout(r, 600))
      }
      setStatus((s) => ({ ...s, [inst.url]: last }))
    } finally { setChecking(null) }
  }

  return (
    <div className="page" data-testid="settings">
      <div className="topbar"><button className="iconbtn" aria-label="Back" onClick={() => (window.history.length > 1 ? nav(-1) : nav('/'))}><IChevronLeft /></button><span className="topbar__title">Settings</span><span style={{ width: 44 }} /></div>
      <div className="settings__sec"><h2 className="settings__title">Streaming quality</h2><p className="settings__hint">{down ? 'Every mirror is down, so songs come straight from TIDAL as 30-second previews. Lossless and the AAC tiers still apply; Hi-Res needs a mirror.' : 'Applies to the next song, and reloads the one playing.'}</p></div>
      {QUALITIES.map((q) => {
        const unavailable = down && q.id === 'HI_RES_LOSSLESS'
        return (
        <button key={q.id} className={`radio${quality === q.id ? ' is-on' : ''}`} role="radio" aria-checked={quality === q.id} aria-disabled={unavailable} disabled={unavailable} data-testid={`quality_${q.id}`} onClick={() => { setQuality(q.id); if (hasTrack) toast(`${q.label}. Reloading the current song.`) }}>
          <span className="radio__mark" aria-hidden /><span><b>{q.label}</b><small>{unavailable ? 'Not available on the TIDAL fallback' : quality === q.id && granted && granted !== q.id ? `${q.note}. This song is only available as ${grantedLabel}.` : q.note}</small></span>
        </button>
        )
      })}
      <div className="settings__sec"><h2 className="settings__title">Instances</h2><p className="settings__hint">Aoide reads the catalogue from hifi-api compatible mirrors, the same ones Monochrome lists, and fails over between them. Public mirrors usually serve 30-second previews; a mirror backed by a subscribed account serves full songs. Yours are tried first.</p></div>
      {down && <div className="settings__notice" role="status" data-testid="fallback_notice">No mirror is answering right now. Aoide is browsing TIDAL's catalogue directly, previews only, and will switch back the moment a mirror returns.</div>}
      {instances.map((inst) => {
        const st = checking === inst.url ? 'checking…' : status[inst.url]
        return (
          <div key={inst.url} className="instance" data-testid="instance_row">
            <div className="instance__main">
              <div className="instance__url">{inst.url.replace(/^https?:\/\//, '')}</div>
              <div className="instance__tags">{[inst.isUser ? 'yours' : 'public', inst.version && inst.version !== 'custom' ? `v${inst.version}` : null, inst.coolUntil && inst.coolUntil > Date.now() ? 'down' : null, inst.lastLatency ? `${Math.round(inst.lastLatency)} ms` : null].filter(Boolean).join(' · ')}</div>
              {st && <div className={`instance__status${st.includes('ms') ? ' is-ok' : ''}`} data-testid="instance_status">{st}</div>}
            </div>
            <button className="pill pill--outline" onClick={() => check(inst)} disabled={checking !== null}>Test</button>
            <button className="iconbtn iconbtn--sub" aria-label={`Remove ${inst.url}`} onClick={() => removeInstance(inst.url)}><ITrash /></button>
          </div>
        )
      })}
      <form className="instance__add" onSubmit={(e) => { e.preventDefault(); if (addInstance(url)) { toast('Instance added. It will be tried first.'); setUrl('') } else toast('Not a valid https origin, or already listed.') }}>
        <input className="input" type="url" placeholder="https://your-hifi-api.example" value={url} onChange={(e) => setUrl(e.target.value)} aria-label="Instance URL" data-testid="instance_url" />
        <button className="pill pill--filled" style={{ height: 44, padding: '0 16px' }} type="submit" data-testid="instance_add">Add</button>
      </form>
      <div className="settings__row">
        <button className="pill pill--outline" onClick={() => refreshFromUptime().then(() => toast('Refreshed the public list'))}>Refresh public list</button>
        <button className="pill pill--outline" onClick={() => { resetInstances(); toast('Instances reset') }}>Reset</button>
        <button className="pill pill--outline" onClick={() => { clearApiCache(); toast('Cache cleared') }}>Clear cache</button>
      </div>
      <div className="settings__sec"><h2 className="settings__title">About</h2></div>
      <div className="settings__about">
        <p>Aoide, the muse of song. A player in the shape of Spotify with the feel of Apple Music, on the Monochrome catalogue. Liked songs, playlists and history stay on this device; nothing leaves it.</p>
        <p style={{ marginTop: 10, fontSize: 12, color: 'var(--muted)' }}>Sources: Monochrome hifi-api mirrors, TIDAL public catalogue, lrclib.net lyrics. Not affiliated with Spotify, Apple, TIDAL or Monochrome.</p>
      </div>
    </div>
  )
}
