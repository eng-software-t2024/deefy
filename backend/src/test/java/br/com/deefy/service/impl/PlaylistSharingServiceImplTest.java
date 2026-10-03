package br.com.deefy.service.impl;

import br.com.deefy.dto.request.PlaylistShareRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistShareRequestDTO;
import br.com.deefy.dto.request.UpdatePlaylistSharingRequestDTO;
import br.com.deefy.exception.PlaylistException;
import br.com.deefy.exception.UsuarioNaoEncontradoException;
import br.com.deefy.model.Playlist;
import br.com.deefy.model.PlaylistShare;
import br.com.deefy.model.User;
import br.com.deefy.repository.PlaylistRepository;
import br.com.deefy.repository.PlaylistShareRepository;
import br.com.deefy.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaylistSharingServiceImplTest {

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private PlaylistShareRepository playlistShareRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PlaylistSharingServiceImpl service;

    private Playlist playlist;
    private User owner;
    private User recipient;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);

        recipient = new User();
        recipient.setId(2L);
        recipient.setEmail("recipient@deefy.com");

        playlist = new Playlist(10L, owner, "Playlist", false, LocalDateTime.now(), null);
    }

    @Test
    void sharePlaylist_QuandoDonoEUsuarioExistem_CriaCompartilhamento() {
        PlaylistShare savedShare = new PlaylistShare(playlist, recipient, "EDITOR", "DIRECT");
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(userRepository.findByEmail("recipient@deefy.com")).thenReturn(Optional.of(recipient));
        when(playlistShareRepository.existsByPlaylistIdAndUsuarioId(10L, 2L)).thenReturn(false);
        when(playlistShareRepository.save(any(PlaylistShare.class))).thenReturn(savedShare);

        PlaylistShare result = service.sharePlaylist(
                10L,
                1L,
                new PlaylistShareRequestDTO("recipient@deefy.com", "editor"));

        assertEquals("EDITOR", result.getPermissao());
        assertEquals("DIRECT", result.getOrigem());
    }

    @Test
    void sharePlaylist_QuandoSolicitanteNaoEDono_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.sharePlaylist(
                10L,
                99L,
                new PlaylistShareRequestDTO("recipient@deefy.com", "VIEW")));
    }

    @Test
    void sharePlaylist_QuandoEmailNaoExiste_LancaExcecao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(userRepository.findByEmail("unknown@deefy.com")).thenReturn(Optional.empty());

        assertThrows(UsuarioNaoEncontradoException.class, () -> service.sharePlaylist(
                10L,
                1L,
                new PlaylistShareRequestDTO("unknown@deefy.com", "VIEW")));
    }

    @Test
    void sharePlaylist_QuandoPermissaoInvalida_LancaExcecao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.sharePlaylist(
                10L,
                1L,
                new PlaylistShareRequestDTO("recipient@deefy.com", "ADMIN")));
    }

    @Test
    void updatePlaylistSharePermission_QuandoDonoECompartilhamentoExistem_AtualizaPermissao() {
        PlaylistShare share = new PlaylistShare(playlist, recipient, "VIEW", "DIRECT");
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistShareRepository.findByPlaylistIdAndUsuarioId(10L, 2L)).thenReturn(Optional.of(share));
        when(playlistShareRepository.save(share)).thenReturn(share);

        PlaylistShare result = service.updatePlaylistSharePermission(
                10L,
                2L,
                1L,
                new UpdatePlaylistShareRequestDTO("editor"));

        assertEquals("EDITOR", result.getPermissao());
    }

    @Test
    void updatePlaylistSharePermission_QuandoSolicitanteNaoEDono_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.updatePlaylistSharePermission(
                10L,
                2L,
                99L,
                new UpdatePlaylistShareRequestDTO("EDITOR")));
    }

    @Test
    void updatePlaylistSharePermission_QuandoCompartilhamentoNaoExiste_LancaExcecao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistShareRepository.findByPlaylistIdAndUsuarioId(10L, 2L)).thenReturn(Optional.empty());

        assertThrows(PlaylistException.class, () -> service.updatePlaylistSharePermission(
                10L,
                2L,
                1L,
                new UpdatePlaylistShareRequestDTO("EDITOR")));
    }

    @Test
    void updatePlaylistSharePermission_QuandoPermissaoInvalida_LancaExcecao() {
        PlaylistShare share = new PlaylistShare(playlist, recipient, "VIEW", "DIRECT");
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistShareRepository.findByPlaylistIdAndUsuarioId(10L, 2L)).thenReturn(Optional.of(share));

        assertThrows(PlaylistException.class, () -> service.updatePlaylistSharePermission(
                10L,
                2L,
                1L,
                new UpdatePlaylistShareRequestDTO("ADMIN")));
    }

    @Test
    void revokePlaylistShare_QuandoDonoECompartilhamentoExistem_DesativaRegistro() {
        PlaylistShare share = new PlaylistShare(playlist, recipient, "VIEW", "DIRECT");
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistShareRepository.findByPlaylistIdAndUsuarioId(10L, 2L)).thenReturn(Optional.of(share));

        service.revokePlaylistShare(10L, 2L, 1L);

        assertFalse(share.getAtivo());
    }

    @Test
    void revokePlaylistShare_QuandoSolicitanteNaoEDono_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.revokePlaylistShare(10L, 2L, 99L));
    }

    @Test
    void revokePlaylistShare_QuandoCompartilhamentoNaoExiste_LancaExcecao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistShareRepository.findByPlaylistIdAndUsuarioId(10L, 2L)).thenReturn(Optional.empty());

        assertThrows(PlaylistException.class, () -> service.revokePlaylistShare(10L, 2L, 1L));
    }

    @Test
    void configureLinkSharing_QuandoNaoPossuiToken_GeraTokenEAtivaLink() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistRepository.save(playlist)).thenReturn(playlist);

        Playlist result = service.configureLinkSharing(
                10L,
                1L,
                new UpdatePlaylistSharingRequestDTO(true, "view"));

        assertTrue(result.isLinkCompartilhamento());
        assertNotNull(result.getTokenCompartilhamento());
        assertEquals("VIEW", result.getPermissaoLink());
    }

    @Test
    void configureLinkSharing_QuandoJaPossuiToken_ReutilizaToken() {
        UUID token = UUID.randomUUID();
        playlist.setTokenCompartilhamento(token);
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistRepository.save(playlist)).thenReturn(playlist);

        Playlist result = service.configureLinkSharing(
                10L,
                1L,
                new UpdatePlaylistSharingRequestDTO(true, "EDITOR"));

        assertEquals(token, result.getTokenCompartilhamento());
        assertTrue(result.isLinkCompartilhamento());
    }

    @Test
    void configureLinkSharing_QuandoSolicitanteNaoEDono_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.configureLinkSharing(
                10L,
                99L,
                new UpdatePlaylistSharingRequestDTO(true, "VIEW")));
    }

    @Test
    void configureLinkSharing_QuandoPermissaoInvalida_LancaExcecao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.configureLinkSharing(
                10L,
                1L,
                new UpdatePlaylistSharingRequestDTO(true, "ADMIN")));
    }

    @Test
    void configureLinkSharing_QuandoSolicitaDesativacao_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.configureLinkSharing(
                10L,
                1L,
                new UpdatePlaylistSharingRequestDTO(false, "VIEW")));
    }

    @Test
    void updateLinkPermission_QuandoDonoElinkAtivo_AtualizaPermissao() {
        playlist.setLinkCompartilhamento(true);
        playlist.setPermissaoLink("VIEW");
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));
        when(playlistRepository.save(playlist)).thenReturn(playlist);

        Playlist result = service.updateLinkPermission(10L, 1L, "editor");

        assertEquals("EDITOR", result.getPermissaoLink());
        assertTrue(result.isLinkCompartilhamento());
    }

    @Test
    void updateLinkPermission_QuandoSolicitanteNaoEDono_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.updateLinkPermission(10L, 99L, "VIEW"));
    }

    @Test
    void updateLinkPermission_QuandoLinkDesativado_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.updateLinkPermission(10L, 1L, "VIEW"));
    }

    @Test
    void updateLinkPermission_QuandoPermissaoInvalida_LancaExcecao() {
        playlist.setLinkCompartilhamento(true);
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.updateLinkPermission(10L, 1L, "ADMIN"));
    }

    @Test
    void deactivateLinkSharing_QuandoDonoElinkAtivo_DesativaLinkEPreservaToken() {
        UUID token = UUID.randomUUID();
        playlist.setLinkCompartilhamento(true);
        playlist.setTokenCompartilhamento(token);
        playlist.setPermissaoLink("VIEW");
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        service.deactivateLinkSharing(10L, 1L);

        assertFalse(playlist.isLinkCompartilhamento());
        assertEquals(token, playlist.getTokenCompartilhamento());
        assertEquals("VIEW", playlist.getPermissaoLink());
    }

    @Test
    void deactivateLinkSharing_QuandoSolicitanteNaoEDono_NegaOperacao() {
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        assertThrows(PlaylistException.class, () -> service.deactivateLinkSharing(10L, 99L));
    }

    @Test
    void deactivateLinkSharing_QuandoLinkJaDesativado_PermaneceIdempotente() {
        UUID token = UUID.randomUUID();
        playlist.setTokenCompartilhamento(token);
        when(playlistRepository.findById(10L)).thenReturn(Optional.of(playlist));

        service.deactivateLinkSharing(10L, 1L);

        assertFalse(playlist.isLinkCompartilhamento());
        assertEquals(token, playlist.getTokenCompartilhamento());
    }
}
