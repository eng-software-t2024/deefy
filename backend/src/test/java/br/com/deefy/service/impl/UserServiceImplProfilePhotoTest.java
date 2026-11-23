package br.com.deefy.service.impl;

import br.com.deefy.dto.response.ProfilePhotoResponseDTO;
import br.com.deefy.exception.InvalidProfilePhotoException;
import br.com.deefy.exception.ProfilePhotoNotFoundException;
import br.com.deefy.exception.ProfilePhotoStorageOperationException;
import br.com.deefy.mapper.UserMapper;
import br.com.deefy.model.User;
import br.com.deefy.model.UserProfilePhoto;
import br.com.deefy.repository.PendingRegistrationRepository;
import br.com.deefy.repository.UserProfilePhotoRepository;
import br.com.deefy.repository.UserRepository;
import br.com.deefy.security.JwtUtil;
import br.com.deefy.service.EmailService;
import br.com.deefy.service.ProfilePhotoStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplProfilePhotoTest {

    private static final String BUCKET = "fotos de perfil";
    private static final String OLD_PATH = "profiles/1/11111111-1111-1111-1111-111111111111.png";
    private static final String NEW_PATH = "profiles/1/22222222-2222-2222-2222-222222222222.png";
    private static final byte[] BYTES = new byte[]{1, 2, 3};

    @Mock private UserRepository userRepository;
    @Mock private PendingRegistrationRepository pendingRegistrationRepository;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private JwtUtil jwtUtil;
    @Mock private ProfilePhotoStorageService profilePhotoStorageService;
    @Mock private UserProfilePhotoRepository userProfilePhotoRepository;

    private UserServiceImpl service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(
                userRepository,
                pendingRegistrationRepository,
                userMapper,
                passwordEncoder,
                emailService,
                jwtUtil,
                profilePhotoStorageService,
                userProfilePhotoRepository
        );
        owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.invalid");
    }

    @Test
    void authorizedUploadStoresOnlyPrivateMetadataAndDoesNotLeakPath() throws Exception {
        ProfilePhotoStorageService.StoredProfilePhoto stored = stored(NEW_PATH);
        MockMultipartFile file = file();
        when(userRepository.findByEmailForUpdate(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userProfilePhotoRepository.findById(1L)).thenReturn(Optional.empty());
        when(profilePhotoStorageService.uploadProfilePhoto(1L, file)).thenReturn(stored);
        when(userProfilePhotoRepository.saveAndFlush(any(UserProfilePhoto.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.saveAndFlush(owner)).thenReturn(owner);

        ProfilePhotoResponseDTO response = service.uploadMyProfilePhoto(owner.getEmail(), file);

        assertTrue(response.available());
        assertEquals("image/png", response.contentType());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("profiles/"));
        assertFalse(json.contains(BUCKET));
        assertFalse(json.toLowerCase().contains("service"));
    }

    @Test
    void invalidUploadDoesNotCreateDatabaseReference() {
        MockMultipartFile file = file();
        when(userRepository.findByEmailForUpdate(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userProfilePhotoRepository.findById(1L)).thenReturn(Optional.empty());
        when(profilePhotoStorageService.uploadProfilePhoto(1L, file))
                .thenThrow(new InvalidProfilePhotoException("invalid"));

        assertThrows(
                InvalidProfilePhotoException.class,
                () -> service.uploadMyProfilePhoto(owner.getEmail(), file)
        );
        verify(userProfilePhotoRepository, never()).saveAndFlush(any());
    }

    @Test
    void replacementRemovesPreviousObject() {
        UserProfilePhoto previous = reference(OLD_PATH);
        MockMultipartFile file = file();
        when(userRepository.findByEmailForUpdate(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userProfilePhotoRepository.findById(1L)).thenReturn(Optional.of(previous));
        when(profilePhotoStorageService.uploadProfilePhoto(1L, file)).thenReturn(stored(NEW_PATH));
        when(userProfilePhotoRepository.saveAndFlush(previous)).thenReturn(previous);
        when(userRepository.saveAndFlush(owner)).thenReturn(owner);

        service.uploadMyProfilePhoto(owner.getEmail(), file);

        verify(profilePhotoStorageService).deleteProfilePhoto(1L, BUCKET, OLD_PATH);
    }

    @Test
    void replacementFailureDeletesNewObjectAndPropagatesFailure() {
        UserProfilePhoto previous = reference(OLD_PATH);
        MockMultipartFile file = file();
        when(userRepository.findByEmailForUpdate(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userProfilePhotoRepository.findById(1L)).thenReturn(Optional.of(previous));
        when(profilePhotoStorageService.uploadProfilePhoto(1L, file)).thenReturn(stored(NEW_PATH));
        when(userProfilePhotoRepository.saveAndFlush(previous)).thenReturn(previous);
        when(userRepository.saveAndFlush(owner)).thenReturn(owner);
        doThrow(new ProfilePhotoStorageOperationException("delete failed"))
                .when(profilePhotoStorageService).deleteProfilePhoto(1L, BUCKET, OLD_PATH);

        assertThrows(
                ProfilePhotoStorageOperationException.class,
                () -> service.uploadMyProfilePhoto(owner.getEmail(), file)
        );
        verify(profilePhotoStorageService).deleteProfilePhoto(1L, BUCKET, NEW_PATH);
    }

    @Test
    void authenticatedReadUsesOnlyTheCurrentUsersReference() {
        UserProfilePhoto reference = reference(OLD_PATH);
        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userProfilePhotoRepository.findById(1L)).thenReturn(Optional.of(reference));
        when(profilePhotoStorageService.downloadProfilePhoto(1L, BUCKET, OLD_PATH))
                .thenReturn(new ProfilePhotoStorageService.ProfilePhotoContent(BYTES, "image/png"));

        var download = service.getMyProfilePhoto(owner.getEmail());

        assertEquals("image/png", download.contentType());
        assertEquals(BYTES.length, download.bytes().length);
    }

    @Test
    void anotherUserCannotSelectTheOwnersReference() {
        User anotherUser = new User();
        anotherUser.setId(2L);
        anotherUser.setEmail("other@example.invalid");
        when(userRepository.findByEmail(anotherUser.getEmail())).thenReturn(Optional.of(anotherUser));
        when(userProfilePhotoRepository.findById(2L)).thenReturn(Optional.empty());

        assertThrows(
                ProfilePhotoNotFoundException.class,
                () -> service.getMyProfilePhoto(anotherUser.getEmail())
        );
        verify(profilePhotoStorageService, never()).downloadProfilePhoto(any(), any(), any());
    }

    @Test
    void ownerDeleteRemovesPhysicalObjectBeforeDatabaseReference() {
        UserProfilePhoto reference = reference(OLD_PATH);
        prepareDelete(reference);
        when(userRepository.saveAndFlush(owner)).thenReturn(owner);

        service.removeMyProfilePhoto(owner.getEmail());

        InOrder order = inOrder(profilePhotoStorageService, userProfilePhotoRepository);
        order.verify(profilePhotoStorageService).downloadProfilePhoto(1L, BUCKET, OLD_PATH);
        order.verify(profilePhotoStorageService).deleteProfilePhoto(1L, BUCKET, OLD_PATH);
        order.verify(userProfilePhotoRepository).delete(reference);
        order.verify(userProfilePhotoRepository).flush();
    }

    @Test
    void anotherUserCannotDeleteTheOwnersReference() {
        User anotherUser = new User();
        anotherUser.setId(2L);
        anotherUser.setEmail("other@example.invalid");
        when(userRepository.findByEmailForUpdate(anotherUser.getEmail())).thenReturn(Optional.of(anotherUser));
        when(userProfilePhotoRepository.findById(2L)).thenReturn(Optional.empty());

        assertThrows(
                ProfilePhotoNotFoundException.class,
                () -> service.removeMyProfilePhoto(anotherUser.getEmail())
        );
        verify(profilePhotoStorageService, never()).deleteProfilePhoto(any(), any(), any());
    }

    @Test
    void storageDeleteFailureKeepsDatabaseReference() {
        UserProfilePhoto reference = reference(OLD_PATH);
        prepareDelete(reference);
        doThrow(new ProfilePhotoStorageOperationException("delete failed"))
                .when(profilePhotoStorageService).deleteProfilePhoto(1L, BUCKET, OLD_PATH);

        assertThrows(
                ProfilePhotoStorageOperationException.class,
                () -> service.removeMyProfilePhoto(owner.getEmail())
        );
        verify(userProfilePhotoRepository, never()).delete(any());
    }

    @Test
    void databaseDeleteFailureRestoresPhysicalObject() {
        UserProfilePhoto reference = reference(OLD_PATH);
        prepareDelete(reference);
        doThrow(new IllegalStateException("database failed")).when(userProfilePhotoRepository).flush();

        assertThrows(IllegalStateException.class, () -> service.removeMyProfilePhoto(owner.getEmail()));
        verify(profilePhotoStorageService).restoreProfilePhoto(
                eq(1L),
                eq(stored(OLD_PATH)),
                argThat(bytes -> java.util.Arrays.equals(bytes, BYTES))
        );
    }

    private void prepareDelete(UserProfilePhoto reference) {
        when(userRepository.findByEmailForUpdate(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userProfilePhotoRepository.findById(1L)).thenReturn(Optional.of(reference));
        when(profilePhotoStorageService.downloadProfilePhoto(1L, BUCKET, OLD_PATH))
                .thenReturn(new ProfilePhotoStorageService.ProfilePhotoContent(BYTES, "image/png"));
    }

    private ProfilePhotoStorageService.StoredProfilePhoto stored(String path) {
        return new ProfilePhotoStorageService.StoredProfilePhoto(BUCKET, path, "image/png", BYTES.length);
    }

    private UserProfilePhoto reference(String path) {
        return new UserProfilePhoto(1L, BUCKET, path, "image/png", BYTES.length);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "avatar.png", "image/png", BYTES);
    }
}
