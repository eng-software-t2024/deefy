package br.com.deefy.controller;

import br.com.deefy.config.OpenApiConfig;
import br.com.deefy.dto.response.PlaylistLinkAcceptanceResponseDTO;
import br.com.deefy.dto.response.PlaylistResponseDTO;
import br.com.deefy.mapper.PlaylistMapper;
import br.com.deefy.service.PlaylistSharingService;
import br.com.deefy.service.impl.AuthenticatedUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shared-playlists")
@Tag(name = "Shared Playlists", description = "Acesso e aceite de playlists compartilhadas por link")
public class SharedPlaylistController {

    private final PlaylistSharingService playlistSharingService;
    private final AuthenticatedUserService authenticatedUserService;
    private final PlaylistMapper playlistMapper;

    public SharedPlaylistController(
            PlaylistSharingService playlistSharingService,
            AuthenticatedUserService authenticatedUserService,
            PlaylistMapper playlistMapper) {
        this.playlistSharingService = playlistSharingService;
        this.authenticatedUserService = authenticatedUserService;
        this.playlistMapper = playlistMapper;
    }

    @GetMapping("/{token}")
    @Operation(summary = "Visualizar playlist compartilhada", description = "Consulta uma playlist por token enquanto o link estiver ativo.")
    public ResponseEntity<PlaylistResponseDTO> getSharedPlaylist(@PathVariable UUID token) {
        var playlist = playlistSharingService.findPlaylistByShareToken(token);
        return ResponseEntity.ok(playlistMapper.toResponseDTO(playlist, null));
    }

    @PostMapping("/{token}/accept")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Aceitar convite da playlist", description = "Registra o aceite do compartilhamento por link para o usuario autenticado.")
    public ResponseEntity<PlaylistLinkAcceptanceResponseDTO> acceptInvitation(@PathVariable UUID token) {
        return ResponseEntity.ok(playlistSharingService.acceptLinkSharing(
                token,
                authenticatedUserService.getAuthenticatedUserId()));
    }
}
