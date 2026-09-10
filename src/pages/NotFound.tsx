import { Link, useNavigate } from 'react-router-dom'
import { useDocumentTitle } from '../lib/hooks'
import { Empty } from '../components/Common'
import { IChevronLeft } from '../components/Icons'

export function NotFound() {
  useDocumentTitle('Page not found')
  const nav = useNavigate()
  return (
    <div className="page page--center">
      <div className="topbar"><button className="iconbtn" aria-label="Back" onClick={() => (window.history.length > 1 ? nav(-1) : nav('/'))}><IChevronLeft /></button></div>
      <div className="page__center"><Empty title="Page not found" body="We can't seem to find the page you are looking for." action={<Link to="/" className="pill pill--white">Home</Link>} /></div>
    </div>
  )
}
