const MAX_DIMENSION = 512
const QUALITY = 0.82

/** Resize profile photos in browsers; preserve GIF animation and fall back to the original on failure. */
export async function optimizeProfileImage(file) {
  if (!file || file.type === 'image/gif' || typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return file
  }

  const sourceUrl = URL.createObjectURL(file)
  try {
    const image = await new Promise((resolve, reject) => {
      const element = new Image()
      element.onload = () => resolve(element)
      element.onerror = reject
      element.src = sourceUrl
    })
    if (!image.naturalWidth || !image.naturalHeight) return file

    const scale = Math.min(1, MAX_DIMENSION / image.naturalWidth, MAX_DIMENSION / image.naturalHeight)
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
    const context = canvas.getContext('2d')
    if (!context || typeof canvas.toBlob !== 'function') return file
    context.drawImage(image, 0, 0, canvas.width, canvas.height)

    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/webp', QUALITY))
    if (!blob || blob.type !== 'image/webp' || blob.size >= file.size) return file
    const name = file.name.replace(/\.[^.]+$/, '') || 'foto-perfil'
    return new File([blob], `${name}.webp`, { type: 'image/webp', lastModified: Date.now() })
  } catch {
    return file
  } finally {
    URL.revokeObjectURL(sourceUrl)
  }
}
