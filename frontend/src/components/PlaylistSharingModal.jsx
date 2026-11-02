import { useCallback, useEffect, useMemo, useState } from 'react'
import { MdClose, MdContentCopy, MdLink, MdLock, MdPersonAdd, MdPublic } from 'react-icons/md'

import { musicService } from '../services/musicService.js'
import { showMusicError, showMusicSuccess } from '../utils/musicToast'

import './PlaylistSharingModal.css'

const PERMISSIONS = [
  { value: 'VIEW', label: 'Leitor' },
  { value: 'EDITOR', label: 'Editor' },
]

function PlaylistSharingModal({ playlistId, playlistName, onClose }) {
  const [sharing, setSharing] = useState(null)
  const [email, setEmail] = useState('')
  const [permission, setPermission] = useState('VIEW')
  const [linkPermission, setLinkPermission] = useState('VIEW')
  const [isLoading, setIsLoading] = useState(true)
  const [isSaving, setIsSaving] = useState(false)
  const [copied, setCopied] = useState(false)

  const shareUrl = useMemo(() => {
    if (!sharing?.tokenCompartilhamento) return ''
    return `${window.location.origin}/shared-playlist/${sharing.tokenCompartilhamento}`
  }, [sharing?.tokenCompartilhamento])

  const loadSharing = useCallback(async () => {
    try {
      setIsLoading(true)
      const data = await musicService.getPlaylistSharing(playlistId)
      setSharing(data)
      if (data?.permissaoLink) setLinkPermission(data.permissaoLink)
    } catch {
      showMusicError('Não foi possível carregar os compartilhamentos.')
    } finally {
      setIsLoading(false)
    }
  }, [playlistId])

  useEffect(() => {
    loadSharing()
  }, [loadSharing])

  const handleAddPerson = async (event) => {
    event.preventDefault()
    if (!email.trim()) return

    try {
      setIsSaving(true)
      await musicService.sharePlaylist(playlistId, { email: email.trim(), permissao: permission })
      setEmail('')
      showMusicSuccess('Acesso concedido com sucesso.')
      await loadSharing()
    } catch (error) {
      showMusicError(error?.message || 'Não foi possível compartilhar a playlist.')
    } finally {
      setIsSaving(false)
    }
  }

  const handlePermissionChange = async (userId, nextPermission) => {
    try {
      setIsSaving(true)
      await musicService.updatePlaylistSharePermission(playlistId, userId, nextPermission)
      showMusicSuccess('Permissão atualizada.')
      await loadSharing()
    } catch (error) {
      showMusicError(error?.message || 'Não foi possível atualizar a permissão.')
    } finally {
      setIsSaving(false)
    }
  }

  const handleRevoke = async (userId) => {
    try {
      setIsSaving(true)
      await musicService.revokePlaylistShare(playlistId, userId)
      showMusicSuccess('Acesso revogado.')
      await loadSharing()
    } catch (error) {
      showMusicError(error?.message || 'Não foi possível revogar o acesso.')
    } finally {
      setIsSaving(false)
    }
  }

  const handleLinkToggle = async () => {
    try {
      setIsSaving(true)
      if (sharing?.linkCompartilhamento) {
        await musicService.deactivatePlaylistLink(playlistId)
        showMusicSuccess('Link desativado.')
      } else {
        await musicService.configurePlaylistLink(playlistId, linkPermission)
        showMusicSuccess('Link ativado.')
      }
      await loadSharing()
    } catch (error) {
      showMusicError(error?.message || 'Não foi possível atualizar o link.')
    } finally {
      setIsSaving(false)
    }
  }

  const handleLinkPermissionChange = async (nextPermission) => {
    setLinkPermission(nextPermission)
    if (!sharing?.linkCompartilhamento) return

    try {
      setIsSaving(true)
      await musicService.updatePlaylistLinkPermission(playlistId, nextPermission)
      showMusicSuccess('Permissão do link atualizada.')
      await loadSharing()
    } catch (error) {
      showMusicError(error?.message || 'Não foi possível atualizar a permissão do link.')
    } finally {
      setIsSaving(false)
    }
  }

  const handleCopyLink = async () => {
    try {
      await navigator.clipboard.writeText(shareUrl)
      setCopied(true)
      showMusicSuccess('Link copiado.')
      window.setTimeout(() => setCopied(false), 1800)
    } catch {
      showMusicError('Não foi possível copiar o link.')
    }
  }

  return (
    <div className="sharing-modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section className="sharing-modal" role="dialog" aria-modal="true" aria-labelledby="sharing-modal-title">
        <header className="sharing-modal-header">
          <div>
            <span className="sharing-modal-kicker">COMPARTILHAMENTO</span>
            <h2 id="sharing-modal-title">Compartilhar “{playlistName}”</h2>
          </div>
          <button type="button" className="sharing-modal-close" onClick={onClose} aria-label="Fechar compartilhamento">
            <MdClose />
          </button>
        </header>

        {isLoading ? (
          <div className="sharing-modal-loading">Carregando acessos...</div>
        ) : (
          <>
            <form className="sharing-add-form" onSubmit={handleAddPerson}>
              <MdPersonAdd aria-hidden="true" />
              <input
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="Adicionar pessoas por email"
                aria-label="Email do usuário"
                required
              />
              <div className="sharing-permission-switch sharing-permission-switch--compact" role="group" aria-label="Permissão do novo usuário">
                {PERMISSIONS.map((item) => (
                  <button
                    key={item.value}
                    type="button"
                    className={permission === item.value ? 'is-active' : ''}
                    onClick={() => setPermission(item.value)}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
              <button type="submit" disabled={isSaving} aria-label="Adicionar usuário">Adicionar</button>
            </form>

            <div className="sharing-section">
              <h3>Pessoas com acesso</h3>
              <div className="sharing-owner-row">
                <span className="sharing-avatar">{sharing?.proprietarioNome?.charAt(0)?.toUpperCase() || 'P'}</span>
                <div>
                  <strong>{sharing?.proprietarioNome || 'Proprietário'} (você)</strong>
                  <small>{sharing?.proprietarioEmail}</small>
                </div>
                <span className="sharing-owner-label">Proprietário</span>
              </div>

              {sharing?.compartilhamentos?.map((share) => (
                <div className="sharing-person-row" key={share.id}>
                  <span className="sharing-avatar sharing-avatar--muted">{share.usuarioNome?.charAt(0)?.toUpperCase() || '?'}</span>
                  <div>
                    <strong>{share.usuarioNome || share.usuarioEmail}</strong>
                    <small>{share.usuarioEmail}</small>
                  </div>
                  <div className="sharing-permission-switch sharing-permission-switch--compact" role="group" aria-label={`Permissão de ${share.usuarioEmail}`}>
                    {PERMISSIONS.map((item) => (
                      <button
                        key={item.value}
                        type="button"
                        className={share.permissao === item.value ? 'is-active' : ''}
                        onClick={() => handlePermissionChange(share.usuarioId, item.value)}
                        disabled={isSaving}
                      >
                        {item.label}
                      </button>
                    ))}
                  </div>
                  <button type="button" className="sharing-revoke" onClick={() => handleRevoke(share.usuarioId)} disabled={isSaving}>
                    Revogar
                  </button>
                </div>
              ))}

              {!sharing?.compartilhamentos?.length && <p className="sharing-empty">Nenhum usuário adicional possui acesso.</p>}
            </div>

            <div className="sharing-section sharing-general-section">
              <h3>Acesso geral</h3>
              <div className="sharing-general-card">
                <span className="sharing-general-icon">{sharing?.linkCompartilhamento ? <MdPublic /> : <MdLock />}</span>
                <div>
                  <strong>{sharing?.linkCompartilhamento ? 'Qualquer pessoa com o link' : 'Restrito'}</strong>
                  <small>{sharing?.linkCompartilhamento ? 'O acesso segue a permissão configurada abaixo.' : 'Somente as pessoas adicionadas podem acessar.'}</small>
                </div>
                <button type="button" className="sharing-toggle" onClick={handleLinkToggle} disabled={isSaving}>
                  {sharing?.linkCompartilhamento ? 'Desativar' : 'Ativar link'}
                </button>
              </div>

              <div className="sharing-link-controls">
                <span id="link-permission-label">Permissão do link</span>
                <div className="sharing-permission-switch" role="group" aria-labelledby="link-permission-label">
                  {PERMISSIONS.map((item) => (
                    <button
                      key={item.value}
                      type="button"
                      className={linkPermission === item.value ? 'is-active' : ''}
                      onClick={() => handleLinkPermissionChange(item.value)}
                      disabled={isSaving}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>

              {shareUrl && sharing?.linkCompartilhamento && (
                <div className="sharing-copy-row">
                  <MdLink aria-hidden="true" />
                  <input value={shareUrl} readOnly aria-label="Link de compartilhamento" />
                  <button type="button" onClick={handleCopyLink} disabled={isSaving}>
                    <MdContentCopy /> {copied ? 'Copiado' : 'Copiar link'}
                  </button>
                </div>
              )}
            </div>
          </>
        )}

        <footer className="sharing-modal-footer">
          <button type="button" className="sharing-done-button" onClick={onClose}>Concluído</button>
        </footer>
      </section>
    </div>
  )
}

export default PlaylistSharingModal
