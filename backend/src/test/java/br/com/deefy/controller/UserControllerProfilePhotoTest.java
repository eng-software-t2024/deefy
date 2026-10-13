package br.com.deefy.controller;

import br.com.deefy.dto.response.ProfilePhotoResponseDTO;
import br.com.deefy.service.ProfilePhotoDownload;
import br.com.deefy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.security.Principal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerProfilePhotoTest {

    @Mock private UserService userService;
    private UserController controller;
    private Principal principal;

    @BeforeEach
    void setUp() {
        controller = new UserController(userService, mock(br.com.deefy.repository.UserRepository.class),
                mock(br.com.deefy.repository.PerfilRepository.class));
        principal = () -> "owner@example.invalid";
    }

    @Test
    void authenticatedProxyReturnsPrivateNoStoreResponse() {
        byte[] bytes = new byte[]{1, 2, 3};
        when(userService.getMyProfilePhoto(principal.getName()))
                .thenReturn(new ProfilePhotoDownload(bytes, "image/png"));

        var response = controller.getMyProfilePhoto(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTruePrivateNoStore(response.getHeaders().getCacheControl());
        assertArrayEquals(bytes, response.getBody());
        verify(userService).getMyProfilePhoto(principal.getName());
    }

    @Test
    void uploadResponseContainsMetadataButNoStorageLocation() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1});
        ProfilePhotoResponseDTO metadata = new ProfilePhotoResponseDTO(
                true,
                "image/png",
                1,
                Instant.now()
        );
        when(userService.uploadMyProfilePhoto(principal.getName(), file)).thenReturn(metadata);

        var response = controller.uploadMyProfilePhoto(principal, file);

        assertEquals(metadata, response.getBody());
        assertFalse(response.getBody().toString().contains("profiles/"));
    }

    @Test
    void deleteReturnsNoContentOnlyAfterServiceCompletes() {
        var response = controller.removeMyProfilePhoto(principal);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(userService).removeMyProfilePhoto(principal.getName());
    }

    private void assertTruePrivateNoStore(String cacheControl) {
        assertFalse(cacheControl == null || cacheControl.isBlank());
        assertFalse(!cacheControl.contains("private") || !cacheControl.contains("no-store"));
    }
}
