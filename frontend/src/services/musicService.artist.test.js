import { beforeEach, expect, it, vi } from 'vitest'
import api from './api'
import { musicService } from './musicService'

vi.mock('./api', () => ({ default: { get: vi.fn() } }))

beforeEach(() => vi.clearAllMocks())

it('busca um artista pelo id', async () => {
  api.get.mockResolvedValue({ data: { id: 3, nome: 'Local Beats', bio: 'Bio', fotoUrl: 'https://example.com/a.jpg' } })

  const artist = await musicService.getArtistById(3)

  expect(api.get).toHaveBeenCalledExactlyOnceWith('/artists/3')
  expect(artist.nome).toBe('Local Beats')
})

it('lista e normaliza as músicas de um artista pelo endpoint paginado', async () => {
  api.get.mockResolvedValue({
    data: {
      content: [
        { id: 1, title: 'Demo Groove', artist: 'Local Beats', fileUrl: 'https://example.com/audio.mp3', durationSeconds: 65, album: 'Pop' },
      ],
      totalPages: 1,
    },
  })

  const musics = await musicService.getMusicsByArtistId(3)

  expect(api.get).toHaveBeenCalledExactlyOnceWith('/musics/artist/3', { params: { page: 0, size: 100 } })
  expect(musics).toHaveLength(1)
  expect(musics[0].audioUrl).toBe('https://example.com/audio.mp3')
  expect(musics[0].duration).toBe('1:05')
})

it('propaga o erro quando o artista não existe', async () => {
  const notFound = { status: 404, message: 'Artist not found' }
  api.get.mockRejectedValue(notFound)

  await expect(musicService.getArtistById(99)).rejects.toBe(notFound)
})