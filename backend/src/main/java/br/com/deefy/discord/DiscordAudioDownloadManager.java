package br.com.deefy.discord;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class DiscordAudioDownloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordAudioDownloadManager.class);
    private static final int MAX_REDIRECTS = 3;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int TOTAL_ATTEMPTS = 3;

    private final DiscordPlaybackProperties properties;
    private final DiscordAudioValidatorPort validator;
    private final ConcurrentMap<Long, CompletableFuture<DiscordPreparedAudio>> inFlight =
            new ConcurrentHashMap<>();
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(4, runnable ->
            Thread.ofPlatform().daemon(true).name("deefy-discord-audio-download").unstarted(runnable));
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().daemon(true).name("deefy-discord-audio-cache-cleanup").unstarted(runnable));

    @Autowired
    public DiscordAudioDownloadManager(
            DiscordPlaybackProperties properties,
            DiscordAudioValidatorPort validator
    ) {
        this.properties = properties;
        this.validator = validator;
    }

    @PostConstruct
    void scheduleCleanup() {
        Duration interval = properties.getCacheCleanupInterval();
        if (interval != null && !interval.isZero() && !interval.isNegative()) {
            cleanupExecutor.scheduleWithFixedDelay(this::cleanupSafely,
                    interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void close() {
        cleanupExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
    }

    public void preflight() {
        Path directory = properties.cachePath();
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
                throw new IOException("cache directory is not writable");
            }
            Path probe = directory.resolve(".deefy-cache-preflight-" + UUID.randomUUID());
            Files.writeString(probe, "ok", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.deleteIfExists(probe);
        } catch (IOException | RuntimeException failure) {
            throw new DiscordAudioPreparationException(
                    "O cache local de áudio não está disponível para gravação.", failure);
        }
    }

    public CompletableFuture<DiscordPreparedAudio> prepare(DiscordTrack track) {
        if (track == null || track.id() == null || track.id() <= 0) {
            return CompletableFuture.failedFuture(new DiscordAudioPreparationException(
                    "A faixa não possui um identificador válido."));
        }
        if (!properties.isSelfHostedAudioUrl(track.audioUrl())) {
            return CompletableFuture.failedFuture(new DiscordAudioPreparationException(
                    "A origem da faixa não é permitida."));
        }

        return inFlight.computeIfAbsent(track.id(), ignored -> {
            CompletableFuture<DiscordPreparedAudio> future;
            try {
                future = CompletableFuture.supplyAsync(() -> prepareBlocking(track), downloadExecutor);
            } catch (RuntimeException failure) {
                future = CompletableFuture.failedFuture(failure);
            }
            CompletableFuture<DiscordPreparedAudio> created = future;
            created.whenComplete((result, failure) -> inFlight.remove(track.id(), created));
            return created;
        });
    }

    void cleanupNow() {
        cleanupSafely();
    }

    int inFlightCount() {
        return inFlight.size();
    }

    private DiscordPreparedAudio prepareBlocking(DiscordTrack track) {
        preflight();
        Path finalPath = finalPath(track.id());
        Path metadataPath = metadataPath(track.id());
        CacheMetadata cached = readUsableCache(track, finalPath, metadataPath);
        if (cached != null) {
            DiscordValidatedAudioMetadata validated = validator.validate(finalPath);
            LOGGER.info("Discord audio cache hit: trackId={}, bytes={}", track.id(), cached.size());
            return cached.audio(finalPath, track.id(), validated);
        }

        deleteCacheFiles(finalPath, metadataPath);
        int attempts = Math.max(1, Math.min(TOTAL_ATTEMPTS, properties.getAudioDownloadAttempts()));
        DownloadedFile downloaded = null;
        DiscordAudioPreparationException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                downloaded = downloadOnce(track, attempt);
                break;
            } catch (DiscordAudioPreparationException failure) {
                lastFailure = failure;
                if (!isRetryable(failure) || attempt == attempts) {
                    throw failure;
                }
                sleepBetweenAttempts(attempt - 1);
            }
        }
        if (downloaded == null) {
            throw lastFailure == null
                    ? new DiscordAudioPreparationException("Não foi possível preparar o áudio.")
                    : lastFailure;
        }

        DiscordValidatedAudioMetadata validated;
        try {
            validated = validator.validate(downloaded.part());
            moveAtomically(downloaded.part(), finalPath);
            writeMetadataAtomically(metadataPath, downloaded.metadata());
        } catch (IOException | DiscordAudioPreparationException failure) {
            deleteQuietly(downloaded.part());
            deleteCacheFiles(finalPath, metadataPath);
            if (failure instanceof DiscordAudioPreparationException preparationFailure) {
                throw preparationFailure;
            }
            throw new DiscordAudioPreparationException("Não foi possível finalizar o cache de áudio.", failure);
        }
        LOGGER.info("Discord audio cached: trackId={}, bytes={}", track.id(), downloaded.metadata().size());
        return downloaded.metadata().audio(finalPath, track.id(), validated);
    }

    private DownloadedFile downloadOnce(DiscordTrack track, int attempt) {
        Path part = properties.cachePath().resolve(track.id() + "." + attempt + ".part");
        try {
            Files.deleteIfExists(part);
            HttpURLConnection connection = open(track.audioUrl(), "GET");
            int status = connection.getResponseCode();
            if (isRedirect(status)) {
                connection.disconnect();
                throw new DiscordAudioPreparationException("A origem redirecionou de forma inesperada.");
            }
            if (status < 200 || status >= 300) {
                boolean retryable = isRetryableStatus(status);
                connection.disconnect();
                throw new DownloadFailure("A origem não entregou o áudio.", retryable);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > properties.getCacheMaxFileBytes()) {
                connection.disconnect();
                throw new DiscordAudioPreparationException("O arquivo de áudio excede o limite configurado.");
            }
            long bytes = 0;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    bytes += read;
                    if (bytes > properties.getCacheMaxFileBytes()) {
                        throw new DiscordAudioPreparationException("O arquivo de áudio excede o limite configurado.");
                    }
                    output.write(buffer, 0, read);
                }
            } finally {
                connection.disconnect();
            }
            if (bytes == 0) {
                throw new DiscordAudioPreparationException("A origem entregou um áudio vazio.");
            }
            return new DownloadedFile(part, new CacheMetadata(
                    bytes,
                    sha256(part),
                    safeHeader(connection, "ETag"),
                    connection.getLastModified()
            ));
        } catch (IOException failure) {
            deleteQuietly(part);
            throw new DownloadFailure("Falha transitória ao baixar o áudio.", true, failure);
        } catch (DiscordAudioPreparationException failure) {
            deleteQuietly(part);
            throw failure;
        }
    }

    private HttpURLConnection open(String rawUrl, String method) {
        URI current;
        try {
            current = URI.create(rawUrl);
        } catch (IllegalArgumentException failure) {
            throw new DiscordAudioPreparationException("A URL de áudio é inválida.", failure);
        }
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!properties.isSelfHostedAudioUrl(current.toString())) {
                throw new DiscordAudioPreparationException("A origem da faixa não é permitida.");
            }
            try {
                HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setConnectTimeout((int) properties.getAudioConnectTimeout().toMillis());
                connection.setReadTimeout((int) properties.getAudioReadTimeout().toMillis());
                int status = connection.getResponseCode();
                if (!isRedirect(status)) {
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank()) {
                    throw new DiscordAudioPreparationException("A origem redirecionou sem destino válido.");
                }
                current = current.resolve(location);
            } catch (IOException failure) {
                throw new DownloadFailure("Falha transitória ao acessar o áudio.", true, failure);
            }
        }
        throw new DiscordAudioPreparationException("A origem excedeu o limite de redirecionamentos.");
    }

    private CacheMetadata readUsableCache(DiscordTrack track, Path finalPath, Path metadataPath) {
        if (!Files.isRegularFile(finalPath) || !Files.isRegularFile(metadataPath)) {
            return null;
        }
        try {
            Instant expiry = Files.getLastModifiedTime(finalPath).toInstant()
                    .plus(properties.getCacheTtl());
            if (Instant.now().isAfter(expiry)) {
                return null;
            }
            Properties stored = new Properties();
            try (InputStream input = Files.newInputStream(metadataPath)) {
                stored.load(input);
            }
            CacheMetadata metadata = CacheMetadata.from(stored);
            if (metadata == null || Files.size(finalPath) != metadata.size()
                    || !metadata.sha256().equals(sha256(finalPath))) {
                return null;
            }
            if ((metadata.etag() != null && !metadata.etag().isBlank()) || metadata.lastModified() > 0) {
                return isRemoteRevisionCurrent(track.audioUrl(), metadata) ? metadata : null;
            }
            return metadata;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private boolean isRemoteRevisionCurrent(String url, CacheMetadata cached) {
        try {
            HttpURLConnection connection = open(url, "HEAD");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                connection.disconnect();
                return false;
            }
            String remoteEtag = connection.getHeaderField("ETag");
            long remoteLastModified = connection.getLastModified();
            connection.disconnect();
            if (cached.etag() != null && !cached.etag().isBlank()
                    && remoteEtag != null && !remoteEtag.isBlank()) {
                return cached.etag().equals(remoteEtag);
            }
            if (cached.lastModified() > 0 && remoteLastModified > 0) {
                return cached.lastModified() == remoteLastModified;
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private void cleanupSafely() {
        try {
            preflight();
            removeExpiredPartials();
            removeExpiredAudio();
            enforceTotalLimit();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Discord audio cache cleanup failed: reason=controlled-failure");
        }
    }

    private void removeExpiredPartials() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(properties.cachePath(), "*.part")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }

    private void removeExpiredAudio() throws IOException {
        Instant cutoff = Instant.now().minus(properties.getCacheTtl());
        try (DirectoryStream<Path> files = Files.newDirectoryStream(properties.cachePath(), "*.audio")) {
            for (Path file : files) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(metadataPathForAudio(file));
                }
            }
        }
    }

    private void enforceTotalLimit() throws IOException {
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(properties.cachePath(), "*.audio")) {
            for (Path file : stream) {
                files.add(file);
                total += Files.size(file);
            }
        }
        files.sort(Comparator.comparing(this::lastModified));
        for (Path file : files) {
            if (total <= properties.getCacheMaxBytes()) {
                break;
            }
            total -= Files.size(file);
            Files.deleteIfExists(file);
            Files.deleteIfExists(metadataPathForAudio(file));
        }
    }

    private Path finalPath(long trackId) {
        return properties.cachePath().resolve(trackId + ".audio");
    }

    private Path metadataPath(long trackId) {
        return properties.cachePath().resolve(trackId + ".meta");
    }

    private Path metadataPathForAudio(Path audio) {
        String name = audio.getFileName().toString();
        return audio.resolveSibling(name.substring(0, name.length() - ".audio".length()) + ".meta");
    }

    private void writeMetadataAtomically(Path target, CacheMetadata metadata) throws IOException {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Properties propertiesFile = metadata.toProperties();
        try {
            try (OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                propertiesFile.store(output, null);
            }
            moveAtomically(partial, target);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("O sistema de arquivos não suporta rename atômico.", failure);
        }
    }

    private void deleteCacheFiles(Path finalPath, Path metadataPath) {
        deleteQuietly(finalPath);
        deleteQuietly(metadataPath);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A próxima limpeza fará nova tentativa sem expor detalhes.
        }
    }

    private void sleepBetweenAttempts(int failedAttemptIndex) {
        List<Duration> backoff = properties.getAudioDownloadBackoff();
        Duration delay = failedAttemptIndex < backoff.size() ? backoff.get(failedAttemptIndex) : Duration.ZERO;
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DiscordAudioPreparationException("A preparação do áudio foi interrompida.", failure);
        }
    }

    private boolean isRetryable(DiscordAudioPreparationException failure) {
        return failure instanceof DownloadFailure downloadFailure && downloadFailure.retryable();
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    private long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String safeHeader(HttpURLConnection connection, String name) {
        return connection.getHeaderField(name);
    }

    private String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append("%02x".formatted(value));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new DiscordAudioPreparationException("Não foi possível verificar o cache de áudio.", failure);
        }
    }

    private record DownloadedFile(Path part, CacheMetadata metadata) { }

    private record CacheMetadata(long size, String sha256, String etag, long lastModified) {
        private Properties toProperties() {
            Properties values = new Properties();
            values.setProperty("size", Long.toString(size));
            values.setProperty("sha256", sha256);
            if (etag != null && !etag.isBlank()) {
                values.setProperty("etag", etag);
            }
            if (lastModified > 0) {
                values.setProperty("lastModified", Long.toString(lastModified));
            }
            return values;
        }

        private static CacheMetadata from(Properties values) {
            try {
                long size = Long.parseLong(values.getProperty("size", "-1"));
                String sha256 = values.getProperty("sha256", "");
                String etag = values.getProperty("etag", "");
                long lastModified = Long.parseLong(values.getProperty("lastModified", "0"));
                return size > 0 && !sha256.isBlank()
                        ? new CacheMetadata(size, sha256, etag, lastModified)
                        : null;
            } catch (NumberFormatException failure) {
                return null;
            }
        }

        private DiscordPreparedAudio audio(
                Path path,
                long trackId,
                DiscordValidatedAudioMetadata metadata
        ) {
            return new DiscordPreparedAudio(trackId, path, metadata, sha256, etag, lastModified);
        }
    }

    private static final class DownloadFailure extends DiscordAudioPreparationException {
        private final boolean retryable;

        private DownloadFailure(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        private DownloadFailure(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        private boolean retryable() {
            return retryable;
        }
    }
}
