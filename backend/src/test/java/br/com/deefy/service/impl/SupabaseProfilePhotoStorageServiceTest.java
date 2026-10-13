package br.com.deefy.service.impl;

import br.com.deefy.exception.InvalidProfilePhotoException;
import br.com.deefy.exception.ProfilePhotoStorageOperationException;
import br.com.deefy.service.ProfilePhotoStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupabaseProfilePhotoStorageServiceTest {

    private static final String BUCKET = "fotos de perfil";
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<Void> emptyResponse;

    @Mock
    private HttpResponse<byte[]> byteResponse;

    private SupabaseProfilePhotoStorageService service;

    @BeforeEach
    void setUp() {
        service = new SupabaseProfilePhotoStorageService(
                httpClient,
                "https://storage.invalid",
                "local-placeholder",
                BUCKET,
                1024
        );
    }

    @Test
    void uploadGeneratesOwnedPathAndDoesNotReturnCredential() throws Exception {
        when(emptyResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), anyDiscardingHandler())).thenReturn(emptyResponse);

        ProfilePhotoStorageService.StoredProfilePhoto stored = service.uploadProfilePhoto(
                42L,
                new MockMultipartFile("file", "avatar.png", "image/png", PNG)
        );

        assertEquals(BUCKET, stored.bucket());
        assertTrue(stored.objectPath().matches(
                "profiles/42/[0-9a-f-]{36}\\.png"
        ));
        assertEquals("image/png", stored.contentType());
        assertEquals(PNG.length, stored.sizeBytes());
        assertFalse(stored.toString().contains("local-placeholder"));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), anyDiscardingHandler());
        HttpRequest request = requestCaptor.getValue();
        assertEquals("POST", request.method());
        assertTrue(request.uri().getRawPath().contains("/storage/v1/object/fotos%20de%20perfil/profiles/42/"));
        assertEquals(List.of("false"), request.headers().allValues("x-upsert"));
    }

    @Test
    void rejectsMimeExtensionMismatchBeforeCallingStorage() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/png", PNG);

        assertThrows(InvalidProfilePhotoException.class, () -> service.uploadProfilePhoto(42L, file));
        verifyNoStorageCall();
    }

    @Test
    void rejectsSpoofedContentBeforeCallingStorage() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                "not-an-image".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(InvalidProfilePhotoException.class, () -> service.uploadProfilePhoto(42L, file));
        verifyNoStorageCall();
    }

    @Test
    void rejectsUnsafeOriginalNameBeforeCallingStorage() {
        MockMultipartFile file = new MockMultipartFile("file", "../avatar.png", "image/png", PNG);

        assertThrows(InvalidProfilePhotoException.class, () -> service.uploadProfilePhoto(42L, file));
        verifyNoStorageCall();
    }

    @Test
    void downloadsOnlyAnOwnedReference() throws Exception {
        String path = "profiles/42/11111111-1111-1111-1111-111111111111.png";
        when(byteResponse.statusCode()).thenReturn(200);
        when(byteResponse.body()).thenReturn(PNG);
        when(byteResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(
                java.util.Map.of("Content-Type", List.of("image/png")),
                (name, value) -> true
        ));
        when(httpClient.send(any(HttpRequest.class), anyByteArrayHandler())).thenReturn(byteResponse);

        ProfilePhotoStorageService.ProfilePhotoContent content =
                service.downloadProfilePhoto(42L, BUCKET, path);

        assertArrayEquals(PNG, content.bytes());
        assertEquals("image/png", content.contentType());
    }

    @Test
    void blocksReferenceOwnedByAnotherUserWithoutCallingStorage() {
        String path = "profiles/42/11111111-1111-1111-1111-111111111111.png";

        assertThrows(
                ProfilePhotoStorageOperationException.class,
                () -> service.downloadProfilePhoto(99L, BUCKET, path)
        );
        verifyNoStorageCall();
    }

    @Test
    void deleteUsesTheStorageApiForTheExactOwnedPath() throws Exception {
        String path = "profiles/42/11111111-1111-1111-1111-111111111111.png";
        when(emptyResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), anyDiscardingHandler())).thenReturn(emptyResponse);

        service.deleteProfilePhoto(42L, BUCKET, path);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), anyDiscardingHandler());
        HttpRequest request = requestCaptor.getValue();
        assertEquals("DELETE", request.method());
        assertEquals("/storage/v1/object/fotos%20de%20perfil", request.uri().getRawPath());
    }

    @Test
    void deleteFailsExplicitlyWhenStorageRejectsIt() throws Exception {
        String path = "profiles/42/11111111-1111-1111-1111-111111111111.png";
        when(emptyResponse.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), anyDiscardingHandler())).thenReturn(emptyResponse);

        assertThrows(
                ProfilePhotoStorageOperationException.class,
                () -> service.deleteProfilePhoto(42L, BUCKET, path)
        );
    }

    @SuppressWarnings("unchecked")
    private HttpResponse.BodyHandler<Void> anyDiscardingHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse.BodyHandler<byte[]> anyByteArrayHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    private void verifyNoStorageCall() {
        try {
            verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
