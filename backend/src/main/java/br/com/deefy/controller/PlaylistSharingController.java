package br.com.deefy.controller;

import br.com.deefy.dto.request.PlaylistShareRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistShareRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistSharingRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistLinkPermissionRequestDTO;
import br.com.deefy.dto.response.PlaylistShareResponseDTO;
import br.com.deefy.dto.response.PlaylistSharingResponseDTO;
import br.com.deefy.config.OpenApiConfig;
import br.com.deefy.model.PlaylistShare;
import br.com.deefy.service.PlaylistSharingService;
import br.com.deefy.service.impl.AuthenticatedUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/playlists/{playlistId}/sharing")
@Tag(name = "Playlist Sharing", description = "Compartilhamento de playlists com usuarios")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PlaylistSharingController {

    private final PlaylistSharingService playlistSharingService;
    private final AuthenticatedUserService authenticatedUserService;

    public PlaylistSharingController(
            PlaylistSharingService playlistSharingService,
            AuthenticatedUserService authenticatedUserService) {
        this.playlistSharingService = playlistSharingService;
        this.authenticatedUserService = authenticatedUserService;
    }

    @PostMapping
    @Operation(summary = "Compartilhar playlist com usuario", description = "Concede acesso direto a uma playlist para um usuario existente.")
    public ResponseEntity<PlaylistShareResponseDTO> sharePlaylist(
            @PathVariable Long playlistId,
            @Valid @RequestBody PlaylistShareRequestDTO request) {
        PlaylistShare share = playlistSharingService.sharePlaylist(
                playlistId,
                authenticatedUserService.getAuthenticatedUserId(),
                request);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(share));
    }

    @PostMapping("/link")
    @Operation(summary = "Ativar compartilhamento por link", description = "Ativa o link da playlist e gera um token UUID quando necessario.")
    public ResponseEntity<PlaylistSharingResponseDTO> configureLinkSharing(
            @PathVariable Long playlistId,
            @Valid @RequestBody UpdatePlaylistSharingRequestDTO request) {
        var playlist = playlistSharingService.configureLinkSharing(
                playlistId,
                authenticatedUserService.getAuthenticatedUserId(),
                request);

        PlaylistSharingResponseDTO response = new PlaylistSharingResponseDTO(
                playlist.isLinkCompartilhamento(),
                playlist.getTokenCompartilhamento(),
                playlist.getPermissaoLink());

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/link")
    @Operation(summary = "Alterar permissao do link", description = "Altera a permissao do compartilhamento por link da playlist.")
    public ResponseEntity<PlaylistSharingResponseDTO> updateLinkPermission(
            @PathVariable Long playlistId,
            @Valid @RequestBody UpdatePlaylistLinkPermissionRequestDTO request) {
        var playlist = playlistSharingService.updateLinkPermission(
                playlistId,
                authenticatedUserService.getAuthenticatedUserId(),
                request.permissaoLink());

        PlaylistSharingResponseDTO response = new PlaylistSharingResponseDTO(
                playlist.isLinkCompartilhamento(),
                playlist.getTokenCompartilhamento(),
                playlist.getPermissaoLink());

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{usuarioId}")
    @Operation(summary = "Alterar permissao de compartilhamento", description = "Altera a permissao de um usuario compartilhado pelo proprietario da playlist.")
    public ResponseEntity<PlaylistShareResponseDTO> updatePermission(
            @PathVariable Long playlistId,
            @PathVariable Long usuarioId,
            @Valid @RequestBody UpdatePlaylistShareRequestDTO request) {
        PlaylistShare share = playlistSharingService.updatePlaylistSharePermission(
                playlistId,
                usuarioId,
                authenticatedUserService.getAuthenticatedUserId(),
                request);

        return ResponseEntity.ok(toResponse(share));
    }

    @DeleteMapping("/{usuarioId}")
    @Operation(summary = "Revogar compartilhamento", description = "Revoga o acesso direto de um usuario a uma playlist sem excluir o historico.")
    public ResponseEntity<Void> revokeSharing(
            @PathVariable Long playlistId,
            @PathVariable Long usuarioId) {
        playlistSharingService.revokePlaylistShare(
                playlistId,
                usuarioId,
                authenticatedUserService.getAuthenticatedUserId());

        return ResponseEntity.noContent().build();
    }

    private PlaylistShareResponseDTO toResponse(PlaylistShare share) {
        return new PlaylistShareResponseDTO(
                share.getId(),
                share.getUsuario().getId(),
                share.getPermissao(),
                share.getOrigem(),
                share.getAtivo());
    }
}
