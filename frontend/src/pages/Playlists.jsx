import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { FaHeart, FaMusic, FaPlus } from 'react-icons/fa'

import './Playlists.css'
import Sidebar from '../components/Sidebar.jsx'
import InlineError from '../components/InlineError.jsx'

const mainPlaylists = [
  {
    title: 'Favoritos',
    description: 'Músicas curtidas por você.',
    icon: <FaHeart />,
    className: 'playlist-main-card--favorites',
  },
]
import { musicService } from '../services/musicService'
import api from '../services/api' // To fetch genres directly if needed
import { normalizeMusic } from '../utils/musicNormalizer'

function Playlists() {
  const [userPlaylistsApi, setUserPlaylistsApi] = useState([])
  const [publicPlaylists, setPublicPlaylists] = useState([])
  const [genresApi, setGenresApi] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let isMounted = true

    Promise.resolve().then(() => {
      if (!isMounted) return
      setIsLoading(true)
      setLoadError('')
    })

    Promise.allSettled([
      musicService.getUserPlaylists(),
      musicService.getPublicPlaylists(),
      api.get('/genres'),
    ])
      .then(([userResult, publicResult, genresResult]) => {
        if (!isMounted) return

        const results = [userResult, publicResult, genresResult]
        if (results.every((result) => result.status === 'rejected')) {
          setUserPlaylistsApi([])
          setPublicPlaylists([])
          setGenresApi([])
          setLoadError('O palco ficou em silêncio: não foi possível carregar as playlists. Tente novamente.')
          return
        }

        setLoadError('')

        if (userResult.status === 'fulfilled') {
          setUserPlaylistsApi(userResult.value)
        }

        if (publicResult.status === 'fulfilled') {
          setPublicPlaylists(Array.from(new Map(
            publicResult.value
              .filter((playlist) => !playlist.canManage)
              .map((playlist) => [String(playlist.id), playlist])
          ).values()))
        }

        if (genresResult.status === 'fulfilled') {
          setGenresApi(genresResult.value.data?.content || genresResult.value.data || [])
        }
      })
      .finally(() => {
        if (isMounted) setIsLoading(false)
      })

    return () => {
      isMounted = false
    }
  }, [reloadKey])
  return (
    <div className="playlists-page">
      <Sidebar />

      <main className="playlists-main">
        <section className="playlists-header">
          <span>PLAYLIST</span>

          <h1>Escolha o que quer ouvir</h1>

          <p>
            Encontre seus favoritos, crie sua própria seleção
            ou explore novos estilos.
          </p>
        </section>

        {/* FAVORITOS + ADD PLAYLIST */}

        <section className="playlists-main-grid">
          {mainPlaylists.map((playlist) => (
            <Link
              to="/favorites"
              className={`playlist-main-card ${playlist.className}`}
              key={playlist.title}
            >
              <div className="playlist-main-icon">
                {playlist.icon}
              </div>

              <div>
                <h2>{playlist.title}</h2>
                <p>{playlist.description}</p>
              </div>
            </Link>
          ))}

<Link
  to="/create-playlist"
  className="playlist-main-card playlist-main-card--add"
>
  <div className="playlist-main-icon">
    <FaPlus />
  </div>

  <div>
    <h2>Adicionar playlist</h2>
    <p>Crie uma nova playlist personalizada.</p>
  </div>
</Link>
        </section>

        {isLoading && (
          <section className="playlists-user-section">
            <div className="playlists-user-grid">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={`skel-${i}`} className="playlist-user-skeleton"></div>
              ))}
            </div>
          </section>
        )}

        {!isLoading && loadError && (
          <InlineError
            message={loadError}
            onRetry={() => setReloadKey((current) => current + 1)}
          />
        )}

        {/* PLAYLISTS DO USUÁRIO */}

        {!isLoading && !loadError && (
        <>
        <section className="playlists-user-section">
          <h2>Suas playlists</h2>

          <div className="playlists-user-grid">
            {userPlaylistsApi.length > 0 ? (
              userPlaylistsApi.map((playlist) => {
                const songs = (playlist.tracks || []).map(normalizeMusic).filter(Boolean)
                const manualCover = playlist.coverUrl || playlist.capaUrl || ''
                const description = playlist.description || playlist.descricao || 'Sem descrição adicionada.'
                const hasGrid = !manualCover && songs.length >= 4
                const singleCover = manualCover || songs[0]?.coverUrl || ''

                return (
                  <Link
                    to={`/user-playlist-detail/${playlist.id}`}
                    className={`playlist-user-card ${hasGrid ? 'playlist-has-grid' : ''}`}
                    key={playlist.id}
                    style={!hasGrid && singleCover ? { backgroundImage: `url(${singleCover})` } : {}}
                  >
                    {hasGrid && (
                      <div className="playlist-grid-bg">
                        {songs.slice(0, 4).map((song, index) => (
                          song.coverUrl ? (
                            <img key={song.id || `${song.title}-${index}`} src={song.coverUrl} alt="" />
                          ) : (
                            <div key={song.id || `${song.title}-${index}`} className="playlist-grid-bg-placeholder" aria-hidden="true">
                              <FaMusic />
                            </div>
                          )
                        ))}
                      </div>
                    )}
                    {!hasGrid && !singleCover && (
                      <div className="playlist-user-card-placeholder" aria-hidden="true">
                        <FaMusic />
                      </div>
                    )}
                    <div className="playlist-user-card-content">
                      <h3>{playlist.name}</h3>
                      <p>{description}</p>
                    </div>
                  </Link>
                )
              })
            ) : (
              <p>Você ainda não tem nenhuma playlist criada.</p>
            )}
          </div>
        </section>

        <section className="playlists-user-section">
          <h2>Playlists públicas</h2>
          <div className="playlists-user-grid">
            {publicPlaylists.length === 0 && <p>Nenhuma playlist pública disponível.</p>}
            {publicPlaylists.map(playlist => (
                <Link
                  to={`/user-playlist-detail/${playlist.id}`}
                  className="playlist-user-card"
                  key={playlist.id}
                  style={playlist.coverUrl ? { backgroundImage: `url(${playlist.coverUrl})` } : {}}
                >
                  {!playlist.coverUrl && <div className="playlist-user-card-placeholder" aria-hidden="true"><FaMusic /></div>}
                  <div className="playlist-user-card-content">
                    <h3>{playlist.name}</h3>
                    <p>{playlist.description || 'Sem descrição adicionada.'}</p>
                  </div>
                </Link>
              ))}
          </div>
        </section>

        {/* EXPLORE */}

        <section className="playlists-explore">
          <div className="playlists-section-title">
            <h2>Explore novos estilos</h2>
          </div>

          <div className="playlists-grid">
            {genresApi.length > 0 ? (
              genresApi.map((genre, idx) => {
                // Pick a random preset class for colors just for style
                const colorClasses = ['sertanejo', 'gospel', 'kpop', 'samba', 'pagode', 'pop', 'rock', 'mpb']
                const presetClass = `playlist-card--${colorClasses[idx % colorClasses.length]}`

                return (
                  <Link
                    className={`playlist-card ${presetClass}`}
                    key={genre.id || genre.name}
                    to={`/home?genre=${encodeURIComponent(genre.name)}`}
                  >
                    <h3>{genre.name}</h3>
                  </Link>
                )
              })
            ) : (
              <p>Nenhum estilo encontrado.</p>
            )}
          </div>
        </section>
        </>
        )}
      </main>
    </div>
  )
}

export default Playlists
