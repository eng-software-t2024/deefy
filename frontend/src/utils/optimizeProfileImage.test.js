import { afterEach, describe, expect, it, vi } from 'vitest'
import { optimizeProfileImage } from './optimizeProfileImage'

describe('optimizeProfileImage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

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

  it('returns a smaller WebP file when browser conversion succeeds', async () => {
    const image = new File(['x'.repeat(2048)], 'avatar.png', { type: 'image/png' })
    const webp = new Blob(['optimized'], { type: 'image/webp' })
    const drawImage = vi.fn()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:avatar')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.stubGlobal('Image', class {
      naturalWidth = 1024
      naturalHeight = 768
      set src(_value) { queueMicrotask(() => this.onload()) }
    })
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage })
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback, type, quality) => {
      expect(type).toBe('image/webp')
      expect(quality).toBeGreaterThan(0.81)
      expect(quality).toBeLessThan(0.83)
      callback(webp)
    })

    const result = await optimizeProfileImage(image)

    expect(result).not.toBe(image)
    expect(result).toBeInstanceOf(File)
    expect(result.name).toBe('avatar.webp')
    expect(result.type).toBe('image/webp')
    expect(result.size).toBeLessThan(image.size)
    expect(await result.text()).toBe('optimized')
    expect(drawImage).toHaveBeenCalledWith(expect.any(Image), 0, 0, 512, 384)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar')
  })
})
