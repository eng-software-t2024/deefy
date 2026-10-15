package br.com.deefy.discord;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordAudioDownloadManagerTest {

    private HttpServer server;
    private Path cacheDirectory;
    private DiscordPlaybackProperties properties;
    private DiscordAudioDownloadManager manager;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cacheDirectory = Files.createTempDirectory("deefy-audio-cache-");
        properties = new DiscordPlaybackProperties();
        properties.setApiUrl(origin());
        properties.setCacheDirectory(cacheDirectory.toString());
        properties.setCacheCleanupInterval(Duration.ZERO);
        properties.setAudioDownloadBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        manager = new DiscordAudioDownloadManager(properties, this::validateFixture);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        server.stop(0);
    }

    @Test
    void shouldDownloadAtomicallyAndReuseCanonicalIdCache() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/audio/1", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "audio-one", null);
        });
        server.start();

        DiscordTrack track = track(1, "/audio/1");
        DiscordPreparedAudio first = manager.prepare(track).join();
        DiscordPreparedAudio second = manager.prepare(track).join();

        assertThat(first.path()).isEqualTo(cacheDirectory.resolve("1.audio"));
        assertThat(Files.readString(first.path())).isEqualTo("audio-one");
        assertThat(second.path()).isEqualTo(first.path());
        assertThat(requests).hasValue(1);
        assertThat(Files.list(cacheDirectory).map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".part")).toList()).isEmpty();
    }

    @Test
    void shouldDeduplicateConcurrentDownloadsByTrackId() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/audio/2", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "audio-two", null);
        });
        server.start();

        DiscordTrack track = track(2, "/audio/2");
        CompletableFuture<DiscordPreparedAudio> first = manager.prepare(track);
        CompletableFuture<DiscordPreparedAudio> second = manager.prepare(track);

        assertThat(first).isSameAs(second);
        assertThat(first.join().path()).isEqualTo(cacheDirectory.resolve("2.audio"));
        assertThat(requests).hasValue(1);
    }

    @Test
    void shouldUseExactlyThreeTotalAttemptsForTransientFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/audio/3", exchange -> {
            int current = requests.incrementAndGet();
            respond(exchange, current < 3 ? 503 : 200, "audio-three", null);
        });
        server.start();

        DiscordPreparedAudio prepared = manager.prepare(track(3, "/audio/3")).join();

        assertThat(prepared.path()).exists();
        assertThat(requests).hasValue(3);
    }

    @Test
    void shouldRejectExternalOriginBeforeOpeningConnection() {
        properties.setApiUrl("https://deefy.olua.me");

        assertThatThrownBy(() -> manager.prepare(new DiscordTrack(
                4L, "title", "artist", "https://old.example/audio/4"
        )).join())
                .hasRootCauseInstanceOf(DiscordAudioPreparationException.class);
    }

    @Test
    void shouldRejectFilesLargerThanConfiguredLimitAndLeaveNoFinalFile() throws Exception {
        properties.setCacheMaxFileBytes(3);
        server.createContext("/audio/5", exchange -> respond(exchange, 200, "1234", null));
        server.start();

        assertThatThrownBy(() -> manager.prepare(track(5, "/audio/5")).join())
                .hasRootCauseInstanceOf(DiscordAudioPreparationException.class);
        assertThat(cacheDirectory.resolve("5.audio")).doesNotExist();
        assertThat(Files.list(cacheDirectory).map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".part")).toList()).isEmpty();
    }

    @Test
    void shouldInvalidateCacheWhenRemoteEtagChanges() throws Exception {
        AtomicReference<String> version = new AtomicReference<>("one");
        AtomicInteger gets = new AtomicInteger();
        server.createContext("/audio/6", exchange -> {
            exchange.getResponseHeaders().set("ETag", version.get());
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            gets.incrementAndGet();
            respond(exchange, 200, "audio-" + version.get(), version.get());
        });
        server.start();

        DiscordTrack track = track(6, "/audio/6");
        manager.prepare(track).join();
        version.set("two");
        DiscordPreparedAudio refreshed = manager.prepare(track).join();

        assertThat(Files.readString(refreshed.path())).isEqualTo("audio-two");
        assertThat(gets).hasValue(2);
    }

    @Test
    void shouldFailClosedWhenCachePathIsNotWritableDirectory() throws Exception {
        Path cacheFile = Files.createTempFile("deefy-cache-file-", ".tmp");
        properties.setCacheDirectory(cacheFile.toString());
        DiscordAudioDownloadManager isolatedManager = new DiscordAudioDownloadManager(
                properties,
                this::validateFixture
        );
        try {
            assertThatThrownBy(() -> isolatedManager.prepare(track(7, "/audio/7")).join())
                    .hasMessageContaining("O cache local de áudio não está disponível para gravação.");
        } finally {
            isolatedManager.close();
            Files.deleteIfExists(cacheFile);
        }
    }

    @Test
    void shouldRejectRedirectOutsideConfiguredOriginBeforeSecondConnection() throws Exception {
        AtomicInteger externalRequests = new AtomicInteger();
        HttpServer external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        external.createContext("/audio/8", exchange -> {
            externalRequests.incrementAndGet();
            respond(exchange, 200, "must-not-be-read", null);
        });
        external.start();
        server.createContext("/audio/8", exchange -> {
            exchange.getResponseHeaders().set(
                    "Location",
                    "http://127.0.0.1:" + external.getAddress().getPort() + "/audio/8"
            );
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        try {
            assertThatThrownBy(() -> manager.prepare(track(8, "/audio/8")).join())
                    .hasRootCauseInstanceOf(DiscordAudioPreparationException.class);
            assertThat(externalRequests).hasValue(0);
        } finally {
            external.stop(0);
        }
    }

    private DiscordTrack track(long id, String path) {
        return new DiscordTrack(id, "Título", "Artista", origin() + path);
    }

    private DiscordValidatedAudioMetadata validateFixture(Path path) {
        try {
            return new DiscordValidatedAudioMetadata(
                    "fixture", 48000, 2, 1000, null, Files.size(path), "fixture-sha256");
        } catch (IOException failure) {
            throw new DiscordAudioPreparationException("fixture validation failed", failure);
        }
    }

    private String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body, String etag) throws IOException {
        if (etag != null) {
            exchange.getResponseHeaders().set("ETag", etag);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
