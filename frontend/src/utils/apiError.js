const FIELD_PREFIX = /^[A-Za-z][A-Za-z0-9_.]*:\s+/

export function getApiErrorMessage(error, fallback) {
  const candidates = [
    error?.message,
    error?.response?.data?.messages?.[0],
    error?.data?.messages?.[0],
    error?.response?.data?.message,
    error?.data?.message,
  ]

  const message = candidates.find((item) => typeof item === 'string' && item.trim())
  if (!message) return fallback

  const cleaned = message.replace(FIELD_PREFIX, '').trim()
  return cleaned || fallback
}
