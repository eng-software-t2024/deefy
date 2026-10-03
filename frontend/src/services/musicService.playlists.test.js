import { beforeEach, expect, it, vi } from 'vitest'
import api from './api'
import { musicService } from './musicService'
vi.mock('./api', () => ({ default: { get: vi.fn() } }))
beforeEach(() => vi.clearAllMocks())
it('propagates denied detail requests without recovering data from lists', async () => {
  const denied = { status: 400, message: 'Acesso negado' }
  api.get.mockRejectedValue(denied)
  await expect(musicService.getPlaylistById(9)).rejects.toBe(denied)
  expect(api.get).toHaveBeenCalledExactlyOnceWith('/playlists/9')
})
it('uses separate public and own endpoints and preserves permissions and audio', async () => {
  api.get.mockResolvedValue({ data: [{ id: 1, publica: true, canManage: false, tracks: [] }] })
  const publicList = await musicService.getPublicPlaylists()
  expect(publicList[0].canManage).toBe(false)
  expect(api.get).toHaveBeenLastCalledWith('/playlists/public')
  await musicService.getUserPlaylists()
  expect(api.get).toHaveBeenLastCalledWith('/playlists')
  api.get.mockResolvedValue({ data: { id: 1, canManage: false, tracks: [
    { id: 10, titulo: 'Track', arquivoUrl: 'https://example.com/audio.mp3', genero: 'Pop' },
  ] } })
  const detail = await musicService.getPlaylistById(1)
  expect(detail.canManage).toBe(false)
  expect(detail.tracks[0].audioUrl).toBe('https://example.com/audio.mp3')
})
