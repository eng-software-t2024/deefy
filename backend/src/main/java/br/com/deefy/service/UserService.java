package br.com.deefy.service;

import br.com.deefy.dto.request.*;
import br.com.deefy.dto.response.RegisterResponseDTO;
import br.com.deefy.dto.response.ProfilePhotoResponseDTO;
import br.com.deefy.dto.response.UserResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface UserService {
    RegisterResponseDTO createUser(UserRequestDTO request);

    UserResponseDTO findUserById(Long id);

    Page<UserResponseDTO> findAllUsers(Pageable pageable);

    //UserResponseDTO updateUser(Long id, UpdateUserRequestDTO updateUserRequestDTO);

    //void deleteUser(Long id);

    UserResponseDTO findProfileByEmail(String email);

    UserResponseDTO updateMyName(String email, UpdateNameRequestDTO request);

    ProfilePhotoResponseDTO uploadMyProfilePhoto(String email, MultipartFile file);

    ProfilePhotoDownload getMyProfilePhoto(String email);

    void removeMyProfilePhoto(String email);

    void changeMyPassword(String email, ChangePasswordRequestDTO request);

    void generatePasswordResetLink(ForgotPasswordRequestDTO request);

    void resetPassword(ResetPasswordRequestDTO request);

    void activateAccount(ActivateAccountRequestDTO request);
}
