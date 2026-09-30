package br.com.deefy.controller;

import br.com.deefy.exception.handler.GlobalExceptionHandler;
import br.com.deefy.mapper.PlaylistMapper;
import br.com.deefy.model.*;
import br.com.deefy.repository.*;
import br.com.deefy.service.impl.PlaylistServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlaylistPrivacyTest {
    private PlaylistRepository playlists;
    private MusicRepository musics;
    private MockMvc mvc;
    private Playlist playlist;
    private User owner;

    @BeforeEach
    void setup() {
        playlists = mock(PlaylistRepository.class);
        musics = mock(MusicRepository.class);
        UserRepository users = mock(UserRepository.class);
        owner = new User(); owner.setId(1L); owner.setEmail("owner@test.com");
        User visitor = new User(); visitor.setId(2L); visitor.setEmail("visitor@test.com");
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(users.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(users.findByEmail(visitor.getEmail())).thenReturn(Optional.of(visitor));
        Music track = new Music(10L, "Track", "Pop", 120, null);
        track.setFileUrl("https://example.com/audio.mp3");
        playlist = new Playlist(7L, owner, "Private name", true, LocalDateTime.now(), List.of(track));
        when(playlists.findById(7L)).thenReturn(Optional.of(playlist));
        when(playlists.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlaylistServiceImpl service = new PlaylistServiceImpl();
        ReflectionTestUtils.setField(service, "playlistRepository", playlists);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "musicRepository", musics);
        ReflectionTestUtils.setField(service, "globalPlaylistOwnerEmail", "system@test.com");
        PlaylistController controller = new PlaylistController();
        ReflectionTestUtils.setField(controller, "playlistService", service);
        ReflectionTestUtils.setField(controller, "userRepository", users);
        ReflectionTestUtils.setField(controller, "playlistMapper", new PlaylistMapper() {});
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        login("visitor@test.com");
    }

    private void login(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void publicPlaylistReturnsTracksAndReadOnlyPermission() throws Exception {
        mvc.perform(get("/api/v1/playlists/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.tracks[0].arquivoUrl").value("https://example.com/audio.mp3"));
    }

    @Test void privatePlaylistDoesNotExposeDataToVisitor() throws Exception {
        playlist.setPublica(false);
        mvc.perform(get("/api/v1/playlists/7")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.tracks").doesNotExist());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ownerCanReadAndManage(boolean publica) throws Exception {
        playlist.setPublica(publica); login(owner.getEmail());
        mvc.perform(get("/api/v1/playlists/7")).andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void visitorCannotMutatePublicOrPrivatePlaylists(boolean publica) throws Exception {
        playlist.setPublica(publica);
        mvc.perform(post("/api/v1/playlists/7/tracks/11")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/playlists/7/tracks/10")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/playlists/7").contentType("application/json")
                .content("{\"name\":\"Changed\",\"publica\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/playlists/7")).andExpect(status().isBadRequest());
        assertEquals("Private name", playlist.getName());
        assertEquals(publica, playlist.isPublica());
        assertEquals(List.of(10L), playlist.getTrackIds());
        verify(playlists, never()).save(any());
        verify(playlists, never()).delete(any(Playlist.class));
        verifyNoInteractions(musics);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ownerRetainsWriteOperations(boolean publica) throws Exception {
        playlist.setPublica(publica); login(owner.getEmail());
        when(musics.findById(11L)).thenReturn(Optional.of(new Music(11L, "Second", "Pop", 90, null)));
        mvc.perform(post("/api/v1/playlists/7/tracks/11")).andExpect(status().isOk());
        assertEquals(List.of(10L, 11L), playlist.getTrackIds());
        mvc.perform(delete("/api/v1/playlists/7/tracks/10")).andExpect(status().isNoContent());
        assertEquals(List.of(11L), playlist.getTrackIds());
        mvc.perform(put("/api/v1/playlists/7").contentType("application/json")
                .content("{\"name\":\"Changed\",\"publica\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(true));
        mvc.perform(delete("/api/v1/playlists/7")).andExpect(status().isNoContent());
        verify(playlists).delete(playlist);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void creationPersistsVisibilityAndAuthenticatedOwner(boolean publica) throws Exception {
        login(owner.getEmail());
        mvc.perform(post("/api/v1/playlists").contentType("application/json")
                .content("{\"name\":\"New playlist\",\"publica\":" + publica + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.publica").value(publica))
                .andExpect(jsonPath("$.canManage").value(true));
        verify(playlists).save(argThat(created -> created.belongsTo(owner.getId()) && created.isPublica() == publica));
    }

    @Test void discoveryDoesNotReplaceOwnOrGlobalLists() throws Exception {
        when(playlists.findByPublicaTrueOrderByDataCriacaoDesc()).thenReturn(List.of(playlist));
        when(playlists.findPersonalByOwnerIdExcludingGlobalOwner(2L, "system@test.com")).thenReturn(List.of());
        when(playlists.findGlobalPlaylists("system@test.com")).thenReturn(List.of());
        mvc.perform(get("/api/v1/playlists/public")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7)).andExpect(jsonPath("$[0].canManage").value(false));
        mvc.perform(get("/api/v1/playlists")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/playlists/global")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
