package br.com.deefy.service.impl;


import br.com.deefy.dto.request.*;
import br.com.deefy.dto.response.ProfilePhotoResponseDTO;
import br.com.deefy.dto.response.RegisterResponseDTO;
import br.com.deefy.dto.response.UserResponseDTO;
import br.com.deefy.exception.EmailJaCadastradoException;
import br.com.deefy.exception.ProfilePhotoNotFoundException;
import br.com.deefy.exception.SenhaAtualIncorretaException;
import br.com.deefy.exception.UsuarioNaoEncontradoException;
import br.com.deefy.mapper.UserMapper;
import br.com.deefy.model.Tipo;
import br.com.deefy.model.User;
import br.com.deefy.model.PendingRegistration;
import br.com.deefy.model.UserProfilePhoto;
import br.com.deefy.repository.UserRepository;
import br.com.deefy.repository.PendingRegistrationRepository;
import br.com.deefy.repository.UserProfilePhotoRepository;
import br.com.deefy.service.ProfilePhotoDownload;
import br.com.deefy.service.ProfilePhotoStorageService;
import br.com.deefy.service.UserService;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import br.com.deefy.security.JwtUtil;
import br.com.deefy.service.EmailService;
import br.com.deefy.exception.TokenInvalidoException;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final JwtUtil jwtUtil;
    private final ProfilePhotoStorageService profilePhotoStorageService;
    private final UserProfilePhotoRepository userProfilePhotoRepository;

    public UserServiceImpl(UserRepository userRepository, PendingRegistrationRepository pendingRegistrationRepository,
                           UserMapper userMapper, PasswordEncoder passwordEncoder,
                           EmailService emailService,
                           JwtUtil jwtUtil,
                           ProfilePhotoStorageService profilePhotoStorageService,
                           UserProfilePhotoRepository userProfilePhotoRepository){
        this.userRepository = userRepository;
        this.pendingRegistrationRepository = pendingRegistrationRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.jwtUtil = jwtUtil;
        this.profilePhotoStorageService = profilePhotoStorageService;
        this.userProfilePhotoRepository = userProfilePhotoRepository;
    }

    @Override
    @Transactional
    public RegisterResponseDTO createUser(UserRequestDTO request){

        if(userRepository.existsByEmail(request.email())){
            throw new EmailJaCadastradoException("Este e-mail já está cadastrado no sistema");
        }


        String senhaCriptografada = passwordEncoder.encode(request.senha());


        String tokenAtivacao = jwtUtil.generateAccountActivationToken(
                request.nome(),
                request.email(),
                senhaCriptografada
        );


        PendingRegistration pendingRegistration = pendingRegistrationRepository.findByEmail(request.email())
                .orElseGet(PendingRegistration::new);

        boolean cadastroPendenteAtualizado = pendingRegistration.getEmail() != null;
        pendingRegistration.setNome(request.nome());
        pendingRegistration.setEmail(request.email());
        pendingRegistration.setSenha(senhaCriptografada);
        pendingRegistration.setToken(tokenAtivacao);
        pendingRegistrationRepository.save(pendingRegistration);

        emailService.enviarEmailAtivacaoConta(request.email(), tokenAtivacao);

        String message = cadastroPendenteAtualizado
                ? "Já havia um cadastro pendente para este e-mail. Um novo link de ativação foi enviado."
                : "Link de ativação enviado. Verifique seu e-mail para concluir o cadastro.";

        return new RegisterResponseDTO(request.nome(), request.email(), cadastroPendenteAtualizado, message);
    }

    @Override
    @Transactional
    public void activateAccount(ActivateAccountRequestDTO request) {
        if (!jwtUtil.isAccountActivationTokenValid(request.token())) {
            throw new TokenInvalidoException("O link de ativação é inválido ou já expirou.");
        }

        String email = jwtUtil.extractEmail(request.token());
        if (userRepository.existsByEmail(email)) {
            throw new EmailJaCadastradoException("Este e-mail já foi ativado e cadastrado no sistema.");
        }

        PendingRegistration pendingRegistration = pendingRegistrationRepository.findByEmail(email).orElse(null);
        String nome;
        String senha;

        if (pendingRegistration != null) {
            if (!request.token().equals(pendingRegistration.getToken())) {
                throw new TokenInvalidoException("O cadastro pendente não foi encontrado ou o link de ativação foi substituído.");
            }

            nome = pendingRegistration.getNome();
            senha = pendingRegistration.getSenha();
        } else {
            nome = jwtUtil.extractNomeFromToken(request.token());
            senha = jwtUtil.extractSenhaHashFromToken(request.token());

            if (nome == null || nome.isBlank() || senha == null || senha.isBlank()) {
                throw new TokenInvalidoException("O cadastro pendente não foi encontrado ou o token não é compatível.");
            }
        }

        User user = new User();
        user.setNome(nome);
        user.setEmail(email);
        user.setSenha(senha);
        user.setTipoUsuario(Tipo.USER);
        user.setCreatedAt(LocalDateTime.now());

        userRepository.save(user);
        if (pendingRegistration != null) {
            pendingRegistrationRepository.delete(pendingRegistration);
        }
    }

    @Override
    public UserResponseDTO findUserById(Long id){
        User user = userRepository.findById(id).orElseThrow(
                () -> new UsuarioNaoEncontradoException("User not found with id: " + id)
        );

        return userMapper.toDTO(user);
    }

    @Override
    public Page<UserResponseDTO> findAllUsers(Pageable pageable) {

        Page<User> usersPage = userRepository.findAll(pageable);

        return usersPage.map(userMapper::toDTO);
    }

    //Removido temporaimente para implementação de um novo mais seguro
    /*
    @Override
    @Transactional
    public UserResponseDTO updateUser(Long id, UpdateUserRequestDTO updateUserRequestDTO){
        User user = userRepository.findById(id).orElseThrow(
                () -> new UsuarioNaoEncontradoException("User not found with id: " + id)
        );

        user.setNome(updateUserRequestDTO.nome());

        if(updateUserRequestDTO.password() != null && !updateUserRequestDTO.password().isBlank()){

            user.setSenha(passwordEncoder.encode(updateUserRequestDTO.password()));
        }

        User savedUser = userRepository.save(user);

        return userMapper.toDTO(savedUser);
    }

    @Override
    @Transactional
    public void deleteUser(Long id){
        findUserById(id);
        userRepository.deleteById(id);
    }
    */

    @Override
    public UserResponseDTO findProfileByEmail(String email) {
        // Busca o usuário pelo e-mail extraído do JWT
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Usuário não encontrado na base de dados.")
        );
        return userMapper.toDTO(user);
    }

    @Override
    @Transactional
    public UserResponseDTO updateMyName(String email, UpdateNameRequestDTO request) {
        //Busca o usuário garantindo que ele existe
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Usuário não encontrado na base de dados.")
        );

        user.setNome(request.nome());

        //Salva e converte para DTO
        User savedUser = userRepository.save(user);
        return userMapper.toDTO(savedUser);
    }

    @Override
    @Transactional
    public void changeMyPassword(String email, ChangePasswordRequestDTO request) {
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Usuário não encontrado na base de dados.")
        );

        if (!passwordEncoder.matches(request.senhaAtual(), user.getSenha())) {
            throw new SenhaAtualIncorretaException("A senha atual informada está incorreta.");
        }

        user.setSenha(passwordEncoder.encode(request.novaSenha()));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public ProfilePhotoResponseDTO uploadMyProfilePhoto(String email, MultipartFile file) {
        User user = userRepository.findByEmailForUpdate(email).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Usuário não encontrado na base de dados.")
        );

        UserProfilePhoto previous = userProfilePhotoRepository.findById(user.getId()).orElse(null);
        ProfilePhotoStorageService.StoredProfilePhoto previousReference = toStoredPhoto(previous);
        ProfilePhotoStorageService.StoredProfilePhoto uploaded =
                profilePhotoStorageService.uploadProfilePhoto(user.getId(), file);

        try {
            UserProfilePhoto reference = previous == null
                    ? new UserProfilePhoto(
                    user.getId(),
                    uploaded.bucket(),
                    uploaded.objectPath(),
                    uploaded.contentType(),
                    uploaded.sizeBytes()
            )
                    : previous;
            if (previous != null) {
                reference.replaceWith(
                        uploaded.bucket(),
                        uploaded.objectPath(),
                        uploaded.contentType(),
                        uploaded.sizeBytes()
                );
            }

            UserProfilePhoto saved = userProfilePhotoRepository.saveAndFlush(reference);
            user.setFotoPerfilUrl(null);
            userRepository.saveAndFlush(user);

            if (previousReference != null) {
                profilePhotoStorageService.deleteProfilePhoto(
                        user.getId(),
                        previousReference.bucket(),
                        previousReference.objectPath()
                );
            }
            return toResponse(saved);
        } catch (RuntimeException operationFailure) {
            compensateUploadedPhoto(user.getId(), uploaded, operationFailure);
            throw operationFailure;
        }
    }

    @Override
    public ProfilePhotoDownload getMyProfilePhoto(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Usuário não encontrado na base de dados.")
        );
        UserProfilePhoto reference = userProfilePhotoRepository.findById(user.getId()).orElseThrow(
                () -> new ProfilePhotoNotFoundException("O usuário autenticado não possui foto de perfil.")
        );

        ProfilePhotoStorageService.ProfilePhotoContent content = profilePhotoStorageService.downloadProfilePhoto(
                user.getId(),
                reference.getBucket(),
                reference.getObjectPath()
        );
        return new ProfilePhotoDownload(content.bytes(), reference.getContentType());
    }

    @Override
    @Transactional
    public void removeMyProfilePhoto(String email) {
        User user = userRepository.findByEmailForUpdate(email).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Usuário não encontrado na base de dados.")
        );
        UserProfilePhoto reference = userProfilePhotoRepository.findById(user.getId()).orElseThrow(
                () -> new ProfilePhotoNotFoundException("O usuário autenticado não possui foto de perfil.")
        );

        ProfilePhotoStorageService.StoredProfilePhoto stored = toStoredPhoto(reference);
        ProfilePhotoStorageService.ProfilePhotoContent backup = profilePhotoStorageService.downloadProfilePhoto(
                user.getId(),
                reference.getBucket(),
                reference.getObjectPath()
        );
        profilePhotoStorageService.deleteProfilePhoto(
                user.getId(),
                reference.getBucket(),
                reference.getObjectPath()
        );

        try {
            userProfilePhotoRepository.delete(reference);
            userProfilePhotoRepository.flush();
            user.setFotoPerfilUrl(null);
            userRepository.saveAndFlush(user);
        } catch (RuntimeException databaseFailure) {
            try {
                profilePhotoStorageService.restoreProfilePhoto(user.getId(), stored, backup.bytes());
            } catch (RuntimeException compensationFailure) {
                databaseFailure.addSuppressed(compensationFailure);
            }
            throw databaseFailure;
        }
    }

    private ProfilePhotoStorageService.StoredProfilePhoto toStoredPhoto(UserProfilePhoto reference) {
        if (reference == null) {
            return null;
        }
        return new ProfilePhotoStorageService.StoredProfilePhoto(
                reference.getBucket(),
                reference.getObjectPath(),
                reference.getContentType(),
                reference.getSizeBytes()
        );
    }

    private ProfilePhotoResponseDTO toResponse(UserProfilePhoto reference) {
        return new ProfilePhotoResponseDTO(
                true,
                reference.getContentType(),
                reference.getSizeBytes(),
                reference.getUpdatedAt()
        );
    }

    private void compensateUploadedPhoto(
            Long userId,
            ProfilePhotoStorageService.StoredProfilePhoto uploaded,
            RuntimeException operationFailure
    ) {
        try {
            profilePhotoStorageService.deleteProfilePhoto(
                    userId,
                    uploaded.bucket(),
                    uploaded.objectPath()
            );
        } catch (RuntimeException compensationFailure) {
            operationFailure.addSuppressed(compensationFailure);
        }
    }

    @Override
    public void generatePasswordResetLink(ForgotPasswordRequestDTO request) {
        // 1. Procuramos se o usuário realmente existe na base
        User user = userRepository.findByEmail(request.email()).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Nenhum usuário cadastrado com este e-mail.")
        );

        // 2. Geramos o token stateless temporário contendo o e-mail dele
        String token = jwtUtil.generatePasswordResetToken(user.getEmail());

        // 3. Disparamos o e-mail com o link contendo o token
        emailService.enviarEmailLinkSenha(user.getEmail(), token);
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequestDTO request) {
        if (!jwtUtil.isPasswordResetTokenValid(request.token())) {
            throw new TokenInvalidoException("O token de redefinição é inválido ou já expirou.");
        }

        String email = jwtUtil.extractEmail(request.token());

        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new UsuarioNaoEncontradoException("Usuário associado ao token não foi encontrado.")
        );

        user.setSenha(passwordEncoder.encode(request.novaSenha()));

        userRepository.save(user);
    }
}
