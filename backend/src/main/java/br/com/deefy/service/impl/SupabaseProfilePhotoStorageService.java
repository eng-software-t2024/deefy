package br.com.deefy.service.impl;

import br.com.deefy.exception.InvalidProfilePhotoException;
import br.com.deefy.exception.ProfilePhotoStorageOperationException;
import br.com.deefy.exception.ProfilePhotoTooLargeException;
import br.com.deefy.service.ProfilePhotoStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SupabaseProfilePhotoStorageService implements ProfilePhotoStorageService {

    private static final Map<String, Set<String>> ALLOWED_EXTENSIONS_BY_TYPE = Map.of(
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/png", Set.of("png"),
            "image/webp", Set.of("webp"),
            "image/gif", Set.of("gif")
    );
    private static final Pattern UUID_FILE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|gif)"
    );
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final String projectUrl;
    private final String serviceRoleKey;
    private final String profileBucket;
    private final long maxSizeBytes;

    @Autowired
    public SupabaseProfilePhotoStorageService(
            @Value("${supabase.project-url:}") String projectUrl,
            @Value("${supabase.service-role-key:}") String serviceRoleKey,
            @Value("${supabase.storage.profile-bucket:fotos de perfil}") String profileBucket,
            @Value("${supabase.storage.profile-max-size-bytes:5242880}") long maxSizeBytes
    ) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                projectUrl,
                serviceRoleKey,
                profileBucket,
                maxSizeBytes
        );
    }

    SupabaseProfilePhotoStorageService(
            HttpClient httpClient,
            String projectUrl,
            String serviceRoleKey,
            String profileBucket,
            long maxSizeBytes
    ) {
        this.httpClient = httpClient;
        this.projectUrl = stripTrailingSlash(projectUrl);
        this.serviceRoleKey = serviceRoleKey == null ? "" : serviceRoleKey;
        this.profileBucket = profileBucket == null ? "" : profileBucket;
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public StoredProfilePhoto uploadProfilePhoto(Long ownerId, MultipartFile file) {
        validateConfiguration();
        validateOwnerId(ownerId);
        byte[] bytes = readAndValidateFile(file);
        String contentType = normalizedContentType(file);
        String extension = canonicalExtension(contentType);
        String objectPath = "profiles/%d/%s.%s".formatted(ownerId, UUID.randomUUID(), extension);

        HttpRequest request = authorizedRequest(objectUri(profileBucket, objectPath))
                .header("Content-Type", contentType)
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        sendExpectingSuccess(request, HttpResponse.BodyHandlers.discarding(), "enviar a foto");
        return new StoredProfilePhoto(profileBucket, objectPath, contentType, bytes.length);
    }

    @Override
    public ProfilePhotoContent downloadProfilePhoto(Long ownerId, String bucket, String objectPath) {
        validateConfiguration();
        validateOwnedReference(ownerId, bucket, objectPath);

        URI uri = URI.create("%s/storage/v1/object/authenticated/%s/%s".formatted(
                projectUrl,
                encodePathSegment(bucket),
                encodePath(objectPath)
        ));
        HttpRequest request = authorizedRequest(uri).GET().build();
        HttpResponse<byte[]> response = sendExpectingSuccess(
                request,
                HttpResponse.BodyHandlers.ofByteArray(),
                "ler a foto"
        );
        String contentType = response.headers()
                .firstValue("Content-Type")
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                .filter(ALLOWED_EXTENSIONS_BY_TYPE::containsKey)
                .orElse("application/octet-stream");
        return new ProfilePhotoContent(response.body(), contentType);
    }

    @Override
    public void deleteProfilePhoto(Long ownerId, String bucket, String objectPath) {
        validateConfiguration();
        validateOwnedReference(ownerId, bucket, objectPath);

        String body = "{\"prefixes\":[\"%s\"]}".formatted(jsonEscape(objectPath));
        URI uri = URI.create("%s/storage/v1/object/%s".formatted(projectUrl, encodePathSegment(bucket)));
        HttpRequest request = authorizedRequest(uri)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        sendExpectingSuccess(request, HttpResponse.BodyHandlers.discarding(), "remover a foto");
    }

    @Override
    public void restoreProfilePhoto(Long ownerId, StoredProfilePhoto reference, byte[] bytes) {
        validateConfiguration();
        validateOwnedReference(ownerId, reference.bucket(), reference.objectPath());
        if (bytes == null || bytes.length != reference.sizeBytes() || bytes.length > maxSizeBytes) {
            throw new ProfilePhotoStorageOperationException("A cópia de compensação da foto é inválida.");
        }

        HttpRequest request = authorizedRequest(objectUri(reference.bucket(), reference.objectPath()))
                .header("Content-Type", reference.contentType())
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        sendExpectingSuccess(request, HttpResponse.BodyHandlers.discarding(), "restaurar a foto");
    }

    private HttpRequest.Builder authorizedRequest(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey);
    }

    private <T> HttpResponse<T> sendExpectingSuccess(
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler,
            String operation
    ) {
        try {
            HttpResponse<T> response = httpClient.send(request, bodyHandler);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProfilePhotoStorageOperationException(
                        "O Storage não pôde %s. Status: %d".formatted(operation, response.statusCode())
                );
            }
            return response;
        } catch (IOException e) {
            throw new ProfilePhotoStorageOperationException(
                    "Não foi possível comunicar com o Storage para " + operation + ".",
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProfilePhotoStorageOperationException("A operação de Storage foi interrompida.", e);
        }
    }

    private byte[] readAndValidateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidProfilePhotoException("Envie uma imagem para atualizar a foto de perfil.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new ProfilePhotoTooLargeException("A imagem excede o tamanho máximo permitido.");
        }

        String originalName = file.getOriginalFilename();
        validateOriginalName(originalName);
        String contentType = normalizedContentType(file);
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS_BY_TYPE.getOrDefault(contentType, Set.of()).contains(extension)) {
            throw new InvalidProfilePhotoException("A extensão do arquivo não corresponde ao tipo MIME informado.");
        }

        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw new InvalidProfilePhotoException("O arquivo de imagem está vazio.");
            }
            if (bytes.length > maxSizeBytes) {
                throw new ProfilePhotoTooLargeException("A imagem excede o tamanho máximo permitido.");
            }
            if (!matchesSignature(contentType, bytes)) {
                throw new InvalidProfilePhotoException("O conteúdo do arquivo não corresponde ao tipo de imagem informado.");
            }
            return bytes;
        } catch (IOException e) {
            throw new InvalidProfilePhotoException("Não foi possível ler o arquivo de imagem.", e);
        }
    }

    private void validateOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank() || originalName.length() > 255) {
            throw new InvalidProfilePhotoException("O arquivo deve ter um nome válido.");
        }
        if (originalName.contains("/") || originalName.contains("\\") || originalName.contains("..")) {
            throw new InvalidProfilePhotoException("O nome do arquivo contém uma sequência inválida.");
        }
        if (originalName.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidProfilePhotoException("O nome do arquivo contém caracteres inválidos.");
        }
        extensionOf(originalName);
    }

    private String normalizedContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new InvalidProfilePhotoException("O tipo MIME da imagem é obrigatório.");
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).trim();
        if (!ALLOWED_EXTENSIONS_BY_TYPE.containsKey(normalized)) {
            throw new InvalidProfilePhotoException("Formato inválido. Use JPG, PNG, WEBP ou GIF.");
        }
        return normalized;
    }

    private String extensionOf(String originalName) {
        int dot = originalName.lastIndexOf('.');
        if (dot <= 0 || dot == originalName.length() - 1) {
            throw new InvalidProfilePhotoException("O arquivo deve ter uma extensão de imagem válida.");
        }
        String extension = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{2,5}")) {
            throw new InvalidProfilePhotoException("A extensão do arquivo é inválida.");
        }
        return extension;
    }

    private String canonicalExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> throw new InvalidProfilePhotoException("Tipo de imagem não suportado.");
        };
    }

    private boolean matchesSignature(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/png" -> startsWith(bytes, new byte[]{
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            });
            case "image/gif" -> startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            case "image/webp" -> bytes.length >= 12
                    && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && Arrays.equals(
                    Arrays.copyOfRange(bytes, 8, 12),
                    "WEBP".getBytes(StandardCharsets.US_ASCII)
            );
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, byte[] signature) {
        return bytes.length >= signature.length
                && Arrays.equals(Arrays.copyOf(bytes, signature.length), signature);
    }

    private void validateOwnedReference(Long ownerId, String bucket, String objectPath) {
        validateOwnerId(ownerId);
        if (!profileBucket.equals(bucket) || objectPath == null) {
            throw new ProfilePhotoStorageOperationException("A referência da foto de perfil é inválida.");
        }
        String prefix = "profiles/%d/".formatted(ownerId);
        String fileName = objectPath.startsWith(prefix) ? objectPath.substring(prefix.length()) : "";
        if (!objectPath.equals(prefix + fileName) || !UUID_FILE.matcher(fileName).matches()) {
            throw new ProfilePhotoStorageOperationException("A referência da foto não pertence ao usuário autenticado.");
        }
    }

    private void validateOwnerId(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new ProfilePhotoStorageOperationException("O proprietário da foto de perfil é inválido.");
        }
    }

    private URI objectUri(String bucket, String objectPath) {
        return URI.create("%s/storage/v1/object/%s/%s".formatted(
                projectUrl,
                encodePathSegment(bucket),
                encodePath(objectPath)
        ));
    }

    private String encodePath(String path) {
        String[] segments = path.split("/");
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                encoded.append('/');
            }
            encoded.append(encodePathSegment(segments[index]));
        }
        return encoded.toString();
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void validateConfiguration() {
        if (projectUrl.isBlank() || serviceRoleKey.isBlank() || profileBucket.isBlank() || maxSizeBytes <= 0) {
            throw new ProfilePhotoStorageOperationException("Storage de foto de perfil não está configurado.");
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("/+$", "");
    }
}
