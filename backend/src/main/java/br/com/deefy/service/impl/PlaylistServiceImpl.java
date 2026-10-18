package br.com.deefy.service.impl;

import br.com.deefy.dto.request.PlaylistRequestDTO;
import br.com.deefy.exception.MusicNotFoundException;
import br.com.deefy.exception.PlaylistException;
import br.com.deefy.exception.UsuarioNaoEncontradoException;
import br.com.deefy.model.Music;
import br.com.deefy.model.Playlist;
import br.com.deefy.model.User;
import br.com.deefy.repository.MusicRepository;
import br.com.deefy.repository.PlaylistRepository;
import br.com.deefy.repository.PlaylistShareRepository;
import br.com.deefy.repository.UserRepository;
import br.com.deefy.service.PlaylistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Service
public class PlaylistServiceImpl implements PlaylistService {

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private PlaylistShareRepository playlistShareRepository;

    @Value("${DEEFY_IMPORT_OWNER_EMAIL:deefy.admin@deefy.com}")
    private String globalPlaylistOwnerEmail;

    @Override
    @Transactional
    public Playlist createPlaylist(Playlist playlist, Long ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new UsuarioNaoEncontradoException("Usuário não encontrado"));

        playlist.setOwner(owner); // Vincula o dono
        return playlistRepository.save(playlist);
    }

    @Override
    public List<Playlist> findAllByOwner(Long ownerId) {
        return playlistRepository.findPersonalByOwnerIdExcludingGlobalOwner(ownerId, globalPlaylistOwnerEmail);
    }

    @Override
    public List<Playlist> findGlobalPlaylists() {
        return playlistRepository.findGlobalPlaylists(globalPlaylistOwnerEmail);
    }

    @Override
    public List<Playlist> findPublicPlaylists() {
        return playlistRepository.findByPublicaTrueOrderByDataCriacaoDesc();
    }

    @Override
    public Playlist findById(Long id, Long ownerId) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada com o ID: " + id));

        if (!playlist.belongsTo(ownerId)) {
            throw new PlaylistException("Acesso negado: Você não é o proprietário desta playlist.");
        }

        return playlist;
    }

    @Override
    public Playlist findAccessibleById(Long id, Long ownerId) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada com o ID: " + id));

        if (playlist.belongsTo(ownerId) || hasActiveShare(playlist.getId(), ownerId) || playlist.isPublica()) {
            return playlist;
        }

        throw new PlaylistException("Acesso negado: Você não tem permissão para visualizar esta playlist.");
    }

    @Override
    @Transactional
    public Playlist updateName(Long id, PlaylistRequestDTO request, Long ownerId) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        // Validação de Segurança: apenas o dono edita
        if (!playlist.getOwner().getId().equals(ownerId)) {
            throw new PlaylistException("Você não tem permissão para editar esta playlist");
        }

        // Atualiza apenas os campos editaveis pelo usuario.
        playlist.setName(request.name());
        playlist.setPublica(request.publica());
        playlist.setDescription(blankToNull(request.description()));
        playlist.setCoverUrl(blankToNull(request.coverUrl()));

        return playlistRepository.save(playlist);
    }

    @Override
    @Transactional
    public void deletePlaylist(Long id, Long ownerId) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        // Valida se quem está deletando é realmente o dono
        if (!playlist.getOwner().getId().equals(ownerId)) {
            throw new PlaylistException("Você não tem permissão para deletar esta playlist");
        }

        playlistRepository.delete(playlist);
    }

    @Override
    @Transactional
    public Playlist addMusicToPlaylist(Long playlistId, Long musicId, Long ownerId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        ensureCanEdit(playlist, ownerId);

        Music music = musicRepository.findById(musicId)
                .orElseThrow(() -> new MusicNotFoundException(musicId));

        // Regra de negócio: evitar duplicatas
        if (playlist.getTracks().contains(music)) {
            throw new PlaylistException("Esta música já está presente na playlist");
        }

        playlist.getTracks().add(music);
        return playlistRepository.save(playlist);
    }

    @Override
    @Transactional
    public Playlist removeMusicFromPlaylist(Long playlistId, Long musicId, Long ownerId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        ensureCanEdit(playlist, ownerId);

        // Usando o metodo do Model
        boolean removed = playlist.removeFirstTrackByMusicId(musicId);

        if (!removed) {
            throw new PlaylistException("Música não encontrada na playlist.");
        }

        return playlistRepository.save(playlist);
    }

    @Override
    @Transactional
    public Playlist reorderPlaylistTracks(Long playlistId, List<Long> musicIds, Long ownerId) {
        Playlist playlist = playlistRepository.findById(playlistId)
                .orElseThrow(() -> new PlaylistException("Playlist não encontrada"));

        ensureCanEdit(playlist, ownerId);

        Set<Long> requestedIds = new HashSet<>(musicIds);
        Set<Long> currentIds = new HashSet<>(playlist.getTrackIds());
        if (requestedIds.size() != musicIds.size() || !requestedIds.equals(currentIds)) {
            throw new PlaylistException("A ordem deve conter exatamente as músicas da playlist");
        }

        List<Music> tracksById = musicIds.stream()
                .map(musicId -> playlist.getTracks().stream()
                        .filter(track -> track.getId().equals(musicId))
                        .findFirst()
                        .orElseThrow(() -> new PlaylistException("Música não encontrada na playlist")))
                .toList();
        playlist.setTracks(tracksById);
        return playlistRepository.save(playlist);
    }

    private void ensureCanEdit(Playlist playlist, Long userId) {
        if (playlist.belongsTo(userId)) {
            return;
        }

        if (!hasActiveShareWithPermission(playlist.getId(), userId, "EDITOR")) {
            throw new PlaylistException("Você não tem permissão para editar esta playlist");
        }
    }

    private boolean hasActiveShare(Long playlistId, Long userId) {
        return playlistShareRepository.findByPlaylistIdAndUsuarioId(playlistId, userId)
                .map(share -> Boolean.TRUE.equals(share.getAtivo()))
                .orElse(false);
    }

    private boolean hasActiveShareWithPermission(Long playlistId, Long userId, String permission) {
        return playlistShareRepository.findByPlaylistIdAndUsuarioId(playlistId, userId)
                .filter(share -> Boolean.TRUE.equals(share.getAtivo()))
                .map(share -> permission.equalsIgnoreCase(share.getPermissao()))
                .orElse(false);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
