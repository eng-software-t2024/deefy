import { useState, useEffect } from 'react'
import { FaArrowLeft, FaMinus, FaMusic, FaPause, FaPlay, FaPlus, FaSearch } from 'react-icons/fa'
import { Link, useNavigate, useParams } from 'react-router-dom'
import './AddMusicToPlaylist.css'
import { usePlayer } from '../contexts/PlayerContext.jsx'
import { recordListeningSignal } from '../utils/recommendationEngine.js'

import Sidebar from '../components/Sidebar.jsx'
import SongListSkeleton from '../components/SongListSkeleton.jsx'
import { musicService } from '../services/musicService.js'
import { useDebounce } from '../hooks/useDebounce.js'
import { showMusicSuccess, showMusicError } from '../utils/musicToast'
import { normalizeMusic } from '../utils/musicNormalizer.js'

function getSongKey(song) {
  const normalizedSong = normalizeMusic(song)
  if (!normalizedSong?.id && normalizedSong?.id !== 0) return null
  return String(normalizedSong.id)
}

function uniqueSongs(songs) {
  return Array.from(new Map(
    songs
      .map(normalizeMusic)
      .filter(Boolean)
      .map((music, index) => [getSongKey(music) || `${music.title}-${music.artist}-${index}`, music])
  ).values())
}

function AddMusicToPlaylistContent() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [editableId, setEditableId] = useState(null)
  const canEdit = editableId === id
  const [search, setSearch] = useState('')
  const debouncedQuery = useDebounce(search, 300)
  
  const [results, setResults] = useState([])
  const [isLoading, setIsLoading] = useState(false)
  const [addingId, setAddingId] = useState(null)
  const [removingId, setRemovingId] = useState(null)
  const [addedIds, setAddedIds] = useState(() => new Set())
  const [addedSongs, setAddedSongs] = useState([])
  const [playlistName, setPlaylistName] = useState('')
  const { currentTrack, playTrack, togglePlay, isPlaying } = usePlayer()

  const handlePlaySong = (song) => {
    const songKey = getSongKey(song)
    const currentKey = currentTrack ? String(currentTrack.id) : null

    if (songKey && currentKey === songKey) {
      togglePlay()
      return
    }

    recordListeningSignal(song)
    playTrack(song, results)
  }


  useEffect(() => {
    if (!id) return

    let isMounted = true

    musicService.getPlaylistById(id)
      .then((playlist) => {
        if (!isMounted) return
        if (!playlist.canManage) {
          navigate(`/user-playlist-detail/${id}`, { replace: true })
          return
        }

        setEditableId(id)
        setPlaylistName(playlist.name || playlist.nome || '')
        const existingIds = (playlist.tracks || [])
          .map(getSongKey)
          .filter(Boolean)
        setAddedIds(new Set(existingIds))
      })
      .catch((err) => {
        if (!isMounted) return
        console.error('Erro ao buscar músicas da playlist', err)
        navigate('/playlists', { replace: true })
      })

    return () => { isMounted = false }
  }, [id, navigate])

  useEffect(() => {
    let isMounted = true
    setIsLoading(true)

    if (!debouncedQuery.trim()) {
      musicService.getHomeMusics(12)
        .then((data) => {
          if (!isMounted) return
          setResults(uniqueSongs(data || []))
        })
        .catch((err) => {
          if (!isMounted) return
          console.error('Erro ao buscar recomendações', err)
          setResults([])
        })
        .finally(() => {
          if (isMounted) setIsLoading(false)
        })

      return () => { isMounted = false }
    }

    musicService.searchMusicsSmart(debouncedQuery, {
      fields: ['title', 'artist', 'album', 'genre'],
    }).then((data) => {
      if (!isMounted) return;
      setResults(uniqueSongs(data || []));
    }).catch((err) => {
      if (!isMounted) return;
      console.error('Erro ao buscar músicas', err);
      setResults([]);
    }).finally(() => {
      if (isMounted) setIsLoading(false);
    })

    return () => { isMounted = false }
  }, [debouncedQuery])

  async function handleAdd(song) {
    if (!id || !canEdit) return;

    const songKey = getSongKey(song)

    if (!songKey) {
      showMusicError("Não foi possível identificar esta música.")
      return
    }

    if (addedIds.has(songKey)) return

    try {
      setAddingId(songKey)
      await musicService.addMusicToPlaylist(id, song)
      setAddedIds((currentIds) => new Set(currentIds).add(songKey))
      setAddedSongs((prev) => {
        const alreadyExists = prev.some((s) => getSongKey(s) === songKey)
        return alreadyExists ? prev : [...prev, song]
      })
      showMusicSuccess("Música adicionada à playlist!")
    } catch (err) {
      const status = err?.status || err?.response?.status
      const errorMsg =
        err?.response?.data?.messages?.[0] ||
        err?.response?.data?.message ||
        err?.message ||
        ""
      const isDuplicate =
        status === 409 ||
        (status === 400 && errorMsg.includes("já está presente"))

      if (isDuplicate) {
        setAddedIds((currentIds) => new Set(currentIds).add(songKey))
        showMusicError("Essa música já está na playlist.")
      } else {
        showMusicError(errorMsg || "Erro ao adicionar música.")
      }
    } finally {
      setAddingId(null)
    }
  }

  async function handleRemove(song) {
    if (!id || !canEdit) return
    const songKey = getSongKey(song)
    if (!songKey) return

    try {
      setRemovingId(songKey)
      await musicService.removeMusicFromPlaylist(id, songKey)
      setAddedIds((currentIds) => {
        const nextIds = new Set(currentIds)
        nextIds.delete(songKey)
        return nextIds
      })
      setAddedSongs((currentSongs) => currentSongs.filter((item) => getSongKey(item) !== songKey))
      showMusicSuccess("Música removida da playlist!")
    } catch (err) {
      showMusicError(err?.response?.data?.message || "Erro ao remover música.")
    } finally {
      setRemovingId(null)
    }
  }

  if (!canEdit) {
    return (
      <div className="add-music-page">
        <Sidebar />
        <main className="add-music-main"><p>Carregando playlist...</p></main>
      </div>
    )
  }

  return (
    <div className="add-music-page">
      <Sidebar />

      <main className="add-music-main">
        <section className="add-music-header">
          <div className="add-music-header-top">
            <span>ADICIONAR MÚSICAS</span>
          </div>

          <h1>Adicione novas musicas à playlist {playlistName ? `"${playlistName}"` : 'esta playlist'}</h1>
          <Link to={`/user-playlist-detail/${id}`} className="add-music-back">
            <FaArrowLeft />
            <span>Voltar para playlist</span>
          </Link>
        </section>

        <section className="add-music-search">
          <FaSearch />
          <input
            type="text"
            placeholder="Buscar música, artista ou álbum..."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </section>

        <section className="add-music-section">
          <h2>{debouncedQuery ? "Resultados da busca" : "Recomendações para você"}</h2>

          {isLoading ? (
            <div style={{ marginTop: '20px' }}>
              <SongListSkeleton count={5} />
            </div>
          ) : (
            <div className="add-music-list">
              {/* Músicas não adicionadas primeiro */}
              {results
                .filter((song) => !addedIds.has(getSongKey(song)))
                .map((song, index) => {
                  const songKey = getSongKey(song)
                  const isAdding = addingId === songKey
                  const isActive = Boolean(songKey && String(currentTrack?.id ?? '') === songKey)
                  const isSongPlaying = isActive && isPlaying

                  return (
                    <article
                      className={`add-music-card${isActive ? ' is-active' : ''}`}
                      key={songKey || `${song.title}-${index}`}
                      onClick={() => handlePlaySong(song)}
                    >
                      <div className="add-music-info">
                        <div className="add-music-info-body">
                          {song.coverUrl ? (
                            <img className="add-music-cover" src={song.coverUrl} alt={`Capa de ${song.title || 'música'}`} />
                          ) : (
                            <span className="add-music-cover-placeholder" aria-hidden="true">
                              <FaMusic />
                            </span>
                          )}
                          <div className="add-music-play">
                            {isSongPlaying ? <FaPause /> : <FaPlay />}
                          </div>
                        </div>
                        <div>
                          <h3 style={{ color: isActive ? '#39f0d0' : undefined }}>
                            {song.title || 'Título não informado'}
                          </h3>
                          <p>{song.artist || 'Artista não informado'}</p>
                        </div>
                      </div>
                      <span className="add-music-album">{song.album || 'Álbum não informado'}</span>
                      <span className="add-music-duration">{song.duration || '--:--'}</span>
                      <button
                        className="add-music-button"
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation()
                          handleAdd(song)
                        }}
                        disabled={isAdding}
                      >
                        <FaPlus />
                        {isAdding ? "..." : "Adicionar"}
                      </button>
                    </article>
                  )
                })}

              {debouncedQuery && results.filter((s) => !addedIds.has(getSongKey(s))).length === 0 && (
                <p>Nenhuma música encontrada.</p>
              )}

              {!debouncedQuery && results.filter((s) => !addedIds.has(getSongKey(s))).length === 0 && (
                <p>Nenhuma recomendação disponível agora.</p>
              )}

              {/* Seção de músicas já adicionadas (persistente no final) */}
              {addedSongs.length > 0 && (
                <>
                  <div className="add-music-added-divider">
                    <span>Adicionadas nesta sessão — {addedSongs.length} {addedSongs.length === 1 ? 'música' : 'músicas'}</span>
                  </div>
                  {addedSongs.map((song, index) => {
                    const songKey = getSongKey(song)
                    const isActive = Boolean(songKey && String(currentTrack?.id ?? '') === songKey)
                    const isSongPlaying = isActive && isPlaying

                    return (
                      <article
                        className={`add-music-card is-added${isActive ? ' is-active' : ''}`}
                        key={`added-${songKey || index}`}
                        onClick={() => handlePlaySong(song)}
                      >
                        <div className="add-music-info">
                          <div className="add-music-info-body">
                            {song.coverUrl ? (
                              <img className="add-music-cover" src={song.coverUrl} alt={`Capa de ${song.title || 'música'}`} />
                            ) : (
                              <span className="add-music-cover-placeholder" aria-hidden="true">
                                <FaMusic />
                              </span>
                            )}
                            <div className="add-music-play">
                              {isSongPlaying ? <FaPause /> : <FaPlay />}
                            </div>
                          </div>
                          <div>
                            <h3 style={{ color: isActive ? '#39f0d0' : undefined }}>
                              {song.title || 'Título não informado'}
                            </h3>
                            <p>{song.artist || 'Artista não informado'}</p>
                          </div>
                        </div>
                        <span className="add-music-album">{song.album || 'Álbum não informado'}</span>
                        <span className="add-music-duration">{song.duration || '--:--'}</span>
                        <button
                          className="add-music-button add-music-button-remove"
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation()
                            handleRemove(song)
                          }}
                          disabled={removingId === songKey}
                        >
                          <FaMinus />
                          {removingId === songKey ? "..." : "Remover"}
                        </button>
                      </article>
                    )
                  })}
                </>
              )}
            </div>
          )}
        </section>
      </main>
    </div>
  )
}

function AddMusicToPlaylist() {
  const { id } = useParams()
  return <AddMusicToPlaylistContent key={id || "new"} />
}

export default AddMusicToPlaylist
