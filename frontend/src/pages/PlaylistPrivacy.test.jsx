import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import UserPlaylistDetail from './UserPlaylistDetail'
import PlaylistDetail from './PlaylistDetail'
import Playlists from './Playlists'
import CreatePlaylist from './CreatePlaylist'
import AddMusicToPlaylist from './AddMusicToPlaylist'
import { useMusicSearch } from '../hooks/useMusicSearch'
import { musicService } from '../services/musicService'

const { playTrack } = vi.hoisted(() => ({ playTrack: vi.fn() }))
vi.mock('../components/Sidebar.jsx', () => ({ default: () => null }))
vi.mock('../services/api', () => ({ default: { get: vi.fn().mockResolvedValue({ data: [] }) } }))
vi.mock('../contexts/PlayerContext.jsx', () => ({ usePlayer: () => ({ playTrack, currentTrack: null, togglePlay: vi.fn() }) }))
vi.mock('../services/musicService', () => ({
  FAVORITE_MUSIC_CHANGED_EVENT: 'favorite-test',
  musicService: {
    getPlaylistById: vi.fn(), getPublicPlaylists: vi.fn(), getUserPlaylists: vi.fn(), getGlobalPlaylists: vi.fn(),
    getFavoriteMusics: vi.fn(), getHomeMusics: vi.fn(), deletePlaylist: vi.fn(), updatePlaylist: vi.fn(),
    addMusicToPlaylist: vi.fn(), removeMusicFromPlaylist: vi.fn(), createPlaylist: vi.fn(),
  },
}))
const track = { id: 10, title: 'Faixa teste', artist: 'Artista', audioUrl: 'https://example.com/audio.mp3' }
const publicPlaylist = { id: 1, name: 'Seleção pública', publica: true, canManage: false, tracks: [track] }
const ownPlaylist = { ...publicPlaylist, id: 2, name: 'Seleção privada própria', publica: false, canManage: true }
function open(Component, initial = '/details/1') {
  const router = createMemoryRouter([
    { path: '/details/:id', element: <Component /> },
    { path: '/create-playlist', element: <Component /> },
    { path: '/user-playlist-detail/:id', element: <UserPlaylistDetail /> },
    { path: '/playlists', element: <p>Lista de playlists</p> },
  ], { initialEntries: [initial] })
  const view = render(<RouterProvider router={router} />)
  return { ...view, router }
}
function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
beforeEach(() => {
  vi.clearAllMocks()
  musicService.getPlaylistById.mockReset().mockResolvedValue(publicPlaylist)
  musicService.getUserPlaylists.mockResolvedValue([ownPlaylist])
  musicService.getPublicPlaylists.mockResolvedValue([publicPlaylist])
  musicService.getGlobalPlaylists.mockResolvedValue([])
  musicService.getFavoriteMusics.mockResolvedValue([])
  musicService.getHomeMusics.mockResolvedValue([])
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

it('lists others public playlists once and keeps own playlists in their section', async () => {
  musicService.getPublicPlaylists.mockResolvedValue([publicPlaylist, publicPlaylist, { ...ownPlaylist, publica: true }])
  open(Playlists)
  await screen.findByText('Seleção pública')
  expect(screen.getAllByText('Seleção pública')).toHaveLength(1)
  expect(screen.getAllByText('Seleção privada própria')).toHaveLength(1)
  expect(screen.getByText('Playlists públicas')).toBeTruthy()
})

it('searches public, own and global playlists without duplicates and ignores accents/case', async () => {
  const global = { ...publicPlaylist, id: 3, name: 'Seleção global' }
  musicService.getGlobalPlaylists.mockResolvedValue([global])
  musicService.getPublicPlaylists.mockResolvedValue([publicPlaylist, global, { ...ownPlaylist, publica: true }])
  const { result } = renderHook(() => useMusicSearch('SELECAO', 'playlists'))
  await waitFor(() => expect(result.current.playlistResults).toHaveLength(3))
  expect(result.current.playlistResults.map(p => p.id).sort()).toEqual([1, 2, 3])
})

it('allows visitor playback but hides playlist management and removal', async () => {
  const { container } = open(UserPlaylistDetail)
  await screen.findByText(publicPlaylist.name)
  expect(screen.queryByRole('button', { name: 'Mais ações da playlist' })).toBeNull()
  expect(screen.queryByRole('link', { name: 'Adicionar mais músicas' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Reproduzir playlist' }))
  expect(playTrack).toHaveBeenCalledWith(expect.objectContaining({ id: 10 }), [expect.objectContaining({ id: 10 })])
  fireEvent.click(screen.getByText(track.title))
  expect(playTrack).toHaveBeenCalledTimes(2)
  fireEvent.click(container.querySelector('.song-options-button'))
  expect(screen.queryByText('Remover da playlist')).toBeNull()
  fireEvent.click(screen.getByText('Adicionar à playlist'))
  await screen.findByText(ownPlaylist.name)
  expect(musicService.getUserPlaylists).toHaveBeenCalled()
  expect(musicService.getPublicPlaylists).not.toHaveBeenCalled()
  expect(musicService.addMusicToPlaylist).not.toHaveBeenCalled()
})

it('retains owner management and removal, and labels private playlists correctly', async () => {
  musicService.getPlaylistById.mockResolvedValue(ownPlaylist)
  const { container } = open(UserPlaylistDetail, '/details/2')
  await screen.findByText(ownPlaylist.name)
  expect(screen.getByText('PLAYLIST PRIVADA')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Mais ações da playlist' }))
  expect(screen.getByRole('link', { name: 'Adicionar mais músicas' }).getAttribute('href')).toBe('/playlist/2/add-music')
  expect(screen.getByText('Editar playlist')).toBeTruthy()
  expect(screen.getByText('Excluir playlist')).toBeTruthy()
  fireEvent.click(container.querySelector('.song-options-button'))
  expect(screen.getByText('Remover da playlist')).toBeTruthy()
})

it('connects global detail play and shuffle without mutating tracks', async () => {
  open(PlaylistDetail)
  await screen.findByText(publicPlaylist.name)
  fireEvent.click(screen.getByRole('button', { name: /Play/ }))
  fireEvent.click(screen.getByRole('button', { name: /Iniciar Aleatoriamente/ }))
  expect(playTrack).toHaveBeenCalledTimes(2)
  expect(publicPlaylist.tracks).toEqual([track])
  expect(musicService.updatePlaylist).not.toHaveBeenCalled()
})

for (const Component of [UserPlaylistDetail, PlaylistDetail]) {
  describe(Component.name, () => {
    it('clears previous data immediately on navigation and after denial, including return to prior id', async () => {
      const { router } = open(Component)
      await screen.findByText(publicPlaylist.name)
      musicService.getPlaylistById.mockRejectedValue(new Error('Acesso negado'))
      await act(() => router.navigate('/details/99'))
      await screen.findByText('Playlist não encontrada.')
      expect(screen.queryByText(publicPlaylist.name)).toBeNull()
      expect(screen.queryByText(track.title)).toBeNull()
      await act(() => router.navigate('/details/1'))
      await screen.findByText('Playlist não encontrada.')
      expect(screen.queryByText(publicPlaylist.name)).toBeNull()
    })
    it('ignores an earlier request that resolves after a denied route', async () => {
      const pending = deferred()
      musicService.getPlaylistById.mockReturnValueOnce(pending.promise).mockRejectedValue(new Error('Acesso negado'))
      const { router } = open(Component)
      await act(() => router.navigate('/details/99'))
      await screen.findByText('Playlist não encontrada.')
      await act(async () => pending.resolve(publicPlaylist))
      expect(screen.queryByText(publicPlaylist.name)).toBeNull()
    })
  })
}

for (const Component of [CreatePlaylist, AddMusicToPlaylist]) {
  it(`blocks direct management route for visitors: ${Component.name}`, async () => {
    const { router } = open(Component)
    await waitFor(() => expect(router.state.location.pathname).toBe('/user-playlist-detail/1'))
    expect(musicService.updatePlaylist).not.toHaveBeenCalled()
    expect(musicService.addMusicToPlaylist).not.toHaveBeenCalled()
  })
  it(`does not display private data in management route: ${Component.name}`, async () => {
    musicService.getPlaylistById.mockRejectedValue(new Error('Acesso negado'))
    const { router } = open(Component)
    await waitFor(() => expect(router.state.location.pathname).toBe('/playlists'))
    expect(screen.queryByText(publicPlaylist.name)).toBeNull()
  })
}

it('keeps the owner edit form and save operation available', async () => {
  musicService.getPlaylistById.mockResolvedValue(ownPlaylist)
  open(CreatePlaylist, '/details/2')
  await screen.findByDisplayValue(ownPlaylist.name)
  fireEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }))
  await waitFor(() => expect(musicService.updatePlaylist).toHaveBeenCalledWith('2', expect.objectContaining({ name: ownPlaylist.name, publica: false })))
})
it('keeps adding songs available to the owner', async () => {
  musicService.getPlaylistById.mockResolvedValue({ ...ownPlaylist, tracks: [] })
  musicService.getHomeMusics.mockResolvedValue([track])
  open(AddMusicToPlaylist, '/details/2')
  fireEvent.click(await screen.findByRole('button', { name: 'Adicionar' }))
  await waitFor(() => expect(musicService.addMusicToPlaylist).toHaveBeenCalledWith('2', expect.objectContaining({ id: 10 })))
})

it('hides the add songs link on an empty public playlist owned by someone else', async () => {
  musicService.getPlaylistById.mockResolvedValue({ ...publicPlaylist, tracks: [] })
  open(UserPlaylistDetail)
  await screen.findByText(publicPlaylist.name)
  expect(screen.queryByRole('link', { name: 'Adicionar mais músicas' })).toBeNull()
})

it.each([false, true])('creates a playlist with publica=%s and opens its detail', async (publica) => {
  musicService.createPlaylist.mockResolvedValue({ id: 2 })
  musicService.getPlaylistById.mockResolvedValue(ownPlaylist)
  const { router } = open(CreatePlaylist, '/create-playlist')
  fireEvent.change(screen.getByLabelText('Nome da playlist'), { target: { value: 'Nova seleção' } })
  if (publica) fireEvent.click(screen.getByRole('button', { name: 'Pública' }))
  fireEvent.click(screen.getByRole('button', { name: 'Salvar playlist' }))
  await waitFor(() => expect(musicService.createPlaylist).toHaveBeenCalledWith({
    name: 'Nova seleção', publica, description: '', coverUrl: '',
  }))
  await waitFor(() => expect(router.state.location.pathname).toBe('/user-playlist-detail/2'))
  expect(await screen.findByRole('link', { name: 'Adicionar mais músicas' })).toBeTruthy()
})

it('plays recommendations and lets the owner remove a song added in this session', async () => {
  musicService.getPlaylistById.mockResolvedValue({ ...ownPlaylist, tracks: [] })
  musicService.getHomeMusics.mockResolvedValue([track])
  open(AddMusicToPlaylist, '/details/2')
  fireEvent.click(await screen.findByText(track.title))
  expect(playTrack).toHaveBeenCalledWith(expect.objectContaining({ id: 10 }), [expect.objectContaining({ id: 10 })])
  fireEvent.click(screen.getByRole('button', { name: 'Adicionar' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Remover' }))
  await waitFor(() => expect(musicService.removeMusicFromPlaylist).toHaveBeenCalledWith('2', '10'))
  expect(await screen.findByRole('button', { name: 'Adicionar' })).toBeTruthy()
  expect(playTrack).toHaveBeenCalledTimes(1)
})
