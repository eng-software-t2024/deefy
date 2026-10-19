import { beforeEach, expect, it, vi } from 'vitest'
import api from './api'
import { musicService } from './musicService'

vi.mock('./api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

beforeEach(() => vi.clearAllMocks())

it('busca as músicas recomendadas e as normaliza', async () => {
  api.get.mockResolvedValue({
    data: [
      { id: 7, title: 'Pop Demo', artist: 'Local Beats', fileUrl: 'https://example.com/pop.mp3', durationSeconds: 90 },
    ],
  })

  const musics = await musicService.getRecommendedMusic()

  expect(api.get).toHaveBeenCalledExactlyOnceWith('/history/recommendations')
  expect(musics).toHaveLength(1)
  expect(musics[0].audioUrl).toBe('https://example.com/pop.mp3')
})

it('registra a reprodução enviando o musicId como número', async () => {
  api.post.mockResolvedValue({ data: { id: 1 } })

  await musicService.recordMusicPlay('7')

  expect(api.post).toHaveBeenCalledExactlyOnceWith('/history', { musicId: 7 })
})

it('não propaga erro ao falhar o registro de reprodução', async () => {
  api.post.mockRejectedValue({ status: 500 })

  await expect(musicService.recordMusicPlay(7)).resolves.toBeUndefined()
})
