import type { SVGProps } from 'react'

type P = SVGProps<SVGSVGElement> & { size?: number }
const base = (size = 16) => ({ width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true })

export const IPlay = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M7 4.5v15l12-7.5z" fill="currentColor" stroke="none" /></svg>
)
export const IPause = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M7 4.5h3.5v15H7zM13.5 4.5H17v15h-3.5z" fill="currentColor" stroke="none" /></svg>
)
export const IPrev = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M6 5v14" /><path d="M18 5.5v13L8 12z" fill="currentColor" stroke="none" /></svg>
)
export const INext = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M18 5v14" /><path d="M6 5.5v13L16 12z" fill="currentColor" stroke="none" /></svg>
)
export const IShuffle = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M3 7h3.5l9 10H20M3 17h3.5l2.5-2.8M13 9.8 15.5 7H20M18 4.5 20.5 7 18 9.5M18 14.5l2.5 2.5-2.5 2.5" /></svg>
)
export const IRepeat = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M17 3.5 20 6.5l-3 3M4 12V9a2.5 2.5 0 0 1 2.5-2.5H20M7 20.5l-3-3 3-3M20 12v3a2.5 2.5 0 0 1-2.5 2.5H4" /></svg>
)
export const IRepeatOne = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M17 3.5 20 6.5l-3 3M4 12V9a2.5 2.5 0 0 1 2.5-2.5H20M7 20.5l-3-3 3-3M20 12v3a2.5 2.5 0 0 1-2.5 2.5H4" /><path d="M11 10.5l1.5-1v5" /></svg>
)
export const IHeart = ({ size, filled, ...p }: P & { filled?: boolean }) => (
  <svg {...base(size)} {...p}><path d="M12 20s-7.5-4.6-7.5-10A4 4 0 0 1 12 7.6 4 4 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z" fill={filled ? 'currentColor' : 'none'} /></svg>
)
export const IPlus = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M12 5v14M5 12h14" /></svg>
)
export const ICheck = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="m5 12.5 4.5 4.5L19 7" /></svg>
)
export const IQueue = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 6h16M4 12h10M4 18h10M17 14l4 2.5-4 2.5z" /></svg>
)
export const ILyrics = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M5 5h14v10H10l-4 4v-4H5z" /><path d="M8 9h8M8 12h5" /></svg>
)
export const ISearch = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="10.5" cy="10.5" r="6" /><path d="m15.5 15.5 4.5 4.5" /></svg>
)
export const IVolume = ({ size, level = 1, ...p }: P & { level?: number }) => (
  <svg {...base(size)} {...p}><path d="M4 9.5v5h3l4.5 3.5v-12L7 9.5z" fill="currentColor" stroke="none" />{level > 0 && <path d="M15 9.5a3.5 3.5 0 0 1 0 5" />}{level > 0.55 && <path d="M17.5 7a7 7 0 0 1 0 10" />}</svg>
)
export const IMute = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 9.5v5h3l4.5 3.5v-12L7 9.5z" fill="currentColor" stroke="none" /><path d="m15 9.5 5 5M20 9.5l-5 5" /></svg>
)
export const IClose = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M6 6l12 12M18 6 6 18" /></svg>
)
export const IChevronDown = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="m6 9 6 6 6-6" /></svg>
)
export const IArrow = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M5 12h14M13 6l6 6-6 6" /></svg>
)
export const IMore = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="6" cy="12" r="1.2" fill="currentColor" stroke="none" /><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none" /><circle cx="18" cy="12" r="1.2" fill="currentColor" stroke="none" /></svg>
)
export const IHome = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 11.5 12 5l8 6.5V19H4z" /></svg>
)
export const ILibrary = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M5 5v14M10 5v14M15 6l4.5 13" /></svg>
)
export const IDrag = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M8 7h.01M8 12h.01M8 17h.01M16 7h.01M16 12h.01M16 17h.01" strokeWidth="2.5" /></svg>
)
export const ITrash = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M5 7h14M9 7V5h6v2M8 7l.8 12h6.4L16 7" /></svg>
)
export const ISettings = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 7h10M18 7h2M4 17h4M12 17h8" /><circle cx="16" cy="7" r="2" /><circle cx="10" cy="17" r="2" /></svg>
)
export const IExpand = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M14 5h5v5M10 19H5v-5M19 5l-6 6M5 19l6-6" /></svg>
)
export const IExternal = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M14 5h5v5M19 5l-8 8M17 13v6H5V7h6" /></svg>
)
export const IClock = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></svg>
)
export const IBack = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="m14.5 6-6 6 6 6" /></svg>
)
export const IForward = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="m9.5 6 6 6-6 6" /></svg>
)
export const INowPlaying = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><rect x="4" y="5" width="16" height="12" rx="1.5" /><path d="M9 20h6" /><path d="M10 9.5v3l3-1.5z" fill="currentColor" stroke="none" /></svg>
)
export const IFullscreen = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5" /></svg>
)
export const IChevronRight = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="m9 6 6 6-6 6" /></svg>
)
export const IChevronLeft = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="m15 6-6 6 6 6" /></svg>
)
export const IRemove = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="12" cy="12" r="8.5" /><path d="M8.5 12h7" /></svg>
)
export const IAlbum = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="12" cy="12" r="8.5" /><circle cx="12" cy="12" r="2.5" /></svg>
)
export const IPerson = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><circle cx="12" cy="8.5" r="3.5" /><path d="M5 19.5c1.2-3.3 3.6-5 7-5s5.8 1.7 7 5" /></svg>
)
export const IPlaylistAdd = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="M4 7h12M4 12h12M4 17h7M17 14v6M14 17h6" /></svg>
)
export const IPencil = ({ size, ...p }: P) => (
  <svg {...base(size)} {...p}><path d="m4 20 4.5-1 10-10-3.5-3.5-10 10z" /><path d="m13 7.5 3.5 3.5" /></svg>
)
