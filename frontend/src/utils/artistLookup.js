import { musicService } from '../services/musicService.js'

let artistIdsPromise = null

export function normalizeArtistName(name) {
  return typeof name === 'string' ? name.trim().toLowerCase() : ''
}

export function loadArtistIdsByName() {
  if (!artistIdsPromise) {
    artistIdsPromise = musicService
      .getArtists()
      .then((artists) => {
        const idsByName = new Map()

        for (const artist of Array.isArray(artists) ? artists : []) {
          const key = normalizeArtistName(artist?.name ?? artist?.nome)
          if (key && artist?.id != null && !idsByName.has(key)) {
            idsByName.set(key, artist.id)
          }
        }

        return idsByName
      })
      .catch((error) => {
        artistIdsPromise = null
        throw error
      })
  }

  return artistIdsPromise
}