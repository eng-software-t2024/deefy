import { afterEach, describe, expect, it, vi } from 'vitest'
import { optimizeProfileImage } from './optimizeProfileImage'

describe('optimizeProfileImage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('preserves GIF files so animation is not lost', async () => {
    const gif = new File(['gif'], 'avatar.gif', { type: 'image/gif' })
    await expect(optimizeProfileImage(gif)).resolves.toBe(gif)
  })

  it('keeps the original file when the browser cannot create an optimized blob', async () => {
    const image = new File(['large image'], 'avatar.png', { type: 'image/png' })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:avatar')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.stubGlobal('Image', class {
      naturalWidth = 1024
      naturalHeight = 768
      set src(_value) { queueMicrotask(() => this.onload()) }
    })
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() })
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => callback(null))

    await expect(optimizeProfileImage(image)).resolves.toBe(image)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar')
  })
})
