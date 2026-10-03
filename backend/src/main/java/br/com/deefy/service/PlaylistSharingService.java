package br.com.deefy.service;

import br.com.deefy.dto.request.PlaylistShareRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistSharingRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistShareRequestDTO;
import br.com.deefy.model.Playlist;
import br.com.deefy.model.PlaylistShare;

public interface PlaylistSharingService {

    PlaylistShare sharePlaylist(Long playlistId, Long ownerId, PlaylistShareRequestDTO request);

    PlaylistShare updatePlaylistSharePermission(
            Long playlistId,
            Long usuarioId,
            Long ownerId,
            UpdatePlaylistShareRequestDTO request);

    void revokePlaylistShare(Long playlistId, Long usuarioId, Long ownerId);

    Playlist configureLinkSharing(Long playlistId, Long ownerId, UpdatePlaylistSharingRequestDTO request);

    Playlist updateLinkPermission(
            Long playlistId,
            Long ownerId,
            String permissaoLink);
}
