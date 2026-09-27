package br.com.deefy.repository;

import br.com.deefy.model.PlaylistShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlaylistShareRepository extends JpaRepository<PlaylistShare, Long> {

    List<PlaylistShare> findByPlaylistId(Long playlistId);

    List<PlaylistShare> findByUsuarioId(Long usuarioId);

    Optional<PlaylistShare> findByPlaylistIdAndUsuarioId(Long playlistId, Long usuarioId);

    boolean existsByPlaylistIdAndUsuarioId(Long playlistId, Long usuarioId);
}
