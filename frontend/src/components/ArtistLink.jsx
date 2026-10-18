import { useEffect, useState } from 'react'
import { Link, useInRouterContext } from 'react-router-dom'
import { loadArtistIdsByName, normalizeArtistName } from '../utils/artistLookup.js'
import './ArtistLink.css'

function stop(event) {
  event.stopPropagation()
}

function ResolvedArtistLink({ name, onNavigate }) {
  const key = normalizeArtistName(name)
  const [resolved, setResolved] = useState({ key: '', id: null })

  useEffect(() => {
    let active = true

    loadArtistIdsByName()
      .then((idsByName) => {
        if (active) setResolved({ key, id: idsByName.get(key) ?? null })
      })
      .catch((error) => {
        console.error('Não foi possível carregar os artistas para o link.', error)
      })

    return () => { active = false }
  }, [key])

  const artistId = resolved.key === key ? resolved.id : null

  if (artistId == null) return <>{name}</>

  return (
    <Link
      to={`/artists/${artistId}`}
      className="artist-link"
      title={`Ver perfil de ${name}`}
      onClick={(event) => {
        event.stopPropagation()
        onNavigate?.()
      }}
      onKeyDown={stop}
      onPointerDown={stop}
      onTouchStart={stop}
    >
      {name}
    </Link>
  )
}


function ArtistLink({ name, onNavigate }) {
  const inRouter = useInRouterContext()

  if (!name) return null
  if (!inRouter) return <>{name}</>

  return <ResolvedArtistLink name={name} onNavigate={onNavigate} />
}

export default ArtistLink