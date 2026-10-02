import './ArtistProfile.css'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { MdClose, MdPerson } from 'react-icons/md'
import { usePlayer } from '../contexts/PlayerContext.jsx'
import Sidebar from '../components/Sidebar.jsx'
import SongList from '../components/SongList.jsx'
import SongListSkeleton from '../components/SongListSkeleton.jsx'
import { musicService } from '../services/musicService.js'
import { resolveMediaUrl } from '../utils/musicNormalizer.js'

function firstText(...values) {
  return values.find((value) => typeof value === 'string' && value.trim())?.trim() || ''
}

function getArtistName(artist) {
  return firstText(artist?.name, artist?.nome, 'Artista')
}

function getArtistPhoto(artist) {
  return resolveMediaUrl(firstText(artist?.photoUrl, artist?.fotoUrl))
}

function getArtistBio(artist) {
  return firstText(artist?.bio, artist?.description)
}

function getUniqueGenres(songs) {
  const genres = songs.map((song) => firstText(song.genre, song.genero))
  return [...new Set(genres.filter(Boolean))]
}

function ArtistProfileContent() {
  const { id } = useParams()
  const { playTrack } = usePlayer()
  const [artist, setArtist] = useState(null)
  const [songs, setSongs] = useState([])
  const [loading, setLoading] = useState(true)
  const [errorStatus, setErrorStatus] = useState(null)

  useEffect(() => {
    let active = true

    Promise.all([
      musicService.getArtistById(id),
      musicService.getMusicsByArtistId(id),
    ])
      .then(([artistData, artistSongs]) => {
        if (!active) return
        setArtist(artistData)
        setSongs(artistSongs)
      })
      .catch((error) => {
        if (!active) return
        console.error('Erro ao carregar o perfil do artista.', error)
        setErrorStatus(error?.status ?? 0)
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => { active = false }
  }, [id])

  const genres = useMemo(() => getUniqueGenres(songs), [songs])

  const playArtist = (shuffle = false) => {
    if (!songs.length) return
    const queue = [...songs]

    if (shuffle) {
      for (let i = queue.length - 1; i > 0; i -= 1) {
        // Playback shuffle does not require cryptographic randomness.
        // eslint-disable-next-line sonarjs/pseudo-random
        const j = Math.floor(Math.random() * (i + 1))
        ;[queue[i], queue[j]] = [queue[j], queue[i]]
      }
    }

    playTrack(queue[0], queue)
  }

  const closeButton = (
    <div className="artist-profile-close-row">
      <Link
        to="/artists"
        className="artist-profile-close-btn"
        aria-label="Voltar para a lista de artistas"
        title="Voltar"
      >
        <MdClose />
      </Link>
    </div>
  )

  if (loading) {
    return (
      <div className="artist-profile-page">
        <Sidebar />
        <main className="artist-profile-main">
          {closeButton}
          <section className="artist-profile-hero artist-profile-hero--loading" aria-busy="true">
            <div className="artist-profile-hero-card">
              <span className="artist-profile-label">Carregando...</span>
              <h1>Aguarde</h1>
            </div>
          </section>
          <SongListSkeleton count={6} />
        </main>
      </div>
    )
  }

  if (!artist) {
    const notFound = errorStatus === 404

    return (
      <div className="artist-profile-page">
        <Sidebar />
        <main className="artist-profile-main">
          {closeButton}
          <div className="artist-profile-message" role="status">
            <h2>{notFound ? 'Artista não encontrado' : 'Não foi possível carregar o artista'}</h2>
            <p>
              {notFound
                ? 'Esse artista não existe ou foi removido do catálogo.'
                : 'Verifique sua conexão e tente novamente em instantes.'}
            </p>
            <Link to="/artists" className="artist-profile-message-link">Ver todos os artistas</Link>
          </div>
        </main>
      </div>
    )
  }

  const name = getArtistName(artist)
  const photo = getArtistPhoto(artist)
  const bio = getArtistBio(artist)
  const songCountLabel = `${songs.length} ${songs.length === 1 ? 'música' : 'músicas'}`

  return (
    <div className="artist-profile-page">
      <Sidebar />

      <main className="artist-profile-main">
        {closeButton}

        <section
          className="artist-profile-hero"
          style={photo ? { backgroundImage: `url("${photo}")` } : undefined}
        >
          <div className="artist-profile-hero-card">
            <span className="artist-profile-label">Artista</span>
            <h1>{name}</h1>
            <p className="artist-profile-count">{songCountLabel}</p>

            <div className="artist-profile-actions">
              <button
                type="button"
                className="artist-profile-play-btn"
                disabled={!songs.length}
                onClick={() => playArtist()}
              >
                ▶ Play
              </button>
              <button
                type="button"
                className="artist-profile-random-btn"
                disabled={!songs.length}
                onClick={() => playArtist(true)}
              >
                ⤨ Aleatório
              </button>
            </div>
          </div>
        </section>

        {songs.length > 0 ? (
          <SongList songs={songs} title="Músicas" />
        ) : (
          <section className="artist-profile-message" role="status">
            <h2>Nenhuma música cadastrada</h2>
            <p>Este artista ainda não tem músicas no catálogo.</p>
          </section>
        )}

        <section className="artist-profile-about" aria-label={`Sobre ${name}`}>
          <h2>Sobre</h2>
          <div className="artist-profile-about-body">
            {photo ? (
              <img className="artist-profile-about-photo" src={photo} alt={`Foto de ${name}`} loading="lazy" />
            ) : (
              <span className="artist-profile-about-photo artist-profile-about-photo--placeholder" aria-hidden="true">
                <MdPerson />
              </span>
            )}

            <div>
              <p className="artist-profile-bio">
                {bio || 'Este artista ainda não tem biografia cadastrada.'}
              </p>

              {genres.length > 0 && (
                <ul className="artist-profile-genres" aria-label="Gêneros">
                  {genres.map((genre) => (
                    <li key={genre}>{genre}</li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </section>
      </main>
    </div>
  )
}

function ArtistProfile() {
  const { id } = useParams()
  return <ArtistProfileContent key={id} />
}

export default ArtistProfile