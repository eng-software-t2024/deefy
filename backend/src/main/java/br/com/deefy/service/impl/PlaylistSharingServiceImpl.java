package br.com.deefy.service.impl;

import br.com.deefy.dto.request.PlaylistShareRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistShareRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistSharingRequestDTO;
import br.com.deefy.dto.response.PlaylistShareDetailsResponseDTO;
import br.com.deefy.dto.response.PlaylistSharingDetailsResponseDTO;
import br.com.deefy.dto.response.PlaylistLinkAcceptanceResponseDTO;
import br.com.deefy.exception.PlaylistException;
import br.com.deefy.exception.UsuarioNaoEncontradoException;
import br.com.deefy.model.Playlist;
import br.com.deefy.model.PlaylistShare;
import br.com.deefy.model.User;
import br.com.deefy.repository.PlaylistRepository;
import br.com.deefy.repository.PlaylistShareRepository;
import br.com.deefy.repository.UserRepository;
import br.com.deefy.service.PlaylistSharingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PlaylistSharingServiceImpl implements PlaylistSharingService {

    private static final Set<String> ALLOWED_PERMISSIONS = Set.of("VIEW", "EDITOR");

    private final PlaylistRepository playlistRepository;
    private final PlaylistShareRepository playlistShareRepository;
    private final UserRepository userRepository;

    public PlaylistSharingServiceImpl(
            PlaylistRepository playlistRepository,
            PlaylistShareRepository playlistShareRepository,
            UserRepository userRepository) {
        this.playlistRepository = playlistRepository;
        this.playlistShareRepository = playlistShareRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public PlaylistShare sharePlaylist(Long playlistId, Long ownerId, PlaylistShareRequestDTO request) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Você não tem permissão para compartilhar esta playlist");
        }

        String permission = normalizePermission(request.permissao());
        User user = userRepository.findByEmail(request.email().trim())
                .orElseThrow(() -> new UsuarioNaoEncontradoException("Usuário não encontrado com o email informado"));

        if (playlistShareRepository.existsByPlaylistIdAndUsuarioId(playlistId, user.getId())) {
            throw new PlaylistException("Esta playlist já foi compartilhada com o usuário informado");
        }

        PlaylistShare share = new PlaylistShare(playlist, user, permission, "DIRECT");
        return playlistShareRepository.save(share);
    }

    @Override
    @Transactional
    public PlaylistShare updatePlaylistSharePermission(
            Long playlistId,
            Long usuarioId,
            Long ownerId,
            UpdatePlaylistShareRequestDTO request) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Você não tem permissão para alterar os compartilhamentos desta playlist");
        }

        PlaylistShare share = playlistShareRepository.findByPlaylistIdAndUsuarioId(playlistId, usuarioId)
                .orElseThrow(() -> new PlaylistException("Compartilhamento não encontrado"));

        if (!share.getAtivo()) {
            throw new PlaylistException("O compartilhamento está revogado");
        }

        share.setPermissao(normalizePermission(request.permissao()));
        return playlistShareRepository.save(share);
    }

    @Override
    @Transactional
    public void revokePlaylistShare(Long playlistId, Long usuarioId, Long ownerId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Você não tem permissão para revogar compartilhamentos desta playlist");
        }

        PlaylistShare share = playlistShareRepository.findByPlaylistIdAndUsuarioId(playlistId, usuarioId)
                .orElseThrow(() -> new PlaylistException("Compartilhamento não encontrado"));

        if (share.getAtivo()) {
            share.desativar();
            playlistShareRepository.save(share);
        }
    }

    @Override
    @Transactional
    public Playlist configureLinkSharing(
            Long playlistId,
            Long ownerId,
            UpdatePlaylistSharingRequestDTO request) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Você não tem permissão para configurar o link desta playlist");
        }

        if (!request.linkCompartilhamento()) {
            throw new PlaylistException("A ativação do link deve ser solicitada com linkCompartilhamento=true");
        }

        playlist.setPermissaoLink(normalizePermission(request.permissaoLink()));
        if (playlist.getTokenCompartilhamento() == null) {
            playlist.setTokenCompartilhamento(UUID.randomUUID());
        }
        playlist.setLinkCompartilhamento(true);

        return playlistRepository.save(playlist);
    }

    @Override
    @Transactional
    public Playlist updateLinkPermission(Long playlistId, Long ownerId, String permissaoLink) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Você não tem permissão para alterar o link desta playlist");
        }

        if (!playlist.isLinkCompartilhamento()) {
            throw new PlaylistException("O compartilhamento por link está desativado");
        }

        playlist.setPermissaoLink(normalizePermission(permissaoLink));
        return playlistRepository.save(playlist);
    }

    @Override
    @Transactional
    public void deactivateLinkSharing(Long playlistId, Long ownerId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Você não tem permissão para desativar o link desta playlist");
        }

        if (playlist.isLinkCompartilhamento()) {
            playlist.setLinkCompartilhamento(false);
            playlistRepository.save(playlist);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PlaylistSharingDetailsResponseDTO getSharingDetails(Long playlistId, Long ownerId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Você não tem permissão para consultar os compartilhamentos desta playlist");
        }

        var shares = playlistShareRepository.findByPlaylistId(playlistId).stream()
                .filter(share -> Boolean.TRUE.equals(share.getAtivo()))
                .map(share -> new PlaylistShareDetailsResponseDTO(
                        share.getId(),
                        share.getUsuario().getId(),
                        share.getUsuario().getNome(),
                        share.getUsuario().getEmail(),
                        share.getPermissao(),
                        share.getOrigem(),
                        share.getAtivo()))
                .collect(Collectors.toList());

        return new PlaylistSharingDetailsResponseDTO(
                playlist.getOwner().getId(),
                playlist.getOwner().getNome(),
                playlist.getOwner().getEmail(),
                playlist.isLinkCompartilhamento(),
                playlist.getTokenCompartilhamento(),
                playlist.getPermissaoLink(),
                shares);
    }

    @Override
    @Transactional(readOnly = true)
    public Playlist findPlaylistByShareToken(UUID token) {
        return playlistRepository.findByTokenCompartilhamentoAndLinkCompartilhamentoTrue(token)
                .orElseThrow(() -> new PlaylistException("Link de compartilhamento inválido ou desativado"));
    }

    @Override
    @Transactional
    public PlaylistLinkAcceptanceResponseDTO acceptLinkSharing(UUID token, Long userId) {
        Playlist playlist = findPlaylistByShareToken(token);

        if (playlist.belongsTo(userId)) {
            return new PlaylistLinkAcceptanceResponseDTO(
                    playlist.getId(), true, "OWNER", "OWNER", true);
        }

        PlaylistShare share = playlistShareRepository.findByPlaylistIdAndUsuarioId(playlist.getId(), userId)
                .orElse(null);

        if (share == null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UsuarioNaoEncontradoException("Usuário não encontrado"));
            share = new PlaylistShare(playlist, user, playlist.getPermissaoLink(), "LINK");
            share = playlistShareRepository.save(share);
        } else if (!Boolean.TRUE.equals(share.getAtivo())) {
            share.ativar();
            share.setPermissao(playlist.getPermissaoLink());
            share.setOrigem("LINK");
            share = playlistShareRepository.save(share);
        }

        return new PlaylistLinkAcceptanceResponseDTO(
                playlist.getId(),
                true,
                share.getPermissao(),
                share.getOrigem(),
                share.getAtivo());
    }

    private String normalizePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new PlaylistException("A permissão deve ser VIEW ou EDITOR");
        }

        String normalized = permission.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_PERMISSIONS.contains(normalized)) {
            throw new PlaylistException("A permissão deve ser VIEW ou EDITOR");
        }
        return normalized;
    }
}
