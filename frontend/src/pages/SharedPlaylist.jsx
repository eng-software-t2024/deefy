import { useEffect, useState } from 'react'
import { MdCheckCircle, MdLibraryMusic, MdLockOpen } from 'react-icons/md'
import { useNavigate, useParams } from 'react-router-dom'

import { musicService } from '../services/musicService.js'
import { isAuthenticated, setIntendedRoute } from '../utils/auth.js'
import { showMusicError, showMusicSuccess } from '../utils/musicToast'
import { normalizeMusic } from '../utils/musicNormalizer.js'

import './SharedPlaylist.css'

function SharedPlaylist() {
  const { token } = useParams()
  const navigate = useNavigate()
  const [playlist, setPlaylist] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isAccepting, setIsAccepting] = useState(false)
  const [accepted, setAccepted] = useState(false)

  useEffect(() => {
    let active = true
    setIsLoading(true)
    musicService.getSharedPlaylist(token)
      .then((data) => {
        if (active) setPlaylist(data)
      })
      .catch(() => {
        if (active) setPlaylist(null)
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })

    return () => { active = false }
  }, [token])

  const handleAccept = async () => {
    if (!isAuthenticated()) {
      setIntendedRoute(`/shared-playlist/${token}`)
      navigate('/login')
      return
    }

    try {
      setIsAccepting(true)
      await musicService.acceptSharedPlaylist(token)
      setAccepted(true)
      showMusicSuccess('Convite aceito com sucesso.')
    } catch (error) {
      showMusicError(error?.message || 'Não foi possível aceitar o convite.')
    } finally {
      setIsAccepting(false)
    }
  }

  const openPlaylist = () => navigate(`/user-playlist-detail/${playlist.id}`)
  const songs = (playlist?.tracks || []).map(normalizeMusic).filter(Boolean)

  if (isLoading) {
    return <main className="shared-playlist-page"><p className="shared-playlist-status">Carregando convite...</p></main>
  }

  if (!playlist) {
    return (
      <main className="shared-playlist-page">
        <section className="shared-playlist-card shared-playlist-card--error">
          <MdLockOpen />
          <h1>Link indisponível</h1>
          <p>Este link é inválido ou o compartilhamento foi desativado.</p>
          <button type="button" onClick={() => navigate('/home')}>Voltar para o Deefy</button>
        </section>
      </main>
    )
  }

  return (
    <main className="shared-playlist-page">
      <section className="shared-playlist-card">
        <div className="shared-playlist-brand">DEEFY / CONVITE</div>
        <div className="shared-playlist-art"><MdLibraryMusic /></div>
        <span className="shared-playlist-label">PLAYLIST COMPARTILHADA</span>
        <h1>{playlist.name}</h1>
        {playlist.description && <p className="shared-playlist-description">{playlist.description}</p>}
        <p className="shared-playlist-meta">{songs.length} {songs.length === 1 ? 'música' : 'músicas'} disponíveis</p>

        {songs.length > 0 && (
          <div className="shared-playlist-track-preview">
            {songs.slice(0, 4).map((song) => <span key={song.id}>{song.title}</span>)}
            {songs.length > 4 && <span>+{songs.length - 4} outras</span>}
          </div>
        )}

        {accepted ? (
          <button type="button" className="shared-playlist-accept-button" onClick={openPlaylist}>
            <MdCheckCircle /> Abrir playlist
          </button>
        ) : (
          <button type="button" className="shared-playlist-accept-button" onClick={handleAccept} disabled={isAccepting}>
            <MdLockOpen /> {isAccepting ? 'Aceitando...' : 'Aceitar convite'}
          </button>
        )}
        {!isAuthenticated() && <small className="shared-playlist-login-hint">Você precisará entrar ou criar uma conta para aceitar.</small>}
      </section>
    </main>
  )
}

export default SharedPlaylist
