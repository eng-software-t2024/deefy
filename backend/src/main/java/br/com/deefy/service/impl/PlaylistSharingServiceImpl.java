package br.com.deefy.service.impl;

import br.com.deefy.dto.request.PlaylistShareRequestDTO;
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

    private String normalizePermission(String permission) {
        String normalized = permission.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_PERMISSIONS.contains(normalized)) {
            throw new PlaylistException("A permissão deve ser VIEW ou EDITOR");
        }
        return normalized;
    }
}
