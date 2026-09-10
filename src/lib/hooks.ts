import { useEffect, useRef, useState } from 'react'
import { mirrorsDown, subscribe as subscribeInstances } from './api/instances'

export interface Resource<T> {
  data: T | null
  error: Error | null
  loading: boolean
  reload: () => void
}

/** Run an async loader tied to its deps; aborts stale runs, exposes reload. */
export function useResource<T>(loader: (signal: AbortSignal) => Promise<T>, deps: unknown[]): Resource<T> {
  const [state, setState] = useState<{ data: T | null; error: Error | null; loading: boolean }>({ data: null, error: null, loading: true })
  const [tick, setTick] = useState(0)
  const loaderRef = useRef(loader)
  loaderRef.current = loader
  useEffect(() => {
    const c = new AbortController()
    setState((s) => ({ ...s, loading: true, error: null }))
    loaderRef
      .current(c.signal)
      .then((data) => !c.signal.aborted && setState({ data, error: null, loading: false }))
      .catch((e: unknown) => {
        if (c.signal.aborted) return
        setState({ data: null, error: e instanceof Error ? e : new Error(String(e)), loading: false })
      })
    return () => c.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  return { ...state, reload: () => setTick((t) => t + 1) }
}

export function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}

export function useMediaQuery(query: string): boolean {
  const [m, setM] = useState(() => (typeof window !== 'undefined' ? window.matchMedia(query).matches : false))
  useEffect(() => {
    const mq = window.matchMedia(query)
    const fn = () => setM(mq.matches)
    mq.addEventListener('change', fn)
    return () => mq.removeEventListener('change', fn)
  }, [query])
  return m
}

export function useDocumentTitle(title: string) {
  useEffect(() => {
    const prev = document.title
    document.title = title ? `${title} · Sleeve` : 'Sleeve'
    return () => {
      document.title = prev
    }
  }, [title])
}

/** True while every mirror is benched and the app is browsing TIDAL's catalogue directly. */
export function useMirrorsDown(): boolean {
  const [down, setDown] = useState(() => mirrorsDown())
  useEffect(() => {
    const unsub = subscribeInstances(() => setDown(mirrorsDown()))
    const t = setInterval(() => setDown(mirrorsDown()), 15000)
    return () => {
      unsub()
      clearInterval(t)
    }
  }, [])
  return down
}
