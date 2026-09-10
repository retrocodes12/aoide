import { Link } from 'react-router-dom'
import { useDocumentTitle } from '../lib/hooks'
import { Empty } from '../components/Common'

export function NotFound() {
  useDocumentTitle('Page not found')
  return <div className="page"><Empty title="Page not found" body="We can't seem to find the page you are looking for." action={<Link to="/" className="pill pill--white">Home</Link>} /></div>
}
