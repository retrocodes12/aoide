/**
 * Instance registry. Sleeve talks to hifi-api compatible mirrors, the same ones
 * Monochrome lists in INSTANCES.md. Users can add their own; the uptime worker
 * refreshes the public list on boot.
 */
export interface Instance {
  url: string
  version?: string
  isUser?: boolean
  /** Epoch ms until which this instance is skipped after repeated failures. */
  coolUntil?: number
  fails?: number
  lastLatency?: number
}

const KEY = 'aoide:instances:v1'
const UPTIME = 'https://tidal-uptime.props-76styles.workers.dev/'

export const DEFAULT_INSTANCES: Instance[] = [
  { url: 'https://lol.samidy.workers.dev', version: '2.10' },
  { url: 'https://monochrome-api.samidy.com', version: '2.3' },
]

type Listener = (list: Instance[]) => void
const listeners = new Set<Listener>()
let list: Instance[] = load()

function load(): Instance[] {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const saved = JSON.parse(raw) as Instance[]
      if (Array.isArray(saved) && saved.length) return dedupe([...saved.filter((i) => i.isUser), ...DEFAULT_INSTANCES, ...saved])
    }
  } catch {
    /* storage unavailable: fall through to defaults */
  }
  return [...DEFAULT_INSTANCES]
}

function dedupe(items: Instance[]): Instance[] {
  const seen = new Map<string, Instance>()
  for (const i of items) {
    const url = normalize(i.url)
    if (!url) continue
    const prev = seen.get(url)
    seen.set(url, { ...prev, ...i, url, isUser: Boolean(prev?.isUser || i.isUser) })
  }
  return [...seen.values()]
}

export function normalize(url: string): string {
  const t = url.trim().replace(/\/+$/, '')
  if (!/^https?:\/\/[^\s/]+$/i.test(t)) return ''
  return t
}

function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(list.map(({ url, version, isUser }) => ({ url, version, isUser }))))
  } catch {
    /* ignore */
  }
  listeners.forEach((l) => l(list))
}

const COOL_KEY = 'aoide:instances:cool'
function persistCooling() {
  try {
    localStorage.setItem(COOL_KEY, JSON.stringify(Object.fromEntries(list.filter((i) => (i.coolUntil ?? 0) > Date.now()).map((i) => [i.url, i.coolUntil]))))
  } catch {
    /* ignore */
  }
}
function loadCooling() {
  try {
    const m = JSON.parse(localStorage.getItem(COOL_KEY) ?? '{}') as Record<string, number>
    for (const i of list) if (m[i.url] && m[i.url] > Date.now()) i.coolUntil = m[i.url]
  } catch {
    /* ignore */
  }
}
loadCooling()

/**
 * One cheap request per mirror at boot, in parallel, so a dead mirror is benched before the
 * first page pays its timeout. Runs in the background; pages fall back to TIDAL meanwhile.
 */
export async function probeMirrors(): Promise<void> {
  await Promise.all(
    list.map(async (inst) => {
      const c = new AbortController()
      const t = setTimeout(() => c.abort(), 6000)
      const t0 = performance.now()
      try {
        const res = await fetch(`${inst.url}/search/?s=a&limit=1`, { signal: c.signal, cache: 'no-store' })
        if (res.ok) reportSuccess(inst.url, performance.now() - t0)
        else reportFailure(inst.url, true)
      } catch {
        reportFailure(inst.url, true)
      } finally {
        clearTimeout(t)
      }
    }),
  )
}

export function getInstances(): Instance[] {
  return list
}

/** Instances in preference order: user first, then healthy, then cooling ones last. */
export function orderedInstances(): Instance[] {
  const now = Date.now()
  const healthy = list.filter((i) => (i.coolUntil ?? 0) <= now)
  const pool = healthy.length ? healthy : list
  return [...pool].sort((a, b) => {
    const ac = (a.coolUntil ?? 0) > now ? 1 : 0
    const bc = (b.coolUntil ?? 0) > now ? 1 : 0
    if (ac !== bc) return ac - bc
    if (Boolean(a.isUser) !== Boolean(b.isUser)) return a.isUser ? -1 : 1
    return (a.lastLatency ?? 1e9) - (b.lastLatency ?? 1e9)
  })
}

export function subscribe(l: Listener): () => void {
  listeners.add(l)
  return () => listeners.delete(l)
}

export function addInstance(url: string): boolean {
  const u = normalize(url)
  if (!u || list.some((i) => i.url === u)) return false
  list = [{ url: u, isUser: true, version: 'custom' }, ...list]
  persist()
  return true
}

export function removeInstance(url: string) {
  list = list.filter((i) => i.url !== url)
  if (!list.length) list = [...DEFAULT_INSTANCES]
  persist()
}

export function resetInstances() {
  list = [...DEFAULT_INSTANCES]
  persist()
}

export function reportSuccess(url: string, latency: number) {
  const i = list.find((x) => x.url === url)
  if (!i) return
  i.fails = 0
  if (i.coolUntil) {
    i.coolUntil = 0
    persistCooling()
  }
  i.lastLatency = latency
  listeners.forEach((l) => l(list))
}

export function reportFailure(url: string, hard = false) {
  const i = list.find((x) => x.url === url)
  if (!i) return
  i.fails = (i.fails ?? 0) + (hard ? 3 : 1)
  if (i.fails >= 3) {
    i.coolUntil = Date.now() + 90_000
    persistCooling()
  }
  listeners.forEach((l) => l(list))
}

/** True when every mirror is benched, so callers can go straight to the fallback. */
export function allCooling(): boolean {
  const now = Date.now()
  return list.length > 0 && list.every((i) => (i.coolUntil ?? 0) > now)
}

/** Pull the public uptime list and merge anything new. Never removes user instances. */
export async function refreshFromUptime(signal?: AbortSignal): Promise<void> {
  try {
    const res = await fetch(UPTIME, { signal, cache: 'no-store' })
    if (!res.ok) return
    const json = (await res.json()) as { api?: Array<{ url: string; version?: string }> }
    const fresh = (json.api ?? []).map((a) => ({ url: normalize(a.url), version: a.version })).filter((a) => a.url)
    if (!fresh.length) return
    list = dedupe([...list, ...fresh])
    persist()
  } catch {
    /* offline or worker down: keep what we have */
  }
}
