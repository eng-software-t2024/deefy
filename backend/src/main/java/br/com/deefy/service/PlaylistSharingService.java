package br.com.deefy.service;

import br.com.deefy.dto.request.PlaylistShareRequestDTO;
import br.com.deefy.model.PlaylistShare;

public interface PlaylistSharingService {

    PlaylistShare sharePlaylist(Long playlistId, Long ownerId, PlaylistShareRequestDTO request);
}
