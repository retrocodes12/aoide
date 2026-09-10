import { allCooling, orderedInstances, reportFailure, reportSuccess } from './instances'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

interface CacheEntry {
  at: number
  value: unknown
}
const cache = new Map<string, CacheEntry>()
const inflight = new Map<string, Promise<unknown>>()
const TTL = 15 * 60_000

function timeout(ms: number, parent?: AbortSignal): AbortSignal {
  const c = new AbortController()
  const t = setTimeout(() => c.abort(new DOMException('timeout', 'TimeoutError')), ms)
  parent?.addEventListener('abort', () => c.abort(parent.reason ?? new DOMException('aborted', 'AbortError')), { once: true })
  c.signal.addEventListener('abort', () => clearTimeout(t), { once: true })
  return c.signal
}

/**
 * GET a hifi-api route across every configured instance until one answers.
 * 404 is treated as authoritative (the resource does not exist); everything
 * else moves on to the next mirror. Responses are memoised for 15 minutes.
 */
export async function apiGet<T>(path: string, opts: { signal?: AbortSignal; ttl?: number; noCache?: boolean } = {}): Promise<T> {
  const key = path
  const hit = cache.get(key)
  if (!opts.noCache && hit && Date.now() - hit.at < (opts.ttl ?? TTL)) return hit.value as T
  if (opts.signal?.aborted) throw opts.signal.reason ?? new DOMException('aborted', 'AbortError')

  // One network run per path, shared by every caller and detached from any
  // caller's AbortSignal: React StrictMode mounts twice, and the second mount
  // must not inherit the first mount's abort.
  let run = inflight.get(key) as Promise<T> | undefined
  if (!run) {
    run = (async () => {
      if (allCooling()) {
        // Every mirror is benched: browse straight from TIDAL, keep the mirrors for later.
        try {
          const json = (await nativeFallback(path)) as T
          cache.set(key, { at: Date.now(), value: json })
          return json
        } catch {
          /* fall through to the mirrors anyway */
        }
      }
      const instances = orderedInstances()
      let lastErr: Error | null = null
      mirrors: for (let pass = 0; pass < 2; pass++) {
        for (const inst of instances) {
          // The boot probe may have benched everything while we were waiting on a timeout.
          if (allCooling()) break mirrors
          if ((inst.coolUntil ?? 0) > Date.now() && instances.some((i) => (i.coolUntil ?? 0) <= Date.now())) continue
          const t0 = performance.now()
          try {
            const res = await fetch(inst.url + path, { signal: timeout(pass === 0 ? 6000 : 10000), cache: 'no-store' })
            if (res.status === 404) throw new ApiError(404, 'Not found')
            if (!res.ok) {
              // 5xx / 401 / 403 mean the mirror itself is broken right now: bench it at once.
              reportFailure(inst.url, res.status >= 500 || res.status === 401 || res.status === 403)
              lastErr = new ApiError(res.status, `${inst.url} answered ${res.status}`)
              continue
            }
            const json = (await res.json()) as T
            reportSuccess(inst.url, performance.now() - t0)
            cache.set(key, { at: Date.now(), value: json })
            return json
          } catch (e) {
            if (e instanceof ApiError && e.status === 404) throw e
            reportFailure(inst.url)
            lastErr = e instanceof Error ? e : new Error(String(e))
          }
        }
      }
      // Every mirror failed: fall back to TIDAL's public catalogue for browsing routes.
      try {
        const json = (await nativeFallback(path)) as T
        cache.set(key, { at: Date.now(), value: json })
        return json
      } catch {
        throw lastErr ?? new ApiError(0, 'No instances configured')
      }
    })()
    inflight.set(key, run)
    void run.finally(() => inflight.delete(key)).catch(() => {})
  }
  return withSignal(run, opts.signal)
}

/** Resolve with `p`, or reject early if `signal` aborts. `p` keeps running either way. */
function withSignal<T>(p: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return p
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(signal.reason ?? new DOMException('aborted', 'AbortError'))
    if (signal.aborted) return onAbort()
    signal.addEventListener('abort', onAbort, { once: true })
    p.then(resolve, reject).finally(() => signal.removeEventListener('abort', onAbort))
  })
}

export function clearApiCache() {
  cache.clear()
}

/* ---------- Native TIDAL fallback (metadata only) ----------
 * Monochrome itself queries api.tidal.com with TIDAL's public browser client for
 * routes the mirrors don't expose (top tracks, bios). Client-credentials tokens
 * cannot stream anything; they read public catalogue data. */
const CLIENT_ID = 'txNoH4kkV41MfH25'
const CLIENT_SECRET = 'dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98='
let token: { value: string; exp: number } | null = null
let tokenPromise: Promise<string> | null = null

async function nativeToken(signal?: AbortSignal): Promise<string> {
  if (token && Date.now() < token.exp) return token.value
  if (tokenPromise) return tokenPromise
  tokenPromise = (async () => {
    try {
      const res = await fetch('https://auth.tidal.com/v1/oauth2/token', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          Authorization: 'Basic ' + btoa(`${CLIENT_ID}:${CLIENT_SECRET}`),
        },
        body: new URLSearchParams({ grant_type: 'client_credentials' }),
        signal: timeout(9000, signal),
      })
      if (!res.ok) throw new ApiError(res.status, 'token')
      const json = (await res.json()) as { access_token: string; expires_in?: number }
      token = { value: json.access_token, exp: Date.now() + ((json.expires_in ?? 3600) - 120) * 1000 }
      return token.value
    } finally {
      tokenPromise = null
    }
  })()
  return tokenPromise
}

export async function nativeGet<T>(path: string, opts: { signal?: AbortSignal } = {}): Promise<T> {
  const key = 'native:' + path
  const hit = cache.get(key)
  if (hit && Date.now() - hit.at < TTL) return hit.value as T
  const tok = await nativeToken(opts.signal)
  const url = 'https://api.tidal.com' + path + (path.includes('?') ? '&' : '?') + 'countryCode=US'
  const res = await fetch(url, { headers: { Authorization: `Bearer ${tok}` }, signal: timeout(9000, opts.signal) })
  if (!res.ok) throw new ApiError(res.status, `tidal ${res.status}`)
  const json = (await res.json()) as T
  cache.set(key, { at: Date.now(), value: json })
  return json
}

export interface NativeManifest {
  trackPresentation: 'FULL' | 'PREVIEW'
  previewReason?: string
  uri: string
  formats?: string[]
}

/** Last-resort stream source: TIDAL's own manifest endpoint (previews only without a subscription). */
export async function nativeManifest(id: number, signal?: AbortSignal, quality: string = 'LOSSLESS'): Promise<NativeManifest> {
  const tok = await nativeToken(signal)
  const p = new URLSearchParams({ adaptive: 'false', manifestType: 'MPEG_DASH', uriScheme: 'HTTPS', usage: 'PLAYBACK', countryCode: 'US' })
  const formats = quality === 'HIGH' || quality === 'LOW' ? ['AACLC', 'HEAACV1', 'FLAC'] : ['FLAC', 'AACLC', 'HEAACV1']
  for (const f of formats) p.append('formats', f)
  const res = await fetch(`https://openapi.tidal.com/v2/trackManifests/${id}?${p}`, {
    headers: { Authorization: `Bearer ${tok}`, Accept: 'application/vnd.api+json' },
    signal: timeout(9000, signal),
  })
  if (!res.ok) throw new ApiError(res.status, `manifest ${res.status}`)
  const json = (await res.json()) as { data: { attributes: NativeManifest } }
  return json.data.attributes
}

/* ---------- hifi-api route -> TIDAL v1 mapping, used only when every mirror is down ----------
 * hifi-api mirrors proxy TIDAL's v1 JSON under `data`, so the shapes line up one to one. */
/** Page through a TIDAL v1 list (max 50 per page) up to `max` items. */
async function nativePages(path: string, max: number): Promise<unknown[]> {
  const out: unknown[] = []
  const sep = path.includes('?') ? '&' : '?'
  for (let offset = 0; offset < max; offset += 50) {
    const page = await nativeGet<{ items: unknown[]; totalNumberOfItems?: number }>(`${path}${sep}limit=50&offset=${offset}`)
    out.push(...(page.items ?? []))
    if ((page.items ?? []).length < 50 || (page.totalNumberOfItems !== undefined && out.length >= page.totalNumberOfItems)) break
  }
  return out
}

async function nativeFallback(path: string): Promise<unknown> {
  const u = new URL('http://x' + path)
  const q = u.searchParams
  const route = u.pathname.replace(/\/+$/, '')
  const lim = q.get('limit') ?? '25'
  const off = q.get('offset') ?? '0'
  const enc = (s: string) => encodeURIComponent(s)
  if (route === '/search') {
    if (q.get('s') !== null) return { data: await nativeGet(`/v1/search/tracks?query=${enc(q.get('s')!)}&limit=${lim}&offset=${off}`) }
    if (q.get('a') !== null) {
      const r = await nativeGet<Record<string, unknown> & { topHit?: unknown; topHits?: unknown[] }>(`/v1/search?query=${enc(q.get('a')!)}&limit=${lim}&types=ARTISTS,ALBUMS,PLAYLISTS,TRACKS`)
      // TIDAL v1 answers with a single `topHit`; the mirrors expose `topHits`. Normalise.
      if (!r.topHits && r.topHit) r.topHits = [r.topHit]
      return { data: r }
    }
    if (q.get('al') !== null) return { data: await nativeGet(`/v1/search?query=${enc(q.get('al')!)}&limit=${lim}&types=ALBUMS`) }
    if (q.get('p') !== null) return { data: { playlists: await nativeGet(`/v1/search/playlists?query=${enc(q.get('p')!)}&limit=${lim}`) } }
  }
  if (route === '/album' && q.get('id')) {
    const id = q.get('id')!
    const [album, items] = await Promise.all([nativeGet<Record<string, unknown>>(`/v1/albums/${id}`), nativeGet<{ items: unknown[] }>(`/v1/albums/${id}/items?limit=100`)])
    return { data: { ...album, items: items.items } }
  }
  if (route === '/album/similar') return { albums: [] }
  if (route === '/artist' && q.get('id')) return { artist: await nativeGet(`/v1/artists/${q.get('id')}`) }
  if (route === '/artist' && q.get('f')) {
    const id = q.get('f')!
    // TIDAL caps this list at 50 per page; walk two pages of each so a long discography still shows.
    const [albums, eps] = await Promise.all([nativePages(`/v1/artists/${id}/albums`, 100), nativePages(`/v1/artists/${id}/albums?filter=EPSANDSINGLES`, 100)])
    return { albums: { items: [...albums, ...eps] }, tracks: [] }
  }
  if (route === '/artist/similar') return { artists: [] }
  if (route === '/playlist' && q.get('id')) {
    const id = q.get('id')!
    const [playlist, items] = await Promise.all([nativeGet(`/v1/playlists/${id}`), nativeGet<{ items: unknown[] }>(`/v1/playlists/${id}/items?limit=100`)])
    return { playlist, items: items.items }
  }
  if (route === '/recommendations') return { data: { items: [] } }
  throw new ApiError(404, 'No native route for ' + path)
}
