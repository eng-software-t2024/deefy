package br.com.deefy.controller;

import br.com.deefy.config.OpenApiConfig;
import br.com.deefy.controller.docs.UserControllerDocs;
import br.com.deefy.dto.request.AdminUserBanRequestDTO;
import br.com.deefy.dto.request.AdminUserRoleRequestDTO;
import br.com.deefy.dto.request.AdminUserUpdateRequestDTO;
import br.com.deefy.dto.request.ChangePasswordRequestDTO;
import br.com.deefy.dto.request.UpdateNameRequestDTO;
import br.com.deefy.dto.response.AdminUserResponseDTO;
import br.com.deefy.dto.response.AdminUserStatsResponseDTO;
import br.com.deefy.dto.response.ProfilePhotoResponseDTO;
import br.com.deefy.dto.response.UserResponseDTO;
import br.com.deefy.exception.EmailJaCadastradoException;
import br.com.deefy.exception.UsuarioNaoEncontradoException;
import br.com.deefy.model.Perfil;
import br.com.deefy.model.User;
import br.com.deefy.repository.PerfilRepository;
import br.com.deefy.repository.UserRepository;
import br.com.deefy.service.UserService;
import br.com.deefy.service.ProfilePhotoDownload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping(value = "/api/v1/users")
@Tag(name = "User/Profile", description = "Usuarios e perfil do usuario autenticado")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class UserController implements UserControllerDocs {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PerfilRepository perfilRepository;

    public UserController(UserService userService, UserRepository userRepository, PerfilRepository perfilRepository){
        this.userService = userService;
        this.userRepository = userRepository;
        this.perfilRepository = perfilRepository;
    }

    @GetMapping(value = "/{id}")
    @Operation(summary = "Buscar usuario por ID", description = "Retorna os dados publicos de um usuario pelo ID.")
    public ResponseEntity<UserResponseDTO> findUserById(
            @PathVariable Long id
    ) {
        UserResponseDTO user = userService.findUserById(id);
        return ResponseEntity.ok().body(user);
    }

    @GetMapping
    @Operation(summary = "Listar usuarios", description = "Retorna usuarios paginados.")
    public ResponseEntity<Page<AdminUserResponseDTO>> findAllUsers(
            @PageableDefault(size = 5, sort = "id")
            Pageable pageable
    ){
        PageRequest safePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<AdminUserResponseDTO> listAllUsers = userRepository.findAll(safePageable).map(this::toAdminDTO);
        return ResponseEntity.ok().body(listAllUsers);
    }

    @GetMapping(value = "/stats")
    @Operation(summary = "Estatisticas administrativas de usuarios", description = "Retorna agregados usados pela tela /admin/users sem exigir novas colunas no banco.")
    public ResponseEntity<AdminUserStatsResponseDTO> getUserStats() {
        List<User> users = userRepository.findAll();
        long total = users.size();
        long admins = users.stream().filter(this::isAdmin).count();
        return ResponseEntity.ok(new AdminUserStatsResponseDTO(
                total,
                total,
                0,
                0,
                admins,
                0,
                total,
                0
        ));
    }

    @PutMapping(value = "/{id}")
    @Transactional
    @Operation(summary = "Atualizar usuario pelo admin", description = "Atualiza nome, e-mail e role quando solicitado pela tela administrativa.")
    public ResponseEntity<AdminUserResponseDTO> updateUser(
            @PathVariable Long id,
            @RequestBody AdminUserUpdateRequestDTO requestDTO
    ){
        User user = findUserOrThrow(id);

        String name = firstNonBlank(requestDTO.name(), null);
        if (name != null) {
            user.setNome(name);
        }

        String email = firstNonBlank(requestDTO.email(), null);
        if (email != null && !email.equalsIgnoreCase(user.getEmail())) {
            userRepository.findByEmail(email)
                    .filter(existing -> !existing.getId().equals(user.getId()))
                    .ifPresent(existing -> {
                        throw new EmailJaCadastradoException("Este e-mail já está cadastrado no sistema");
                    });
            user.setEmail(email);
        }

        if (requestDTO.role() != null && !requestDTO.role().isBlank()) {
            applyRole(user, requestDTO.role());
        }

        return ResponseEntity.ok(toAdminDTO(userRepository.save(user)));
    }

    @DeleteMapping(value = "/{id}")
    @Transactional
    @Operation(summary = "Excluir usuario pelo admin", description = "Remove o usuario quando o banco permitir a exclusao sem violar relacionamentos.")
    public ResponseEntity<Void> deleteUser(
            @PathVariable Long id
    ){
        if (!userRepository.existsById(id)) {
            throw new UsuarioNaoEncontradoException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping(value = "/{id}/ban")
    @Operation(summary = "Banir/desbanir usuario", description = "Endpoint compatível com o painel admin; banimento persistente depende de coluna propria no banco.")
    public ResponseEntity<AdminUserResponseDTO> setBanStatus(
            @PathVariable Long id,
            @RequestBody AdminUserBanRequestDTO requestDTO
    ) {
        User user = findUserOrThrow(id);
        if (requestDTO.banned()) {
            throw new IllegalArgumentException("Banimento ainda depende de uma coluna/campo oficial no banco de dados.");
        }
        return ResponseEntity.ok(toAdminDTO(user));
    }

    @PatchMapping(value = "/{id}/role")
    @Transactional
    @Operation(summary = "Alterar role do usuario", description = "Atualiza perfil_id conforme os perfis existentes no banco.")
    public ResponseEntity<AdminUserResponseDTO> setUserRole(
            @PathVariable Long id,
            @RequestBody AdminUserRoleRequestDTO requestDTO
    ) {
        User user = findUserOrThrow(id);
        applyRole(user, requestDTO.role());
        return ResponseEntity.ok(toAdminDTO(userRepository.save(user)));
    }


    @GetMapping(value = "/me")
    @Operation(summary = "Buscar meu perfil", description = "Retorna o perfil do usuario autenticado pelo JWT.")
    public ResponseEntity<UserResponseDTO> getMyProfile(Principal principal) {
        // principal.getName() retorna o "username" do JWT, que no nosso caso é o e-mail.
        String email = principal.getName();
        UserResponseDTO profile = userService.findProfileByEmail(email);
        return ResponseEntity.ok(profile);
    }

    @PatchMapping(value = "/me/name")
    @Operation(summary = "Atualizar meu nome", description = "Atualiza o nome do usuario autenticado pelo JWT.")
    public ResponseEntity<UserResponseDTO> updateMyName(
            Principal principal,
            @Valid @RequestBody UpdateNameRequestDTO request) {

        String email = principal.getName();
        UserResponseDTO updatedProfile = userService.updateMyName(email, request);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping(value = "/me/password")
    @Operation(summary = "Alterar minha senha", description = "Altera a senha do usuario autenticado validando a senha atual.")
    public ResponseEntity<Void> changeMyPassword(
            Principal principal,
            @Valid @RequestBody ChangePasswordRequestDTO request) {

        String email = principal.getName();
        userService.changeMyPassword(email, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/me/photo")
    @Operation(summary = "Ler minha foto de perfil", description = "Entrega a foto privada do usuario autenticado por proxy.")
    public ResponseEntity<byte[]> getMyProfilePhoto(Principal principal) {
        ProfilePhotoDownload photo = userService.getMyProfilePhoto(principal.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .contentLength(photo.bytes().length)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.bytes());
    }

    @PostMapping(value = "/me/photo/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Enviar minha foto de perfil", description = "Envia uma imagem privada e salva somente a referencia controlada pelo backend.")
    public ResponseEntity<ProfilePhotoResponseDTO> uploadMyProfilePhoto(
            Principal principal,
            @RequestPart("file") MultipartFile file) {

        String email = principal.getName();
        return ResponseEntity.ok(userService.uploadMyProfilePhoto(email, file));
    }

    @DeleteMapping(value = "/me/photo")
    @Operation(summary = "Remover minha foto de perfil", description = "Remove fisicamente a foto privada antes de limpar sua referencia.")
    public ResponseEntity<Void> removeMyProfilePhoto(Principal principal) {
        userService.removeMyProfilePhoto(principal.getName());
        return ResponseEntity.noContent().build();
    }

    private User findUserOrThrow(Long id) {
        return userRepository.findById(id).orElseThrow(
                () -> new UsuarioNaoEncontradoException("User not found with id: " + id)
        );
    }

    private AdminUserResponseDTO toAdminDTO(User user) {
        String role = roleOf(user);
        String username = usernameOf(user);
        return new AdminUserResponseDTO(
                user.getId(),
                user.getNome(),
                user.getNome(),
                user.getEmail(),
                username,
                role,
                user.getFotoPerfilUrl(),
                user.getFotoPerfilUrl(),
                user.getCreatedAt(),
                false,
                false,
                false,
                "offline"
        );
    }

    private void applyRole(User user, String requestedRole) {
        String role = requestedRole == null ? "" : requestedRole.trim().toUpperCase(Locale.ROOT);
        if (role.equals("ADMIN")) {
            role = "ROLE_ADMIN";
        }

        if (role.equals("ROLE_ADMIN")) {
            Perfil adminPerfil = perfilRepository.findAll().stream()
                    .filter(perfil -> perfil.getNome() != null)
                    .filter(perfil -> perfil.getNome().toUpperCase(Locale.ROOT).contains("ADMIN"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Perfil administrador não existe no banco de dados."));
            user.setPerfil(adminPerfil);
            return;
        }

        if (role.equals("ROLE_USER") || role.equals("USER") || role.isBlank()) {
            Perfil userPerfil = perfilRepository.findAll().stream()
                    .filter(perfil -> perfil.getNome() != null)
                    .filter(perfil -> !perfil.getNome().toUpperCase(Locale.ROOT).contains("ADMIN"))
                    .findFirst()
                    .orElse(null);
            user.setPerfil(userPerfil);
            return;
        }

        throw new IllegalArgumentException("Role inválida.");
    }

    private boolean isAdmin(User user) {
        return roleOf(user).equals("ROLE_ADMIN");
    }

    private String roleOf(User user) {
        String perfilNome = user.getPerfil() == null ? "" : user.getPerfil().getNome();
        return perfilNome.toUpperCase(Locale.ROOT).contains("ADMIN") ? "ROLE_ADMIN" : "ROLE_USER";
    }

    private String usernameOf(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return null;
        }
        int at = user.getEmail().indexOf('@');
        return at > 0 ? user.getEmail().substring(0, at) : user.getEmail();
    }

    private String firstNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
